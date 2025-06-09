package com.arvindand.mcp.maven.model;

import java.time.Instant;
import java.util.List;

/**
 * Represents the result of version comparison.
 *
 * @param comparisonDate when the comparison was performed
 * @param dependencies list of dependency comparison results
 * @param updateSummary summary of available updates
 * @author Arvind Menon
 * @since 0.1.0
 */
public record VersionComparisonResponse(
    Instant comparisonDate,
    List<DependencyComparisonResult> dependencies,
    UpdateSummary updateSummary) {
  /**
   * Individual dependency comparison result.
   */
  public record DependencyComparisonResult(
      String dependency,
      String currentVersion,
      String latestVersion,
      String latestType,
      String updateType,
      boolean updateAvailable,
      String status,
      String error) {

    public static DependencyComparisonResult success(
        String dependency,
        String currentVersion,
        String latestVersion,
        String latestType,
        String updateType,
        boolean updateAvailable) {
      return new DependencyComparisonResult(
          dependency,
          currentVersion,
          latestVersion,
          latestType,
          updateType,
          updateAvailable,
          "success",
          null);
    }

    public static DependencyComparisonResult notFound(String dependency, String currentVersion) {
      return new DependencyComparisonResult(
          dependency, currentVersion, null, null, null, false, "not_found", null);
    }

    public static DependencyComparisonResult noCurrentVersion(String dependency) {
      return new DependencyComparisonResult(
          dependency, null, null, null, null, false, "no_current_version", null);
    }

    public static DependencyComparisonResult error(String dependency, String error) {
      return new DependencyComparisonResult(
          dependency, null, null, null, null, false, "error", error);
    }
  }

  /**
   * Summary of update types.
   */
  public record UpdateSummary(
      int majorUpdates, int minorUpdates, int patchUpdates, int noUpdates) {}
}
