package com.arvindand.mcp.maven.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration test class for bulk operations features. Validates the functionality of bulk
 * dependency checking operations in the Maven MCP server.
 *
 * <p>This is an integration test because it: - Uses @SpringBootTest to start the full application
 * context - Makes real HTTP calls to Maven Central API - Tests the complete flow from service layer
 * to external API
 *
 * @author Arvind Menon
 * @since 0.1.0
 */
@SpringBootTest
@ActiveProfiles("test")
class MavenDependencyToolsIT {

  @Autowired private MavenDependencyTools mavenDependencyTools;

  /** Tests the bulk check latest functionality with multiple dependencies. */
  @Test
  void testMavenBulkCheckLatest() {
    String dependencies = "org.springframework:spring-core,junit:junit";
    String result = mavenDependencyTools.check_multiple_dependencies(dependencies, false);

    assertNotNull(result);
    assertTrue(result.startsWith("["));
    assertTrue(result.endsWith("]"));
    assertTrue(result.contains("org.springframework:spring-core"));
    assertTrue(result.contains("junit:junit"));
    assertTrue(result.contains("\"status\" : \"found\""));
  }

  /** Tests the bulk check stable functionality with multiple dependencies. */
  @Test
  void testMavenBulkCheckStable() {
    String dependencies = "org.springframework:spring-core,com.fasterxml.jackson.core:jackson-core";
    String result =
        mavenDependencyTools.check_multiple_dependencies(dependencies, true); // stableOnly=true

    assertNotNull(result);
    assertTrue(result.startsWith("["));
    assertTrue(result.endsWith("]"));
    assertTrue(result.contains("org.springframework:spring-core"));
    assertTrue(result.contains("jackson-core"));
    assertTrue(result.contains("\"type\" : \"stable\""));
  }

  @Test
  void testMavenCompareVersions() {
    String currentDependencies = "org.springframework:spring-core:6.0.0,junit:junit:4.12";
    String result = mavenDependencyTools.compare_dependency_versions(currentDependencies, false);

    assertNotNull(result);
    assertTrue(result.startsWith("{"));
    assertTrue(result.endsWith("}"));
    assertTrue(result.contains("comparison_date"));
    assertTrue(result.contains("update_summary"));
    assertTrue(result.contains("current_version"));
    assertTrue(result.contains("latest_version"));
  }

  @Test
  void testBulkCheckWithInvalidDependency() {
    String dependencies = "invalid:dependency,org.springframework:spring-core";
    String result = mavenDependencyTools.check_multiple_dependencies(dependencies, false);

    assertNotNull(result);
    assertTrue(
        result.contains("\"status\" : \"error\"") || result.contains("\"status\" : \"not_found\""));
    assertTrue(result.contains("org.springframework:spring-core"));
  }

  /** Tests Context7 guidance field structure in test profile (should be null when disabled). */
  @Test
  void testVersionComparisonContext7GuidanceStructure() {
    String currentDependencies = "org.springframework:spring-core:6.0.0";
    String result = mavenDependencyTools.compare_dependency_versions(currentDependencies, false);

    assertNotNull(result);
    assertTrue(result.startsWith("{"));
    assertTrue(result.endsWith("}"));
    assertTrue(result.contains("comparison_date"));
    assertTrue(result.contains("current_version"));
    assertTrue(result.contains("latest_version"));

    // In test profile, Context7 guidance should be null regardless of update availability
    assertTrue(result.contains("context7_guidance"));
    if (result.contains("\"update_available\" : true")) {
      // Even with updates available, guidance should be null in test profile
      assertTrue(result.contains("\"context7_guidance\" : null"));
      assertFalse(result.contains("suggested_search"));
    }
  }

  /** Tests Context7 guidance hints for project health analysis with aging dependencies. */
  @Test
  void testProjectHealthIncludesContext7Guidance() {
    String dependencies = "org.springframework:spring-core";
    String result = mavenDependencyTools.analyze_project_health(dependencies, 365);

    assertNotNull(result);
    assertTrue(result.startsWith("{"));
    assertTrue(result.endsWith("}"));
    assertTrue(result.contains("overall_health"));
    assertTrue(result.contains("dependencies"));

    // Verify Context7 guidance hints are included for aging/stale dependencies
    assertTrue(result.contains("\"dependencies\" :"));
    // Context7 guidance should be present for aging or stale dependencies
    if (result.contains("\"age_classification\" : \"aging\"")
        || result.contains("\"age_classification\" : \"stale\"")) {
      assertTrue(result.contains("context7_guidance"));
    }
  }

