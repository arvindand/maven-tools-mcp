package com.arvindand.mcp.maven.model;

import java.util.List;
import java.util.Optional;

/**
 * Represents the results of project health analysis for multiple dependencies.
 *
 * @param overallHealth overall health assessment (excellent/good/fair/poor)
 * @param averageHealthScore average health score across all dependencies (0-100)
 * @param totalDependencies total number of dependencies analyzed
 * @param analyzedSuccessfully number of dependencies successfully analyzed
 * @param ageDistribution distribution of dependencies by age classification
 * @param dependencies list of individual dependency health analyses
 * @param recommendations list of actionable recommendations
 * @author Arvind Menon
 * @since 1.2.0
 */
public record ProjectHealthAnalysis(
    String overallHealth,
    int averageHealthScore,
    int totalDependencies,
    int analyzedSuccessfully,
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
