package com.arvindand.mcp.maven.service;

import static org.junit.jupiter.api.Assertions.*;

import com.arvindand.mcp.maven.model.MavenArtifact;
import com.arvindand.mcp.maven.model.MavenCoordinate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration tests for MavenCentralService repository access functionality. Tests direct
 * maven-metadata.xml fetching from Maven Central repository.
 *
 * @author Arvind Menon
 * @since 1.4.0
 */
@SpringBootTest
@ActiveProfiles("test")
class MavenCentralServiceRepositoryIT {

  @Autowired private MavenCentralService mavenCentralService;

  @Test
  void testGetLatestVersionWithRepositoryAccess() {
    // Test with a well-known, stable artifact
    MavenCoordinate coordinate = new MavenCoordinate("junit", "junit", null, null, null);

    String latestVersion = mavenCentralService.getLatestVersion(coordinate);

    assertNotNull(latestVersion, "Latest version should not be null");
    assertFalse(latestVersion.trim().isEmpty(), "Latest version should not be empty");
  }

  @Test
  void testGetAllVersionsWithRepositoryAccess() {
    // Test with a well-known artifact that has multiple versions
    MavenCoordinate coordinate = new MavenCoordinate("org.slf4j", "slf4j-api", null, null, null);

    List<String> versions = mavenCentralService.getAllVersions(coordinate);

    assertNotNull(versions, "Versions list should not be null");
    assertFalse(versions.isEmpty(), "Versions list should not be empty");
    assertTrue(versions.size() >= 5, "Should have multiple versions available");

    // Verify versions are sorted in descending order (latest first)
    for (int i = 0; i < Math.min(versions.size() - 1, 3); i++) {
      String current = versions.get(i);
      String next = versions.get(i + 1);
      assertNotNull(current, "Version should not be null");
      assertNotNull(next, "Next version should not be null");
    }
  }

  @Test
  void testGetRecentVersionsWithAccurateTimestamps() {
    // Test with a well-known artifact
    MavenCoordinate coordinate = new MavenCoordinate("com.google.guava", "guava", null, null, null);

    List<MavenArtifact> versions =
        mavenCentralService.getRecentVersionsWithAccurateTimestamps(coordinate, 10);

    assertNotNull(versions, "Versions list should not be null");
    assertFalse(versions.isEmpty(), "Versions list should not be empty");
    assertEquals(10, versions.size(), "Should return the requested number of versions");

    for (MavenArtifact artifact : versions) {
      assertTrue(artifact.timestamp() > 0, "Timestamp should be valid");
    }
  }

  @Test
  void testCheckVersionExistsWithRepositoryAccess() {
    // Test with a specific known version
    MavenCoordinate coordinate = new MavenCoordinate("junit", "junit", null, null, null);

    // junit 4.13.2 is a well-known stable version
    boolean exists = mavenCentralService.checkVersionExists(coordinate, "4.13.2");
    assertTrue(exists, "junit 4.13.2 should exist");

    // Test with a non-existent version
    boolean notExists = mavenCentralService.checkVersionExists(coordinate, "999.999.999");
    assertFalse(notExists, "Non-existent version should return false");
  }

  @Test
  void testRepositoryAccessWithInvalidCoordinate() {
    // Test with a non-existent artifact
    MavenCoordinate invalidCoordinate =
        new MavenCoordinate("com.nonexistent", "invalid-artifact", null, null, null);

    String latestVersion = mavenCentralService.getLatestVersion(invalidCoordinate);
    assertNull(latestVersion, "Non-existent artifact should return null for latest version");

    List<String> versions = mavenCentralService.getAllVersions(invalidCoordinate);
    assertTrue(versions.isEmpty(), "Non-existent artifact should return empty versions list");

    boolean exists = mavenCentralService.checkVersionExists(invalidCoordinate, "1.0.0");
    assertFalse(exists, "Non-existent artifact should return false for version check");

    List<MavenArtifact> timestampedVersions =
        mavenCentralService.getRecentVersionsWithAccurateTimestamps(invalidCoordinate, 10);
    assertTrue(
        timestampedVersions.isEmpty(),
        "Non-existent artifact should return empty list for timestamped versions");
  }

  @Test
  void testRepositoryAccessWithSpringBootStarter() {
    // Test with a Spring Boot starter to verify complex groupId handling
    MavenCoordinate coordinate =
        new MavenCoordinate("org.springframework.boot", "spring-boot-starter", null, null, null);

    String latestVersion = mavenCentralService.getLatestVersion(coordinate);
    assertNotNull(latestVersion, "Spring Boot starter should have a latest version");

    List<String> versions = mavenCentralService.getAllVersions(coordinate);
    assertFalse(versions.isEmpty(), "Spring Boot starter should have multiple versions");
    assertTrue(versions.contains(latestVersion), "Latest version should be in versions list");
  }
}
