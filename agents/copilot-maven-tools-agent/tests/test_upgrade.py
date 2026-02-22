"""
Tests for upgrade workflow helpers.

@author Arvind Menon
"""

from __future__ import annotations

from pathlib import Path

from scripts.upgrade import PomUpdater, parse_mcp_response


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
