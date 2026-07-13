"""
Tests for upgrade workflow helpers.

@author Arvind Menon
"""

from __future__ import annotations

from pathlib import Path

import pytest

from scripts.upgrade import (
    PomUpdater,
    _action_key,
    _apply_deterministic_actions,
    _filter_actions_by_ignored,
    _filter_attention_by_ignored,
    parse_ignored_dependency_keys,
    parse_mcp_response,
    should_use_copilot_session,
)
from src.mcp.direct_client import extract_tool_response_payload, parse_http_mcp_response


def _write_pom(tmp_path: Path, content: str) -> Path:
    pom_path = tmp_path / "pom.xml"
    pom_path.write_text(content)
    return pom_path


def test_update_version_preserves_property_reference(tmp_path: Path) -> None:
    pom_path = _write_pom(
        tmp_path,
        """
        <project>
          <properties>
            <jackson.version>2.15.0</jackson.version>
          </properties>
          <dependencies>
            <dependency>
              <groupId>com.fasterxml.jackson.core</groupId>
              <artifactId>jackson-databind</artifactId>
              <version>${jackson.version}</version>
            </dependency>
          </dependencies>
        </project>
        """,
    )

    updater = PomUpdater(pom_path)
    assert (
        updater.update_version("com.fasterxml.jackson.core", "jackson-databind", "2.18.0") is True
    )

    assert "<version>${jackson.version}</version>" in updater.content
    assert "<jackson.version>2.18.0</jackson.version>" in updater.content


def test_update_parent_version_updates_parent_block(tmp_path: Path) -> None:
    pom_path = _write_pom(
        tmp_path,
        """
        <project>
          <parent>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-parent</artifactId>
            <version>3.4.0</version>
          </parent>
        </project>
        """,
    )

    updater = PomUpdater(pom_path)
    assert updater.update_parent_version("3.4.2") is True
    assert "<version>3.4.2</version>" in updater.content


@pytest.mark.parametrize(
    ("group_id", "artifact_id", "property_tag"),
    [
        ("com.fasterxml.jackson.core", "jackson-databind", "jackson-databind.version"),
        ("org.springframework", "spring-core", "springframework.version"),
        ("org.slf4j", "slf4j-api", "slf4j-api-version"),
    ],
)
def test_update_version_fallback_property_name_patterns(
    tmp_path: Path,
    group_id: str,
    artifact_id: str,
    property_tag: str,
) -> None:
    pom_path = _write_pom(
        tmp_path,
        f"""
        <project>
          <properties>
            <{property_tag}>1.0.0</{property_tag}>
          </properties>
          <dependencies>
            <dependency>
              <groupId>{group_id}</groupId>
              <artifactId>{artifact_id}</artifactId>
            </dependency>
          </dependencies>
        </project>
        """,
    )

    updater = PomUpdater(pom_path)
    assert updater.update_version(group_id, artifact_id, "2.0.0") is True
    assert f"<{property_tag}>2.0.0</{property_tag}>" in updater.content


def test_parse_mcp_response_parses_nested_dependencies() -> None:
    response = {
        "data": {
            "dependencies": [
                {
                    "coordinate": "org.springframework:spring-core",
                    "currentVersion": "6.1.0",
                    "latestVersion": "6.1.3",
                    "updateType": "PATCH",
                }
            ]
        }
    }

    updates = parse_mcp_response(response)

    assert len(updates) == 1
    assert updates[0].coordinate == "org.springframework:spring-core"
    assert updates[0].current_version == "6.1.0"
    assert updates[0].latest_version == "6.1.3"
    assert updates[0].update_type == "patch"


def test_parse_ignored_dependency_keys_merges_cli_and_env() -> None:
    ignored = parse_ignored_dependency_keys(
        ignore_dependencies=(
            "io.modelcontextprotocol.sdk:mcp-bom",
            " org.springframework.boot:spring-boot-starter-parent:3.5.11 ",
        ),
        env_value="""
            com.fasterxml.jackson.core:jackson-databind,
            io.modelcontextprotocol.sdk:mcp-bom
        """,
    )

    assert ignored == {
        "io.modelcontextprotocol.sdk:mcp-bom",
        "org.springframework.boot:spring-boot-starter-parent",
        "com.fasterxml.jackson.core:jackson-databind",
    }


