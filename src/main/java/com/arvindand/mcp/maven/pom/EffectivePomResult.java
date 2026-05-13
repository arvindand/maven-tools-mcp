package com.arvindand.mcp.maven.pom;

import com.arvindand.mcp.maven.model.MavenCoordinate;
import java.util.List;
import java.util.Objects;

/**
 * The complete result of resolving a POM.
 *
 * @param dependencies one entry per declared dependency in the input POM (transitive dependencies
 *     are intentionally NOT included — Phase 6a scope)
 * @param parentChain the resolved parent coordinates walked during resolution, closest-first (the
 *     input POM's immediate parent at index 0; deeper ancestors at higher indices). Empty if the
 *     POM has no parent.
 * @param warnings non-fatal issues — unresolved properties, parents that couldn't be fetched,
 *     ranges left as opaque strings, etc. Resolution still produces a result; warnings let the
 *     caller decide whether to trust each entry.
 */
public record EffectivePomResult(
    List<EffectiveDependency> dependencies,
    List<MavenCoordinate> parentChain,
    List<String> warnings) {

  public EffectivePomResult {
    Objects.requireNonNull(dependencies, "dependencies must not be null");
    Objects.requireNonNull(parentChain, "parentChain must not be null");
    Objects.requireNonNull(warnings, "warnings must not be null");
    dependencies = List.copyOf(dependencies);
    parentChain = List.copyOf(parentChain);
    warnings = List.copyOf(warnings);
  }
}
