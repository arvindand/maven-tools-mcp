package com.arvindand.mcp.maven.util;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.arvindand.mcp.maven.model.MavenCoordinate;

/**
 * Comprehensive unit tests for MavenCoordinateParser.
 *
 * @author Arvind Menon
 * @since 0.1.0
 */
class MavenCoordinateParserTest {

  /** Test data for valid coordinate parsing. */
  @SuppressWarnings("unused")
  private static Stream<Arguments> validCoordinateTestData() {
    return Stream.of(
        // Basic groupId:artifactId
        Arguments.of(
            "org.springframework:spring-core",
            "org.springframework",
            "spring-core",
            null,
            null,
            null),

        // With version
        Arguments.of(
            "org.springframework:spring-core:6.1.4",
            "org.springframework",
            "spring-core",
            "6.1.4",
            null,
            null),

        // With version and packaging
        Arguments.of(
            "org.springframework:spring-core:6.1.4:jar",
            "org.springframework",
            "spring-core",
            "6.1.4",
            "jar",
            null),

        // With version, packaging, and classifier
        Arguments.of(
            "org.springframework:spring-core:6.1.4:jar:sources",
            "org.springframework",
            "spring-core",
            "6.1.4",
            "jar",
            "sources"),

        // POM packaging
        Arguments.of(
            "org.springframework.boot:spring-boot-starter-parent:3.2.0:pom",
            "org.springframework.boot",
            "spring-boot-starter-parent",
            "3.2.0",
            "pom",
            null),

        // With classifier but no explicit packaging (empty packaging becomes null)
        Arguments.of(
            "org.springframework:spring-core:6.1.4::sources",
            "org.springframework",
            "spring-core",
            "6.1.4",
            null,
            "sources"),

        // Complex version strings
        Arguments.of(
            "com.example:test-artifact:1.0.0-RC1",
            "com.example",
            "test-artifact",
            "1.0.0-RC1",
            null,
            null),

        // Long groupId with many segments
        Arguments.of(
            "com.company.department.team:artifact-name:2.5.1",
            "com.company.department.team",
            "artifact-name",
            "2.5.1",
            null,
            null),

        // Artifact with numbers and hyphens
        Arguments.of(
            "org.apache.commons:commons-lang3:3.12.0",
            "org.apache.commons",
            "commons-lang3",
            "3.12.0",
            null,
            null),

        // Snapshot version
        Arguments.of(
            "com.example:test:1.0.0-SNAPSHOT:jar",
            "com.example",
            "test",
            "1.0.0-SNAPSHOT",
            "jar",
            null));
  }

