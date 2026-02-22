"""
Tests for upgrade workflow helpers.

@author Arvind Menon
"""

from __future__ import annotations

from pathlib import Path

import pytest

from scripts.upgrade import (
    PomUpdater,
    extract_server_same_major_fallback_updates,
    parse_ignored_dependency_keys,
    parse_mcp_response,
)


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
    assert updater.update_version("com.fasterxml.jackson.core", "jackson-databind", "2.18.0") is True

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

def test_extract_server_same_major_fallback_updates_reads_optional_field() -> None:
    response = {
        "data": {
            "dependencies": [
                {
                    "coordinate": "org.springframework.boot:spring-boot-starter-parent",
                    "currentVersion": "3.5.9",
                    "latestVersion": "4.0.0",
                    "updateType": "MAJOR",
                    "sameMajorStableFallback": {
                        "latestVersion": "3.5.11",
                        "updateType": "PATCH",
                    },
                },
                {
                    "coordinate": "org.slf4j:slf4j-api",
                    "currentVersion": "2.0.16",
                    "latestVersion": "2.0.17",
                    "updateType": "PATCH",
                },
            ]
        }
    }

    fallback_updates = extract_server_same_major_fallback_updates(response)

    assert len(fallback_updates) == 1
    assert fallback_updates[0].coordinate == "org.springframework.boot:spring-boot-starter-parent"
    assert fallback_updates[0].current_version == "3.5.9"
    assert fallback_updates[0].latest_version == "3.5.11"
    assert fallback_updates[0].update_type == "patch"


def test_extract_server_same_major_fallback_updates_supports_snake_case_payload() -> None:
    response = {
        "dependencies": [
            {
                "dependency": "org.springframework.boot:spring-boot-starter-parent",
                "current_version": "3.5.9",
                "latest_version": "4.0.0",
                "update_type": "major",
                "same_major_stable_fallback": {
                    "latest_version": "3.5.11",
                    "update_type": "patch",
                },
            }
        ]
    }

    fallback_updates = extract_server_same_major_fallback_updates(response)

    assert len(fallback_updates) == 1
    assert fallback_updates[0].latest_version == "3.5.11"
    assert fallback_updates[0].update_type == "patch"
