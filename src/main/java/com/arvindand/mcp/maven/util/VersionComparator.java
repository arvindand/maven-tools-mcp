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
  private static final String ALPHA = "alpha";
  private static final String BETA = "beta";
  private static final String MILESTONE = "milestone";
  private static final String PATCH = "patch";
  private static final Set<String> STABLE_QUALIFIERS = Set.of("final", "ga", "release");
  private static final Set<String> ALPHA_QUALIFIERS = Set.of(ALPHA, "a");
  private static final Set<String> BETA_QUALIFIERS = Set.of(BETA, "b");
  private static final Set<String> MILESTONE_QUALIFIERS = Set.of(MILESTONE, "m");
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
    if (version == null || version.trim().isEmpty()) {
      return new VersionComponents(new int[0], "");
    }

    String trimmed = version.trim();

    // Split on first hyphen to separate numeric part from qualifier
    int hyphenIndex = findFirstQualifierSeparator(trimmed);
    String numericPart = hyphenIndex != -1 ? trimmed.substring(0, hyphenIndex) : trimmed;
    String qualifier = hyphenIndex != -1 ? trimmed.substring(hyphenIndex + 1).toLowerCase() : "";

    // Parse numeric components (major.minor.patch.etc)
    String[] segments = numericPart.split("\\.");
    int[] numericParts = new int[segments.length];

    for (int i = 0; i < segments.length; i++) {
      try {
        // Handle cases like "1.0.0-SNAPSHOT" where hyphen is within a segment
        String segment = segments[i];
        int segmentHyphen = segment.indexOf('-');
        if (segmentHyphen != -1) {
          segment = segment.substring(0, segmentHyphen);
          // If this is the first time we see a qualifier, capture it
          if (qualifier.isEmpty()) {
            qualifier = segments[i].substring(segmentHyphen + 1).toLowerCase();
          }
        }
        numericParts[i] = Integer.parseInt(segment);
      } catch (NumberFormatException _) {
        numericParts[i] = 0;
      }
    }

    return new VersionComponents(numericParts, qualifier);
  }

  private int findFirstQualifierSeparator(String version) {
    // Look for common qualifier separators: - or _
    // But be smart about it - avoid separating dates (2023-01-15) or similar patterns
    int hyphenIndex = version.indexOf('-');
    int underscoreIndex = version.indexOf('_');

    // Return the first separator found, preferring hyphen
    if (hyphenIndex == -1 && underscoreIndex == -1) return -1;
    if (hyphenIndex == -1) return underscoreIndex;
    if (underscoreIndex == -1) return hyphenIndex;
    return Math.min(hyphenIndex, underscoreIndex);
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
    if (qualifier.isEmpty() || STABLE_QUALIFIERS.contains(qualifier)) {
      return QualifierType.STABLE;
    }

    String lower = qualifier.toLowerCase();

    // Treat service packs as stable (e.g., 1.0.0-SP1)
    if (lower.startsWith("sp")) {
      return QualifierType.STABLE;
    }

    // Common pre-release markers
    if (lower.contains("snapshot")
        || lower.contains("rc")
        || lower.contains("cr")
        || lower.contains("m")
        || lower.contains(BETA)
        || lower.contains(ALPHA)
        || lower.contains("preview")
        || lower.contains("dev")) {
      return QualifierType.PRE_RELEASE;
    }

    // Unknown qualifier: conservatively treat as pre-release rather than stable
    return QualifierType.PRE_RELEASE;
  }

  private VersionType determinePreReleaseVersionType(String qualifier) {
    if (isAlphaQualifier(qualifier)) return VersionType.ALPHA;
    if (isBetaQualifier(qualifier)) return VersionType.BETA;
    if (isMilestoneQualifier(qualifier)) return VersionType.MILESTONE;
    if (isRcQualifier(qualifier)) return VersionType.RC;
    return VersionType.ALPHA; // Default unknown pre-releases to alpha
  }

  private boolean isAlphaQualifier(String qualifier) {
    return ALPHA_QUALIFIERS.contains(qualifier)
        || qualifier.startsWith(ALPHA)
        || qualifier.startsWith("a")
        || qualifier.contains("dev")
        || qualifier.contains("preview");
  }

  private boolean isBetaQualifier(String qualifier) {
    return BETA_QUALIFIERS.contains(qualifier)
        || qualifier.startsWith(BETA)
        || qualifier.startsWith("b");
  }

  private boolean isMilestoneQualifier(String qualifier) {
    return MILESTONE_QUALIFIERS.contains(qualifier)
        || qualifier.startsWith(MILESTONE)
        || qualifier.startsWith("m");
  }

  private boolean isRcQualifier(String qualifier) {
    return RC_QUALIFIERS.contains(qualifier)
        || qualifier.startsWith("rc")
        || qualifier.startsWith("cr")
        || qualifier.contains("candidate");
  }

  private String determineUpdateType(VersionComponents current, VersionComponents latest) {
    // Handle case where numeric parts are identical but qualifiers differ
    boolean sameNumericVersion = areNumericVersionsEqual(current, latest);

    if (sameNumericVersion) {
      // If numeric versions are the same, check qualifiers
      return determineQualifierUpdate(current.qualifier(), latest.qualifier());
    }

    // Compare numeric parts to determine update type
    int maxLength = Math.max(current.numericParts().length, latest.numericParts().length);

    for (int i = 0; i < maxLength; i++) {
      int currentPart = i < current.numericParts().length ? current.numericParts()[i] : 0;
      int latestPart = i < latest.numericParts().length ? latest.numericParts()[i] : 0;

      if (latestPart > currentPart) {
        return switch (i) {
          case 0 -> "major";
          case 1 -> "minor";
          default -> PATCH;
        };
      } else if (currentPart > latestPart) {
        // Current version is higher than "latest" - this is a downgrade scenario
        return UNKNOWN;
      }
    }
    return "none";
  }

  private boolean areNumericVersionsEqual(VersionComponents current, VersionComponents latest) {
    int maxLength = Math.max(current.numericParts().length, latest.numericParts().length);

    for (int i = 0; i < maxLength; i++) {
      int currentPart = i < current.numericParts().length ? current.numericParts()[i] : 0;
      int latestPart = i < latest.numericParts().length ? latest.numericParts()[i] : 0;

      if (currentPart != latestPart) {
        return false;
      }
    }
    return true;
  }

  private String determineQualifierUpdate(String currentQualifier, String latestQualifier) {
    // If both are empty, versions are identical
    if (currentQualifier.isEmpty() && latestQualifier.isEmpty()) {
      return "none";
    }

    // If current has qualifier but latest doesn't, it's upgrading to stable
    if (!currentQualifier.isEmpty() && latestQualifier.isEmpty()) {
      return PATCH; // Treat pre-release to stable as patch update
    }

    // If current is stable but latest has qualifier, this is unusual (downgrade to pre-release)
    if (currentQualifier.isEmpty() && !latestQualifier.isEmpty()) {
      return UNKNOWN;
    }

    // Both have qualifiers - compare stability levels
    return compareQualifierStability(currentQualifier, latestQualifier);
  }

  private String compareQualifierStability(String currentQualifier, String latestQualifier) {
    // Define stability order (lower index = less stable)
    String[] stabilityOrder = {ALPHA, BETA, MILESTONE, "rc", "snapshot"};

    int currentStability = getQualifierStability(currentQualifier, stabilityOrder);
    int latestStability = getQualifierStability(latestQualifier, stabilityOrder);

    if (latestStability > currentStability) {
      return PATCH; // Upgrading to more stable pre-release
    } else if (latestStability < currentStability) {
      return UNKNOWN; // Downgrading to less stable
    } else {
      // Same stability level - might be version number change within qualifier
      return currentQualifier.equals(latestQualifier) ? "none" : PATCH;
    }
  }

  private int getQualifierStability(String qualifier, String[] stabilityOrder) {
    String lowerQualifier = qualifier.toLowerCase();

    for (int i = 0; i < stabilityOrder.length; i++) {
      if (lowerQualifier.contains(stabilityOrder[i])) {
        return i;
      }
    }

    // Unknown qualifier types get medium stability
    return stabilityOrder.length / 2;
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