  @ParameterizedTest(name = "Parse {0}")
  @MethodSource("validCoordinateTestData")
  void testParse_ValidCoordinates(
      String coordinateString,
      String expectedGroupId,
      String expectedArtifactId,
      String expectedVersion,
      String expectedPackaging,
      String expectedClassifier) {

    // When
    MavenCoordinate result = MavenCoordinateParser.parse(coordinateString);

    // Then
    assertThat(result.groupId()).isEqualTo(expectedGroupId);
    assertThat(result.artifactId()).isEqualTo(expectedArtifactId);
    assertThat(result.version()).isEqualTo(expectedVersion);
    assertThat(result.packaging()).isEqualTo(expectedPackaging);
    assertThat(result.classifier()).isEqualTo(expectedClassifier);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "invalid", // Too few parts
        ":", // Empty parts
        ":::", // Only separators
        "group:", // Missing artifactId
        ":artifact", // Missing groupId
        "", // Empty string
        "   ", // Whitespace only
        "group:artifact:version:packaging:classifier:extra", // Too many parts
        "group::version", // Missing artifactId (empty)
        ":artifact:version" // Missing groupId (empty)
      })
  void testParse_InvalidCoordinates(String invalidCoordinate) {
    // When & Then - Expect either invalid format or empty groupId/artifactId messages
    assertThatThrownBy(() -> MavenCoordinateParser.parse(invalidCoordinate))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageMatching(
            ".*(Invalid Maven coordinate format|Dependency string cannot be null or empty|GroupId and artifactId cannot be empty).*");
  }

  @Test
  void testParse_NullInput() {
    // When & Then
    assertThatThrownBy(() -> MavenCoordinateParser.parse(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Dependency string cannot be null or empty");
  }

  @Test
  void testParse_WithWhitespace() {
    // Given
    String coordinateWithSpaces = "  org.springframework : spring-core : 6.1.4  ";

    // When
    MavenCoordinate result = MavenCoordinateParser.parse(coordinateWithSpaces);

    // Then - Should trim whitespace from each part
    assertThat(result.groupId()).isEqualTo("org.springframework");
    assertThat(result.artifactId()).isEqualTo("spring-core");
    assertThat(result.version()).isEqualTo("6.1.4");
  }

  @Test
  void testParse_EmptyVersionString() {
    // Given - Empty version part
    String coordinate = "org.springframework:spring-core:";

    // When
    MavenCoordinate result = MavenCoordinateParser.parse(coordinate);

    // Then - Empty version should be converted to null
    assertThat(result.groupId()).isEqualTo("org.springframework");
    assertThat(result.artifactId()).isEqualTo("spring-core");
    assertThat(result.version()).isNull();
  }

  @Test
  void testParse_EmptyPackagingString() {
    // Given - Empty packaging part
    String coordinate = "org.springframework:spring-core:6.1.4:";

    // When
    MavenCoordinate result = MavenCoordinateParser.parse(coordinate);

    // Then - Empty packaging should be converted to null
    assertThat(result.groupId()).isEqualTo("org.springframework");
    assertThat(result.artifactId()).isEqualTo("spring-core");
    assertThat(result.version()).isEqualTo("6.1.4");
    assertThat(result.packaging()).isNull();
  }

  @Test
  void testParse_EmptyClassifierString() {
    // Given - Empty classifier part
    String coordinate = "org.springframework:spring-core:6.1.4:jar:";

    // When
    MavenCoordinate result = MavenCoordinateParser.parse(coordinate);

    // Then - Empty classifier should be converted to null
    assertThat(result.groupId()).isEqualTo("org.springframework");
    assertThat(result.artifactId()).isEqualTo("spring-core");
    assertThat(result.version()).isEqualTo("6.1.4");
    assertThat(result.packaging()).isEqualTo("jar");
    assertThat(result.classifier()).isNull();
  }

  @Test
  void testParse_SpecialCharactersInNames() {
    // Given - Coordinates with special characters that should be valid
    String coordinate = "com.example-company:my-artifact_name:1.0.0-beta.1:jar";

    // When
    MavenCoordinate result = MavenCoordinateParser.parse(coordinate);

    // Then
    assertThat(result.groupId()).isEqualTo("com.example-company");
    assertThat(result.artifactId()).isEqualTo("my-artifact_name");
    assertThat(result.version()).isEqualTo("1.0.0-beta.1");
    assertThat(result.packaging()).isEqualTo("jar");
  }

  @Test
  void testParse_NumericVersions() {
    // Given - Various numeric version formats
    String coordinate1 = "com.example:test:1:jar";
    String coordinate2 = "com.example:test:1.0:jar";
    String coordinate3 = "com.example:test:1.0.0:jar";

    // When
    MavenCoordinate result1 = MavenCoordinateParser.parse(coordinate1);
    MavenCoordinate result2 = MavenCoordinateParser.parse(coordinate2);
    MavenCoordinate result3 = MavenCoordinateParser.parse(coordinate3);

    // Then
    assertThat(result1.version()).isEqualTo("1");
    assertThat(result2.version()).isEqualTo("1.0");
    assertThat(result3.version()).isEqualTo("1.0.0");
  }

  @Test
  void testParse_ConsistencyWithToCoordinateString() {
    // Given - A coordinate string
    String originalCoordinate = "org.springframework:spring-core:6.1.4:jar:sources";

    // When - Parse and convert back to string
    MavenCoordinate parsed = MavenCoordinateParser.parse(originalCoordinate);
    String reconstructed = parsed.toCoordinateString();

    // Then - Should be identical
    assertThat(reconstructed).isEqualTo(originalCoordinate);
  }

  @Test
  void testParse_MinimalCoordinate() {
    // Given - Minimal valid coordinate
    String coordinate = "g:a";

    // When
    MavenCoordinate result = MavenCoordinateParser.parse(coordinate);

    // Then
    assertThat(result.groupId()).isEqualTo("g");
    assertThat(result.artifactId()).isEqualTo("a");
    assertThat(result.version()).isNull();
    assertThat(result.packaging()).isNull();
    assertThat(result.classifier()).isNull();
  }

  @Test
  void testParse_RealWorldExamples() {
    // Test with real-world Maven coordinates

    // Spring Boot Starter
    MavenCoordinate springBoot =
        MavenCoordinateParser.parse("org.springframework.boot:spring-boot-starter:3.2.0");
    assertThat(springBoot.groupId()).isEqualTo("org.springframework.boot");
    assertThat(springBoot.artifactId()).isEqualTo("spring-boot-starter");
    assertThat(springBoot.version()).isEqualTo("3.2.0");

    // Jackson with classifier
    MavenCoordinate jackson =
        MavenCoordinateParser.parse("com.fasterxml.jackson.core:jackson-core:2.15.2:jar:sources");
    assertThat(jackson.groupId()).isEqualTo("com.fasterxml.jackson.core");
    assertThat(jackson.artifactId()).isEqualTo("jackson-core");
    assertThat(jackson.version()).isEqualTo("2.15.2");
    assertThat(jackson.packaging()).isEqualTo("jar");
    assertThat(jackson.classifier()).isEqualTo("sources");

    // Apache Commons
    MavenCoordinate commons =
        MavenCoordinateParser.parse("org.apache.commons:commons-lang3:3.12.0");
    assertThat(commons.groupId()).isEqualTo("org.apache.commons");
    assertThat(commons.artifactId()).isEqualTo("commons-lang3");
    assertThat(commons.version()).isEqualTo("3.12.0");
  }

  @Test
  void testParse_EdgeCaseVersionFormats() {
    // Test various version formats that should be valid
    String[] validVersions = {
      "1",
      "1.0",
      "1.0.0",
      "1.0.0-SNAPSHOT",
      "1.0.0-RC1",
      "1.0.0.RELEASE",
      "1.0.0.Final",
      "2021.0.0",
      "1.0.0-alpha.1",
      "1.0.0+build.1"
    };

    for (String version : validVersions) {
      String coordinate = "com.example:test:" + version;
      MavenCoordinate result = MavenCoordinateParser.parse(coordinate);
      assertThat(result.version()).isEqualTo(version);
    }
  }
}
