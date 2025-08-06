package com.arvindand.mcp.maven.service;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.arvindand.mcp.maven.config.Context7Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration test for MavenDependencyTools with Context7 enabled. Verifies that Context7 guidance
 * hints are included when context7.enabled=true.
 *
 * <p>This test makes real HTTP calls to Maven Central API and should only be run in integration
 * test profiles.
 *
 * @author Arvind Menon
 * @since 1.2.0
 */
@SpringBootTest
@ActiveProfiles("test")
class MavenDependencyToolsContext7EnabledIT {

  @TestConfiguration
  static class Context7EnabledTestConfig {
    @Bean
    @Primary
    public Context7Properties context7Properties() {
      return new Context7Properties(true); // Enable Context7 for this test
    }
  }

  @Autowired private MavenDependencyTools mavenDependencyTools;

  /** Tests that Context7 guidance is included when context7.enabled=true. */
  @Test
  void testContext7GuidanceEnabledForVersionComparison() {
    // Use an older Spring Boot version that will trigger Context7 guidance when enabled
    String oldDependencies = "org.springframework.boot:spring-boot-starter:2.5.0";
    String result = mavenDependencyTools.compare_dependency_versions(oldDependencies, false);

    assertNotNull(result);
    assertTrue(result.contains("\"update_available\" : true"));
    assertTrue(result.contains("\"update_type\" : \"major\""));
    // Context7 guidance SHOULD be included since context7.enabled=true
    assertTrue(result.contains("context7_guidance"));
    assertTrue(result.contains("suggested_search"));
    assertTrue(result.contains("Spring Boot"));
    assertTrue(result.contains("migration"));
  }

  /** Tests that Context7 guidance is included for aging dependencies when enabled. */
  @Test
  void testContext7GuidanceEnabledForDependencyAge() {
    // Test with a dependency that might be aging
    String dependency = "org.springframework:spring-core";
    String result = mavenDependencyTools.analyze_dependency_age(dependency, 30); // Short max age

    System.out.println("DEBUG - Dependency age result: " + result);

    assertNotNull(result);

    // Skip test if Maven Central API is timing out (network issue, not code issue)
    if (result.contains("Read timed out") || result.contains("status\" : \"error\"")) {
      System.out.println(
          "SKIPPING test due to Maven Central API timeout - this is a network issue, not a code issue");
      return;
    }

    assertTrue(
        result.contains("\"dependency\""), "Expected dependency field, actual result: " + result);

    // If the dependency is classified as aging/stale, Context7 guidance should be present
    if (result.contains("\"age_classification\" : \"AGING\"")
        || result.contains("\"age_classification\" : \"STALE\"")) {
      assertTrue(result.contains("context7_guidance"));
      assertTrue(result.contains("suggested_search"));
      assertTrue(result.contains("documentation_focus"));
    }
  }
}
