package com.arvindand.mcp.maven.pom;

import com.arvindand.mcp.maven.model.MavenCoordinate;
import java.util.Objects;
import java.util.Optional;

/**
 * The resolved view of a single declared dependency in a POM.
 *
 * @param coordinate the dependency's {@code groupId:artifactId}
 * @param effectiveVersion the version that would be used at build time after parent + BOM
 *     resolution. Never null or blank; resolution failures are surfaced via warnings on {@link
 *     EffectivePomResult}, not silent nulls.
 * @param source where {@code effectiveVersion} came from — see {@link Source}
 * @param managedBy the BOM or parent coordinate that supplied {@code effectiveVersion} when {@code
 *     source == MANAGED} or {@code source == EXPLICIT_OVERRIDE}. Empty for {@code EXPLICIT}.
 */
public record EffectiveDependency(
    MavenCoordinate coordinate,
    String effectiveVersion,
    Source source,
    Optional<MavenCoordinate> managedBy) {

  public EffectiveDependency {
    Objects.requireNonNull(coordinate, "coordinate must not be null");
    if (effectiveVersion == null || effectiveVersion.isBlank()) {
      throw new IllegalArgumentException("effectiveVersion must not be blank");
    }
    Objects.requireNonNull(source, "source must not be null");
    Objects.requireNonNull(managedBy, "managedBy must not be null (use Optional.empty())");
    if (source == Source.EXPLICIT && managedBy.isPresent()) {
      throw new IllegalArgumentException("managedBy must be empty when source is EXPLICIT");
    }
  }
}
