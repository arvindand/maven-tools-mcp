#!/usr/bin/env python3
"""
Automated dependency upgrade workflow.

Calls the recommend_pom_upgrades MCP tool and applies the server-returned
deterministic_actions directly to pom.xml. The needs_attention list (majors,
multi-BOM conflicts, explicit overrides) is surfaced for review. PR creation
and build validation are handled externally (e.g. by GitHub Actions).

@author Arvind Menon
"""

from __future__ import annotations

import asyncio
import logging
import os
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Any, Optional

import click
from rich.console import Console
from rich.logging import RichHandler
from rich.panel import Panel
from rich.table import Table

# Add src to path for imports
sys.path.insert(0, str(Path(__file__).parent.parent))

from src.analysis.dependency import DependencyAnalyzer, DependencyUpdate, ParsedDependency
from src.copilot.sdk_client import CopilotSDKClient
from src.mcp.direct_client import DirectMcpClient

# Constants
MAX_SECURITY_ISSUES_DISPLAY = 10
IGNORE_DEPENDENCIES_ENV_VAR = "MAVEN_AGENT_IGNORE_DEPENDENCIES"
# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format="%(message)s",
    handlers=[RichHandler(rich_tracebacks=True)],
)
logger = logging.getLogger(__name__)

console = Console()


