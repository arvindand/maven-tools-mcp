package com.arvindand.mcp.maven.service;

import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Service for analyzing version types and comparing versions. Provides classification of Maven
 * versions into types (stable, snapshot, rc, alpha, beta, milestone) and determines update types
 * between versions.
 *
 * @author Arvind Menon
 * @since 0.1.0
 */
@Service
public class VersionAnalysisService {

  private static final String UNKNOWN_TYPE = "unknown";

  // Version type patterns
  private static final Pattern SNAPSHOT_PATTERN =
      Pattern.compile(".*-SNAPSHOT$", Pattern.CASE_INSENSITIVE);
  private static final Pattern RC_PATTERN = Pattern.compile(".*-RC\\d*$", Pattern.CASE_INSENSITIVE);
  private static final Pattern ALPHA_PATTERN =
      Pattern.compile(".*-(ALPHA|A)\\d*$", Pattern.CASE_INSENSITIVE);
  private static final Pattern BETA_PATTERN =
      Pattern.compile(".*-(BETA|B)\\d*$", Pattern.CASE_INSENSITIVE);
  private static final Pattern MILESTONE_PATTERN = Pattern.compile(".*-[Mm]\\d+$");

  /**
   * Determines the version type (stable, snapshot, rc, alpha, beta, milestone).
   *
   * @param version the version string to analyze
   * @return the version type
   */
  public String getVersionType(String version) {
    if (version == null) {
      return UNKNOWN_TYPE;
    }

    if (SNAPSHOT_PATTERN.matcher(version).matches()) return "snapshot";
    if (RC_PATTERN.matcher(version).matches()) return "rc";
    if (ALPHA_PATTERN.matcher(version).matches()) return "alpha";
    if (BETA_PATTERN.matcher(version).matches()) return "beta";
    if (MILESTONE_PATTERN.matcher(version).matches()) return "milestone";

    return "stable";
  }

  /**
   * Checks if a version is considered stable (not snapshot, rc, alpha, beta, or milestone).
   *
   * @param version the version to check
   * @return true if the version is stable
   */
  public boolean isStableVersion(String version) {
    return "stable".equals(getVersionType(version));
  }

  /**
   * Determines the update type between two versions (major, minor, patch, none, unknown).
   *
   * @param currentVersion the current version
   * @param latestVersion the latest version
   * @return the update type as a string
   */
  public String determineUpdateType(String currentVersion, String latestVersion) {
    if (currentVersion == null || latestVersion == null) {
      return UNKNOWN_TYPE;
    }

    if (currentVersion.equals(latestVersion)) {
      return "none";
    }
    try {
      return compareVersions(currentVersion, latestVersion);
    } catch (Exception _) {
      return UNKNOWN_TYPE;
    }
  }

  /**
   * Compares two versions and determines the update type.
   *
   * @param currentVersion the current version
   * @param latestVersion the latest version
   * @return the update type (major, minor, patch, or unknown)
   */
  private String compareVersions(String currentVersion, String latestVersion) {
    var currentParts = parseVersionParts(currentVersion);
    var latestParts = parseVersionParts(latestVersion);

    if (currentParts.length >= 1 && latestParts.length >= 1 && latestParts[0] > currentParts[0]) {
      return "major";
    }

    if (currentParts.length >= 2 && latestParts.length >= 2 && latestParts[1] > currentParts[1]) {
      return "minor";
    }

    if (currentParts.length >= 3 && latestParts.length >= 3 && latestParts[2] > currentParts[2]) {
      return "patch";
    }

    return UNKNOWN_TYPE;
  }

  /**
   * Parses version string into numeric parts for comparison.
   *
   * @param version the version string to parse
   * @return array of numeric version parts
   */
  private int[] parseVersionParts(String version) {
    String[] parts = version.split("\\.");
    int[] numericParts = new int[parts.length];

    for (int i = 0; i < parts.length; i++) {
      // Extract only numeric part of each segment
      String numericPart = parts[i].replaceAll("\\D", "");
      numericParts[i] = numericPart.isEmpty() ? 0 : Integer.parseInt(numericPart);
    }

    return numericParts;
  }
}
