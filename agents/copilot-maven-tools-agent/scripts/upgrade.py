#!/usr/bin/env python3
"""
Automated dependency upgrade workflow.

Analyzes dependencies using MCP tools and applies minor/patch updates.
PR creation and build validation are handled externally (e.g. by GitHub Actions).

@author Arvind Menon
"""

from __future__ import annotations

import asyncio
import json
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

from src.copilot.sdk_client import CopilotSDKClient
from src.analysis.dependency import DependencyAnalyzer, DependencyUpdate, ParsedDependency

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

    def _update_property_by_name(self, prop_name: str, new_version: str) -> bool:
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
                return self._update_property_by_name(prop_ref_match.group(1), new_version)

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
            if self._update_property_by_name(prop_name, new_version):
                return True

        return False

    def update_parent_version(self, new_version: str) -> bool:
        """Update the parent POM version."""
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
            security_issues = response_data.get("securityIssues", response_data.get("vulnerabilities", []))

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


def _apply_pom_updates(
    pom_updater: PomUpdater,
    updates: list[DependencyUpdate],
    parsed_deps: list[ParsedDependency],
) -> list[DependencyUpdate]:
    """Apply updates to POM and return list of successfully applied updates."""
    applied = []
    for update in updates:
        is_parent = any(
            p.source == "parent" and p.group_id == update.group_id and p.artifact_id == update.artifact_id
            for p in parsed_deps
        )

        if is_parent:
            success = pom_updater.update_parent_version(update.latest_version)
        else:
            success = pom_updater.update_version(update.group_id, update.artifact_id, update.latest_version)

        if success:
            applied.append(update)
            console.print(f"  [green]✓[/green] {update.coordinate}: {update.current_version} → {update.latest_version}")
        else:
            console.print(f"  [yellow]⚠[/yellow] Could not update {update.coordinate} (may be managed by BOM)")

    return applied


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

    filtered_deps, ignored_deps = _filter_ignored_parsed_dependencies(parsed_deps, ignored_dependency_keys)

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


async def _fetch_mcp_updates(
    project_dir: Path,
    mcp_input: str,
    mode: str,
    mcp_transport: str,
    mcp_url: Optional[str],
) -> dict[str, Any]:
    """Fetch dependency updates from MCP server."""
    console.print("\n[bold]Step 2: Checking for updates via MCP...[/bold]")
    stability_filter = "STABLE_ONLY" if mode in ("minor_patch", "major") else "ALL"

    async with CopilotSDKClient(
        working_dir=str(project_dir),
        mcp_transport=mcp_transport,
        mcp_url=mcp_url,
    ) as client:
        console.print("[green]✓[/green] Connected to Copilot SDK")
        mcp_response = await client.compare_versions(
            dependencies=mcp_input,
            stability_filter=stability_filter,
            include_security=True,
        )
        console.print("[green]✓[/green] Received MCP response")
        logger.debug(f"MCP response: {mcp_response}")
        return mcp_response


def _filter_updates_by_mode(
    updates: list[DependencyUpdate],
    mode: str,
) -> tuple[list[DependencyUpdate], list[DependencyUpdate]]:
    """Filter updates into minor/patch and major based on mode."""
    if mode == "minor_patch":
        minor_patch = [u for u in updates if u.update_type in ("minor", "patch")]
        major = [u for u in updates if u.update_type == "major"]
    else:
        minor_patch = []
        major = updates
    return minor_patch, major


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


def _extract_same_major_fallback_update(dep: dict[str, Any]) -> Optional[DependencyUpdate]:
    """
    Parse an optional server-computed same-major stable fallback from a dependency result.

    The Maven Tools MCP server may include this when the latest stable recommendation is a major
    update but a newer stable version exists on the current major line.
    """
    fallback = dep.get("sameMajorStableFallback", dep.get("same_major_stable_fallback"))
    if not isinstance(fallback, dict):
        return None

    base_update = _parse_single_dependency(dep)
    if not base_update or base_update.update_type != "major":
        return None

    latest = fallback.get("latestVersion", fallback.get("latest_version", ""))
    update_type = fallback.get("updateType", fallback.get("update_type", ""))
    if not isinstance(latest, str) or not latest.strip():
        return None

    normalized_type = str(update_type).strip().lower()
    if normalized_type not in ("minor", "patch"):
        return None

    return DependencyUpdate(
        group_id=base_update.group_id,
        artifact_id=base_update.artifact_id,
        current_version=base_update.current_version,
        latest_version=latest.strip(),
        update_type=normalized_type,
    )


