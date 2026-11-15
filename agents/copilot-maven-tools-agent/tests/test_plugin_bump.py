"""
Tests for plugin_bump edits, the release-cooldown option and incomplete-result handling.

@author Arvind Menon
"""

from __future__ import annotations

from pathlib import Path
from typing import Any, Optional

import pytest
from click.testing import CliRunner

import scripts.upgrade as upgrade
from src.analysis.pom_edits import PomEdits
from src.mcp.direct_client import DirectMcpClient

BUILD = "build.plugins.plugin"
MANAGEMENT = "build.pluginManagement.plugins.plugin"


def plugin_action(
    artifact: str,
    current: str,
    target: str,
    group: str = "org.apache.maven.plugins",
    declared_in: str = BUILD,
    **metadata: Any,
) -> dict[str, Any]:
    return dict(
        kind="plugin_bump",
        groupId=group,
        artifactId=artifact,
        current=current,
        target=target,
        updateType="minor",
        declaredIn=declared_in,
        **({"editTarget": "literal_version"} | metadata),
    )


def test_literal_plugin_version_is_edited_in_place_preserving_formatting() -> None:
    source = """<project xmlns="http://maven.apache.org/POM/4.0.0">
  <!-- keep me -->
  <build>
    <plugins>
      <plugin>
        <groupId>com.google.cloud.tools</groupId>
        <artifactId>jib-maven-plugin</artifactId>
        <version> 3.5.2 </version>
      </plugin>
    </plugins>
  </build>
</project>"""
    updated, applied, failed = PomEdits(source).apply(
        [plugin_action("jib-maven-plugin", "3.5.2", "3.5.3", group="com.google.cloud.tools")]
    )
    assert len(applied) == 1 and not failed
    assert updated == source.replace("3.5.2", "3.5.3")


def test_plugin_without_group_id_defaults_to_maven_plugins_group() -> None:
    source = (
        "<project><build><plugins>"
        "<plugin><artifactId>maven-shade-plugin</artifactId><version>3.2.0</version></plugin>"
        "<plugin><groupId>other</groupId><artifactId>maven-shade-plugin</artifactId>"
        "<version>3.2.0</version></plugin>"
        "</plugins></build></project>"
    )
    updated, applied, failed = PomEdits(source).apply(
        [plugin_action("maven-shade-plugin", "3.2.0", "3.6.1")]
    )
    assert len(applied) == 1 and not failed
    assert updated.count("<version>3.6.1</version>") == 1
    assert updated.index("3.6.1") < updated.index("<groupId>other")


def test_property_versioned_plugin_edits_the_root_property() -> None:
    source = (
        "<project><properties><jacoco.version>0.8.5</jacoco.version></properties>"
        "<build><plugins><plugin><groupId>org.jacoco</groupId>"
        "<artifactId>jacoco-maven-plugin</artifactId><version>${jacoco.version}</version>"
        "</plugin></plugins></build></project>"
    )
    updated, applied, failed = PomEdits(source).apply(
        [
            plugin_action(
                "jacoco-maven-plugin",
                "0.8.5",
                "0.8.14",
                group="org.jacoco",
                editTarget="property",
                propertyName="jacoco.version",
            )
        ]
    )
    assert len(applied) == 1 and not failed
    assert updated == source.replace("<jacoco.version>0.8.5<", "<jacoco.version>0.8.14<")


def test_property_shared_by_plugins_and_plugin_management_is_one_authorised_action() -> None:
    plugin = (
        "<plugin><groupId>g</groupId><artifactId>p</artifactId>"
        "<version>${p.version}</version></plugin>"
    )
    source = (
        "<project><properties><p.version>1.0</p.version></properties><build>"
        f"<plugins>{plugin}</plugins>"
        f"<pluginManagement><plugins>{plugin}</plugins></pluginManagement>"
        "</build></project>"
    )
    action = plugin_action(
        "p", "1.0", "1.1", group="g", editTarget="property", propertyName="p.version"
    )
    updated, applied, failed = PomEdits(source).apply([action])
    assert applied == [action] and not failed
    assert updated == source.replace("<p.version>1.0<", "<p.version>1.1<")


