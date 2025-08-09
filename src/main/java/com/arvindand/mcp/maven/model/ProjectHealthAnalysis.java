package com.arvindand.mcp.maven.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;
import java.util.Optional;

/**
 * Comprehensive health analysis for multiple dependencies in a project.
 *
 * @param analysisDate ISO formatted date when analysis was performed
 * @param dependencyCount total number of dependencies analyzed
 * @param healthSummary overall health assessment
 * @param ageDistribution breakdown of dependencies by age classification
 * @param recommendations prioritized list of recommendations
 * @param dependencies individual analysis for each dependency
 * @param maxAgeInDays the age threshold used for classification
 * @author Arvind Menon
 * @since 1.1.0
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ProjectHealthAnalysis(
    String analysisDate,
    int dependencyCount,
    int successfulAnalysis,
    int failedAnalysis,
    AgeDistribution ageDistribution,
    List<DependencyHealthAnalysis> dependencies,
    List<String> recommendations) {

  /** Distribution of dependencies by age classification. */
  public record AgeDistribution(int fresh, int current, int aging, int stale) {}

  /** Health analysis for a single dependency. */
  public record DependencyHealthAnalysis(
      String dependency,
      String status,
      String latestVersion,
      String ageClassification,
      long daysSinceRelease,
      int healthScore,
      String maintenanceLevel,
      Optional<Context7Guidance> context7Guidance,
      Optional<String> error) {

    /** Creates a successful analysis result. */
    public static DependencyHealthAnalysis success(
        String dependency,
        String latestVersion,
        String ageClassification,
        long daysSinceRelease,
        int healthScore,
        String maintenanceLevel,
        boolean context7Enabled) {
      // Add Context7 guidance for aging/stale dependencies when Context7 is enabled
      Optional<Context7Guidance> guidance =
          (context7Enabled
                  && ("aging".equals(ageClassification) || "stale".equals(ageClassification)))
              ? Optional.of(Context7Guidance.forModernization(dependency, ageClassification))
              : Optional.empty();

      return new DependencyHealthAnalysis(
          dependency,
          "success",
          latestVersion,
          ageClassification,
          daysSinceRelease,
          healthScore,
          maintenanceLevel,
          guidance,
          Optional.empty());
    }

    /** Creates an error result. */
    public static DependencyHealthAnalysis error(String dependency, String error) {
      return new DependencyHealthAnalysis(
          dependency, "error", null, null, 0, 0, null, Optional.empty(), Optional.of(error));
    }

    /** Creates a not found result. */
    public static DependencyHealthAnalysis notFound(String dependency) {
      return new DependencyHealthAnalysis(
          dependency,
          "not_found",
          null,
          null,
          0,
          0,
          null,
          Optional.empty(),
          Optional.of("Dependency not found in Maven Central"));
    }
  }
}
