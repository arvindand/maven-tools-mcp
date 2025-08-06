package com.arvindand.mcp.maven.util;

import com.arvindand.mcp.maven.model.VersionInfo.VersionType;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;
import java.util.Set;
import org.apache.maven.artifact.versioning.ComparableVersion;
import org.springframework.stereotype.Component;

/**
 * Utility for comparing and analyzing Maven version strings.
 *
 * @author Arvind Menon
 * @since 0.1.0
 */
@Component
public final class VersionComparator implements Comparator<String> {

  private static final String UNKNOWN = "unknown";
  private static final Set<String> STABLE_QUALIFIERS = Set.of("final", "ga", "release");
  private static final Set<String> ALPHA_QUALIFIERS = Set.of("alpha", "a");
  private static final Set<String> BETA_QUALIFIERS = Set.of("beta", "b");
  private static final Set<String> MILESTONE_QUALIFIERS = Set.of("milestone", "m");
  private static final Set<String> RC_QUALIFIERS = Set.of("rc", "cr");

  /**
   * Compares two version strings using Maven's ComparableVersion.
   *
   * @param version1 first version to compare
   * @param version2 second version to compare
   * @return negative if version1 < version2, 0 if equal, positive if version1 > version2
   */
  @Override
  public int compare(String version1, String version2) {
    return switch ((version1 == null ? 1 : 0) + (version2 == null ? 2 : 0)) {
      case 0 ->
          new ComparableVersion(version1)
              .compareTo(new ComparableVersion(version2)); // both non-null
      case 1 -> -1; // version1 is null, version2 is not
      case 2 -> 1; // version2 is null, version1 is not
      case 3 -> 0; // both null
      default -> throw new IllegalStateException("Unexpected comparison state");
    };
  }

  /**
   * Gets the latest version from an array of version strings.
   *
   * @param versions array of version strings
   * @return the latest version or null if array is empty
   */
  public static String getLatest(String[] versions) {
    if (versions == null || versions.length == 0) return null;
    return Arrays.stream(versions).max(new VersionComparator()).orElse(null);
  }

  /**
   * Determines the type of update between current and latest versions.
   *
   * @param currentVersion the current version
   * @param latestVersion the latest available version
   * @return update type: "major", "minor", "patch", "none", or "unknown"
   */
  public String determineUpdateType(String currentVersion, String latestVersion) {
    return switch (validateVersions(currentVersion, latestVersion)) {
      case INVALID -> UNKNOWN;
      case EQUAL -> "none";
      case VALID -> {
        int comparison = compare(currentVersion, latestVersion);
        if (comparison >= 0) {
          yield comparison == 0 ? "none" : UNKNOWN;
        } else {
          yield determineUpdateType(parseVersion(currentVersion), parseVersion(latestVersion));
        }
      }
    };
  }

  /**
   * Checks if a version is considered stable (not pre-release).
   *
   * @param version the version to check
   * @return true if the version is stable
   */
  public boolean isStableVersion(String version) {
    if (version == null) return false;

    return switch (classifyQualifier(extractQualifier(version))) {
      case STABLE -> true;
      case PRE_RELEASE -> false;
    };
  }

  /**
   * Gets the version type enum for a version string.
   *
   * @param version the version to analyze
   * @return the version type enum
   */
  public VersionType getVersionType(String version) {
    if (version == null) return VersionType.STABLE;

    return switch (classifyQualifier(extractQualifier(version))) {
      case STABLE -> VersionType.STABLE;
      case PRE_RELEASE -> determinePreReleaseVersionType(extractQualifier(version));
    };
  }

  /**
   * Gets the version type as a display string.
   *
   * @param version the version to analyze
   * @return the version type as a string
   */
  public String getVersionTypeString(String version) {
    if (version == null) return UNKNOWN;
    return getVersionType(version).getDisplayName();
  }

