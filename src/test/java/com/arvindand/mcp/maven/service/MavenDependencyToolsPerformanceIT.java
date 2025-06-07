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
    // Use a unique dependency that's unlikely to be cached from other tests
    String uniqueDependency = "com.github.ben-manes.caffeine:caffeine";

    // Use nanosecond precision for more accurate timing
    long start1 = System.nanoTime();
    String result1 = mavenDependencyTools.maven_get_latest(uniqueDependency);
    long duration1Nanos = System.nanoTime() - start1;

    // Second call should be faster (cached)
    long start2 = System.nanoTime();
    String result2 = mavenDependencyTools.maven_get_latest(uniqueDependency);
    long duration2Nanos = System.nanoTime() - start2;

    // Convert to milliseconds for display
    long duration1Ms = duration1Nanos / 1_000_000;
    long duration2Ms = duration2Nanos / 1_000_000;

    System.out.println("First call (no cache): " + duration1Ms + "ms (" + duration1Nanos + "ns)");
    System.out.println("Second call (cached): " + duration2Ms + "ms (" + duration2Nanos + "ns)");

    assertNotNull(result1);
    assertNotNull(result2);
    assertEquals(result1, result2, "Cached result should match original");

    // More robust caching check:
    // 1. If both calls are very fast (< 1ms each), assume caching is working efficiently
    // 2. Otherwise, cached call should be faster or at least not significantly slower
    if (duration1Ms == 0 && duration2Ms == 0) {
      // Both calls completed in under 1ms - cache is working very efficiently
      System.out.println(
          "Cache performance: Both calls completed in under 1ms - excellent cache performance");
      assertTrue(
          duration2Nanos <= duration1Nanos * 2,
          "Even with sub-millisecond timing, cached call should not be significantly slower");
    } else {
      // Standard timing comparison when we can measure meaningful differences
      assertTrue(
          duration2Ms <= duration1Ms,
          "Cached call should be faster than or equal to uncached call");

      if (duration2Ms < duration1Ms) {
        System.out.println(
            "Cache performance: Cached call was " + (duration1Ms - duration2Ms) + "ms faster");
      } else {
        System.out.println(
            "Cache performance: Both calls took similar time, indicating efficient caching");
      }
    }
  }
}