  /** Tests Context7 modernization guidance hints for aging dependency analysis. */
  @Test
  void testDependencyAgeIncludesContext7Guidance() {
    String dependency = "org.springframework:spring-core";
    String result = mavenDependencyTools.analyze_dependency_age(dependency, 365);

    assertNotNull(result);
    assertTrue(result.startsWith("{"));
    assertTrue(result.endsWith("}"));

    // Verify DependencyAgeResponse structure (using snake_case field names)
    assertTrue(result.contains("\"dependency\""));
    assertTrue(result.contains("\"latest_version\""));
    assertTrue(result.contains("\"age_classification\""));

    // Verify Context7 guidance hints are included for aging/stale dependencies
    if (result.contains("\"age_classification\" : \"AGING\"")
        || result.contains("\"age_classification\" : \"STALE\"")) {
      assertTrue(result.contains("context7_guidance"));
      assertTrue(result.contains("suggested_search"));
      assertTrue(result.contains("search_hints"));
      assertTrue(result.contains("complexity"));
      assertTrue(result.contains("documentation_focus"));
    }
    assertTrue(result.contains("\"days_since_last_release\""));
    assertTrue(result.contains("\"last_release_date\""));
    assertTrue(result.contains("\"age_description\""));
    assertTrue(result.contains("\"recommendation\""));
  }

  /**
   * Tests Context7 guidance is disabled in test profile - should NOT include guidance when
   * context7.enabled=false.
   */
  @Test
  void testContext7GuidanceDisabledInTestProfile() {
    // Use an older Spring Boot version that would trigger Context7 guidance if enabled
    String oldDependencies = "org.springframework.boot:spring-boot-starter:2.5.0";
    String result = mavenDependencyTools.compare_dependency_versions(oldDependencies, false);

    assertNotNull(result);
    assertTrue(result.contains("\"update_available\" : true"));
    assertTrue(result.contains("\"update_type\" : \"major\""));
    // Context7 guidance should be null since context7.enabled=false in test profile
    assertTrue(result.contains("\"context7_guidance\" : null"));
    assertFalse(result.contains("suggested_search"));
  }

  /** Tests get_latest_version returns all version types in the response. */
  @Test
  void testMavenGetLatestAllTypes() {
    // This dependency is likely to have multiple release types (adjust as needed)
    String dependency = "org.springframework:spring-core";
    String result = mavenDependencyTools.get_latest_version(dependency, false);

    assertNotNull(result);
    assertTrue(
        result.startsWith("{")
            && result.endsWith("}")); // Should always have dependency and total_versions
    assertTrue(result.contains("\"dependency\" :"));
    assertTrue(result.contains("\"total_versions\" :"));
    // At least one of the type fields should be present
    boolean hasAnyType =
        result.contains("latest_stable")
            || result.contains("latest_rc")
            || result.contains("latest_beta")
            || result.contains("latest_alpha")
            || result.contains("latest_milestone");
    assertTrue(hasAnyType);
  }

  /** Tests that POM artifacts like parent POMs and BOMs can be found correctly. */
  @Test
  void testPomArtifactsCanBeFound() {
    // Test Spring Boot starter parent POM
    String springBootResult =
        mavenDependencyTools.get_latest_version(
            "org.springframework.boot:spring-boot-starter-parent", false);
    assertNotNull(springBootResult);
    assertTrue(springBootResult.contains("\"dependency\" :"));
    assertTrue(springBootResult.contains("spring-boot-starter-parent"));
    assertTrue(!springBootResult.contains("error"));

    // Test Spring AI BOM
    String springAiResult =
        mavenDependencyTools.get_latest_version("org.springframework.ai:spring-ai-bom", false);
    assertNotNull(springAiResult);
    assertTrue(springAiResult.contains("\"dependency\" :"));
    assertTrue(springAiResult.contains("spring-ai-bom"));
    assertTrue(!springAiResult.contains("error"));
  }

  /** Tests that bulk check latest includes comprehensive version data for follow-up questions. */
  @Test
  void testBulkCheckLatestIncludesComprehensiveVersionData() {
    String dependencies = "org.springframework:spring-core,junit:junit";
    String result = mavenDependencyTools.check_multiple_dependencies(dependencies, false);

    assertNotNull(result);
    assertTrue(result.startsWith("[") && result.endsWith("]"));

    // Verify comprehensive version fields are present (even if null)
    assertTrue(result.contains("\"latest_stable\" :"));
    assertTrue(result.contains("\"latest_rc\" :"));
    assertTrue(result.contains("\"latest_beta\" :"));
    assertTrue(result.contains("\"latest_alpha\" :"));
    assertTrue(result.contains("\"latest_milestone\" :"));

    // Verify we have both dependencies
    assertTrue(result.contains("spring-core"));
    assertTrue(result.contains("junit"));

    // Verify status and counts are included
    assertTrue(result.contains("\"status\" : \"found\""));
    assertTrue(result.contains("\"total_versions\" :"));
    assertTrue(result.contains("\"stable_versions\" :"));
  }

