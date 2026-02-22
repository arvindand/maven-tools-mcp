"""
Dependency analysis logic.

Provides POM parsing and data models for Maven dependency analysis.

@author Arvind Menon
"""

from __future__ import annotations

import logging
import re
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Optional, Union

logger = logging.getLogger(__name__)

# Constants
HEALTH_SCORE_THRESHOLD = 70


def _clean_pom_namespaces(pom_content: str) -> str:
    """Remove XML namespaces from POM content for easier parsing."""
    content = re.sub(r'xmlns="[^"]+"', "", pom_content)
    content = re.sub(r'xmlns:xsi="[^"]+"', "", content)
    return re.sub(r'xsi:schemaLocation="[^"]+"', "", content)


def _extract_properties(root: ET.Element) -> dict[str, str]:
    """Extract properties from POM root element."""
    properties: dict[str, str] = {}
    props_elem = root.find(".//properties")
    if props_elem is None:
        return properties

    for prop in props_elem:
        tag_name = prop.tag.split("}")[-1] if "}" in prop.tag else prop.tag
        if prop.text:
            properties[tag_name] = prop.text.strip()

    logger.debug(f"Found {len(properties)} properties")
    return properties


def _extract_parent_dependency(root: ET.Element) -> Optional[ParsedDependency]:
    """Extract parent POM as a ParsedDependency if present."""
    parent = root.find("parent")
    if parent is None:
        return None

    parent_group = parent.find("groupId")
    parent_artifact = parent.find("artifactId")
    parent_version = parent.find("version")

    if not all(e is not None and e.text for e in [parent_group, parent_artifact, parent_version]):
        return None

    dep = ParsedDependency(
        group_id=parent_group.text.strip(),  # type: ignore[union-attr]
        artifact_id=parent_artifact.text.strip(),  # type: ignore[union-attr]
        version=parent_version.text.strip(),  # type: ignore[union-attr]
        source="parent",
    )
    logger.debug(f"Found parent: {dep.coordinate_with_version}")
    return dep


def _resolve_version(version: str, properties: dict[str, str]) -> Optional[str]:
    """Resolve version, handling property references. Returns None if unresolvable."""
    if not version.startswith("${") or not version.endswith("}"):
        return version

    prop_name = version[2:-1]
    resolved = properties.get(prop_name)
    if resolved:
        logger.debug(f"Resolved {prop_name} to {resolved}")
        return resolved

    logger.debug(f"Could not resolve property: {prop_name}")
    return None


def _parse_dependency_element(
    dep: ET.Element,
    properties: dict[str, str],
) -> Optional[ParsedDependency]:
    """Parse a single dependency element into ParsedDependency."""
    group_id_elem = dep.find("groupId")
    artifact_id_elem = dep.find("artifactId")
    version_elem = dep.find("version")

    if group_id_elem is None or artifact_id_elem is None:
        return None

    group_id = group_id_elem.text.strip() if group_id_elem.text else ""
    artifact_id = artifact_id_elem.text.strip() if artifact_id_elem.text else ""

    # Skip if no version (BOM-managed)
    if version_elem is None or not version_elem.text:
        logger.debug(f"Skipping BOM-managed: {group_id}:{artifact_id}")
        return None

    version = _resolve_version(version_elem.text.strip(), properties)
    if not version:
        return None

    return ParsedDependency(
        group_id=group_id,
        artifact_id=artifact_id,
        version=version,
        source="dependency",
    )


@dataclass
class DependencyUpdate:
    """Represents a dependency that has an available update."""

    group_id: str
    artifact_id: str
    current_version: str
    latest_version: str
    update_type: str  # major, minor, patch

    @property
    def coordinate(self) -> str:
        """Get Maven coordinate string."""
        return f"{self.group_id}:{self.artifact_id}"


@dataclass
class SecurityIssue:
    """Represents a security issue in a dependency."""

    dependency: str
    cve: str
    severity: str
    description: str = ""


