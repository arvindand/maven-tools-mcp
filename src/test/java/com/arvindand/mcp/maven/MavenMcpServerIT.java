package com.arvindand.mcp.maven;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.arvindand.mcp.maven.model.MavenCoordinate;
import com.arvindand.mcp.maven.service.MavenCentralException;
import com.arvindand.mcp.maven.service.MavenCentralService;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Integration tests for Maven MCP Server functionality.
 *
 * @author Arvind Menon
 * @since 0.1.0
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {"maven.central.timeout=PT10S", "maven.central.max-results=50"})
class MavenMcpServerIT {

  @Autowired private MavenCentralService mavenCentralService;

  /** Provides test data for the parameterized getLatestVersion test. */
  @SuppressWarnings("unused")
  static Stream<Arguments> getLatestVersionTestData() {
    return Stream.of(
        Arguments.of("org.springframework", "spring-core", "Spring Core"),
        Arguments.of("junit", "junit", "JUnit"),
        Arguments.of("com.google.guava", "guava", "Google Guava"));
  }

  /**
   * Tests getting the latest version of various Maven artifacts. This test verifies the complete
   * integration with Maven Central API for different types of artifacts.
   */
  @ParameterizedTest(name = "Getting latest version for {2}")
  @MethodSource("getLatestVersionTestData")
  void testGetLatestVersion(String groupId, String artifactId, String displayName) {
    // Given
    MavenCoordinate coordinate = MavenCoordinate.of(groupId, artifactId);

    // When
    String latestVersion = mavenCentralService.getLatestVersion(coordinate);

    // Then - Version should be a valid version string (may contain dots, letters, numbers, etc.)
    assertThat(latestVersion).isNotNull().isNotEmpty().matches("^[\\w\\.\\-]+$");
    System.out.println(displayName + " latest version: " + latestVersion);
  }

  /**
   * Tests checking if a specific version exists for Spring Core. This tests the version existence
   * checking functionality.
   */
  @Test
  void testCheckVersionExists_SpringCore_ExistingVersion() {
    // Given
    MavenCoordinate coordinate = MavenCoordinate.of("org.springframework", "spring-core");

    // When - checking for version 6.0.0 which should exist
    boolean exists = mavenCentralService.checkVersionExists(coordinate, "6.0.0");

    // Then
    assertThat(exists).isTrue();
    System.out.println("Spring Core 6.0.0 exists: " + exists);
  }

  /**
   * Tests checking if a non-existing version exists. This tests error handling for non-existing
   * versions.
   */
  @Test
  void testCheckVersionExists_NonExistingVersion() {
    // Given
    MavenCoordinate coordinate = MavenCoordinate.of("org.springframework", "spring-core");

    // When - checking for a version that definitely doesn't exist
    boolean exists = mavenCentralService.checkVersionExists(coordinate, "999.999.999");

    // Then
    assertThat(exists).isFalse();
    System.out.println("Spring Core 999.999.999 exists: " + exists);
  }

  /**
   * Tests caching performance by making the same request twice. The second request should be
   * significantly faster due to caching.
   */
  @Test
  void testCaching_Performance() {
    // Given
    MavenCoordinate coordinate = MavenCoordinate.of("org.springframework", "spring-core");

    // When - first request (will hit Maven Central API)
    long startTime1 = System.nanoTime();
    String version1 = mavenCentralService.getLatestVersion(coordinate);
    long duration1 = System.nanoTime() - startTime1;

    // When - second request (should use cache)
    long startTime2 = System.nanoTime();
    String version2 = mavenCentralService.getLatestVersion(coordinate);
    long duration2 = System.nanoTime() - startTime2;

    // Then
    assertThat(version1).isEqualTo(version2);

    // Convert to milliseconds for readability
    long duration1Ms = duration1 / 1_000_000;
    long duration2Ms = duration2 / 1_000_000;

    // Second request should be much faster (cached) - allow for some variance
    // If both are 0ms, the cache is working very efficiently
    assertThat(duration2).isLessThanOrEqualTo(duration1);

    System.out.println("First request took: " + duration1Ms + "ms");
    System.out.println("Second request took: " + duration2Ms + "ms (cached)");
    System.out.println("Performance improvement: " + (duration1Ms - duration2Ms) + "ms");
  }

  /**
   * Tests error handling for invalid group and artifact IDs. This verifies proper exception
   * handling for non-existing artifacts.
   */
  @Test
  void testErrorHandling_InvalidArtifact() {
    // Given
    MavenCoordinate coordinate =
        MavenCoordinate.of("com.nonexistent.invalid", "invalid-artifact-xyz");

    // When & Then
    assertThatThrownBy(() -> mavenCentralService.getLatestVersion(coordinate))
        .isInstanceOf(MavenCentralException.class)
        .hasMessageContaining("No versions found");
  }

  /**
   * Tests getting the latest version for a POM artifact (different packaging type). This ensures
   * our service can handle different Maven packaging types correctly.
   */
  @Test
  void testGetLatestVersion_PomPackaging() {
    // Given - Spring Boot Starter Parent is a POM artifact, not JAR
    MavenCoordinate coordinate =
        new MavenCoordinate(
            "org.springframework.boot", "spring-boot-starter-parent", null, "pom", null);

    // When
    String latestVersion = mavenCentralService.getLatestVersion(coordinate);

    // Then
    assertThat(latestVersion).isNotNull().isNotEmpty().contains(".");
    System.out.println("Spring Boot Starter Parent (POM) latest version: " + latestVersion);
  }
}