def extract_server_same_major_fallback_updates(response: dict[str, Any]) -> list[DependencyUpdate]:
    """Extract server-provided same-major stable fallback updates from compare_dependency_versions response."""
    if not isinstance(response, dict):
        return []

    deps_data = _extract_deps_data(response)
    if not isinstance(deps_data, list):
        return []

    updates: list[DependencyUpdate] = []
    for dep in deps_data:
        if not isinstance(dep, dict):
            continue
        fallback_update = _extract_same_major_fallback_update(dep)
        if fallback_update:
            updates.append(fallback_update)
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

    # Step 1: Parse POM
    try:
        parsed_deps, mcp_input = _parse_pom_dependencies(pom_content, ignored_dependency_keys)
        if not parsed_deps:
            return 0
    except (ValueError, ET.ParseError) as e:
        console.print(f"[red]Error parsing POM: {e}[/red]")
        return 1

    # Step 2: Fetch updates from MCP
    try:
        mcp_response = await _fetch_mcp_updates(project_dir, mcp_input, mode, mcp_transport, mcp_url)
    except (RuntimeError, ValueError, ImportError) as e:
        console.print(f"[red]Error calling MCP tool: {e}[/red]")
        return 1

    # Step 3: Parse and filter updates
    updates = _filter_ignored_updates(parse_mcp_response(mcp_response), ignored_dependency_keys)
    if not updates:
        console.print("\n[green]All dependencies are up to date![/green]")
        return 0

    if mode == "minor_patch":
        fallback_updates = _filter_ignored_updates(
            extract_server_same_major_fallback_updates(mcp_response),
            ignored_dependency_keys,
        )
        if fallback_updates:
            console.print("\n[bold]Step 2b: Server-provided same-major stable fallbacks...[/bold]")
            existing_pairs = {(u.coordinate, u.latest_version) for u in updates}
            for fallback in fallback_updates:
                if (fallback.coordinate, fallback.latest_version) in existing_pairs:
                    continue
                updates.append(fallback)
                existing_pairs.add((fallback.coordinate, fallback.latest_version))
                console.print(
                    f"[green]✓[/green] Same-major fallback for {fallback.coordinate}: "
                    f"{fallback.current_version} → {fallback.latest_version} ({fallback.update_type})"
                )

    minor_patch_updates, major_updates = _filter_updates_by_mode(updates, mode)

    # Step 4: Display findings
    _display_upgrade_findings(updates, minor_patch_updates, major_updates, mode, mcp_response)

    # Early exit conditions
    if _should_skip_apply(dry_run, mode, minor_patch_updates):
        return 0

    # Step 5: Apply updates
    console.print("\n[bold]Step 3: Applying minor/patch updates...[/bold]")
    pom_updater = PomUpdater(pom_path)
    applied = _apply_pom_updates(pom_updater, minor_patch_updates, parsed_deps)

    if not pom_updater.has_changes():
        console.print("[yellow]No changes were made to the POM[/yellow]")
        return 0

    pom_updater.save()
    console.print(f"\n[green]✓[/green] Applied {len(applied)} updates to POM")

    # Summary
    _print_upgrade_summary(len(applied), len(major_updates))
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
        title = "Major Updates (requires manual review)" if is_minor_patch_mode else "Available Updates (report only)"
        _display_updates_table(major_updates, title, is_major_warning=is_minor_patch_mode)
        if is_minor_patch_mode:
            console.print("[yellow]Major updates require manual review and won't be applied automatically[/yellow]")

    _display_security_issues(mcp_response)


def _print_upgrade_summary(applied_count: int, major_count: int) -> None:
    """Print the upgrade summary panel."""
    console.print("\n" + "=" * 50)
    console.print(Panel(
        f"[green]Applied:[/green] {applied_count} minor/patch updates\n"
        f"[yellow]Skipped:[/yellow] {major_count} major updates (manual review required)",
        title="Upgrade Summary",
        expand=False,
    ))


def _should_skip_apply(dry_run: bool, mode: str, minor_patch_updates: list[DependencyUpdate]) -> bool:
    """Check if we should skip applying updates and print appropriate message."""
    if dry_run:
        console.print("\n[yellow]Dry run - no changes will be made[/yellow]")
        return True
    if mode != "minor_patch":
        console.print(f"\n[yellow]Mode '{mode}' is report-only - no changes will be made[/yellow]")
        return True
    if not minor_patch_updates:
        console.print("\n[yellow]No minor/patch updates to apply. Only major updates found.[/yellow]")
        return True
    return False


@click.command()
@click.option(
    "--pom-file", "-f",
    default="pom.xml",
    help="Path to pom.xml file to analyze and update",
)
@click.option(
    "--mode", "-m",
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
    "--verbose", "-v",
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

    Analyzes Maven dependencies via compare_dependency_versions MCP tool,
    applies minor/patch updates. PR creation and build validation are handled
    externally (e.g. by GitHub Actions using peter-evans/create-pull-request).

    Modes:
      - minor_patch (default): Auto-apply minor/patch updates, report major
      - major: Report all updates including major (no auto-apply)
      - all: Include pre-release versions (no auto-apply)

    Examples:
        maven-agent -f pom.xml --dry-run
        maven-agent -f pom.xml --mode major --dry-run
        maven-agent -f pom.xml --http --mcp-url http://localhost:8080/mcp
        maven-agent --ignore-dependency io.modelcontextprotocol.sdk:mcp-bom --dry-run
    """
    if verbose:
        logging.getLogger().setLevel(logging.DEBUG)

    console.print(Panel(
        "[bold blue]Copilot Maven Tools Agent[/bold blue]\n"
        "MCP-Based Dependency Upgrade Workflow",
        expand=False,
    ))

    exit_code = asyncio.run(run_upgrade(
        pom_file=pom_file,
        mode=mode,
        dry_run=dry_run,
        mcp_transport="http" if http else "stdio",
        mcp_url=mcp_url if http else None,
        ignore_dependencies=ignore_dependencies,
    ))

    sys.exit(exit_code)


if __name__ == "__main__":
    main()
