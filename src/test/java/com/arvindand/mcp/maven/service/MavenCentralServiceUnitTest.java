package com.arvindand.mcp.maven.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.arvindand.mcp.maven.config.MavenCentralProperties;
import com.arvindand.mcp.maven.model.MavenCoordinate;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for MavenCentralService. Tests the service behavior with utility methods and models.
 *
 * @author Arvind Menon
 * @since 0.1.0
 */
class MavenCentralServiceUnitTest {

  private MavenCentralProperties properties;

  @BeforeEach
  void setUp() {
    properties =
        new MavenCentralProperties("https://repo1.maven.org/maven2", Duration.ofSeconds(10), 100);
  }

  /** Tests version comparison and ordering. */
  @Test
  void testVersionOrdering() {
    // Given versions in random order
    String[] versions = {"1.0.0", "2.1.0", "2.0.0", "1.5.0", "2.0.1"};

    // When getting latest
    String latest = com.arvindand.mcp.maven.util.VersionComparator.getLatest(versions);

    // Then
    assertThat(latest).isEqualTo("2.1.0");
  }

  /** Tests Maven coordinate parsing. */
  @Test
  void testMavenCoordinateParsing() {
    // Given
    String coordinateString = "org.springframework:spring-core:6.1.4:jar";

    // When
    MavenCoordinate coordinate =
        com.arvindand.mcp.maven.util.MavenCoordinateParser.parse(coordinateString);

    // Then
    assertThat(coordinate.groupId()).isEqualTo("org.springframework");
    assertThat(coordinate.artifactId()).isEqualTo("spring-core");
    assertThat(coordinate.version()).isEqualTo("6.1.4");
    assertThat(coordinate.packaging()).isEqualTo("jar");
  }

  /** Tests invalid coordinate parsing. */
  @Test
  void testInvalidCoordinateParsing() {
    // Given
    String invalidCoordinate = "invalid";

    // When & Then
    assertThatThrownBy(
            () -> com.arvindand.mcp.maven.util.MavenCoordinateParser.parse(invalidCoordinate))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Invalid Maven coordinate format");
  }

  /** Tests coordinate string formatting. */
  @Test
  void testCoordinateStringFormatting() {
    // Given
    MavenCoordinate coordinate =
        new MavenCoordinate("org.springframework", "spring-core", "6.1.4", "jar", null);

    // When
    String coordinateString = coordinate.toCoordinateString();

    // Then
    assertThat(coordinateString).isEqualTo("org.springframework:spring-core:6.1.4:jar");
  }

  /** Tests MavenCoordinate static factory methods. */
  @Test
  void testMavenCoordinateFactoryMethods() {
    // Test of() with groupId and artifactId only
    MavenCoordinate coord1 = MavenCoordinate.of("org.springframework", "spring-core");
    assertThat(coord1.groupId()).isEqualTo("org.springframework");
    assertThat(coord1.artifactId()).isEqualTo("spring-core");
    assertThat(coord1.version()).isNull();
    assertThat(coord1.packaging()).isNull();
    assertThat(coord1.classifier()).isNull();

    // Test of() with groupId, artifactId, and version
    MavenCoordinate coord2 = MavenCoordinate.of("org.springframework", "spring-core", "6.1.4");
    assertThat(coord2.groupId()).isEqualTo("org.springframework");
    assertThat(coord2.artifactId()).isEqualTo("spring-core");
    assertThat(coord2.version()).isEqualTo("6.1.4");
    assertThat(coord2.packaging()).isNull();
    assertThat(coord2.classifier()).isNull();
  }

  /** Tests properties configuration. */
  @Test
  void testPropertiesConfiguration() {
    assertThat(properties.repositoryBaseUrl()).isEqualTo("https://repo1.maven.org/maven2");
    assertThat(properties.timeout()).isEqualTo(Duration.ofSeconds(10));
    assertThat(properties.maxResults()).isEqualTo(100);
  }
}