def test_property_referenced_outside_the_authorised_plugins_is_rejected() -> None:
    source = (
        "<project><properties><p.version>1.0</p.version></properties><build><plugins>"
        "<plugin><groupId>g</groupId><artifactId>p</artifactId>"
        "<version>${p.version}</version>"
        "<configuration><v>${p.version}</v></configuration></plugin></plugins></build></project>"
    )
    action = plugin_action(
        "p", "1.0", "1.1", group="g", editTarget="property", propertyName="p.version"
    )
    updated, applied, failed = PomEdits(source).apply([action])
    assert updated == source and not applied and failed == [action]


def test_different_literals_in_plugins_and_plugin_management_are_edited_independently() -> None:
    source = (
        "<project><build>"
        "<plugins><plugin><groupId>g</groupId><artifactId>p</artifactId>"
        "<version>1.0.0</version></plugin></plugins>"
        "<pluginManagement><plugins><plugin><groupId>g</groupId><artifactId>p</artifactId>"
        "<version>1.0.1</version></plugin></plugins></pluginManagement>"
        "</build></project>"
    )
    actions = [
        plugin_action("p", "1.0.0", "1.1.0", group="g", declared_in=BUILD),
        plugin_action("p", "1.0.1", "1.1.0", group="g", declared_in=MANAGEMENT),
    ]
    updated, applied, failed = PomEdits(source).apply(actions)
    assert len(applied) == 2 and not failed
    assert "1.0.0" not in updated and "1.0.1" not in updated
    assert updated.count("<version>1.1.0</version>") == 2


@pytest.mark.parametrize(
    "mutation",
    [
        # stale current version
        lambda a: a | {"current": "9.9.9"},
        # unknown location
        lambda a: a | {"declaredIn": "build.plugins.plugin.dependencies"},
        # property metadata for a literal declaration
        lambda a: a | {"editTarget": "property", "propertyName": "x"},
    ],
)
def test_unsupported_or_stale_plugin_actions_are_reported_not_guessed(mutation) -> None:
    source = (
        "<project><build><plugins><plugin><groupId>g</groupId><artifactId>p</artifactId>"
        "<version>1.0.0</version></plugin></plugins></build></project>"
    )
    action = mutation(plugin_action("p", "1.0.0", "1.1.0", group="g"))
    updated, applied, failed = PomEdits(source).apply([action])
    assert updated == source and not applied and failed == [action]


def test_duplicate_literal_declarations_at_one_location_are_ambiguous() -> None:
    plugin = (
        "<plugin><groupId>g</groupId><artifactId>p</artifactId><version>1.0.0</version></plugin>"
    )
    source = f"<project><build><plugins>{plugin}{plugin}</plugins></build></project>"
    action = plugin_action("p", "1.0.0", "1.1.0", group="g")
    updated, applied, failed = PomEdits(source).apply([action])
    assert updated == source and not applied and failed == [action]


def test_profile_and_reporting_plugins_are_never_edited() -> None:
    plugin = (
        "<plugin><groupId>g</groupId><artifactId>p</artifactId><version>1.0.0</version></plugin>"
    )
    source = (
        f"<project><reporting><plugins>{plugin}</plugins></reporting>"
        f"<profiles><profile><build><plugins>{plugin}</plugins></build></profile></profiles>"
        "</project>"
    )
    action = plugin_action("p", "1.0.0", "1.1.0", group="g")
    updated, applied, failed = PomEdits(source).apply([action])
    assert updated == source and not applied and failed == [action]


def test_apply_deterministic_actions_applies_plugin_bump_through_the_updater(
    tmp_path: Path,
) -> None:
    pom = tmp_path / "pom.xml"
    pom.write_text(
        "<project><build><plugins><plugin><artifactId>maven-shade-plugin</artifactId>"
        "<version>3.2.0</version></plugin></plugins></build></project>"
    )
    updater = upgrade.PomUpdater(pom)
    action = plugin_action("maven-shade-plugin", "3.2.0", "3.6.1")
    applied, failed = upgrade._apply_deterministic_actions(updater, [action])
    assert applied == [action] and failed == []
    assert "<version>3.6.1</version>" in updater.content


# ---------------------------------------------------------------- cooldown option


def test_display_shows_held_back_note() -> None:
    upgrade._display_deterministic_actions(
        [plugin_action("p", "1.0.0", "1.0.5", group="g") | {"heldBackFrom": "1.1.0"}]
    )


