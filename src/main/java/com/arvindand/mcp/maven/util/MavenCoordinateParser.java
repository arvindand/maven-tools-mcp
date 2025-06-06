package com.arvindand.mcp.maven.util;

import com.arvindand.mcp.maven.model.MavenCoordinate;

/**
 * Utility class for parsing Maven coordinates from strings. Provides static methods to parse and
 * validate Maven coordinate strings.
 *
 * @author Arvind Menon
 * @since 0.1.0
 */
public final class MavenCoordinateParser {

  private MavenCoordinateParser() {
    // Prevent instantiation
  }

  /**
   * Parses a Maven coordinate string in the format:
   * "groupId:artifactId[:version][:packaging][:classifier]"
   *
   * @param dependency the dependency string to parse
   * @return the parsed MavenCoordinate
   * @throws IllegalArgumentException if the format is invalid
   */
  public static MavenCoordinate parse(String dependency) {
    if (dependency == null || dependency.trim().isEmpty()) {
      throw new IllegalArgumentException("Dependency string cannot be null or empty");
    }

    String[] parts = dependency.split(":");
    if (parts.length < 2) {
      throw new IllegalArgumentException(
          "Invalid Maven coordinate format. Minimum format is 'groupId:artifactId'. Got: "
              + dependency);
    }

    String groupId = parts[0].trim();
    String artifactId = parts[1].trim();

    if (groupId.isEmpty() || artifactId.isEmpty()) {
      throw new IllegalArgumentException(
          "GroupId and artifactId cannot be empty. Got: " + dependency);
    }

    String version = parts.length > 2 && !parts[2].trim().isEmpty() ? parts[2].trim() : null;
    String packaging = parts.length > 3 && !parts[3].trim().isEmpty() ? parts[3].trim() : null;
    String classifier = parts.length > 4 && !parts[4].trim().isEmpty() ? parts[4].trim() : null;

    return new MavenCoordinate(groupId, artifactId, version, packaging, classifier);
  }

  /**
   * Validates that a Maven coordinate string has the minimum required components.
   *
   * @param dependency the dependency string to validate
   * @throws IllegalArgumentException if the format is invalid
   */
  public static void validate(String dependency) {
    parse(dependency); // This will throw if invalid
  }
}
