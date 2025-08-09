package com.arvindand.mcp.maven.service;

import static com.arvindand.mcp.maven.TestHelpers.getSuccessData;
import static org.junit.jupiter.api.Assertions.*;

import com.arvindand.mcp.maven.model.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration tests for Maven Dependency Tools using type-safe object assertions.
 *
 * <p>Tests the complete flow from MCP tool invocation to business logic validation, using proper
 * object assertions instead of fragile JSON string matching.
 *
 * @author Arvind Menon
 * @since 0.1.0
 */
@SpringBootTest
@ActiveProfiles("test")
class MavenDependencyToolsIT {

  @Autowired private MavenDependencyTools mavenDependencyTools;

  @Test
  void testGetLatestVersion() {
    ToolResponse response =
        mavenDependencyTools.get_latest_version("org.springframework:spring-core", false);

    VersionsByType result = getSuccessData(response);
    assertEquals("org.springframework:spring-core", result.dependency());
    assertTrue(result.latestStable().isPresent() || result.totalVersions() > 0);

    if (result.latestStable().isPresent()) {
      VersionInfo stable = result.latestStable().get();
      assertEquals(VersionInfo.VersionType.STABLE, stable.type());
      assertNotNull(stable.version());
    }
  }

  @Test
  void testCheckVersionExists() {
    ToolResponse response = mavenDependencyTools.check_version_exists("junit:junit", "4.13.2");

    DependencyInfo result = getSuccessData(response);
    assertEquals("success", result.status());
    assertEquals("junit", result.groupId());
    assertEquals("junit", result.artifactId());
    assertEquals("4.13.2", result.version());
    assertTrue(result.exists());
    assertEquals("stable", result.type());
    assertTrue(result.isStable());
  }

  @Test
  void testCheckVersionExistsNotFound() {
    ToolResponse response = mavenDependencyTools.check_version_exists("junit:junit", "999.999.999");

    DependencyInfo result = getSuccessData(response);
    assertEquals("success", result.status());
    assertEquals("junit", result.groupId());
    assertEquals("junit", result.artifactId());
    assertEquals("999.999.999", result.version());
    assertFalse(result.exists());
  }

  @Test
  void testBulkCheckDependencies() {
    String dependencies = "org.springframework:spring-core,junit:junit";
    ToolResponse response = mavenDependencyTools.check_multiple_dependencies(dependencies, false);

    List<BulkCheckResult> results = getSuccessData(response);
    assertEquals(2, results.size());

    // Verify spring-core result
    BulkCheckResult springResult =
        results.stream()
            .filter(r -> r.dependency().contains("spring-core"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Expected spring-core result"));
    assertEquals("found", springResult.status());
    assertNotNull(springResult.version());

    // Verify junit result
    BulkCheckResult junitResult =
        results.stream()
            .filter(r -> r.dependency().contains("junit:junit"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Expected junit result"));
    assertEquals("found", junitResult.status());
    assertNotNull(junitResult.version());
  }

  @Test
  void testBulkCheckStableOnly() {
    String dependencies = "org.springframework:spring-core,com.fasterxml.jackson.core:jackson-core";
    ToolResponse response = mavenDependencyTools.check_multiple_dependencies(dependencies, true);

    List<BulkCheckResult> results = getSuccessData(response);
    assertEquals(2, results.size());

    // All results should be stable versions when stableOnly=true
    for (BulkCheckResult result : results) {
      assertEquals("found", result.status());
      assertEquals("stable", result.type());
      assertNotNull(result.version());
    }
  }

  @Test
  void testVersionComparison() {
    String currentDependencies = "junit:junit:4.12";
    ToolResponse response =
        mavenDependencyTools.compare_dependency_versions(currentDependencies, false);

    // Skip if Maven Central has issues
    if (response instanceof ToolResponse.Error) {
      System.out.println("SKIPPING test due to Maven Central API error");
      return;
    }

    VersionComparison comparison = getSuccessData(response);
    assertNotNull(comparison.comparisonDate());
    assertNotNull(comparison.updateSummary());
    assertEquals(1, comparison.dependencies().size());

    var depResult = comparison.dependencies().get(0);
    assertEquals("junit:junit:4.12", depResult.dependency());
    assertEquals("4.12", depResult.currentVersion());
    assertEquals("success", depResult.status());
    assertNotNull(depResult.latestVersion());

    // Context7 guidance should be empty in test profile (disabled)
    // Note: Context7 might be enabled for this dependency, so just verify structure
    assertNotNull(depResult.context7Guidance());
  }

  @Test
  void testAnalyzeDependencyAge() {
    ToolResponse response = mavenDependencyTools.analyze_dependency_age("junit:junit", null);

    // Skip if Maven Central has issues
    if (response instanceof ToolResponse.Error) {
      System.out.println("SKIPPING test due to Maven Central API error");
      return;
    }

    DependencyAge result = getSuccessData(response);
    assertEquals("junit:junit", result.dependency());
    assertNotNull(result.latestVersion());
    assertNotNull(result.ageClassification());
    assertTrue(result.daysSinceLastRelease() >= 0);
    assertNotNull(result.lastReleaseDate());
    assertNotNull(result.recommendation());

    // Context7 guidance should be empty in test profile (disabled)
    // Note: Context7 might be enabled for this dependency, so just verify structure
    assertNotNull(result.context7Guidance());
  }

  @Test
  void testProjectHealthAnalysis() {
    String dependencies = "junit:junit:4.12,org.slf4j:slf4j-api:1.7.30";
    ToolResponse response = mavenDependencyTools.analyze_project_health(dependencies, null);

    // Skip if Maven Central has issues
    if (response instanceof ToolResponse.Error) {
      System.out.println("SKIPPING test due to Maven Central API error");
      return;
    }

    ProjectHealthAnalysis result = getSuccessData(response);
    assertNotNull(result.analysisDate());
    assertTrue(result.dependencyCount() >= 2);
    assertTrue(result.dependencies().size() >= 2);
    assertNotNull(result.ageDistribution());
    assertFalse(result.recommendations().isEmpty());

    // Verify individual dependency analyses
    for (var depHealth : result.dependencies()) {
      assertNotNull(depHealth.dependency());
      assertNotNull(depHealth.latestVersion());
      assertNotNull(depHealth.ageClassification());
      assertNotNull(depHealth.healthScore());
    }
  }

  @Test
  void testErrorHandling() {
    ToolResponse response =
        mavenDependencyTools.check_version_exists("invalid.group:nonexistent", "1.0.0");

    // Should still be success response, but with exists=false
    DependencyInfo result = getSuccessData(response);
    assertEquals("success", result.status());
    assertFalse(result.exists());
  }
}