  /**
   * Parses a version string into numeric components and qualifier.
   *
   * @param version the version string to parse
   * @return parsed version components
   */
  public VersionComponents parseVersion(String version) {
    if (version == null) return new VersionComponents(new int[0], "");

    String normalized = new ComparableVersion(version).toString();
    int hyphenIndex = normalized.indexOf('-');

    String numericPart = hyphenIndex != -1 ? normalized.substring(0, hyphenIndex) : normalized;
    String qualifier = hyphenIndex != -1 ? normalized.substring(hyphenIndex + 1).toLowerCase() : "";

    String[] segments = numericPart.split("\\.");
    int[] numericParts = new int[segments.length];

    for (int i = 0; i < segments.length; i++) {
      try {
        numericParts[i] = Integer.parseInt(segments[i]);
      } catch (NumberFormatException _) {
        numericParts[i] = 0;
      }
    }

    return new VersionComponents(numericParts, qualifier);
  }

  private String extractQualifier(String version) {
    return parseVersion(version).qualifier();
  }

  private ValidationResult validateVersions(String current, String latest) {
    if (current == null || latest == null) return ValidationResult.INVALID;
    if (current.equals(latest)) return ValidationResult.EQUAL;
    return ValidationResult.VALID;
  }

  private QualifierType classifyQualifier(String qualifier) {
    if (qualifier.isEmpty()
        || STABLE_QUALIFIERS.contains(qualifier)
        || qualifier.startsWith("sp")) {
      return QualifierType.STABLE;
    }
    return QualifierType.PRE_RELEASE;
  }

  private VersionType determinePreReleaseVersionType(String qualifier) {
    if (isAlphaQualifier(qualifier)) return VersionType.ALPHA;
    if (isBetaQualifier(qualifier)) return VersionType.BETA;
    if (isMilestoneQualifier(qualifier)) return VersionType.MILESTONE;
    if (isRcQualifier(qualifier)) return VersionType.RC;
    return VersionType.STABLE;
  }

  private boolean isAlphaQualifier(String qualifier) {
    return ALPHA_QUALIFIERS.contains(qualifier)
        || qualifier.startsWith("alpha")
        || qualifier.startsWith("a")
        || qualifier.contains("dev")
        || qualifier.contains("preview");
  }

  private boolean isBetaQualifier(String qualifier) {
    return BETA_QUALIFIERS.contains(qualifier)
        || qualifier.startsWith("beta")
        || qualifier.startsWith("b");
  }

  private boolean isMilestoneQualifier(String qualifier) {
    return MILESTONE_QUALIFIERS.contains(qualifier)
        || qualifier.startsWith("milestone")
        || qualifier.startsWith("m");
  }

  private boolean isRcQualifier(String qualifier) {
    return RC_QUALIFIERS.contains(qualifier)
        || qualifier.startsWith("rc")
        || qualifier.startsWith("cr")
        || qualifier.contains("candidate");
  }

  private String determineUpdateType(VersionComponents current, VersionComponents latest) {
    int maxLength = Math.max(current.numericParts().length, latest.numericParts().length);

    for (int i = 0; i < maxLength; i++) {
      int currentPart = i < current.numericParts().length ? current.numericParts()[i] : 0;
      int latestPart = i < latest.numericParts().length ? latest.numericParts()[i] : 0;

      if (latestPart > currentPart) {
        return switch (i) {
          case 0 -> "major";
          case 1 -> "minor";
          default -> "patch";
        };
      } else if (currentPart > latestPart) {
        return UNKNOWN;
      }
    }
    return "none";
  }

  /**
   * Represents parsed version components.
   *
   * @param numericParts the numeric parts of the version
   * @param qualifier the qualifier part (e.g., "alpha", "beta")
   */
  public record VersionComponents(int[] numericParts, String qualifier) {
    @Override
    public boolean equals(Object obj) {
      if (this == obj) return true;
      if (obj == null || getClass() != obj.getClass()) return false;
      VersionComponents that = (VersionComponents) obj;
      return Arrays.equals(numericParts, that.numericParts)
          && Objects.equals(qualifier, that.qualifier);
    }

    @Override
    public int hashCode() {
      return Objects.hash(Arrays.hashCode(numericParts), qualifier);
    }

    @Override
    public String toString() {
      return "VersionComponents{numericParts="
          + Arrays.toString(numericParts)
          + ", qualifier='"
          + qualifier
          + "'}";
    }
  }

  private enum ValidationResult {
    INVALID,
    EQUAL,
    VALID
  }

  private enum QualifierType {
    STABLE,
    PRE_RELEASE
  }
}