class PomUpdater:
    """Updates POM file with new dependency versions."""

    def __init__(self, pom_path: Path):
        self.pom_path = pom_path
        self.original_content = pom_path.read_text()
        self.content = self.original_content

    def update_property(self, prop_name: str, new_version: str) -> bool:
        """Update an exact property value in the POM."""
        prop_pattern = rf"(<{re.escape(prop_name)}>)([^<]+)(</{re.escape(prop_name)}>)"
        new_content, count = re.subn(prop_pattern, rf"\g<1>{new_version}\g<3>", self.content)
        if count > 0:
            self.content = new_content
            return True
        return False

    def update_version(self, group_id: str, artifact_id: str, new_version: str) -> bool:
        """
        Update a dependency version in the POM.

        Returns True if the update was made.
        """
        # Pattern for dependency block
        dep_pattern = rf"""
            (<dependency>\s*
            <groupId>{re.escape(group_id)}</groupId>\s*
            <artifactId>{re.escape(artifact_id)}</artifactId>\s*
            <version>)([^<]+)(</version>)
        """
        regex = re.compile(dep_pattern, re.VERBOSE | re.DOTALL)

        match = regex.search(self.content)
        if match:
            current_declared_version = match.group(2).strip()
            prop_ref_match = re.fullmatch(r"\$\{([^}]+)\}", current_declared_version)
            if prop_ref_match:
                # Preserve shared property-based versioning by updating the property value instead.
                return self.update_property(prop_ref_match.group(1), new_version)

            new_content, count = regex.subn(rf"\g<1>{new_version}\g<3>", self.content)
            if count > 0:
                self.content = new_content
                return True

        # Also try updating properties like <jackson-bom.version>X</jackson-bom.version>
        # Common property naming patterns
        property_names = [
            f"{artifact_id}.version",
            f"{group_id.split('.')[-1]}.version",
            f"{artifact_id}-version",
        ]

        for prop_name in property_names:
            if self.update_property(prop_name, new_version):
                return True

        return False

    def update_parent_version(self, new_version: str) -> bool:
        """Update the parent POM version (matches any parent block)."""
        parent_pattern = r"""
            (<parent>\s*
            <groupId>[^<]+</groupId>\s*
            <artifactId>[^<]+</artifactId>\s*
            <version>)([^<]+)(</version>)
        """
        regex = re.compile(parent_pattern, re.VERBOSE | re.DOTALL)
        new_content, count = regex.subn(rf"\g<1>{new_version}\g<3>", self.content)

        if count > 0:
            self.content = new_content
            return True
        return False

    def update_plugin_dependency_version(
        self,
        owner_artifact_id: str,
        group_id: str,
        artifact_id: str,
        new_version: str,
        declared_in: str,
    ) -> bool:
        """Update one dependency only inside the identified owner plugin block."""
        plugin_pattern = re.compile(
            rf"<plugin>(?:(?!</plugin>).)*?"
            rf"<artifactId>{re.escape(owner_artifact_id)}</artifactId>"
            rf"(?:(?!</plugin>).)*?</plugin>",
            re.DOTALL,
        )
        dependency_pattern = re.compile(
            rf"(<dependency>\s*"
            rf"<groupId>{re.escape(group_id)}</groupId>\s*"
            rf"<artifactId>{re.escape(artifact_id)}</artifactId>\s*"
            rf"<version>)([^<]+)(</version>)",
            re.DOTALL,
        )
        plugin_management_spans = [
            match.span()
            for match in re.finditer(
                r"<pluginManagement>.*?</pluginManagement>", self.content, re.DOTALL
            )
        ]
        expects_plugin_management = (
            declared_in == "build.pluginManagement.plugins.plugin.dependencies"
        )
        for plugin_match in plugin_pattern.finditer(self.content):
            in_plugin_management = any(
                start <= plugin_match.start() < end
                for start, end in plugin_management_spans
            )
            if in_plugin_management != expects_plugin_management:
                continue
            plugin_block = plugin_match.group(0)
            updated_block, count = dependency_pattern.subn(
                rf"\g<1>{new_version}\g<3>", plugin_block, count=1
            )
            if count > 0:
                self.content = (
                    self.content[: plugin_match.start()]
                    + updated_block
                    + self.content[plugin_match.end() :]
                )
                return True
        return False

    def update_parent_version_if_matches(
        self, group_id: str, artifact_id: str, new_version: str
    ) -> bool:
        """Update the parent POM version only when groupId+artifactId match."""
        parent_pattern = rf"""
            (<parent>\s*
            <groupId>{re.escape(group_id)}</groupId>\s*
            <artifactId>{re.escape(artifact_id)}</artifactId>\s*
            <version>)([^<]+)(</version>)
        """
        regex = re.compile(parent_pattern, re.VERBOSE | re.DOTALL)
        new_content, count = regex.subn(rf"\g<1>{new_version}\g<3>", self.content)
        if count > 0:
            self.content = new_content
            return True
        return False

    def apply_action(
        self,
        group_id: str,
        artifact_id: str,
        new_version: str,
        edit_target: Optional[str] = None,
        property_name: Optional[str] = None,
        declared_in: Optional[str] = None,
        owner_artifact_id: Optional[str] = None,
    ) -> bool:
        """Apply a version bump using exact edit metadata when the server supplies it."""
        if edit_target == "property":
            return bool(property_name) and self.update_property(property_name, new_version)
        if declared_in in {
            "build.plugins.plugin.dependencies",
            "build.pluginManagement.plugins.plugin.dependencies",
        }:
            return bool(owner_artifact_id) and self.update_plugin_dependency_version(
                owner_artifact_id, group_id, artifact_id, new_version, declared_in
            )
        if self.update_version(group_id, artifact_id, new_version):
            return True
        return self.update_parent_version_if_matches(group_id, artifact_id, new_version)

    def save(self) -> None:
        """Save the updated POM."""
        self.pom_path.write_text(self.content)

    def restore(self) -> None:
        """Restore the original POM content."""
        self.pom_path.write_text(self.original_content)

    def has_changes(self) -> bool:
        """Check if there are changes to save."""
        return self.content != self.original_content


def _extract_deps_data(response: dict[str, Any]) -> list[dict[str, Any]]:
    """Extract dependencies list from MCP response (handles nested structures)."""
    if "data" in response and isinstance(response["data"], dict):
        return response["data"].get("dependencies", response["data"].get("results", []))
    return response.get("dependencies", response.get("results", []))


def _parse_single_dependency(dep: dict[str, Any]) -> Optional[DependencyUpdate]:
    """Parse a single dependency dict into DependencyUpdate, or None if invalid."""
    current = dep.get(
        "currentVersion",
        dep.get("current_version", dep.get("current", dep.get("version", ""))),
    )
    latest = dep.get(
        "latestVersion",
        dep.get("latest_version", dep.get("latest", dep.get("recommendedVersion", ""))),
    )
    update_type = dep.get(
        "updateType",
        dep.get("update_type", dep.get("type", dep.get("upgradeType", "unknown"))),
    )

    # Skip if no update available
    if not latest or current == latest:
        return None

    # Parse coordinate
    coordinate = dep.get("coordinate", dep.get("dependency", ""))
    if ":" in coordinate:
        parts = coordinate.split(":")
        group_id = parts[0]
        artifact_id = parts[1] if len(parts) > 1 else ""
    else:
        group_id = dep.get("groupId", "")
        artifact_id = dep.get("artifactId", "")

    if not (group_id and artifact_id and current and latest):
        return None

    return DependencyUpdate(
        group_id=group_id,
        artifact_id=artifact_id,
        current_version=current,
        latest_version=latest,
        update_type=update_type.lower() if update_type else "unknown",
    )


