package com.arvindand.mcp.maven.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Represents the result of comparing dependency versions with upgrade recommendations.
 *
 * @param comparisonDate when the comparison was performed
 * @param dependencies individual comparison results for each dependency
 * @param updateSummary overall summary of available updates
 * @author Arvind Menon
 * @since 1.3.0
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record VersionComparison(
    Instant comparisonDate,
    List<DependencyComparisonResult> dependencies,
    UpdateSummary updateSummary) {

  /** Individual dependency comparison result. */
  public record DependencyComparisonResult(
      String dependency,
      String currentVersion,
      String latestVersion,
      String latestType,
      String updateType,
      boolean updateAvailable,
      String status,
      String error,
      Optional<Context7Guidance> context7Guidance) {

    public static DependencyComparisonResult success(
        String dependency,
        String currentVersion,
        String latestVersion,
        String latestType,
        String updateType,
        boolean updateAvailable,
        boolean context7Enabled) {
      // Add Context7 guidance if update is available and Context7 is enabled
      Optional<Context7Guidance> guidance =
          (updateAvailable && context7Enabled)
              ? Optional.of(Context7Guidance.forMigration(dependency, updateType))
              : Optional.empty();

      return new DependencyComparisonResult(
          dependency,
          currentVersion,
          latestVersion,
          latestType,
          updateType,
          updateAvailable,
          "success",
          null,
          guidance);
    }

    public static DependencyComparisonResult notFound(String dependency, String currentVersion) {
      return new DependencyComparisonResult(
          dependency, currentVersion, null, null, null, false, "not_found", null, Optional.empty());
    }

    public static DependencyComparisonResult noCurrentVersion(String dependency) {
      return new DependencyComparisonResult(
          dependency, null, null, null, null, false, "no_current_version", null, Optional.empty());
    }

    public static DependencyComparisonResult error(String dependency, String error) {
      return new DependencyComparisonResult(
          dependency, null, null, null, null, false, "error", error, Optional.empty());
    }
  }

  /** Summary of update types. */
  public record UpdateSummary(
      int majorUpdates, int minorUpdates, int patchUpdates, int noUpdates) {}
}