@pytest.mark.parametrize(
    ("mode", "expected"),
    [
        ("minor_patch", False),
        ("all", False),
        ("major", True),
    ],
)
def test_should_use_copilot_session_only_for_major_mode(mode: str, expected: bool) -> None:
    assert should_use_copilot_session(mode) is expected


def test_parse_http_mcp_response_supports_sse_json_rpc() -> None:
    response = parse_http_mcp_response("""
        event: message
        data: {"jsonrpc":"2.0","id":2,"result":{"ok":true}}

        """)

    assert response == {"jsonrpc": "2.0", "id": 2, "result": {"ok": True}}


def test_extract_tool_response_payload_reads_text_content_json() -> None:
    payload = extract_tool_response_payload(
        {
            "content": [
                {
                    "type": "text",
                    "text": '{"data":{"dependencies":[{"coordinate":"junit:junit"}]}}',
                }
            ],
            "isError": False,
        }
    )

    assert payload == {"data": {"dependencies": [{"coordinate": "junit:junit"}]}}


def test_apply_action_updates_dependency_block(tmp_path: Path) -> None:
    pom_path = _write_pom(
        tmp_path,
        """
        <project>
          <dependencies>
            <dependency>
              <groupId>com.fasterxml.jackson.core</groupId>
              <artifactId>jackson-databind</artifactId>
              <version>2.15.0</version>
            </dependency>
          </dependencies>
        </project>
        """,
    )
    updater = PomUpdater(pom_path)
    assert (
        updater.apply_action("com.fasterxml.jackson.core", "jackson-databind", "2.18.0") is True
    )
    assert "<version>2.18.0</version>" in updater.content


def test_apply_action_falls_back_to_parent_block(tmp_path: Path) -> None:
    pom_path = _write_pom(
        tmp_path,
        """
        <project>
          <parent>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-parent</artifactId>
            <version>3.5.9</version>
          </parent>
        </project>
        """,
    )
    updater = PomUpdater(pom_path)
    assert (
        updater.apply_action(
            "org.springframework.boot", "spring-boot-starter-parent", "3.5.11"
        )
        is True
    )
    assert "<version>3.5.11</version>" in updater.content


def test_apply_action_does_not_match_unrelated_parent(tmp_path: Path) -> None:
    pom_path = _write_pom(
        tmp_path,
        """
        <project>
          <parent>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-parent</artifactId>
            <version>3.5.9</version>
          </parent>
        </project>
        """,
    )
    updater = PomUpdater(pom_path)
    assert updater.apply_action("io.example", "some-bom", "1.0.0") is False
    assert "<version>3.5.9</version>" in updater.content


def test_filter_actions_by_ignored_drops_matching_coords() -> None:
    actions = [
        {"kind": "explicit_bump", "groupId": "g1", "artifactId": "a1"},
        {"kind": "bom_bump", "groupId": "g2", "artifactId": "a2"},
    ]
    filtered = _filter_actions_by_ignored(actions, {"g1:a1"})
    assert len(filtered) == 1
    assert filtered[0]["groupId"] == "g2"


def test_filter_attention_by_ignored_drops_matching_coords() -> None:
    attention = [
        {"kind": "major_available", "groupId": "g1", "artifactId": "a1"},
        {"kind": "conflict", "groupId": "g2", "artifactId": "a2"},
    ]
    filtered = _filter_attention_by_ignored(attention, {"g2:a2"})
    assert len(filtered) == 1
    assert filtered[0]["groupId"] == "g1"


def test_action_key_combines_group_and_artifact() -> None:
    assert _action_key({"groupId": "g", "artifactId": "a"}) == "g:a"
    assert _action_key({}) == ":"


