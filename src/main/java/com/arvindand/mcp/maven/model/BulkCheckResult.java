package com.arvindand.mcp.maven.model;

/**
 * Represents the result of a bulk dependency check.
 *
 * @param dependency the dependency coordinate
 * @param version the version (if found)
 * @param type the version type (if found)
 * @param status the status (found, not_found, error)
 * @param error the error message (if status is error)
 * @param totalVersions total versions count (for stable check)
 * @param stableVersions stable versions count (for stable check)
 * @author Arvind Menon
 * @since 0.1.0
 */
public record BulkCheckResult(
    String dependency,
    String version,
    String type,
    String status,
    String error,
    Integer totalVersions,
    Integer stableVersions) {

  private static final String STATUS_FOUND = "found";
  private static final String STATUS_NOT_FOUND = "not_found";
  private static final String STATUS_NO_STABLE_VERSION = "no_stable_version";
  private static final String STATUS_ERROR = "error";

  public static BulkCheckResult found(String dependency, String version, String type) {
    return new BulkCheckResult(dependency, version, type, STATUS_FOUND, null, null, null);
  }

  public static BulkCheckResult foundStable(
      String dependency, String version, String type, int totalVersions, int stableVersions) {
    return new BulkCheckResult(
        dependency, version, type, STATUS_FOUND, null, totalVersions, stableVersions);
  }

  public static BulkCheckResult foundWithCounts(
      String dependency, String version, String type, int totalVersions, int stableVersions) {
    return new BulkCheckResult(
        dependency, version, type, STATUS_FOUND, null, totalVersions, stableVersions);
  }

  public static BulkCheckResult notFound(String dependency) {
    return new BulkCheckResult(dependency, null, null, STATUS_NOT_FOUND, null, null, null);
  }

  public static BulkCheckResult noStableVersion(String dependency, int totalVersions) {
    return new BulkCheckResult(
        dependency, null, null, STATUS_NO_STABLE_VERSION, null, totalVersions, null);
  }

  public static BulkCheckResult error(String dependency, String error) {
    return new BulkCheckResult(dependency, null, null, STATUS_ERROR, error, null, null);
  }
}