def _display_updates_table(
    updates: list[DependencyUpdate],
    title: str,
    is_major_warning: bool = False,
) -> None:
    """Display a table of dependency updates."""
    table = Table(title=title, style="red" if is_major_warning else None)
    table.add_column("Dependency", style="cyan")
    table.add_column("Current Version", style="yellow")
    table.add_column("New Version", style="red" if is_major_warning else "green")
    table.add_column("Update Type", style="red" if is_major_warning else "blue")

    for u in updates:
        update_type_display = u.update_type.upper() if is_major_warning else u.update_type
        table.add_row(u.coordinate, u.current_version, u.latest_version, update_type_display)
    console.print(table)


def _display_security_issues(mcp_response: dict[str, Any]) -> None:
    """Display security issues from MCP response if any exist."""
    security_issues: list[dict[str, Any]] = []
    if isinstance(mcp_response, dict):
        response_data = mcp_response.get("data", mcp_response)
        if isinstance(response_data, dict):
            security_issues = response_data.get(
                "securityIssues", response_data.get("vulnerabilities", [])
            )

    if not security_issues:
        return

    console.print("\n[bold red]Security Issues Found:[/bold red]")
    sec_table = Table(title="Vulnerabilities")
    sec_table.add_column("Dependency", style="cyan")
    sec_table.add_column("CVE", style="red")
    sec_table.add_column("Severity", style="yellow")

    for issue in security_issues[:MAX_SECURITY_ISSUES_DISPLAY]:
        if isinstance(issue, dict):
            sec_table.add_row(
                issue.get("dependency", issue.get("coordinate", "unknown")),
                issue.get("cve", issue.get("id", "N/A")),
                issue.get("severity", "unknown"),
            )
    console.print(sec_table)


def _normalize_dependency_key(value: str) -> str:
    """Normalize a dependency identifier to 'groupId:artifactId'."""
    parts = [part.strip() for part in (value or "").split(":")]
    if len(parts) >= 2 and parts[0] and parts[1]:
        return f"{parts[0]}:{parts[1]}"
    return (value or "").strip()


def parse_ignored_dependency_keys(
    ignore_dependencies: tuple[str, ...] = (),
    env_value: Optional[str] = None,
) -> set[str]:
    """
    Parse ignored dependency coordinates from CLI args and/or env var.

    Supported format:
      - exact Maven coordinates without version: groupId:artifactId
      - comma/newline separated in env var (extra whitespace allowed)
      - if a version is accidentally included, it is ignored
    """
    ignored: set[str] = set()

    for raw in ignore_dependencies:
        normalized = _normalize_dependency_key(raw)
        if normalized and ":" in normalized:
            ignored.add(normalized)

    if env_value:
        for token in re.split(r"[\n,]+", env_value):
            normalized = _normalize_dependency_key(token)
            if normalized and ":" in normalized:
                ignored.add(normalized)

    return ignored


def _filter_ignored_parsed_dependencies(
    parsed_deps: list[ParsedDependency],
    ignored_dependency_keys: set[str],
) -> tuple[list[ParsedDependency], list[ParsedDependency]]:
    """Split parsed dependencies into kept and ignored lists."""
    if not ignored_dependency_keys:
        return parsed_deps, []

    kept: list[ParsedDependency] = []
    ignored: list[ParsedDependency] = []
    for dep in parsed_deps:
        if dep.coordinate in ignored_dependency_keys:
            ignored.append(dep)
        else:
            kept.append(dep)
    return kept, ignored


def _filter_ignored_updates(
    updates: list[DependencyUpdate],
    ignored_dependency_keys: set[str],
) -> list[DependencyUpdate]:
    """Filter updates for ignored coordinates (safety net; usually filtered before MCP call)."""
    if not ignored_dependency_keys:
        return updates
    return [u for u in updates if u.coordinate not in ignored_dependency_keys]