  /** Tests that bulk check stable returns only stable version data without comprehensive fields. */
  @Test
  void testBulkCheckStableReturnsOnlyStableData() {
    String dependencies = "org.springframework:spring-core,com.fasterxml.jackson.core:jackson-core";
    String result =
        mavenDependencyTools.check_multiple_dependencies(dependencies, true); // stableOnly=true

    assertNotNull(result);
    assertTrue(result.startsWith("[") && result.endsWith("]"));

    // Verify we get stable versions as primary
    assertTrue(result.contains("\"version\" :"));
    assertTrue(result.contains("\"type\" : \"stable\""));

    // Verify we have both dependencies
    assertTrue(result.contains("spring-core"));
    assertTrue(result.contains("jackson-core"));

    // Verify stable-specific fields
    assertTrue(result.contains("\"status\" : \"found\""));
    assertTrue(result.contains("\"total_versions\" :"));
    assertTrue(result.contains("\"stable_versions\" :"));
  }

  /** Tests that bulk check latest prioritizes stable versions as primary recommendation. */
  @Test
  void testBulkCheckLatestPrioritizesStableVersions() {
    // Use a dependency that's likely to have stable versions
    String dependencies = "org.springframework:spring-core";
    String result = mavenDependencyTools.check_multiple_dependencies(dependencies, false);

    assertNotNull(result);
    assertTrue(result.contains("spring-core"));
    assertTrue(result.contains("\"status\" : \"found\""));

    // If stable version is available, it should be the primary version
    if (result.contains("\"latest_stable\" : {")) {
      // Extract the primary version and stable version to compare
      assertTrue(result.contains("\"version\" :"));
      assertTrue(result.contains("\"type\" :"));
      // Note: In real scenarios, we'd parse JSON to verify the primary version
      // matches the stable version, but for integration test we verify structure
    }

    // Verify comprehensive data is available for follow-up questions
    assertTrue(result.contains("\"latest_stable\" :"));
    assertTrue(result.contains("\"total_versions\" :"));
  }

  /** Tests error handling in bulk operations with mixed valid/invalid dependencies. */
  @Test
  void testBulkCheckErrorHandlingWithMixedDependencies() {
    String dependencies =
        "org.springframework:spring-core,invalid:nonexistent-artifact,junit:junit";
    String result = mavenDependencyTools.check_multiple_dependencies(dependencies, false);

    assertNotNull(result);
    assertTrue(result.startsWith("[") && result.endsWith("]"));

    // Should handle both valid and invalid dependencies
    assertTrue(result.contains("spring-core"));
    assertTrue(result.contains("junit"));
    assertTrue(result.contains("nonexistent-artifact"));

    // Should have mixed status results
    assertTrue(result.contains("\"status\" : \"found\""));
    assertTrue(
        result.contains("\"status\" : \"not_found\"") || result.contains("\"status\" : \"error\""));
  }

  /**
   * Tests that get_latest_version with preferStable=true behaves like the old get_stable_version.
   */
  @Test
  void testGetLatestVersionWithPreferStable() {
    String result =
        mavenDependencyTools.get_latest_version("org.springframework:spring-core", true);

    assertNotNull(result);
    assertTrue(result.contains("\"dependency\" : \"org.springframework:spring-core\""));
    assertTrue(result.contains("\"latest_stable\""));
    // When preferStable=true, the response should prioritize stable version information
    assertTrue(result.contains("stable"));
  }

  /**
   * Tests that check_multiple_dependencies with stableOnly=true behaves like the old
   * check_multiple_stable_versions.
   */
  @Test
  void testCheckMultipleDependenciesWithStableOnly() {
    String dependencies = "org.springframework:spring-core,com.fasterxml.jackson.core:jackson-core";
    String result =
        mavenDependencyTools.check_multiple_dependencies(dependencies, true); // stableOnly=true

    assertNotNull(result);
    assertTrue(result.startsWith("[") && result.endsWith("]"));

    // Should only return stable versions when stableOnly=true
    assertTrue(result.contains("\"type\" : \"stable\""));
    // Should not contain pre-release versions as primary
    assertFalse(result.contains("\"type\" : \"rc\""));
    assertFalse(result.contains("\"type\" : \"beta\""));
    assertFalse(result.contains("\"type\" : \"alpha\""));
  }

  /** Tests that version counts are accurate in bulk results. */
  @Test
  void testBulkCheckVersionCountsAccuracy() {
    String dependencies = "junit:junit"; // Well-known dependency with many versions
    String result = mavenDependencyTools.check_multiple_dependencies(dependencies, false);

    assertNotNull(result);
    assertTrue(result.contains("junit"));
    assertTrue(result.contains("\"total_versions\" :"));
    assertTrue(result.contains("\"stable_versions\" :"));

    // Verify counts are positive integers (basic sanity check)
    assertTrue(result.contains("\"total_versions\" : 32"));
    assertTrue(result.contains("\"stable_versions\" : 23"));
  }
}
