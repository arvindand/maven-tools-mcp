package com.arvindand.mcp.maven.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for Context7Guidance model and guidance generation logic.
 *
 * @author Arvind Menon
 * @since 1.2.0
 */
class Context7GuidanceTest {

  @Test
  void testForMigration_SpringBoot() {
    Context7Guidance guidance =
        Context7Guidance.forMigration("org.springframework.boot:spring-boot-starter", "major");

    assertNotNull(guidance);
    assertEquals("Spring Boot major version upgrade migration guide", guidance.suggestedSearch());
    assertEquals("high", guidance.complexity());
    assertEquals(
        "migration guides, breaking changes, upgrade paths", guidance.documentationFocus());
    assertTrue(
        guidance
            .searchHints()
            .contains("Search for 'Spring Boot migration' or 'Spring Framework upgrade'"));
  }

  @Test
  void testForMigration_Hibernate() {
    Context7Guidance guidance =
        Context7Guidance.forMigration("org.hibernate:hibernate-core", "major");

    assertNotNull(guidance);
    assertTrue(guidance.suggestedSearch().toLowerCase().contains("hibernate"));
    assertEquals("high", guidance.complexity());
    assertTrue(
        guidance
            .searchHints()
            .contains("Search for 'Hibernate ORM Java' to avoid NHibernate (.NET) results"));
  }

  @Test
  void testForModernization_StaleDependency() {
    Context7Guidance guidance =
        Context7Guidance.forModernization("com.fasterxml.jackson.core:jackson-core", "stale");

    assertNotNull(guidance);
    assertTrue(guidance.suggestedSearch().contains("modernization guide latest version"));
    assertEquals("moderate", guidance.complexity());
    assertEquals("best practices, modern usage, latest features", guidance.documentationFocus());
    assertTrue(
        guidance
            .searchHints()
            .contains("Consider searching for alternatives or replacement libraries"));
  }

  @Test
  void testForModernization_AgingDependency() {
    Context7Guidance guidance = Context7Guidance.forModernization("junit:junit", "aging");

    assertNotNull(guidance);
    assertTrue(guidance.suggestedSearch().contains("upgrade to latest best practices"));
    assertEquals("moderate", guidance.complexity());
    assertTrue(
        guidance.searchHints().contains("Search for 'best practices' and 'modern usage patterns'"));
  }

  @Test
  void testLibraryNameExtraction() {
    // Test Spring Boot extraction
    Context7Guidance springGuidance =
        Context7Guidance.forMigration("org.springframework.boot:spring-boot-starter", "major");
    assertTrue(springGuidance.suggestedSearch().contains("Spring Boot"));

    // Test Hibernate extraction
    Context7Guidance hibernateGuidance =
        Context7Guidance.forMigration("org.hibernate:hibernate-core", "minor");
    assertTrue(hibernateGuidance.suggestedSearch().contains("Hibernate ORM"));

    // Test Jackson extraction
    Context7Guidance jacksonGuidance =
        Context7Guidance.forMigration("com.fasterxml.jackson.core:jackson-databind", "patch");
    assertTrue(jacksonGuidance.suggestedSearch().contains("Jackson"));

    // Test Apache extraction
    Context7Guidance apacheGuidance =
        Context7Guidance.forMigration("org.apache.commons:commons-lang3", "minor");
    assertTrue(apacheGuidance.suggestedSearch().contains("commons lang3"));
  }

  @Test
  void testComplexityMapping() {
    assertEquals("high", Context7Guidance.forMigration("test:test", "major").complexity());
    assertEquals("moderate", Context7Guidance.forMigration("test:test", "minor").complexity());
    assertEquals("low", Context7Guidance.forMigration("test:test", "patch").complexity());
    assertEquals("moderate", Context7Guidance.forMigration("test:test", "unknown").complexity());
  }

  @Test
  void testSearchHintsIncludeJavaKeyword() {
    Context7Guidance guidance = Context7Guidance.forModernization("general:dependency", "aging");

    assertTrue(
        guidance.searchHints().contains("Include 'Java' keyword to get JVM-specific guidance"));
  }
}
