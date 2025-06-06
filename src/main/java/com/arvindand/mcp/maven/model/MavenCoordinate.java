package com.arvindand.mcp.maven.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Represents a Maven coordinate with groupId, artifactId, version, packaging, and classifier. This
 * record provides an immutable representation of Maven artifact coordinates.
 *
 * @param groupId the Maven group identifier
 * @param artifactId the Maven artifact identifier
 * @param version the artifact version (optional)
 * @param packaging the packaging type (optional, defaults to 'jar')
 * @param classifier the artifact classifier (optional)
 * @author Arvind Menon
 * @since 0.1.0
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MavenCoordinate(
    String groupId, String artifactId, String version, String packaging, String classifier) {

  /**
   * Creates a MavenCoordinate with groupId and artifactId only.
   *
   * @param groupId the Maven group identifier
   * @param artifactId the Maven artifact identifier
   * @return a new MavenCoordinate instance
   */
  public static MavenCoordinate of(String groupId, String artifactId) {
    return new MavenCoordinate(groupId, artifactId, null, null, null);
  }

  /**
   * Creates a MavenCoordinate with groupId, artifactId, and version.
   *
   * @param groupId the Maven group identifier
   * @param artifactId the Maven artifact identifier
   * @param version the artifact version
   * @return a new MavenCoordinate instance
   */
  public static MavenCoordinate of(String groupId, String artifactId, String version) {
    return new MavenCoordinate(groupId, artifactId, version, null, null);
  }

  /**
   * Creates a formatted Maven coordinate string for display.
   *
   * @return the formatted coordinate string in Maven standard format
   */
  public String toCoordinateString() {
    StringBuilder sb = new StringBuilder();
    sb.append(groupId).append(":").append(artifactId);
    if (version != null) {
      sb.append(":").append(version);
    }
    if (packaging != null) {
      sb.append(":").append(packaging);
    }
    if (classifier != null) {
      sb.append(":").append(classifier);
    }
    return sb.toString();
  }
}