def _parse_pom_dependencies(
    pom_content: str,
    ignored_dependency_keys: Optional[set[str]] = None,
) -> tuple[list[ParsedDependency], str]:
    """Parse POM and return dependencies with MCP-formatted string."""
    console.print("\n[bold]Step 1: Parsing dependencies...[/bold]")
    parsed_deps = DependencyAnalyzer.parse_pom_with_properties(pom_content)
    ignored_dependency_keys = ignored_dependency_keys or set()

    if not parsed_deps:
        console.print("[yellow]No dependencies with explicit versions found[/yellow]")
        return [], ""

    filtered_deps, ignored_deps = _filter_ignored_parsed_dependencies(
        parsed_deps, ignored_dependency_keys
    )

    console.print(f"[green]✓[/green] Found {len(parsed_deps)} dependencies with versions")
    if ignored_deps:
        ignored_list = ", ".join(sorted({dep.coordinate for dep in ignored_deps}))
        console.print(
            f"[yellow]⚠[/yellow] Ignoring {len(ignored_deps)} dependency entries "
            f"({len({dep.coordinate for dep in ignored_deps})} unique): {ignored_list}"
        )

    if not filtered_deps:
        console.print("[yellow]No dependencies remain after ignore filters[/yellow]")
        return [], ""

    mcp_input = DependencyAnalyzer.format_for_mcp(filtered_deps)
    logger.debug(f"MCP input: {mcp_input}")
    return filtered_deps, mcp_input


def should_use_copilot_session(mode: str) -> bool:
    """Return whether a mode should use an AI session instead of direct MCP."""
    return mode == "major"


def _action_key(action: dict[str, Any]) -> str:
    """Return groupId:artifactId for an UpgradeAction-shaped dict."""
    return f"{action.get('groupId', '')}:{action.get('artifactId', '')}"


def _filter_actions_by_ignored(
    actions: list[dict[str, Any]], ignored: set[str]
) -> list[dict[str, Any]]:
    if not ignored:
        return actions
    return [a for a in actions if _action_key(a) not in ignored]


def _filter_attention_by_ignored(
    attention: list[dict[str, Any]], ignored: set[str]
) -> list[dict[str, Any]]:
    if not ignored:
        return attention
    return [n for n in attention if _action_key(n) not in ignored]


def _display_deterministic_actions(actions: list[dict[str, Any]]) -> None:
    """Render the deterministic_actions table."""
    if not actions:
        return
    table = Table(title="Deterministic Updates (will be applied)")
    table.add_column("Kind", style="magenta")
    table.add_column("Dependency", style="cyan")
    table.add_column("Current", style="yellow")
    table.add_column("Target", style="green")
    table.add_column("Type", style="blue")
    for a in actions:
        table.add_row(
            a.get("kind", "?"),
            f"{a.get('groupId', '')}:{a.get('artifactId', '')}",
            a.get("current", ""),
            a.get("target", ""),
            a.get("updateType", ""),
        )
    console.print(table)


def _display_needs_attention(attention: list[dict[str, Any]]) -> None:
    """Render the needs_attention table (informational)."""
    if not attention:
        return
    table = Table(title="Needs Attention (manual review)", style="yellow")
    table.add_column("Kind", style="red")
    table.add_column("Dependency", style="cyan")
    table.add_column("Current", style="yellow")
    table.add_column("Latest", style="green")
    table.add_column("Notes", style="white")
    for n in attention:
        kind = n.get("kind", "?")
        coord = f"{n.get('groupId', '')}:{n.get('artifactId', '')}"
        if kind == "major_available":
            current = n.get("current", "")
            latest = n.get("latestStable", "")
            notes = f"same-major latest: {n.get('currentMajorLatest', current)}"
        elif kind == "conflict":
            current = n.get("currentlyResolvesTo", "")
            latest = n.get("latestOnCentral", "")
            candidates = n.get("candidates", [])
            notes = f"{len(candidates)} BOMs disagree"
        elif kind == "explicit_override":
            current = n.get("currentExplicit", "")
            latest = n.get("latestOnCentral", "")
            candidates = n.get("managingCandidates", [])
            notes = f"override; {len(candidates)} managing candidate(s)"
        else:
            current = ""
            latest = ""
            notes = ""
        table.add_row(kind, coord, current, latest, notes)
    console.print(table)


