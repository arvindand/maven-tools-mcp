package com.arvindand.mcp.maven.util;

import java.util.Comparator;
import java.util.regex.Pattern;

/**
 * Utility class for comparing Maven version strings. Implements semantic version comparison logic
 * with support for numeric and alpha components.
 *
 * @author Arvind Menon
 * @since 0.1.0
 */
public final class VersionComparator implements Comparator<String> {

  public VersionComparator() {
    // Public constructor for use as Comparator
  }

  private static final Pattern NUMERIC_PATTERN = Pattern.compile("\\d+");

  /**
   * Compares two version strings using semantic versioning logic.
   *
   * @param version1 the first version to compare
   * @param version2 the second version to compare
   * @return negative if version1 < version2, positive if version1 > version2, zero if equal
   */
  @Override
  public int compare(String version1, String version2) {
    if (version1.equals(version2)) {
      return 0;
    }

    String[] parts1 = version1.split("[.\\-_]");
    String[] parts2 = version2.split("[.\\-_]");

    int maxLength = Math.max(parts1.length, parts2.length);

    for (int i = 0; i < maxLength; i++) {
      String part1 = i < parts1.length ? parts1[i] : "0";
      String part2 = i < parts2.length ? parts2[i] : "0";

      int comparison = compareParts(part1, part2);
      if (comparison != 0) {
        return comparison;
      }
    }

    return 0;
  }

  /**
   * Compares individual parts of version strings. Handles both numeric and string comparisons with
   * special qualifier priorities.
   *
   * @param part1 the first part to compare
   * @param part2 the second part to compare
   * @return comparison result
   */
  private int compareParts(String part1, String part2) { // Try to compare as numbers first
    if (NUMERIC_PATTERN.matcher(part1).matches() && NUMERIC_PATTERN.matcher(part2).matches()) {
      try {
        Long num1 = Long.valueOf(part1);
        Long num2 = Long.valueOf(part2);
        return num1.compareTo(num2); // Normal ascending order
      } catch (NumberFormatException _) {
        // Fall back to string comparison
      }
    } // Handle special version qualifiers
    int priority1 = getQualifierPriority(part1);
    int priority2 = getQualifierPriority(part2);
    if (priority1 != priority2) {
      return Integer.compare(priority1, priority2); // Normal ascending order
    }

    // String comparison for qualifiers of same priority
    return part1.compareToIgnoreCase(part2); // Normal ascending order
  }

  /**
   * Determines the priority of version qualifiers for comparison. Higher priority numbers indicate
   * more stable/preferred versions.
   *
   * @param qualifier the version qualifier to evaluate
   * @return priority value (higher = more stable)
   */
  private int getQualifierPriority(String qualifier) {
    String lower = qualifier.toLowerCase();

    // Check if this is a plain numeric version (no qualifier) - highest priority
    if (NUMERIC_PATTERN.matcher(qualifier).matches()) {
      return 100; // Plain numeric versions are most stable
    }

    // Special qualifiers are actually considered LESS than plain releases according to test
    // expectations
    if (lower.contains("final") || lower.contains("release") || lower.contains("ga")) {
      return 95; // Slightly less than plain numeric
    } else if (lower.contains("rc")) {
      return 90;
    } else if (lower.contains("beta")) {
      return 80;
    } else if (lower.contains("alpha")) {
      return 70;
    } else if (lower.contains("m") && NUMERIC_PATTERN.matcher(lower.substring(1)).matches()) {
      return 50; // Milestone
    }
    return 75; // Default for unknown qualifiers
  }

  /**
   * Gets the latest version from an array of version strings.
   *
   * @param versions array of version strings
   * @return the latest version, or null if array is empty
   */
  public static String getLatest(String[] versions) {
    if (versions == null || versions.length == 0) {
      return null;
    }

    return java.util.Arrays.stream(versions).max(new VersionComparator()).orElse(null);
  }
}