def test_apply_deterministic_actions_applies_and_reports_failures(tmp_path: Path) -> None:
    pom_path = _write_pom(
        tmp_path,
        """
        <project>
          <dependencies>
            <dependency>
              <groupId>org.slf4j</groupId>
              <artifactId>slf4j-api</artifactId>
              <version>2.0.16</version>
            </dependency>
          </dependencies>
        </project>
        """,
    )
    updater = PomUpdater(pom_path)
    actions = [
        {
            "kind": "explicit_bump",
            "groupId": "org.slf4j",
            "artifactId": "slf4j-api",
            "current": "2.0.16",
            "target": "2.0.17",
            "updateType": "patch",
        },
        {
            "kind": "bom_bump",
            "groupId": "io.missing",
            "artifactId": "not-in-pom",
            "current": "1.0.0",
            "target": "1.1.0",
            "updateType": "minor",
        },
    ]
    applied, failed = _apply_deterministic_actions(updater, actions)
    assert len(applied) == 1
    assert applied[0]["artifactId"] == "slf4j-api"
    assert len(failed) == 1
    assert failed[0]["artifactId"] == "not-in-pom"
    assert "<version>2.0.17</version>" in updater.content


def test_apply_managed_declaration_action_uses_exact_property_metadata(
    tmp_path: Path,
) -> None:
    pom_path = _write_pom(
        tmp_path,
        """
        <project>
          <properties>
            <shared.platform.line>1.0.0</shared.platform.line>
          </properties>
          <dependencyManagement>
            <dependencies>
              <dependency>
                <groupId>io.fabric8</groupId>
                <artifactId>kubernetes-client</artifactId>
                <version>${shared.platform.line}</version>
              </dependency>
            </dependencies>
          </dependencyManagement>
        </project>
        """,
    )
    updater = PomUpdater(pom_path)
    action = {
        "kind": "managed_decl_bump",
        "groupId": "io.fabric8",
        "artifactId": "kubernetes-client",
        "current": "1.0.0",
        "target": "1.1.0",
        "updateType": "minor",
        "editTarget": "property",
        "propertyName": "shared.platform.line",
        "declaredIn": "dependency_management",
    }

    applied, failed = _apply_deterministic_actions(updater, [action])

    assert applied == [action]
    assert failed == []
    assert "<shared.platform.line>1.1.0</shared.platform.line>" in updater.content
    assert "<version>${shared.platform.line}</version>" in updater.content


def test_apply_plugin_dependency_action_scopes_literal_edit_to_owner_plugin(
    tmp_path: Path,
) -> None:
    pom_path = _write_pom(
        tmp_path,
        """
        <project>
          <dependencies>
            <dependency>
              <groupId>com.puppycrawl.tools</groupId>
              <artifactId>checkstyle</artifactId>
              <version>9.0.0</version>
            </dependency>
          </dependencies>
          <build>
            <plugins>
              <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-checkstyle-plugin</artifactId>
                <dependencies>
                  <dependency>
                    <groupId>com.puppycrawl.tools</groupId>
                    <artifactId>checkstyle</artifactId>
                    <version>10.0.0</version>
                  </dependency>
                </dependencies>
              </plugin>
            </plugins>
            <pluginManagement>
              <plugins>
                <plugin>
                  <groupId>org.apache.maven.plugins</groupId>
                  <artifactId>maven-checkstyle-plugin</artifactId>
                  <dependencies>
                    <dependency>
                      <groupId>com.puppycrawl.tools</groupId>
                      <artifactId>checkstyle</artifactId>
                      <version>8.0.0</version>
                    </dependency>
                  </dependencies>
                </plugin>
              </plugins>
            </pluginManagement>
          </build>
        </project>
        """,
    )
    updater = PomUpdater(pom_path)
    action = {
        "kind": "plugin_dep_bump",
        "groupId": "com.puppycrawl.tools",
        "artifactId": "checkstyle",
        "current": "10.0.0",
        "target": "10.1.0",
        "updateType": "minor",
        "editTarget": "literal_version",
        "declaredIn": "build.plugins.plugin.dependencies",
        "ownerGroupId": "org.apache.maven.plugins",
        "ownerArtifactId": "maven-checkstyle-plugin",
    }

    applied, failed = _apply_deterministic_actions(updater, [action])

    assert applied == [action]
    assert failed == []
    assert "<version>8.0.0</version>" in updater.content
    assert "<version>9.0.0</version>" in updater.content
    assert "<version>10.1.0</version>" in updater.content
    assert "<version>10.0.0</version>" not in updater.content