def _apply_deterministic_actions(
    pom_updater: PomUpdater, actions: list[dict[str, Any]]
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    """Apply each action via PomUpdater. Returns (applied, failed)."""
    applied: list[dict[str, Any]] = []
    failed: list[dict[str, Any]] = []
    for a in actions:
        group_id = a.get("groupId", "")
        artifact_id = a.get("artifactId", "")
        target = a.get("target", "")
        if not (group_id and artifact_id and target):
            failed.append(a)
            continue
        if pom_updater.apply_action(
            group_id,
            artifact_id,
            target,
            edit_target=a.get("editTarget"),
            property_name=a.get("propertyName"),
            declared_in=a.get("declaredIn"),
            owner_artifact_id=a.get("ownerArtifactId"),
        ):
            applied.append(a)
            console.print(
                f"  [green]✓[/green] [{a.get('kind', '?')}] "
                f"{group_id}:{artifact_id}: {a.get('current', '')} → {target}"
            )
        else:
            failed.append(a)
            console.print(
                f"  [yellow]⚠[/yellow] Could not locate {group_id}:{artifact_id} in POM"
            )
    return applied, failed


async def _fetch_recommendations(
    pom_content: str,
    mode: str,
    mcp_transport: str,
    mcp_url: Optional[str],
) -> dict[str, Any]:
    """Call recommend_pom_upgrades and return the parsed payload."""
    server_mode = "ALL" if mode == "all" else "MINOR_PATCH"
    async with DirectMcpClient(transport=mcp_transport, url=mcp_url) as client:
        console.print("[green]✓[/green] Connected directly to Maven Tools MCP")
        response = await client.recommend_upgrades(pom_content, server_mode)
        console.print("[green]✓[/green] Received MCP response")
        logger.debug(f"MCP response: {response}")
        return response


async def _run_deterministic_upgrade(
    pom_path: Path,
    pom_content: str,
    mode: str,
    dry_run: bool,
    mcp_transport: str,
    mcp_url: Optional[str],
    ignored_keys: set[str],
) -> int:
    """Deterministic upgrade path — single MCP call, apply actions directly."""
    console.print("\n[bold]Step 1: Asking MCP for upgrade recommendations...[/bold]")
    try:
        response = await _fetch_recommendations(pom_content, mode, mcp_transport, mcp_url)
    except (RuntimeError, ValueError, ImportError) as e:
        console.print(f"[red]Error calling MCP tool: {e}[/red]")
        return 1

    # ToolResponse.Success wraps the payload under "data" (snake_case envelope).
    payload = response.get("data") if isinstance(response.get("data"), dict) else response
    actions = payload.get("deterministicActions") or payload.get("deterministic_actions") or []
    attention = payload.get("needsAttention") or payload.get("needs_attention") or []
    warnings = payload.get("warnings") or []

    actions = _filter_actions_by_ignored(actions, ignored_keys)
    attention = _filter_attention_by_ignored(attention, ignored_keys)

    console.print(
        f"\n[bold]Found {len(actions)} deterministic action(s), "
        f"{len(attention)} needing attention.[/bold]"
    )
    _display_deterministic_actions(actions)
    _display_needs_attention(attention)
    for warning in warnings:
        console.print(f"[yellow]⚠ Warning:[/yellow] {warning}")

    if dry_run:
        console.print("\n[yellow]Dry run - no changes will be made[/yellow]")
        return 0
    if not actions:
        console.print("\n[green]No deterministic upgrades to apply.[/green]")
        return 0

    console.print("\n[bold]Step 2: Applying actions...[/bold]")
    pom_updater = PomUpdater(pom_path)
    applied, failed = _apply_deterministic_actions(pom_updater, actions)

    if not pom_updater.has_changes():
        console.print("[yellow]No changes were made to the POM[/yellow]")
        return 0

    pom_updater.save()
    console.print(f"\n[green]✓[/green] Applied {len(applied)} updates to POM")
    if failed:
        console.print(f"[yellow]Skipped {len(failed)} unmatched action(s)[/yellow]")

    console.print("\n" + "=" * 50)
    console.print(
        Panel(
            f"[green]Applied:[/green] {len(applied)} deterministic actions\n"
            f"[yellow]Needs attention:[/yellow] {len(attention)} (manual review)",
            title="Upgrade Summary",
            expand=False,
        )
    )
    return 0


async def _fetch_copilot_major_review(
    project_dir: Path,
    mcp_input: str,
    mcp_transport: str,
    mcp_url: Optional[str],
) -> dict[str, Any]:
    """Fetch major-review updates through the Copilot SDK."""
    console.print("\n[bold]Step 2: Checking for updates via Copilot SDK + MCP...[/bold]")
    async with CopilotSDKClient(
        working_dir=str(project_dir),
        mcp_transport=mcp_transport,
        mcp_url=mcp_url,
    ) as client:
        console.print("[green]✓[/green] Connected to Copilot SDK")
        mcp_response = await client.compare_versions(
            dependencies=mcp_input,
            stability_filter="STABLE_ONLY",
            include_security=True,
        )
        console.print("[green]✓[/green] Received MCP response")
        logger.debug(f"MCP response: {mcp_response}")
        return mcp_response


def parse_mcp_response(response: dict[str, Any]) -> list[DependencyUpdate]:
    """Parse MCP compare_dependency_versions response into DependencyUpdate objects."""
    if not isinstance(response, dict):
        return []

    deps_data = _extract_deps_data(response)
    if not isinstance(deps_data, list):
        return []

    updates = []
    for dep in deps_data:
        if isinstance(dep, dict):
            update = _parse_single_dependency(dep)
            if update:
                updates.append(update)

    return updates


async def run_upgrade(
    pom_file: str,
    mode: str,
    dry_run: bool,
    mcp_transport: str = "stdio",
    mcp_url: Optional[str] = None,
    ignore_dependencies: tuple[str, ...] = (),
) -> int:
    """Run the complete upgrade workflow."""
    pom_path = Path(pom_file).absolute()
    if not pom_path.exists():
        console.print(f"[red]Error: POM file not found: {pom_file}[/red]")
        return 1

    project_dir = pom_path.parent
    pom_content = pom_path.read_text()

    console.print(f"[green]✓[/green] Found POM: {pom_path}")
    console.print(f"[cyan]Mode:[/cyan] {mode}")
    transport_info = f"HTTP ({mcp_url})" if mcp_transport == "http" else "STDIO (Docker)"
    console.print(f"[cyan]MCP Transport:[/cyan] {transport_info}")
    ignored_dependency_keys = parse_ignored_dependency_keys(
        ignore_dependencies=ignore_dependencies,
        env_value=os.getenv(IGNORE_DEPENDENCIES_ENV_VAR),
    )
    if ignored_dependency_keys:
        console.print(
            f"[cyan]Ignored Dependencies:[/cyan] {', '.join(sorted(ignored_dependency_keys))}"
        )

    # Deterministic path (minor_patch / all): single MCP call, no Python POM parsing.
    if mode in ("minor_patch", "all"):
        return await _run_deterministic_upgrade(
            pom_path=pom_path,
            pom_content=pom_content,
            mode=mode,
            dry_run=dry_run,
            mcp_transport=mcp_transport,
            mcp_url=mcp_url,
            ignored_keys=ignored_dependency_keys,
        )

    # Major-review path (Copilot SDK): keeps the per-coordinate compare flow.
    try:
        parsed_deps, mcp_input = _parse_pom_dependencies(pom_content, ignored_dependency_keys)
        if not parsed_deps:
            return 0
    except (ValueError, ET.ParseError) as e:
        console.print(f"[red]Error parsing POM: {e}[/red]")
        return 1

    try:
        mcp_response = await _fetch_copilot_major_review(
            project_dir, mcp_input, mcp_transport, mcp_url
        )
    except (RuntimeError, ValueError, ImportError) as e:
        console.print(f"[red]Error calling MCP tool: {e}[/red]")
        return 1

    updates = _filter_ignored_updates(parse_mcp_response(mcp_response), ignored_dependency_keys)
    if not updates:
        console.print("\n[green]All dependencies are up to date![/green]")
        return 0

    _display_upgrade_findings(updates, [], updates, mode, mcp_response)
    console.print(
        f"\n[yellow]Mode '{mode}' is report-only - no changes will be made[/yellow]"
    )
    return 0


def _display_upgrade_findings(
    updates: list[DependencyUpdate],
    minor_patch_updates: list[DependencyUpdate],
    major_updates: list[DependencyUpdate],
    mode: str,
    mcp_response: dict[str, Any],
) -> None:
    """Display all upgrade findings including tables and security issues."""
    console.print(f"\n[bold]Found {len(updates)} updates:[/bold]")

    if minor_patch_updates:
        _display_updates_table(minor_patch_updates, "Minor/Patch Updates (will be applied)")

    if major_updates:
        is_minor_patch_mode = mode == "minor_patch"
        title = (
            "Major Updates (requires manual review)"
            if is_minor_patch_mode
            else "Available Updates (report only)"
        )
        _display_updates_table(major_updates, title, is_major_warning=is_minor_patch_mode)
        if is_minor_patch_mode:
            console.print(
                "[yellow]Major updates require manual review and won't be applied automatically[/yellow]"
            )

    _display_security_issues(mcp_response)


@click.command()
@click.option(
    "--pom-file",
    "-f",
    default="pom.xml",
    help="Path to pom.xml file to analyze and update",
)
@click.option(
    "--mode",
    "-m",
    type=click.Choice(["minor_patch", "major", "all"]),
    default="minor_patch",
    help="Upgrade mode: minor_patch (auto-apply safe updates), major (report only), all (include pre-release)",
)
@click.option(
    "--dry-run",
    is_flag=True,
    help="Analyze only, don't apply changes",
)
@click.option(
    "--http",
    is_flag=True,
    help="Use HTTP transport for MCP (connect to running server instead of spawning Docker)",
)
@click.option(
    "--mcp-url",
    default="http://localhost:8080/mcp",
    help="MCP server URL for HTTP transport (default: http://localhost:8080/mcp)",
)
@click.option(
    "--ignore-dependency",
    "ignore_dependencies",
    multiple=True,
    help=(
        "Exact dependency coordinate to ignore (groupId:artifactId). "
        "Repeat option for multiple entries. "
        f"Also supports {IGNORE_DEPENDENCIES_ENV_VAR} env var (comma/newline separated)."
    ),
)
@click.option(
    "--verbose",
    "-v",
    is_flag=True,
    help="Enable verbose logging",
)
def main(
    pom_file: str,
    mode: str,
    dry_run: bool,
    http: bool,
    mcp_url: str,
    ignore_dependencies: tuple[str, ...],
    verbose: bool,
) -> None:
    """
    Automated dependency upgrade workflow using MCP tools.

    Hands the raw pom.xml to the recommend_pom_upgrades MCP tool and applies
    the returned deterministic_actions. The needs_attention list is displayed
    for visibility but never auto-applied. PR creation and build validation are
    handled externally (e.g. by GitHub Actions using peter-evans/create-pull-request).

    Modes:
      - minor_patch (default): Server mode MINOR_PATCH — apply minor/patch
        bumps; majors land in needs_attention for manual review
      - major: Route through Copilot SDK for human review of major upgrades
      - all: Server mode ALL — apply majors as deterministic too (rarely
        what you want)

    Examples:
        maven-agent -f pom.xml --dry-run
        maven-agent -f pom.xml --mode major --dry-run
        maven-agent -f pom.xml --http --mcp-url http://localhost:8080/mcp
        maven-agent --ignore-dependency io.modelcontextprotocol.sdk:mcp-bom --dry-run
    """
    if verbose:
        logging.getLogger().setLevel(logging.DEBUG)

    console.print(
        Panel(
            "[bold blue]Maven Tools Dependency Agent[/bold blue]\n"
            "Direct MCP updates; Copilot only for major review",
            expand=False,
        )
    )

    exit_code = asyncio.run(
        run_upgrade(
            pom_file=pom_file,
            mode=mode,
            dry_run=dry_run,
            mcp_transport="http" if http else "stdio",
            mcp_url=mcp_url if http else None,
            ignore_dependencies=ignore_dependencies,
        )
    )

    sys.exit(exit_code)


if __name__ == "__main__":
    main()
