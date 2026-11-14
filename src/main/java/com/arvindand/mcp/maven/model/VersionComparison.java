package com.arvindand.mcp.maven.model;

import com.arvindand.mcp.maven.model.security.SecurityAssessment;
import com.arvindand.mcp.maven.model.security.SecuritySummary;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * Represents the result of comparing dependency versions with upgrade recommendations.
 *
 * @param comparisonDate when the comparison was performed
 * @param dependencies individual comparison results for each dependency
 * @param updateSummary overall summary of available updates
 * @param securitySummary aggregate security findings (null if scanning disabled)
 * @param context7Guidance orchestration template for the per-dependency {@code context7Query}
 *     hints, emitted once (empty when Context7 hints are disabled or no dependency has a hint)
 * @author Arvind Menon
 * @since 1.3.0
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record VersionComparison(
    Instant comparisonDate,
    List<DependencyComparisonResult> dependencies,
    UpdateSummary updateSummary,
    SecuritySummary securitySummary,
    Optional<Context7Guidance> context7Guidance) {

  /** Constructor without security summary for backward compatibility. */
  public VersionComparison(
      Instant comparisonDate,
      List<DependencyComparisonResult> dependencies,
      UpdateSummary updateSummary) {
    this(comparisonDate, dependencies, updateSummary, null);
  }

  /** Constructor that derives the single top-level Context7 template from the dependencies. */
  public VersionComparison(
      Instant comparisonDate,
      List<DependencyComparisonResult> dependencies,
      UpdateSummary updateSummary,
      SecuritySummary securitySummary) {
    this(
        comparisonDate,
        dependencies,
        updateSummary,
        securitySummary,
        dependencies.stream().anyMatch(dep -> dep.context7Query().isPresent())
            ? Optional.of(Context7Guidance.bulkTemplate())
            : Optional.empty());
  }

  /**
   * Individual dependency comparison result.
   *
   * <p>{@code retryAfterSeconds} is set only on errors caused by a retryable upstream failure;
   * {@code relocation} only when the artifact's newest POM relocates it to new coordinates. Both
   * are omitted from the JSON otherwise.
   */
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
      Optional<String> context7Query,
      Optional<SameMajorStableFallback> sameMajorStableFallback,
      @JsonInclude(JsonInclude.Include.NON_NULL) Integer retryAfterSeconds,
      @JsonInclude(JsonInclude.Include.NON_NULL) RelocationInfo relocation) {

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
      // Add a short Context7 query hint if update is available and Context7 is enabled
      Optional<String> guidance =
          (updateAvailable && context7Enabled)
              ? Optional.of(Context7Guidance.migrationQuery(dependency, updateType))
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
          Optional.ofNullable(sameMajorStableFallback),
          null,
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
      Optional<String> guidance =
          (updateAvailable && context7Enabled)
              ? Optional.of(Context7Guidance.migrationQuery(dependency, updateType))
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
          Optional.ofNullable(sameMajorStableFallback),
          null,
          null);
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
          Optional.empty(),
          null,
          null);
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
          Optional.empty(),
          null,
          null);
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
          Optional.empty(),
          null,
          null);
    }

    /**
     * Creates an error result that carries the retry hint of a classified upstream failure.
     *
     * @param dependency the dependency
     * @param error human-readable message
     * @param retryAfterSeconds retry hint, or null
     * @return error result
     */
    public static DependencyComparisonResult error(
        String dependency, String error, Integer retryAfterSeconds) {
      DependencyComparisonResult base = error(dependency, error);
      return new DependencyComparisonResult(
          base.dependency,
          base.currentVersion,
          base.latestVersion,
          base.latestType,
          base.updateType,
          base.updateAvailable,
          base.status,
          base.error,
          base.security,
          base.context7Query,
          base.sameMajorStableFallback,
          retryAfterSeconds,
          null);
    }

    /**
     * Returns a copy carrying the relocation.
     *
     * @param info relocation, or null for none
     * @return the copy
     */
    public DependencyComparisonResult withRelocation(RelocationInfo info) {
      return new DependencyComparisonResult(
          dependency,
          currentVersion,
          latestVersion,
          latestType,
          updateType,
          updateAvailable,
          status,
          error,
          security,
          context7Query,
          sameMajorStableFallback,
          retryAfterSeconds,
          info);
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
