package com.arvindand.mcp.maven.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.Nullable;

/**
 * A relocation reported to tool clients: the artifact's newest POM says it moved to new coordinates
 * (for example {@code mysql:mysql-connector-java} to {@code com.mysql:mysql-connector-j}).
 *
 * <p>Always optional in a response: the field is omitted when the artifact is not relocated or when
 * the best-effort lookup failed.
 *
 * @param groupId groupId of the new coordinates
 * @param artifactId artifactId of the new coordinates
 * @param version version the POM names at the new coordinates; null means "same version"
 * @param message free-text explanation from the POM, if any
 * @param latestVersion newest stable version published at the new coordinates, if it could be
 *     looked up
 * @since 3.2.5
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RelocationInfo(
    String groupId,
    String artifactId,
    @Nullable String version,
    @Nullable String message,
    @Nullable String latestVersion) {

  /**
   * Builds the tool-facing record from a POM relocation.
   *
   * @param relocation the relocation read from the POM
   * @param latestVersion newest stable version at the new coordinates, or null
   * @return the record
   */
  public static RelocationInfo of(Relocation relocation, @Nullable String latestVersion) {
    return new RelocationInfo(
        relocation.groupId(),
        relocation.artifactId(),
        relocation.version(),
        relocation.message(),
        latestVersion);
  }

  /**
   * Returns the new coordinates as {@code groupId:artifactId}.
   *
   * @return the new coordinates
   */
  public String coordinate() {
    return groupId + ":" + artifactId;
  }
}