@dataclass
class AnalysisResult:
    """Complete dependency analysis result."""

    health_score: int
    updates: list[DependencyUpdate] = field(default_factory=list)
    security_issues: list[SecurityIssue] = field(default_factory=list)
    recommendations: list[str] = field(default_factory=list)
    raw_response: dict[str, Any] = field(default_factory=dict)

    @property
    def has_updates(self) -> bool:
        """Check if there are any available updates."""
        return len(self.updates) > 0

    @property
    def has_security_issues(self) -> bool:
        """Check if there are any security issues."""
        return len(self.security_issues) > 0

    @property
    def needs_attention(self) -> bool:
        """Check if the project needs attention."""
        return self.has_updates or self.has_security_issues or self.health_score < HEALTH_SCORE_THRESHOLD


@dataclass
class ParsedDependency:
    """Represents a parsed dependency with resolved version."""

    group_id: str
    artifact_id: str
    version: str
    source: str = "dependency"  # "dependency", "parent", "property"

    @property
    def coordinate(self) -> str:
        """Get Maven coordinate string (groupId:artifactId)."""
        return f"{self.group_id}:{self.artifact_id}"

    @property
    def coordinate_with_version(self) -> str:
        """Get Maven coordinate string with version (groupId:artifactId:version)."""
        return f"{self.group_id}:{self.artifact_id}:{self.version}"


class DependencyAnalyzer:
    """
    Analyzes Maven dependencies for the upgrade workflow.

    Provides static methods for:
    - Parsing POM files and extracting dependencies
    - Resolving property references in versions
    - Formatting dependencies for MCP tool input
    """

    @staticmethod
    def parse_pom_with_properties(pom_content: str) -> list[ParsedDependency]:
        """
        Parse POM XML and extract dependencies with resolved property versions.

        Handles:
        - Direct version declarations
        - Property references (${property.name})
        - Parent POM version
        - Skips BOM-managed dependencies (no explicit version)

        Args:
            pom_content: POM XML content

        Returns:
            List of ParsedDependency objects with resolved versions
        """
        dependencies: list[ParsedDependency] = []
        pom_content_clean = _clean_pom_namespaces(pom_content)

        try:
            root = ET.fromstring(pom_content_clean)
            properties = _extract_properties(root)

            # Extract parent POM as a dependency
            parent_dep = _extract_parent_dependency(root)
            if parent_dep:
                dependencies.append(parent_dep)

            # Extract dependencies with versions
            for dep_elem in root.findall(".//dependencies/dependency"):
                parsed = _parse_dependency_element(dep_elem, properties)
                if parsed:
                    dependencies.append(parsed)

            logger.debug(f"Parsed {len(dependencies)} dependencies with versions")

        except ET.ParseError as e:
            logger.error(f"Failed to parse POM: {e}")
            raise

        return dependencies

    @staticmethod
    def format_for_mcp(dependencies: list[ParsedDependency]) -> str:
        """
        Format dependencies for MCP tool input.

        Args:
            dependencies: List of parsed dependencies

        Returns:
            Comma-separated string in format "groupId:artifactId:version,..."
        """
        return ",".join(dep.coordinate_with_version for dep in dependencies)

    @staticmethod
    def parse_pom(pom_content: str) -> list[dict[str, str]]:
        """
        Parse POM XML and extract dependencies (legacy method).

        Args:
            pom_content: POM XML content

        Returns:
            List of dependency dicts with groupId, artifactId, version
        """
        dependencies = []

        # Remove namespace for easier parsing
        pom_content_clean = re.sub(r'xmlns="[^"]+"', "", pom_content)

        try:
            root = ET.fromstring(pom_content_clean)

            # Find all dependency elements
            for dep in root.findall(".//dependency"):
                group_id = dep.find("groupId")
                artifact_id = dep.find("artifactId")
                version = dep.find("version")

                if group_id is not None and artifact_id is not None:
                    dep_info = {
                        "groupId": group_id.text or "",
                        "artifactId": artifact_id.text or "",
                        "version": version.text if version is not None else "managed",
                    }
                    dependencies.append(dep_info)
                    logger.debug(f"Found dependency: {dep_info}")

        except ET.ParseError as e:
            logger.error(f"Failed to parse POM: {e}")
            raise

        return dependencies

    @staticmethod
    def read_pom_file(pom_path: Union[str, Path]) -> str:
        """
        Read POM file content.

        Args:
            pom_path: Path to pom.xml

        Returns:
            POM file content
        """
        path = Path(pom_path)
        if not path.exists():
            raise FileNotFoundError(f"POM file not found: {path}")
        return path.read_text()
