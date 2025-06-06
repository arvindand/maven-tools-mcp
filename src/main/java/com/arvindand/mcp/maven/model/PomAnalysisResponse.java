package com.arvindand.mcp.maven.model;

import java.time.Instant;
import java.util.List;

/**
 * Represents the result of a POM analysis.
 *
 * @param analysisDate when the analysis was performed
 * @param totalDependencies total number of dependencies analyzed
 * @param dependencies list of dependency analysis results
 * @param summary summary statistics
 * @author Arvind Menon
 * @since 0.1.0
 */
public record PomAnalysisResponse(
    Instant analysisDate,
    int totalDependencies,
    List<DependencyAnalysisResult> dependencies,
    AnalysisSummary summary) {

  /** Individual dependency analysis result. */
  public record DependencyAnalysisResult(
      String dependency,
      String currentVersion,
      String latestVersion,
      String latestType,
      String latestStable,
      boolean isOutdated,
      String status,
      String error) {

    public static DependencyAnalysisResult found(
        String dependency,
        String currentVersion,
        String latestVersion,
        String latestType,
        String latestStable,
        boolean isOutdated) {
      return new DependencyAnalysisResult(
          dependency,
          currentVersion,
          latestVersion,
          latestType,
          latestStable,
          isOutdated,
          "found",
          null);
    }

    public static DependencyAnalysisResult notFound(String dependency, String currentVersion) {
      return new DependencyAnalysisResult(
          dependency, currentVersion, null, null, null, false, "not_found", null);
    }

    public static DependencyAnalysisResult error(String dependency, String error) {
      return new DependencyAnalysisResult(
          dependency, null, null, null, null, false, "error", error);
    }
  }

  /** Summary of the analysis. */
  public record AnalysisSummary(int total, int outdated, int upToDate, int errors) {}
}
