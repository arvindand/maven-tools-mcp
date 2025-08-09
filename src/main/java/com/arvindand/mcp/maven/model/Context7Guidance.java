package com.arvindand.mcp.maven.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * Context7 orchestration instructions for LLM tool delegation.
 *
 * <p>Provides explicit step-by-step instructions for LLMs to effectively orchestrate the raw
 * Context7 tools 'resolve-library-id' and 'get-library-docs', including fallback web search
 * instructions when Context7 data is insufficient.
 *
 * @author Arvind Menon
 * @since 1.2.0
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record Context7Guidance(String orchestrationInstructions) {

  private static final String STALE = "stale";
  private static final String MAJOR = "major";

  /**
   * Create orchestration instructions for version migration scenarios.
   *
   * @param dependency the Maven coordinate (groupId:artifactId format)
   * @param updateType the type of update (major, minor, patch)
   * @return Context7 orchestration instructions for migration
   */
  public static Context7Guidance forMigration(String dependency, String updateType) {
    String artifactId = extractArtifactId(dependency);
    String orchestrationInstructions = generateMigrationOrchestration(artifactId, updateType);

    return new Context7Guidance(orchestrationInstructions);
  }

  /**
   * Create orchestration instructions for dependency modernization scenarios.
   *
   * @param dependency the Maven coordinate (groupId:artifactId format)
   * @param ageClassification the age classification (fresh, current, aging, stale)
   * @return Context7 orchestration instructions for modernization
   */
  public static Context7Guidance forModernization(String dependency, String ageClassification) {
    String artifactId = extractArtifactId(dependency);
    String orchestrationInstructions =
        generateModernizationOrchestration(artifactId, ageClassification);

    return new Context7Guidance(orchestrationInstructions);
  }

  private static String extractArtifactId(String dependency) {
    if (dependency == null || !dependency.contains(":")) {
      return dependency;
    }

    String[] parts = dependency.split(":");
    return parts[1]; // Return artifactId directly
  }

  private static String generateMigrationOrchestration(String artifactId, String updateType) {
    String topic = MAJOR.equals(updateType) ? "migration guide" : "upgrade guide";
    String fallbackSearch = artifactId + " " + updateType + " version upgrade guide";

    return String.format(
        "Use resolve-library-id tool with libraryName='%s' to find documentation ID. "
            + "Then use get-library-docs tool with the returned Context7 ID and topic='%s' to get upgrade instructions. "
            + "If Context7 doesn't provide sufficient information, perform a web search for '%s'.",
        artifactId, topic, fallbackSearch);
  }

  private static String generateModernizationOrchestration(
      String artifactId, String ageClassification) {
    String topic =
        STALE.equals(ageClassification)
            ? "alternatives and replacements"
            : "modern usage and best practices";
    String fallbackSearch =
        artifactId
            + " "
            + (STALE.equals(ageClassification)
                ? "modernization alternatives"
                : "latest features best practices");

    return String.format(
        "Use resolve-library-id tool with libraryName='%s' to find documentation ID. "
            + "Then use get-library-docs tool with the returned Context7 ID and topic='%s' to get modernization guidance. "
            + "If Context7 doesn't provide sufficient information, perform a web search for '%s'.",
        artifactId, topic, fallbackSearch);
  }
}
