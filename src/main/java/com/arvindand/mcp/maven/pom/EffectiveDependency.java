package com.arvindand.mcp.maven.pom;

import com.arvindand.mcp.maven.model.MavenCoordinate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The resolved view of a single declared dependency in a POM.
 *
 * @param groupId the dependency's group ID
 * @param artifactId the dependency's artifact ID
 * @param effectiveVersion the version that would be used at build time after parent + BOM
 *     resolution. Never null or blank; resolution failures are surfaced via warnings on {@link
 *     EffectivePomResult}, not silent nulls.
 * @param source where {@code effectiveVersion} came from — see {@link Source}
 * @param managedBy the BOM or parent coordinate that supplied {@code effectiveVersion} when {@code
 *     source == MANAGED} or {@code source == EXPLICIT_OVERRIDE}. Empty for {@code EXPLICIT}.
 * @param conflicts other parent POMs or imported BOMs that would have managed this coordinate but
 *     lost to {@code managedBy} (per closest-ancestor-wins / first-declared semantics). Empty when
 *     there were no competing candidates. Surfaced as raw data — the caller decides whether the
 *     winning version is the right one, or whether the dependency should be pinned explicitly.
 * @author Arvind Menon
 * @since 3.0.0
 */
public record EffectiveDependency(
    String groupId,
    String artifactId,
    String effectiveVersion,
    Source source,
    Optional<MavenCoordinate> managedBy,
    List<ManagedAlternative> conflicts) {

  public EffectiveDependency {
    Objects.requireNonNull(groupId, "groupId must not be null");
    Objects.requireNonNull(artifactId, "artifactId must not be null");
    if (effectiveVersion == null || effectiveVersion.isBlank()) {
      throw new IllegalArgumentException("effectiveVersion must not be blank");
    }
    Objects.requireNonNull(source, "source must not be null");
    Objects.requireNonNull(managedBy, "managedBy must not be null (use Optional.empty())");
    if (source == Source.EXPLICIT && managedBy.isPresent()) {
      throw new IllegalArgumentException("managedBy must be empty when source is EXPLICIT");
    }
    Objects.requireNonNull(conflicts, "conflicts must not be null (use List.of())");
    conflicts = List.copyOf(conflicts);
  }
}
