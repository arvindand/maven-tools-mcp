package com.arvindand.mcp.maven.model;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for simplified Context7Guidance model and orchestration instruction generation.
 *
 * @author Arvind Menon
 * @since 1.2.0
 */
class Context7GuidanceTest {

  @Test
  void testForMigration_Major() {
    Context7Guidance guidance =
        Context7Guidance.forMigration("org.springframework.boot:spring-boot-starter", "major");

    assertNotNull(guidance);
    assertNotNull(guidance.orchestrationInstructions());
    assertTrue(guidance.orchestrationInstructions().contains("resolve-library-id"));
    assertTrue(guidance.orchestrationInstructions().contains("get-library-docs"));
    assertTrue(guidance.orchestrationInstructions().contains("spring-boot-starter"));
    assertTrue(guidance.orchestrationInstructions().contains("migration guide"));
    assertTrue(guidance.orchestrationInstructions().contains("web search"));
  }

  @Test
  void testForMigration_Minor() {
    Context7Guidance guidance =
        Context7Guidance.forMigration("com.fasterxml.jackson.core:jackson-core", "minor");

    assertNotNull(guidance.orchestrationInstructions());
    assertTrue(guidance.orchestrationInstructions().contains("jackson-core"));
    assertTrue(guidance.orchestrationInstructions().contains("upgrade guide"));
    assertTrue(guidance.orchestrationInstructions().contains("minor version"));
  }

  @Test
  void testForModernization_Aging() {
    Context7Guidance guidance =
        Context7Guidance.forModernization("org.hibernate:hibernate-core", "aging");

    assertNotNull(guidance.orchestrationInstructions());
    assertTrue(guidance.orchestrationInstructions().contains("hibernate-core"));
    assertTrue(guidance.orchestrationInstructions().contains("modern usage and best practices"));
    assertTrue(guidance.orchestrationInstructions().contains("latest features best practices"));
  }

  @Test
  void testForModernization_Stale() {
    Context7Guidance guidance =
        Context7Guidance.forModernization("commons-lang:commons-lang", "stale");

    assertNotNull(guidance.orchestrationInstructions());
    assertTrue(guidance.orchestrationInstructions().contains("commons-lang"));
    assertTrue(guidance.orchestrationInstructions().contains("alternatives and replacements"));
    assertTrue(guidance.orchestrationInstructions().contains("modernization alternatives"));
  }

  @Test
  void testArtifactIdExtraction() {
    // Test that we use artifactId directly without complex library name extraction
    Context7Guidance springGuidance =
        Context7Guidance.forMigration("org.springframework.boot:spring-boot-starter", "major");
    assertTrue(springGuidance.orchestrationInstructions().contains("spring-boot-starter"));

    Context7Guidance hibernateGuidance =
        Context7Guidance.forMigration("org.hibernate:hibernate-core", "major");
    assertTrue(hibernateGuidance.orchestrationInstructions().contains("hibernate-core"));

    Context7Guidance jacksonGuidance =
        Context7Guidance.forMigration("com.fasterxml.jackson.core:jackson-core", "major");
    assertTrue(jacksonGuidance.orchestrationInstructions().contains("jackson-core"));
  }

  @Test
  void testOrchestrationInstructionsContainRequiredElements() {
    Context7Guidance guidance = Context7Guidance.forMigration("test:test-artifact", "patch");

    // Verify all required orchestration elements are present
    String instructions = guidance.orchestrationInstructions();
    assertTrue(instructions.contains("resolve-library-id tool"));
    assertTrue(instructions.contains("get-library-docs tool"));
    assertTrue(instructions.contains("Context7 ID"));
    assertTrue(instructions.contains("web search"));
    assertTrue(instructions.contains("test-artifact"));
  }
}
