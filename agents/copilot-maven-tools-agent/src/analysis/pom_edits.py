"""Locate exact XML edit spans without reimplementing Maven version resolution.

Apache Maven supplies effective versions and edit metadata. Expat is used only to
locate declarations; byte replacements preserve comments, namespaces and formatting.

@author Arvind Menon
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Any
from xml.parsers import expat


@dataclass(eq=False)
class Element:
    """An XML element with the original byte span of its content."""

    name: str
    parent: Element | None
    start: int
    end: int = 0
    children: list[Element] = field(default_factory=list)
    text: str = ""

    @property
    def path(self) -> str:
        return f"{self.parent.path}.{self.name}" if self.parent else self.name

    def child(self, name: str) -> Element | None:
        matches = [node for node in self.children if node.name == name]
        return matches[0] if len(matches) == 1 else None

    def value(self, name: str, default: str = "") -> str:
        node = self.child(name)
        return node.text.strip() if node else default


class PomEdits:
    """Plan precise, stale-safe version edits against one immutable POM snapshot."""

    def __init__(self, content: str):
        self.raw = content.encode("utf-8")
        if len(self.raw) > 4 * 1024 * 1024:
            raise ValueError("POM exceeds the editing size limit")
        self.nodes: list[Element] = []
        stack: list[Element] = []
        parser = expat.ParserCreate(namespace_separator="}")

        def start(name: str, _attrs: dict[str, str]) -> None:
            if len(stack) >= 64:
                raise ValueError("POM exceeds the editing depth limit")
            if name.startswith("http://maven.apache.org/POM/4.0.0}"):
                name = name.split("}", 1)[1]
            offset = self.raw.find(b">", parser.CurrentByteIndex) + 1
            node = Element(name, stack[-1] if stack else None, offset)
            if stack:
                stack[-1].children.append(node)
            self.nodes.append(node)
            stack.append(node)

        def end(_name: str) -> None:
            stack.pop().end = parser.CurrentByteIndex

        def text(value: str) -> None:
            if stack:
                stack[-1].text += value

        def reject_dtd(*_args: Any) -> None:
            raise ValueError("DTD declarations are not supported")

        parser.StartElementHandler = start
        parser.EndElementHandler = end
        parser.CharacterDataHandler = text
        parser.StartDoctypeDeclHandler = reject_dtd
        parser.ExternalEntityRefHandler = lambda *_args: 0
        parser.Parse(self.raw, True)

    def _version_node(self, action: dict[str, Any]) -> Element:
        location = action.get("declaredIn")
        paths = {
            "dependency_management": {"project.dependencyManagement.dependencies.dependency"},
            "dependencies": {"project.dependencies.dependency"},
            "parent": {"project.parent"},
            "build.plugins.plugin.dependencies": {
                "project.build.plugins.plugin.dependencies.dependency"
            },
            "build.pluginManagement.plugins.plugin.dependencies": {
                "project.build.pluginManagement.plugins.plugin.dependencies.dependency"
            },
        }
        if location:
            allowed = paths.get(location, set())
        elif action.get("kind") == "bom_bump":
            allowed = {"project.parent", "project.dependencyManagement.dependencies.dependency"}
        elif action.get("kind") == "explicit_bump":
            allowed = {"project.dependencies.dependency"}
        else:
            allowed = {
                "project.parent",
                "project.dependencies.dependency",
                "project.dependencyManagement.dependencies.dependency",
            }
        matches = [
            node
            for node in self.nodes
            if node.path in allowed
            and node.value("groupId") == action["groupId"]
            and node.value("artifactId") == action["artifactId"]
        ]
        if location and location.startswith("build."):
            matches = [
                node
                for node in matches
                if node.parent
                and node.parent.parent
                and node.parent.parent.value("artifactId") == action.get("ownerArtifactId")
                and node.parent.parent.value("groupId", "org.apache.maven.plugins")
                == action.get("ownerGroupId", "org.apache.maven.plugins")
            ]
        if len(matches) != 1:
            raise ValueError("Missing or ambiguous declaration")
        node = matches[0]
        # Existing action schema has no classifier/type selector: ambiguous or non-default
        # artifact variants need manual review rather than guessing an edit target.
        if node.value("classifier") or node.value("type", "jar") not in {"jar", "pom"}:
            raise ValueError("Artifact variant requires explicit edit metadata")
        version = node.child("version")
        if version is None:
            raise ValueError("Declaration has no explicit version")
        return version

    def _target(self, action: dict[str, Any]) -> tuple[Element, Element, str | None]:
        version = self._version_node(action)
        reference = re.fullmatch(r"\$\{([^}]+)}", version.text.strip())
        if not reference:
            if action.get("editTarget") == "property":
                raise ValueError("Property edit does not match declaration")
            return version, version, None
        name = reference.group(1)
        if action.get("propertyName") not in {None, name}:
            raise ValueError("Property edit does not match declaration")
        properties = [node for node in self.nodes if node.path == f"project.properties.{name}"]
        if len(properties) != 1:
            raise ValueError("Missing or ambiguous root property")
        return properties[0], version, name

    def apply(self, actions: list[dict[str, Any]]) -> tuple[str, list[dict], list[dict]]:
        """Apply only edits whose complete set of affected references agrees on the target."""
        planned: list[tuple[dict, Element, Element, str | None]] = []
        failed: list[dict] = []
        for action in actions:
            try:
                if not re.fullmatch(r"[A-Za-z0-9_][A-Za-z0-9_.+-]*", action.get("target", "")):
                    raise ValueError("Invalid target version")
                target, declaration, name = self._target(action)
                raw_value = self.raw[target.start : target.end].decode("utf-8")
                if target.children or raw_value.strip() != target.text.strip():
                    raise ValueError("Version is not plain text")
                current = action.get("current")
                if current is not None and current != target.text.strip():
                    raise ValueError("Stale current version")
                planned.append((action, target, declaration, name))
            except (KeyError, ValueError):
                failed.append(action)

        replacements: dict[tuple[int, int], bytes] = {}
        applied: list[dict] = []
        for action, target, declaration, name in planned:
            peers = [item for item in planned if item[1] is target]
            valid = len({item[0]["target"] for item in peers}) == 1
            if name:
                reference = ("${" + name + "}").encode("utf-8")
                owners = {item[2] for item in peers}
                # References in profiles, ignored dependencies, plugin configuration, chained
                # properties or comments are intentionally not authorized by these actions.
                valid = valid and self.raw.count(reference) == len(owners)
            if not valid:
                failed.append(action)
                continue
            value = self.raw[target.start : target.end].decode("utf-8")
            replacement = re.sub(
                r"\S(?:.*\S)?", lambda _match: action["target"], value, count=1, flags=re.DOTALL
            ).encode("utf-8")
            replacements[(target.start, target.end)] = replacement
            applied.append(action)
        output = self.raw
        for (start, end), value in sorted(replacements.items(), reverse=True):
            output = output[:start] + value + output[end:]
        return output.decode("utf-8"), applied, failed
