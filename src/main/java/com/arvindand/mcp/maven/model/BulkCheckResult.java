package com.arvindand.mcp.maven.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * Represents the result of a bulk dependency check with comprehensive version information.
 *
 * @param dependency the dependency coordinate
 * @param version the primary version (latest stable or latest overall if no stable)
 * @param type the version type of the primary version
 * @param status the status (found, not_found, error)
 * @param error the error message (if status is error)
 * @param totalVersions total versions count
 * @param stableVersions stable versions count
 * @param latestStable latest stable version info
 * @param latestRc latest RC version info
 * @param latestBeta latest beta version info
 * @param latestAlpha latest alpha version info
 * @param latestMilestone latest milestone version info
 * @author Arvind Menon
 * @since 0.1.0
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record BulkCheckResult(
    String dependency,
    String version,
    String type,
    String status,
    String error,
    Integer totalVersions,
    Integer stableVersions,
    VersionInfo latestStable,
    VersionInfo latestRc,
    VersionInfo latestBeta,
    VersionInfo latestAlpha,
    VersionInfo latestMilestone) {

  public enum Status {
    FOUND("found"),
    NOT_FOUND("not_found"),
    NO_STABLE_VERSION("no_stable_version"),
    ERROR("error");

    private final String value;

    Status(String value) {
      this.value = value;
    }

    public String getValue() {
      return value;
    }
  }

  public static BulkCheckResult found(String dependency, String version, String type) {
    return new BulkCheckResult(
        dependency,
        version,
        type,
        Status.FOUND.getValue(),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  public static BulkCheckResult foundStable(
      String dependency, String version, String type, int totalVersions, int stableVersions) {
    return new BulkCheckResult(
        dependency,
        version,
        type,
        Status.FOUND.getValue(),
        null,
        totalVersions,
        stableVersions,
        null,
        null,
        null,
        null,
        null);
  }

  public static BulkCheckResult foundWithCounts(
      String dependency, String version, String type, int totalVersions, int stableVersions) {
    return new BulkCheckResult(
        dependency,
        version,
        type,
        Status.FOUND.getValue(),
        null,
        totalVersions,
        stableVersions,
        null,
        null,
        null,
        null,
        null);
  }

  public static BulkCheckResult foundComprehensive(
      String dependency,
      String primaryVersion,
      String primaryType,
      int totalVersions,
      int stableVersions,
      VersionInfo latestStable,
      VersionInfo latestRc,
      VersionInfo latestBeta,
      VersionInfo latestAlpha,
      VersionInfo latestMilestone) {
    return new BulkCheckResult(
        dependency,
        primaryVersion,
        primaryType,
        Status.FOUND.getValue(),
        null,
        totalVersions,
        stableVersions,
        latestStable,
        latestRc,
        latestBeta,
        latestAlpha,
        latestMilestone);
  }

  public static BulkCheckResult notFound(String dependency) {
    return new BulkCheckResult(
        dependency,
        null,
        null,
        Status.NOT_FOUND.getValue(),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  public static BulkCheckResult noStableVersion(String dependency, int totalVersions) {
    return new BulkCheckResult(
        dependency,
        null,
        null,
        Status.NO_STABLE_VERSION.getValue(),
        null,
        totalVersions,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  public static BulkCheckResult error(String dependency, String error) {
    return new BulkCheckResult(
        dependency,
        null,
        null,
        Status.ERROR.getValue(),
        error,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }
}