async def test_direct_client_passes_min_release_age_days_only_when_set() -> None:
    sent: list[dict[str, Any]] = []

    class Recorder(DirectMcpClient):
        def __init__(self) -> None:  # no transport needed
            pass

        async def _request(self, method, params=None, timeout=None):  # type: ignore[override]
            sent.append(params["arguments"])
            return {"structuredContent": {"status": "success", "data": {}}}

    client = Recorder()
    await client.recommend_upgrades("<project/>", "MINOR_PATCH")
    await client.recommend_upgrades("<project/>", "MINOR_PATCH", min_release_age_days=7)
    assert "minReleaseAgeDays" not in sent[0]
    assert sent[1]["minReleaseAgeDays"] == 7


def test_cli_option_is_forwarded_and_defaults_to_none(monkeypatch, tmp_path: Path) -> None:
    seen: list[Optional[int]] = []

    async def fake_run_upgrade(**kwargs: Any) -> int:
        seen.append(kwargs["min_release_age_days"])
        return 0

    monkeypatch.setattr(upgrade, "run_upgrade", fake_run_upgrade)
    runner = CliRunner()
    assert runner.invoke(upgrade.main, ["-f", str(tmp_path / "pom.xml")]).exit_code == 0
    result = runner.invoke(
        upgrade.main, ["-f", str(tmp_path / "pom.xml"), "--min-release-age-days", "7"]
    )
    assert result.exit_code == 0
    assert seen == [None, 7]


async def test_cooldown_value_reaches_the_mcp_call(monkeypatch, tmp_path: Path) -> None:
    pom = tmp_path / "pom.xml"
    pom.write_text("<project/>")
    seen: list[Any] = []

    async def fake_fetch(pom_content, mode, transport, url, min_release_age_days=None):
        seen.append(min_release_age_days)
        return {"status": "success", "data": {"deterministicActions": []}}

    monkeypatch.setattr(upgrade, "_fetch_recommendations", fake_fetch)
    code = await upgrade.run_upgrade(str(pom), "minor_patch", True, min_release_age_days=14)
    assert code == 0 and seen == [14]


# ---------------------------------------------------------------- incomplete results


def _stub_recommendations(monkeypatch, payload: dict[str, Any]) -> None:
    async def fake_fetch(*_args, **_kwargs):
        return {"status": "success", "data": payload}

    monkeypatch.setattr(upgrade, "_fetch_recommendations", fake_fetch)


POM_WITH_PLUGIN = (
    "<project><build><plugins><plugin><artifactId>maven-shade-plugin</artifactId>"
    "<version>3.2.0</version></plugin></plugins></build></project>"
)


async def test_incomplete_result_applies_received_actions_then_exits_non_zero(
    monkeypatch, tmp_path: Path
) -> None:
    pom = tmp_path / "pom.xml"
    pom.write_text(POM_WITH_PLUGIN)
    _stub_recommendations(
        monkeypatch,
        {
            "deterministicActions": [plugin_action("maven-shade-plugin", "3.2.0", "3.6.1")],
            "needsAttention": [],
            "warnings": ["Version lookup failed for 1 coordinate (upstream error): a:b"],
            "incomplete": True,
        },
    )
    code = await upgrade.run_upgrade(str(pom), "minor_patch", False)
    assert code == 1
    assert "<version>3.6.1</version>" in pom.read_text()


async def test_incomplete_result_with_no_actions_still_fails(monkeypatch, tmp_path: Path) -> None:
    pom = tmp_path / "pom.xml"
    pom.write_text(POM_WITH_PLUGIN)
    _stub_recommendations(
        monkeypatch,
        {"deterministicActions": [], "needsAttention": [], "warnings": [], "incomplete": True},
    )
    assert await upgrade.run_upgrade(str(pom), "minor_patch", False) == 1
    assert await upgrade.run_upgrade(str(pom), "minor_patch", True) == 1


async def test_complete_result_exits_zero(monkeypatch, tmp_path: Path) -> None:
    pom = tmp_path / "pom.xml"
    pom.write_text(POM_WITH_PLUGIN)
    _stub_recommendations(
        monkeypatch,
        {
            "deterministicActions": [plugin_action("maven-shade-plugin", "3.2.0", "3.6.1")],
            "needsAttention": [],
            "warnings": [],
        },
    )
    assert await upgrade.run_upgrade(str(pom), "minor_patch", False) == 0
    assert "<version>3.6.1</version>" in pom.read_text()
