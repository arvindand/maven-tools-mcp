package com.arvindand.mcp.maven.service;

import java.util.Arrays;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Service for analyzing and categorizing Maven artifact versions.
 *
 * <p>Provides utilities to determine the type of a version (e.g., release candidate, alpha, beta,
 * milestone, stable), check if a version is stable, and determine the type of update (major, minor,
 * patch, or none) between two versions.
 *
 * <p>Version types are detected using regular expressions and string patterns for common
 * pre-release and release identifiers.
 *
 * @author Arvind Menon
 * @since 0.1.0
 */
@Service
public class VersionAnalysisService {

  private static final String RC_TYPE = "rc";
  private static final String ALPHA_TYPE = "alpha";
  private static final String BETA_TYPE = "beta";
  private static final String MILESTONE_TYPE = "milestone";
  private static final String STABLE_TYPE = "stable";
  private static final String UNKNOWN_TYPE = "unknown";
  private static final String NONE_TYPE = "none";
  private static final String MAJOR_TYPE = "major";
  private static final String MINOR_TYPE = "minor";
  private static final String PATCH_TYPE = "patch";
  private static final Pattern RC_PATTERN =
      Pattern.compile(".*[-.](?:RC|CR)\\d*$", Pattern.CASE_INSENSITIVE);
  private static final Pattern ALPHA_PATTERN =
      Pattern.compile(".*[-.](?:ALPHA|A)\\d*$", Pattern.CASE_INSENSITIVE);
  private static final Pattern BETA_PATTERN =
      Pattern.compile(".*[-.](?:BETA|B)\\d*$", Pattern.CASE_INSENSITIVE);
  private static final Pattern MILESTONE_PATTERN =
      Pattern.compile(".*[-.](?:M|MILESTONE)\\d*$", Pattern.CASE_INSENSITIVE);

  public String getVersionType(String version) {
    if (version == null) return UNKNOWN_TYPE;

    var normalizedVersion = version.trim();

    return switch (checkVersionPattern(normalizedVersion)) {
      case RC_TYPE -> RC_TYPE;
      case ALPHA_TYPE -> ALPHA_TYPE;
      case BETA_TYPE -> BETA_TYPE;
      case MILESTONE_TYPE -> MILESTONE_TYPE;
      default -> checkStringPatterns(normalizedVersion.toLowerCase());
    };
  }

  private String checkVersionPattern(String version) {
    if (RC_PATTERN.matcher(version).matches()) return RC_TYPE;
    if (ALPHA_PATTERN.matcher(version).matches()) return ALPHA_TYPE;
    if (BETA_PATTERN.matcher(version).matches()) return BETA_TYPE;
    if (MILESTONE_PATTERN.matcher(version).matches()) return MILESTONE_TYPE;
    return STABLE_TYPE;
  }

  private String checkStringPatterns(String lowerVersion) {
    if (lowerVersion.contains("preview")
        || lowerVersion.contains("pre")
        || lowerVersion.contains("dev")
        || lowerVersion.contains("experimental")) {
      return ALPHA_TYPE;
    }
    if (lowerVersion.contains("candidate") || lowerVersion.contains("release-candidate")) {
      return RC_TYPE;
    }
    return STABLE_TYPE;
  }

  public boolean isStableVersion(String version) {
    return STABLE_TYPE.equals(getVersionType(version));
  }

  public String determineUpdateType(String currentVersion, String latestVersion) {
    if (currentVersion == null || latestVersion == null) return UNKNOWN_TYPE;
    if (currentVersion.equals(latestVersion)) return NONE_TYPE;

    try {
      return compareVersions(currentVersion, latestVersion);
    } catch (Exception _) {
      return UNKNOWN_TYPE;
    }
  }

  private String compareVersions(String currentVersion, String latestVersion) {
    var currentParts = parseVersionParts(currentVersion);
    var latestParts = parseVersionParts(latestVersion);

    if (currentParts.length >= 1 && latestParts.length >= 1 && latestParts[0] > currentParts[0]) {
      return MAJOR_TYPE;
    }
    if (currentParts.length >= 2 && latestParts.length >= 2 && latestParts[1] > currentParts[1]) {
      return MINOR_TYPE;
    }
    if (currentParts.length >= 3 && latestParts.length >= 3 && latestParts[2] > currentParts[2]) {
      return PATCH_TYPE;
    }
    return UNKNOWN_TYPE;
  }

  private int[] parseVersionParts(String version) {
    return Arrays.stream(version.split("\\."))
        .mapToInt(
            part -> {
              String numeric = part.replaceAll("\\D", "");
              return numeric.isEmpty() ? 0 : Integer.valueOf(numeric);
            })
        .toArray();
  }
}
