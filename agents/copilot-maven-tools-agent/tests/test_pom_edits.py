"""Regression tests for precise, stale-safe POM edits.

@author Arvind Menon
"""

import pytest

from src.analysis.pom_edits import PomEdits
from src.mcp.direct_client import extract_tool_response_payload


def dep(artifact="a", version="1"):
    return (
        f"<dependency><groupId>g</groupId><artifactId>{artifact}</artifactId>"
        f"<version>{version}</version></dependency>"
    )


def action(artifact="a", target="2", **metadata):
    return dict(groupId="g", artifactId=artifact, current="1", target=target, **metadata)


def test_root_management_action_leaves_profile_and_direct_dependency_unchanged():
    declaration = dep()
    source = f"""<project xmlns="http://maven.apache.org/POM/4.0.0">
      <!-- café: preserve UTF-8 and formatting -->
      <dependencyManagement><dependencies>{declaration}</dependencies></dependencyManagement>
      <dependencies>{declaration}</dependencies>
      <profiles><profile><id>legacy</id><dependencyManagement><dependencies>
      {declaration}</dependencies></dependencyManagement></profile></profiles></project>"""
    updated, applied, failed = PomEdits(source).apply(
        [action(declaredIn="dependency_management", editTarget="literal_version")]
    )
    assert len(applied) == 1 and not failed
    assert updated == source.replace("<version>1</version>", "<version>2</version>", 1)


def test_plugin_owner_group_is_part_of_the_selector():
    plugin = (
        "<plugin><groupId>{}</groupId><artifactId>plugin</artifactId><dependencies>"
        + dep()
        + "</dependencies></plugin>"
    )
    source = (
        "<project><build><plugins>"
        + plugin.format("other")
        + plugin.format("owner")
        + "</plugins></build></project>"
    )
    updated, applied, failed = PomEdits(source).apply(
        [
            action(
                declaredIn="build.plugins.plugin.dependencies",
                ownerGroupId="owner",
                ownerArtifactId="plugin",
            )
        ]
    )
    assert len(applied) == 1 and not failed
    assert updated == source.replace(
        plugin.format("owner"), plugin.format("owner").replace("<version>1", "<version>2")
    )


def test_stale_version_and_duplicate_declarations_are_rejected():
    for source in [
        "<project><dependencies>" + dep(version="3") + "</dependencies></project>",
        "<project><dependencies>" + dep() + dep() + "</dependencies></project>",
    ]:
        updated, applied, failed = PomEdits(source).apply([action(kind="explicit_bump")])
        assert updated == source and not applied and len(failed) == 1


def test_shared_property_requires_complete_matching_actions():
    source = (
        "<project><properties><line>1</line></properties><dependencies>"
        + dep(version="${line}")
        + dep("b", "${line}")
        + "</dependencies></project>"
    )
    for actions in [[action()], [action(), action("b", "3")]]:
        updated, applied, failed = PomEdits(source).apply(actions)
        assert updated == source and not applied and failed
    updated, applied, failed = PomEdits(source).apply([action(), action("b")])
    assert len(applied) == 2 and not failed
    assert updated == source.replace("<line>1</line>", "<line>2</line>")


def test_comment_or_profile_reference_cannot_be_silently_changed():
    source = (
        "<project><properties><line>1</line></properties><!-- ${line} --><dependencies>"
        + dep(version="${line}")
        + "</dependencies></project>"
    )
    updated, applied, failed = PomEdits(source).apply([action()])
    assert updated == source and not applied and failed


def test_rejects_dtd_and_xml_injection_in_versions():
    with pytest.raises(ValueError, match="DTD"):
        PomEdits('<!DOCTYPE project [<!ENTITY test "x">]><project/>')
    source = "<project><dependencies>" + dep() + "</dependencies></project>"
    updated, applied, failed = PomEdits(source).apply([action(target="2</version>")])
    assert updated == source and not applied and failed


@pytest.mark.parametrize(
    "envelope",
    [
        {"structuredContent": {"status": "error", "message": "Unavailable"}},
        {"content": [{"type": "text", "text": '{"status":"error","message":"Unavailable"}'}]},
    ],
)
def test_application_errors_are_not_reported_as_no_updates(envelope):
    with pytest.raises(RuntimeError, match="application error"):
        extract_tool_response_payload(envelope)
