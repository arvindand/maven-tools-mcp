"""
Tests for dependency analysis functionality.

@author Arvind Menon
"""

from __future__ import annotations


from src.analysis.dependency import (
    AnalysisResult,
    DependencyAnalyzer,
    DependencyUpdate,
    ParsedDependency,
)


class TestDependencyParsing:
    """Tests for POM parsing functionality."""

    def test_parse_simple_pom(self) -> None:
        """Test parsing a simple POM file."""
        pom_content = """
        <project>
            <dependencies>
                <dependency>
                    <groupId>org.springframework</groupId>
                    <artifactId>spring-core</artifactId>
                    <version>5.3.0</version>
                </dependency>
            </dependencies>
        </project>
        """

        deps = DependencyAnalyzer.parse_pom(pom_content)

        assert len(deps) == 1
        assert deps[0]["groupId"] == "org.springframework"
        assert deps[0]["artifactId"] == "spring-core"
        assert deps[0]["version"] == "5.3.0"

    def test_parse_pom_with_managed_version(self) -> None:
        """Test parsing POM with managed (missing) version."""
        pom_content = """
        <project>
            <dependencies>
                <dependency>
                    <groupId>com.fasterxml.jackson.core</groupId>
                    <artifactId>jackson-databind</artifactId>
                </dependency>
            </dependencies>
        </project>
        """

        deps = DependencyAnalyzer.parse_pom(pom_content)

        assert len(deps) == 1
        assert deps[0]["version"] == "managed"

    def test_parse_pom_multiple_dependencies(self) -> None:
        """Test parsing POM with multiple dependencies."""
        pom_content = """
        <project>
            <dependencies>
                <dependency>
                    <groupId>org.springframework</groupId>
                    <artifactId>spring-core</artifactId>
                    <version>5.3.0</version>
                </dependency>
                <dependency>
                    <groupId>com.fasterxml.jackson.core</groupId>
                    <artifactId>jackson-databind</artifactId>
                    <version>2.13.0</version>
                </dependency>
            </dependencies>
        </project>
        """

        deps = DependencyAnalyzer.parse_pom(pom_content)

        assert len(deps) == 2
        assert deps[0]["artifactId"] == "spring-core"
        assert deps[1]["artifactId"] == "jackson-databind"

    def test_parse_pom_with_namespace(self) -> None:
        """Test parsing POM with Maven namespace."""
        pom_content = """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
            <dependencies>
                <dependency>
                    <groupId>org.junit.jupiter</groupId>
                    <artifactId>junit-jupiter</artifactId>
                    <version>5.10.0</version>
                </dependency>
            </dependencies>
        </project>
        """

        deps = DependencyAnalyzer.parse_pom(pom_content)

        assert len(deps) == 1
        assert deps[0]["artifactId"] == "junit-jupiter"


class TestDependencyUpdate:
    """Tests for DependencyUpdate model."""

    def test_coordinate_property(self) -> None:
        """Test coordinate string generation."""
        update = DependencyUpdate(
            group_id="org.springframework",
            artifact_id="spring-core",
            current_version="5.3.0",
            latest_version="6.0.0",
            update_type="major",
        )

        assert update.coordinate == "org.springframework:spring-core"

    def test_update_types(self) -> None:
        """Test different update types."""
        major = DependencyUpdate("g", "a", "1.0.0", "2.0.0", "major")
        minor = DependencyUpdate("g", "a", "1.0.0", "1.1.0", "minor")
        patch = DependencyUpdate("g", "a", "1.0.0", "1.0.1", "patch")

        assert major.update_type == "major"
        assert minor.update_type == "minor"
        assert patch.update_type == "patch"


class TestAnalysisResult:
    """Tests for AnalysisResult model."""

    def test_has_updates_true(self) -> None:
        """Test has_updates property when updates exist."""
        result = AnalysisResult(
            health_score=75,
            updates=[
                DependencyUpdate(
                    group_id="org.springframework",
                    artifact_id="spring-core",
                    current_version="5.3.0",
                    latest_version="6.0.0",
                    update_type="major",
                )
            ],
        )

        assert result.has_updates is True
        assert result.needs_attention is True

    def test_has_updates_false(self) -> None:
        """Test has_updates property when no updates exist."""
        result = AnalysisResult(health_score=90)

        assert result.has_updates is False

    def test_needs_attention_low_score(self) -> None:
        """Test needs_attention when health score is low."""
        result = AnalysisResult(health_score=50)

        assert result.needs_attention is True

    def test_needs_attention_false(self) -> None:
        """Test needs_attention when everything is healthy."""
        result = AnalysisResult(health_score=85)

        assert result.needs_attention is False

    def test_needs_attention_with_security_issues(self) -> None:
        """Test needs_attention when security issues exist."""
        from src.analysis.dependency import SecurityIssue

        result = AnalysisResult(
            health_score=90,
            security_issues=[
                SecurityIssue(
                    dependency="log4j-core",
                    cve="CVE-2021-44228",
                    severity="critical",
                )
            ],
        )

        assert result.needs_attention is True
        assert result.has_security_issues is True


