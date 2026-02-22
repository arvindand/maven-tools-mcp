package com.arvindand.mcp.maven.model;

import com.arvindand.mcp.maven.model.security.SecurityAssessment;
import com.arvindand.mcp.maven.model.security.SecuritySummary;
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
 * @param securitySummary aggregate security findings (null if scanning disabled)
 * @author Arvind Menon
 * @since 1.3.0
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record VersionComparison(
    Instant comparisonDate,
    List<DependencyComparisonResult> dependencies,
    UpdateSummary updateSummary,
    SecuritySummary securitySummary) {

  /** Constructor without security summary for backward compatibility. */
  public VersionComparison(
      Instant comparisonDate,
      List<DependencyComparisonResult> dependencies,
      UpdateSummary updateSummary) {
    this(comparisonDate, dependencies, updateSummary, null);
  }

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
      Optional<SecurityAssessment> security,
      Optional<Context7Guidance> context7Guidance,
      Optional<SameMajorStableFallback> sameMajorStableFallback) {

    public static DependencyComparisonResult success(
        String dependency,
        String currentVersion,
        String latestVersion,
        String latestType,
        String updateType,
        boolean updateAvailable,
        boolean context7Enabled) {
      return success(
          dependency,
          currentVersion,
          latestVersion,
          latestType,
          updateType,
          updateAvailable,
          context7Enabled,
          null);
    }

    public static DependencyComparisonResult success(
        String dependency,
        String currentVersion,
        String latestVersion,
        String latestType,
        String updateType,
        boolean updateAvailable,
        boolean context7Enabled,
        SameMajorStableFallback sameMajorStableFallback) {
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
          Optional.empty(),
          guidance,
          Optional.ofNullable(sameMajorStableFallback));
    }

    public static DependencyComparisonResult successWithSecurity(
        String dependency,
        String currentVersion,
        String latestVersion,
        String latestType,
        String updateType,
        boolean updateAvailable,
        SecurityAssessment security,
        boolean context7Enabled) {
      return successWithSecurity(
          dependency,
          currentVersion,
          latestVersion,
          latestType,
          updateType,
          updateAvailable,
          security,
          context7Enabled,
          null);
    }

    public static DependencyComparisonResult successWithSecurity(
        String dependency,
        String currentVersion,
        String latestVersion,
        String latestType,
        String updateType,
        boolean updateAvailable,
        SecurityAssessment security,
        boolean context7Enabled,
        SameMajorStableFallback sameMajorStableFallback) {
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
          Optional.ofNullable(security),
          guidance,
          Optional.ofNullable(sameMajorStableFallback));
    }

    public static DependencyComparisonResult notFound(String dependency, String currentVersion) {
      return new DependencyComparisonResult(
          dependency,
          currentVersion,
          null,
          null,
          null,
          false,
          "not_found",
          null,
          Optional.empty(),
          Optional.empty(),
          Optional.empty());
    }

    public static DependencyComparisonResult noCurrentVersion(String dependency) {
      return new DependencyComparisonResult(
          dependency,
          null,
          null,
          null,
          null,
          false,
          "no_current_version",
          null,
          Optional.empty(),
          Optional.empty(),
          Optional.empty());
    }

    public static DependencyComparisonResult error(String dependency, String error) {
      return new DependencyComparisonResult(
          dependency,
          null,
          null,
          null,
          null,
          false,
          "error",
          error,
          Optional.empty(),
          Optional.empty(),
          Optional.empty());
    }

    /** Check if this dependency requires urgent security action. */
    public boolean requiresSecurityAction() {
      return security.isPresent()
          && security.get().status() == SecurityAssessment.Status.VULNERABLE
          && security.get().requiresAction();
    }
  }

  /** Optional server-computed same-major stable fallback when latest stable is a major upgrade. */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record SameMajorStableFallback(String latestVersion, String updateType) {}

  /** Summary of update types. */
  public record UpdateSummary(
      int majorUpdates, int minorUpdates, int patchUpdates, int noUpdates) {}
}
