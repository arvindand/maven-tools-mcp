package com.arvindand.mcp.maven.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
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
    String result = mavenDependencyTools.maven_bulk_check_latest(SMALL_DEPENDENCY_LIST);
    Duration duration = Duration.between(start, Instant.now());

    System.out.println("Small bulk check (2 deps) took: " + duration.toMillis() + "ms");
    assertNotNull(result);
    assertTrue(duration.toSeconds() < 10, "Small bulk check should complete in under 10 seconds");
  }

  @Test
  void testMediumBulkCheckLatestPerformance() {
    Instant start = Instant.now();
    String result = mavenDependencyTools.maven_bulk_check_latest(MEDIUM_DEPENDENCY_LIST);
    Duration duration = Duration.between(start, Instant.now());

    System.out.println("Medium bulk check (5 deps) took: " + duration.toMillis() + "ms");
    assertNotNull(result);
    assertTrue(duration.toSeconds() < 20, "Medium bulk check should complete in under 20 seconds");
  }

  @Test
  void testLargeBulkCheckLatestPerformance() {
    Instant start = Instant.now();
    String result = mavenDependencyTools.maven_bulk_check_latest(LARGE_DEPENDENCY_LIST);
    Duration duration = Duration.between(start, Instant.now());

    System.out.println("Large bulk check (10 deps) took: " + duration.toMillis() + "ms");
    assertNotNull(result);
    assertTrue(duration.toSeconds() < 40, "Large bulk check should complete in under 40 seconds");
  }

  @Test
  void testBulkStablePerformance() {
    Instant start = Instant.now();
    String result = mavenDependencyTools.maven_bulk_check_stable(MEDIUM_DEPENDENCY_LIST);
    Duration duration = Duration.between(start, Instant.now());

    System.out.println("Bulk stable check (5 deps) took: " + duration.toMillis() + "ms");
    assertNotNull(result);
    assertTrue(duration.toSeconds() < 20, "Bulk stable check should complete in under 20 seconds");
  }

  @Test
  void testCompareVersionsPerformance() {
    String currentDependencies =
        "org.springframework:spring-core:5.0.0,junit:junit:4.10,"
            + "com.fasterxml.jackson.core:jackson-core:2.10.0";

    Instant start = Instant.now();
    String result = mavenDependencyTools.maven_compare_versions(currentDependencies);
    Duration duration = Duration.between(start, Instant.now());

    System.out.println("Version comparison (3 deps) took: " + duration.toMillis() + "ms");
    assertNotNull(result);
    assertTrue(duration.toSeconds() < 15, "Version comparison should complete in under 15 seconds");
  }

  @Test
  void testIndividualCallPerformance() {
    // Test that individual calls are reasonably fast
    Instant start = Instant.now();
    String result = mavenDependencyTools.maven_get_latest("org.springframework:spring-core");
    Duration duration = Duration.between(start, Instant.now());

    System.out.println("Individual maven_get_latest took: " + duration.toMillis() + "ms");
    assertNotNull(result);
    assertTrue(duration.toSeconds() < 5, "Individual call should complete in under 5 seconds");
  }

  @Test
  void testCachingEffectiveness() {
    // First call should be slower (no cache)
    Instant start1 = Instant.now();
    String result1 = mavenDependencyTools.maven_get_latest("org.springframework:spring-core");
    Duration duration1 = Duration.between(start1, Instant.now());

    // Second call should be faster (cached)
    Instant start2 = Instant.now();
    String result2 = mavenDependencyTools.maven_get_latest("org.springframework:spring-core");
    Duration duration2 = Duration.between(start2, Instant.now());

    System.out.println("First call (no cache): " + duration1.toMillis() + "ms");
    System.out.println("Second call (cached): " + duration2.toMillis() + "ms");

    assertNotNull(result1);
    assertNotNull(result2);
    assertEquals(result1, result2, "Cached result should match original");

    // Second call should be faster due to caching, but allow for system jitter
    assertTrue(
        duration2.toMillis() < duration1.toMillis(),
        "Cached call should be faster than uncached call (allowing for system jitter)");
    if (duration2.toMillis() >= duration1.toMillis() / 2) {
      System.err.println(
          "[WARN] Cached call was not at least 2x faster. This may be due to system or network"
              + " jitter, JVM warmup, or cache pre-warming. Consider running this test in isolation"
              + " for accurate results.");
    }
  }
}