class TestParsedDependency:
    """Tests for ParsedDependency model."""

    def test_coordinate_property(self) -> None:
        """Test coordinate string generation."""
        dep = ParsedDependency(
            group_id="org.springframework",
            artifact_id="spring-core",
            version="6.0.0",
        )
        assert dep.coordinate == "org.springframework:spring-core"

    def test_coordinate_with_version(self) -> None:
        """Test coordinate with version string generation."""
        dep = ParsedDependency(
            group_id="org.springframework",
            artifact_id="spring-core",
            version="6.0.0",
        )
        assert dep.coordinate_with_version == "org.springframework:spring-core:6.0.0"

    def test_source_default(self) -> None:
        """Test default source is dependency."""
        dep = ParsedDependency("g", "a", "1.0.0")
        assert dep.source == "dependency"

    def test_source_parent(self) -> None:
        """Test parent source."""
        dep = ParsedDependency("g", "a", "1.0.0", source="parent")
        assert dep.source == "parent"


class TestParsePomWithProperties:
    """Tests for parse_pom_with_properties functionality."""

    def test_parse_simple_dependency(self) -> None:
        """Test parsing a dependency with explicit version."""
        pom_content = """
        <project>
            <dependencies>
                <dependency>
                    <groupId>org.springframework</groupId>
                    <artifactId>spring-core</artifactId>
                    <version>6.0.0</version>
                </dependency>
            </dependencies>
        </project>
        """
        deps = DependencyAnalyzer.parse_pom_with_properties(pom_content)

        assert len(deps) == 1
        assert deps[0].group_id == "org.springframework"
        assert deps[0].artifact_id == "spring-core"
        assert deps[0].version == "6.0.0"
        assert deps[0].source == "dependency"

    def test_parse_parent_pom(self) -> None:
        """Test extracting parent POM version."""
        pom_content = """
        <project>
            <parent>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-starter-parent</artifactId>
                <version>3.2.0</version>
            </parent>
            <dependencies>
            </dependencies>
        </project>
        """
        deps = DependencyAnalyzer.parse_pom_with_properties(pom_content)

        assert len(deps) == 1
        assert deps[0].group_id == "org.springframework.boot"
        assert deps[0].artifact_id == "spring-boot-starter-parent"
        assert deps[0].version == "3.2.0"
        assert deps[0].source == "parent"

    def test_parse_property_reference(self) -> None:
        """Test resolving property references in versions."""
        pom_content = """
        <project>
            <properties>
                <jackson.version>2.15.0</jackson.version>
            </properties>
            <dependencies>
                <dependency>
                    <groupId>com.fasterxml.jackson.core</groupId>
                    <artifactId>jackson-core</artifactId>
                    <version>${jackson.version}</version>
                </dependency>
            </dependencies>
        </project>
        """
        deps = DependencyAnalyzer.parse_pom_with_properties(pom_content)

        assert len(deps) == 1
        assert deps[0].version == "2.15.0"

    def test_skip_bom_managed_dependencies(self) -> None:
        """Test that dependencies without versions are skipped."""
        pom_content = """
        <project>
            <dependencies>
                <dependency>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-starter-web</artifactId>
                </dependency>
                <dependency>
                    <groupId>com.h2database</groupId>
                    <artifactId>h2</artifactId>
                    <version>2.2.224</version>
                </dependency>
            </dependencies>
        </project>
        """
        deps = DependencyAnalyzer.parse_pom_with_properties(pom_content)

        assert len(deps) == 1
        assert deps[0].artifact_id == "h2"

    def test_parse_with_namespace(self) -> None:
        """Test parsing POM with XML namespace."""
        pom_content = """
        <project xmlns="http://maven.apache.org/POM/4.0.0"
                 xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                 xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
            <dependencies>
                <dependency>
                    <groupId>org.junit.jupiter</groupId>
                    <artifactId>junit-jupiter</artifactId>
                    <version>5.10.0</version>
                </dependency>
            </dependencies>
        </project>
        """
        deps = DependencyAnalyzer.parse_pom_with_properties(pom_content)

        assert len(deps) == 1
        assert deps[0].artifact_id == "junit-jupiter"


class TestFormatForMcp:
    """Tests for format_for_mcp functionality."""

    def test_format_single_dependency(self) -> None:
        """Test formatting a single dependency."""
        deps = [ParsedDependency("org.springframework", "spring-core", "6.0.0")]
        result = DependencyAnalyzer.format_for_mcp(deps)
        assert result == "org.springframework:spring-core:6.0.0"

    def test_format_multiple_dependencies(self) -> None:
        """Test formatting multiple dependencies."""
        deps = [
            ParsedDependency("org.springframework", "spring-core", "6.0.0"),
            ParsedDependency("com.h2database", "h2", "2.2.224"),
        ]
        result = DependencyAnalyzer.format_for_mcp(deps)
        assert result == "org.springframework:spring-core:6.0.0,com.h2database:h2:2.2.224"

    def test_format_empty_list(self) -> None:
        """Test formatting empty list."""
        result = DependencyAnalyzer.format_for_mcp([])
        assert result == ""
