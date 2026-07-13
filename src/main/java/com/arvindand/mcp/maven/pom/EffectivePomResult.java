package com.arvindand.mcp.maven.pom;

import com.arvindand.mcp.maven.model.MavenCoordinate;
import java.util.List;
import java.util.Objects;

/**
 * The complete result of resolving a POM.
 *
 * @param dependencies one entry per declared dependency in the input POM. Transitive dependencies
 *     are intentionally not included; the resolver answers "what version does this POM resolve for
 *     each thing it declares?", not "what's the full classpath?"
 * @param parentChain the resolved parent coordinates walked during resolution, closest-first (the
 *     input POM's immediate parent at index 0; deeper ancestors at higher indices). Empty if the
 *     POM has no parent.
 * @param rootImportedBoms the BOMs imported directly by the root POM's {@code
 *     <dependencyManagement>} via {@code <scope>import</scope><type>pom</type>}. These (along with
 *     the direct parent at {@code parentChain[0]}) are the user-controllable knobs — anything else
 *     in the management chain is transitively imported and not directly editable in the input POM.
 * @param rootManagedDeclarations non-import dependencies declared directly in the input POM's
 *     {@code <dependencyManagement>} whose literal version or root-owned backing property can be
 *     edited deterministically
 * @param rootPluginDependencyDeclarations dependencies declared directly under root build plugins
 *     or plugin-management plugins whose literal version or root-owned backing property can be
 *     edited deterministically
 * @param warnings non-fatal issues — unresolved properties, parents that couldn't be fetched,
 *     ranges left as opaque strings, etc. Resolution still produces a result; warnings let the
 *     caller decide whether to trust each entry.
 * @author Arvind Menon
 * @since 3.0.0
 */
public record EffectivePomResult(
    List<EffectiveDependency> dependencies,
    List<MavenCoordinate> parentChain,
    List<MavenCoordinate> rootImportedBoms,
    List<ManagedDeclaration> rootManagedDeclarations,
    List<PluginDependencyDeclaration> rootPluginDependencyDeclarations,
    List<String> warnings) {

  public EffectivePomResult {
    Objects.requireNonNull(dependencies, "dependencies must not be null");
    Objects.requireNonNull(parentChain, "parentChain must not be null");
    Objects.requireNonNull(rootImportedBoms, "rootImportedBoms must not be null");
    Objects.requireNonNull(rootManagedDeclarations, "rootManagedDeclarations must not be null");
    Objects.requireNonNull(
        rootPluginDependencyDeclarations, "rootPluginDependencyDeclarations must not be null");
    Objects.requireNonNull(warnings, "warnings must not be null");
    dependencies = List.copyOf(dependencies);
    parentChain = List.copyOf(parentChain);
    rootImportedBoms = List.copyOf(rootImportedBoms);
    rootManagedDeclarations = List.copyOf(rootManagedDeclarations);
    rootPluginDependencyDeclarations = List.copyOf(rootPluginDependencyDeclarations);
    warnings = List.copyOf(warnings);
  }

  /** Backwards-compatible constructor for callers without plugin dependency declarations. */
  public EffectivePomResult(
      List<EffectiveDependency> dependencies,
      List<MavenCoordinate> parentChain,
      List<MavenCoordinate> rootImportedBoms,
      List<ManagedDeclaration> rootManagedDeclarations,
      List<String> warnings) {
    this(dependencies, parentChain, rootImportedBoms, rootManagedDeclarations, List.of(), warnings);
  }

  /** Backwards-compatible constructor for callers that do not supply root managed declarations. */
  public EffectivePomResult(
      List<EffectiveDependency> dependencies,
      List<MavenCoordinate> parentChain,
      List<MavenCoordinate> rootImportedBoms,
      List<String> warnings) {
    this(dependencies, parentChain, rootImportedBoms, List.of(), List.of(), warnings);
  }
}
