package com.arvindand.mcp.maven.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * Context7 orchestration instructions for LLM tool delegation.
 *
 * <p>Provides explicit step-by-step instructions for LLMs to effectively orchestrate the raw
 * Context7 tools 'resolve-library-id' and 'query-docs', including fallback web search instructions
 * when Context7 data is insufficient.
 *
 * <p>Updated for Context7 MCP Server 2.0.0 which introduced breaking changes:
 *
 * <ul>
 *   <li>Renamed 'get-library-docs' to 'query-docs'
 *   <li>Added required 'query' parameter to 'resolve-library-id'
 *   <li>Renamed 'context7CompatibleLibraryID' to 'libraryId'
 *   <li>Removed 'topic', 'mode', 'page', 'limit' parameters (now query-based)
 * </ul>
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
    String query =
        MAJOR.equals(updateType)
            ? artifactId + " migration guide breaking changes"
            : artifactId + " upgrade guide";
    String fallbackSearch = artifactId + " " + updateType + " version upgrade guide";

    return String.format(
        "Use resolve-library-id tool with query='%s' and libraryName='%s' to find the library ID. "
            + "Then use query-docs tool with the returned libraryId and query='%s' to get upgrade instructions. "
            + "If Context7 doesn't provide sufficient information, perform a web search for '%s'.",
        query, artifactId, query, fallbackSearch);
  }

  private static String generateModernizationOrchestration(
      String artifactId, String ageClassification) {
    String query =
        STALE.equals(ageClassification)
            ? artifactId + " alternatives replacements deprecated"
            : artifactId + " modern usage best practices";
    String fallbackSearch =
        artifactId
            + " "
            + (STALE.equals(ageClassification)
                ? "modernization alternatives"
                : "latest features best practices");

    return String.format(
        "Use resolve-library-id tool with query='%s' and libraryName='%s' to find the library ID. "
            + "Then use query-docs tool with the returned libraryId and query='%s' to get modernization guidance. "
            + "If Context7 doesn't provide sufficient information, perform a web search for '%s'.",
        query, artifactId, query, fallbackSearch);
  }
}
