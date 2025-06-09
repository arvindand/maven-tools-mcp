package com.arvindand.mcp.maven.util;

import com.arvindand.mcp.maven.model.MavenCoordinate;

/**
 * Utility class for parsing Maven coordinates from strings.
 *
 * @author Arvind Menon
 * @since 0.1.0
 */
public final class MavenCoordinateParser {

  private MavenCoordinateParser() {}

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
    return switch (parts.length) {
      case 0, 1 ->
          throw new IllegalArgumentException(
              "Invalid Maven coordinate format. Minimum format is 'groupId:artifactId'. Got: "
                  + dependency);
      case 2, 3, 4, 5 -> parseValidCoordinate(parts, dependency);
      default ->
          throw new IllegalArgumentException(
              "Invalid Maven coordinate format. Maximum format is"
                  + " 'groupId:artifactId:version:packaging:classifier'. Got: "
                  + dependency);
    };
  }

  /**
   * Validates that a Maven coordinate string has the minimum required components.
   *
   * @param dependency the dependency string to validate
   * @throws IllegalArgumentException if the format is invalid
   */
  public static void validate(String dependency) {
    parse(dependency);
  }

  private static MavenCoordinate parseValidCoordinate(String[] parts, String original) {
    String groupId = parts[0].trim();
    String artifactId = parts[1].trim();

    if (groupId.isEmpty() || artifactId.isEmpty()) {
      throw new IllegalArgumentException(
          "GroupId and artifactId cannot be empty. Got: " + original);
    }

    String version = getPartOrNull(parts, 2);
    String packaging = getPartOrNull(parts, 3);
    String classifier = getPartOrNull(parts, 4);

    return new MavenCoordinate(groupId, artifactId, version, packaging, classifier);
  }

  private static String getPartOrNull(String[] parts, int index) {
    if (index >= parts.length) return null;
    String part = parts[index].trim();
    return part.isEmpty() ? null : part;
  }
}
