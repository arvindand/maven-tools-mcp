package com.arvindand.mcp.maven.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.arvindand.mcp.maven.model.MavenCoordinate;
import com.arvindand.mcp.maven.model.StabilityFilter;
import com.arvindand.mcp.maven.model.ToolResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Performance tests for Maven Dependency Tools to validate that our optimizations actually improve
 * performance rather than hurt it.
 *
 * @author Arvind Menon
 * @since 0.1.0
 */
@SpringBootTest
@ActiveProfiles("test")
class MavenDependencyToolsPerformanceIT {

  @Autowired private MavenDependencyTools mavenDependencyTools;

  @Autowired private MavenCentralService mavenCentralService;

  private static final String SMALL_DEPENDENCY_LIST = "org.springframework:spring-core,junit:junit";

  private static final String MEDIUM_DEPENDENCY_LIST =
      "org.springframework:spring-core,junit:junit,com.fasterxml.jackson.core:jackson-core,"
          + "org.apache.commons:commons-lang3,com.google.guava:guava";

  private static final String LARGE_DEPENDENCY_LIST =
      "org.springframework:spring-core,junit:junit,com.fasterxml.jackson.core:jackson-core,"
          + "org.apache.commons:commons-lang3,com.google.guava:guava,org.slf4j:slf4j-api,"
          + "ch.qos.logback:logback-classic,org.apache.httpcomponents:httpclient,"
          + "com.squareup.okhttp3:okhttp,org.apache.maven:maven-core";

  @Test
  void testSmallBulkCheckLatestPerformance() {
    Instant start = Instant.now();
    ToolResponse resp =
        mavenDependencyTools.check_multiple_dependencies(
            SMALL_DEPENDENCY_LIST, StabilityFilter.ALL);
    Duration duration = Duration.between(start, Instant.now());

    System.out.println("Small bulk check (2 deps) took: " + duration.toMillis() + "ms");
    assertNotNull(resp);
    assertTrue(duration.toSeconds() < 10, "Small bulk check should complete in under 10 seconds");
  }

  @Test
  void testMediumBulkCheckLatestPerformance() {
    Instant start = Instant.now();
    ToolResponse resp =
        mavenDependencyTools.check_multiple_dependencies(
            MEDIUM_DEPENDENCY_LIST, StabilityFilter.ALL);
    Duration duration = Duration.between(start, Instant.now());

    System.out.println("Medium bulk check (5 deps) took: " + duration.toMillis() + "ms");
    assertNotNull(resp);
    assertTrue(duration.toSeconds() < 20, "Medium bulk check should complete in under 20 seconds");
  }

  @Test
  void testLargeBulkCheckLatestPerformance() {
    Instant start = Instant.now();
    ToolResponse resp =
        mavenDependencyTools.check_multiple_dependencies(
            LARGE_DEPENDENCY_LIST, StabilityFilter.ALL);
    Duration duration = Duration.between(start, Instant.now());

    System.out.println("Large bulk check (10 deps) took: " + duration.toMillis() + "ms");
    assertNotNull(resp);
    assertTrue(duration.toSeconds() < 40, "Large bulk check should complete in under 40 seconds");
  }

  @Test
  void testBulkStablePerformance() {
    Instant start = Instant.now();
    ToolResponse resp =
        mavenDependencyTools.check_multiple_dependencies(
            MEDIUM_DEPENDENCY_LIST, StabilityFilter.STABLE_ONLY);
    Duration duration = Duration.between(start, Instant.now());

    System.out.println("Bulk stable check (5 deps) took: " + duration.toMillis() + "ms");
    assertNotNull(resp);
    assertTrue(duration.toSeconds() < 20, "Bulk stable check should complete in under 20 seconds");
  }

  @Test
  void testCompareVersionsPerformance() {
    String currentDependencies =
        "org.springframework:spring-core:5.0.0,junit:junit:4.10,"
            + "com.fasterxml.jackson.core:jackson-core:2.10.0";

    Instant start = Instant.now();
    ToolResponse resp =
        mavenDependencyTools.compare_dependency_versions(
            currentDependencies, StabilityFilter.ALL, false);
    Duration duration = Duration.between(start, Instant.now());

    System.out.println("Version comparison (3 deps) took: " + duration.toMillis() + "ms");
    assertNotNull(resp);
    assertTrue(duration.toSeconds() < 15, "Version comparison should complete in under 15 seconds");
  }

  @Test
  void testIndividualCallPerformance() {
    // Test that individual calls are reasonably fast
    Instant start = Instant.now();
    ToolResponse resp =
        mavenDependencyTools.get_latest_version(
            "org.springframework:spring-core", StabilityFilter.ALL);
    Duration duration = Duration.between(start, Instant.now());

    System.out.println("Individual get_latest_version took: " + duration.toMillis() + "ms");
    assertNotNull(resp);
    assertTrue(duration.toSeconds() < 5, "Individual call should complete in under 5 seconds");
  }

  @Test
  void testCachingEffectiveness() {
    String dependency = "com.github.ben-manes.caffeine:caffeine";
    MavenCoordinate coordinate =
        MavenCoordinate.of("com.github.ben-manes.caffeine", "caffeine", null);
    ToolResponse first = mavenDependencyTools.get_latest_version(dependency, StabilityFilter.ALL);
    List<String> cachedVersions = mavenCentralService.getAllVersions(coordinate);
    ToolResponse second = mavenDependencyTools.get_latest_version(dependency, StabilityFilter.ALL);

    assertInstanceOf(ToolResponse.Success.class, first);
    assertInstanceOf(ToolResponse.Success.class, second);
    assertFalse(cachedVersions.isEmpty(), "The metadata cache must contain real versions");
    // Full tool calls may perform other network work. Object identity verifies that the
    // proxied metadata lookup reuses its cached result without relying on runner timing.
    assertSame(cachedVersions, mavenCentralService.getAllVersions(coordinate));
  }
}
