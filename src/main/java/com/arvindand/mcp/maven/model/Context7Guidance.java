package com.arvindand.mcp.maven.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;

/**
 * Context7 guidance hints for LLM orchestration with raw Context7 MCP tools.
 *
 * <p>Provides smart search suggestions and ecosystem-specific hints to help LLMs effectively use
 * the raw Context7 tools 'resolve-library-id' and 'get-library-docs'.
 *
 * @author Arvind Menon
 * @since 1.2.0
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record Context7Guidance(
    String suggestedSearch,
    List<String> searchHints,
    String complexity,
    String documentationFocus) {

  /**
   * Create guidance for version comparison/migration scenarios.
   *
   * @param dependency the Maven coordinate
   * @param updateType the type of update (major, minor, patch)
   * @return Context7 guidance for migration
   */
  public static Context7Guidance forMigration(String dependency, String updateType) {
    String libraryName = extractLibraryName(dependency);
    String suggestedSearch = generateMigrationSearch(libraryName, updateType);
    List<String> searchHints = generateMigrationHints(dependency, updateType);
    String complexity = determineComplexity(updateType);
    String documentationFocus = "migration guides, breaking changes, upgrade paths";

    return new Context7Guidance(suggestedSearch, searchHints, complexity, documentationFocus);
  }

  /**
   * Create guidance for dependency age/modernization scenarios.
   *
   * @param dependency the Maven coordinate
   * @param ageClassification the age classification (fresh, current, aging, stale)
   * @return Context7 guidance for modernization
   */
  public static Context7Guidance forModernization(String dependency, String ageClassification) {
    String libraryName = extractLibraryName(dependency);
    String suggestedSearch = generateModernizationSearch(libraryName, ageClassification);
    List<String> searchHints = generateModernizationHints(dependency, ageClassification);
    String complexity =
        "aging".equals(ageClassification) || "stale".equals(ageClassification) ? "moderate" : "low";
    String documentationFocus = "best practices, modern usage, latest features";

    return new Context7Guidance(suggestedSearch, searchHints, complexity, documentationFocus);
  }

  private static String extractLibraryName(String dependency) {
    if (dependency == null || !dependency.contains(":")) {
      return dependency;
    }

    String[] parts = dependency.split(":");
    String groupId = parts[0];
    String artifactId = parts[1];

    // Smart extraction based on common patterns
    if (groupId.contains("springframework")) {
      if (artifactId.contains("spring-boot")) {
        return "Spring Boot";
      } else if (artifactId.contains("spring-")) {
        return "Spring Framework " + artifactId.replace("spring-", "");
      }
      return "Spring Framework";
    } else if (groupId.contains("hibernate")) {
      return "Hibernate ORM";
    } else if (groupId.contains("fasterxml.jackson")) {
      return "Jackson " + artifactId.replace("jackson-", "");
    } else if (groupId.contains("apache")) {
      return artifactId.replace("-", " ");
    }

    // Default: use artifact ID
    return artifactId.replace("-", " ");
  }

  private static String generateMigrationSearch(String libraryName, String updateType) {
    return switch (updateType) {
      case "major" -> libraryName + " major version upgrade migration guide";
      case "minor" -> libraryName + " minor version upgrade guide";
      case "patch" -> libraryName + " patch update guide";
      default -> libraryName + " upgrade guide";
    };
  }

  private static String generateModernizationSearch(String libraryName, String ageClassification) {
    return switch (ageClassification) {
      case "stale" -> libraryName + " modernization guide latest version";
      case "aging" -> libraryName + " upgrade to latest best practices";
      default -> libraryName + " latest features improvements";
    };
  }

  private static List<String> generateMigrationHints(String dependency, String updateType) {
    List<String> hints =
        List.of(
            "Include 'Java' in search to avoid .NET or other language results",
            "Look for official project documentation and migration guides",
            "Focus on '" + updateType + " version' or 'breaking changes' keywords");

    // Add ecosystem-specific hints
    if (dependency.contains("springframework")) {
      return List.of(
          "Search for 'Spring Boot migration' or 'Spring Framework upgrade'",
          "Include version numbers for specific migration paths",
          "Look for official Spring.io documentation");
    } else if (dependency.contains("hibernate")) {
      return List.of(
          "Search for 'Hibernate ORM Java' to avoid NHibernate (.NET) results",
          "Include 'JPA' keyword for persistence-related guidance",
          "Look for official Hibernate documentation");
    }

    return hints;
  }

  private static List<String> generateModernizationHints(
      String dependency, String ageClassification) {
    if ("stale".equals(ageClassification)) {
      return List.of(
          "Consider searching for alternatives or replacement libraries",
          "Look for migration guides to newer maintained alternatives",
          "Check if the library has been superseded by newer projects");
    }

    return List.of(
        "Search for 'best practices' and 'modern usage patterns'",
        "Look for recent version release notes and new features",
        "Include 'Java' keyword to get JVM-specific guidance");
  }

  private static String determineComplexity(String updateType) {
    return switch (updateType) {
      case "major" -> "high";
      case "minor" -> "moderate";
      case "patch" -> "low";
      default -> "moderate";
    };
  }
}
