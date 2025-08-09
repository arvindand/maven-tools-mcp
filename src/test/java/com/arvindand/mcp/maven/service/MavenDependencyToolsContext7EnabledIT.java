package com.arvindand.mcp.maven.service;

import static com.arvindand.mcp.maven.TestHelpers.getSuccessData;
import static org.junit.jupiter.api.Assertions.*;

import com.arvindand.mcp.maven.config.Context7Properties;
import com.arvindand.mcp.maven.model.*;
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
 * @author Arvind Menon
 * @since 1.3.0
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
    ToolResponse resp = mavenDependencyTools.compare_dependency_versions(oldDependencies, false);

    if (resp instanceof ToolResponse.Error) {
      System.out.println("SKIPPING test due to Maven Central API error");
      return;
    }

    VersionComparison comparison = getSuccessData(resp);
    assertNotNull(comparison);

    // Check if any dependencies have updates available and Context7 guidance
    for (var dep : comparison.dependencies()) {
      if (dep.updateAvailable()) {
        // When Context7 is enabled and updates are available, guidance should be present
        assertTrue(
            dep.context7Guidance().isPresent(),
            "Context7 guidance should be present for updates when enabled");

        var guidance = dep.context7Guidance().get();
        assertNotNull(guidance.suggestedSearch());
        assertNotNull(guidance.documentationFocus());
      }
    }
  }

  /** Tests that the tool works with Context7 enabled (simplified test). */
  @Test
  void testContext7EnabledBasicOperation() {
    ToolResponse resp = mavenDependencyTools.get_latest_version("junit:junit", false);

    // Just verify the tool works when Context7 is enabled
    VersionsByType result = getSuccessData(resp);
    assertNotNull(result);
    assertEquals("junit:junit", result.dependency());
  }
}
