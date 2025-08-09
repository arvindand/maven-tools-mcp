package com.arvindand.mcp.maven.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * Represents comprehensive dependency information including existence, version details, and
 * metadata.
 *
 * @param status the status of the dependency check (success, error, not_found)
 * @param groupId the Maven group ID
 * @param artifactId the Maven artifact ID
 * @param version the specific version checked
 * @param exists whether the dependency version exists in Maven Central
 * @param type the version type classification (stable, rc, beta, alpha, milestone)
 * @param isStable whether this is considered a stable version
 * @param timestamp the release timestamp in milliseconds since epoch (if available)
 * @author Arvind Menon
 * @since 1.3.0
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DependencyInfo(
    String status,
    String groupId,
    String artifactId,
    String version,
    boolean exists,
    String type,
    boolean isStable,
    Long timestamp) {
  public static DependencyInfo success(
      MavenCoordinate coordinate,
      String version,
      boolean exists,
      String type,
      boolean isStable,
      Long timestamp) {
    return new DependencyInfo(
        "success",
        coordinate.groupId(),
        coordinate.artifactId(),
        version,
        exists,
        type,
        isStable,
        timestamp);
  }
}
