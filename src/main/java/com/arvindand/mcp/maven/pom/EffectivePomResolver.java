package com.arvindand.mcp.maven.pom;

import static com.arvindand.mcp.maven.config.CacheConstants.MAVEN_EFFECTIVE_POM;

import com.arvindand.mcp.maven.model.MavenCoordinate;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * Resolves the effective version of each declared dependency in a POM.
 *
 * <p>Walks the parent chain (up to 10 levels) via {@link PomFetcher}, merges {@code <properties>}
 * blocks closest-ancestor-wins, interpolates {@code ${name}} placeholders in dependency versions
 * via {@link PropertyInterpolator}, and merges {@code <dependencyManagement>} entries (again
 * closest-ancestor-wins) to classify each declared dependency as {@link Source#EXPLICIT}, {@link
 * Source#MANAGED}, or {@link Source#EXPLICIT_OVERRIDE}. Unresolved placeholders and unreachable
 * parents surface as warnings on {@link EffectivePomResult}; resolution still produces a result
 * rather than aborting.
 *
 * <p>BOM imports ({@code <scope>import</scope><type>pom</type>}) in {@code <dependencyManagement>}
 * are resolved recursively: the BOM is fetched, its properties are merged (caller wins on
 * collision), and its managed entries are accumulated with the same closest-ancestor-wins logic.
 * When multiple BOMs imported at the same level disagree about a coordinate, the first-declared
 * wins per Maven semantics and the losing candidates are exposed on {@link
 * EffectiveDependency#conflicts()} so the caller can decide whether to pin the version explicitly.
 *
 * <p>See {@code package-info.java} for design notes and attribution.
 *
 * @author Arvind Menon
 * @since 3.0.0
 */
@Service
public class EffectivePomResolver {

  private static final int MAX_PARENT_DEPTH = 10;
  private static final Pattern EXACT_PROPERTY_REFERENCE = Pattern.compile("\\$\\{([^}]+)}");

  private final PomFetcher fetcher;

  public EffectivePomResolver(PomFetcher fetcher) {
    this.fetcher = fetcher;
  }

  /**
   * Resolves the effective POM for the given POM XML string.
   *
   * <p>Results are cached by raw {@code pomXml} content for 1 hour. A follow-up call from the same
   * client (e.g., {@code analyze_pom_dependencies} then {@code recommend_pom_upgrades} on the same
   * POM) skips the entire parent / DM walk including XML reparse.
   *
   * <p>The 1h TTL here is independent of the underlying {@code maven-pom-xml} cache (24h). When
   * this entry expires the recomputation re-uses upstream-cached parent / BOM XML rather than
   * refetching — so a freshly-published parent only surfaces once both caches expire (or the input
   * pomXml itself changes). The shorter TTL here biases toward freshness for resolver-code edits
   * while the upstream stays patient about network calls.
   *
   * @param pomXml the raw POM XML content
   * @return the resolved effective POM result
   * @throws IllegalArgumentException if the input is not valid POM XML
   */
  @Cacheable(value = MAVEN_EFFECTIVE_POM, key = "#pomXml")
  public EffectivePomResult resolve(String pomXml) {
    Model root = parsePom(pomXml);
    List<String> warnings = new ArrayList<>();
    ParentContext parents = walkParents(root, warnings);
    Set<String> visitedBoms = new HashSet<>();
    Map<ManagementKey, ManagedEntry> managed =
        buildManagedVersionMap(root, parents, warnings, visitedBoms);
    List<EffectiveDependency> deps =
        classifyDependencies(root, parents.properties(), managed, warnings);
    List<MavenCoordinate> rootBomImports = extractRootBomImports(root, parents.properties());
    List<ManagedDeclaration> rootManagedDeclarations =
        extractRootManagedDeclarations(root, parents.properties());
    List<PluginDependencyDeclaration> rootPluginDependencyDeclarations =
        extractRootPluginDependencyDeclarations(root, parents.properties());
    return new EffectivePomResult(
        deps,
        parents.chain(),
        rootBomImports,
        rootManagedDeclarations,
        rootPluginDependencyDeclarations,
        warnings);
  }

  /** Collects directly-editable dependencies from root build plugins and plugin management. */
  private static List<PluginDependencyDeclaration> extractRootPluginDependencyDeclarations(
      Model root, Map<String, String> properties) {
    if (root.getBuild() == null) {
      return List.of();
    }
    List<PluginDependencyDeclaration> declarations = new ArrayList<>();
    declarations.addAll(
        extractPluginDependencies(
            root.getBuild().getPlugins(),
            root,
            properties,
            PluginDependencyDeclaration.BUILD_PLUGINS));
    if (root.getBuild().getPluginManagement() != null) {
      declarations.addAll(
          extractPluginDependencies(
              root.getBuild().getPluginManagement().getPlugins(),
              root,
              properties,
              PluginDependencyDeclaration.PLUGIN_MANAGEMENT));
    }
    return List.copyOf(declarations);
  }

  private static List<PluginDependencyDeclaration> extractPluginDependencies(
      List<Plugin> plugins, Model root, Map<String, String> properties, String declaredIn) {
    return plugins.stream()
        .flatMap(
            plugin ->
                plugin.getDependencies().stream()
                    .map(
                        dependency ->
                            toPluginDependencyDeclaration(
                                dependency, plugin, root, properties, declaredIn))
                    .flatMap(Optional::stream))
        .toList();
  }

  private static Optional<PluginDependencyDeclaration> toPluginDependencyDeclaration(
      Dependency dependency,
      Plugin plugin,
      Model root,
      Map<String, String> properties,
      String declaredIn) {
    String groupId = PropertyInterpolator.interpolate(dependency.getGroupId(), properties);
    String artifactId = PropertyInterpolator.interpolate(dependency.getArtifactId(), properties);
    String version = PropertyInterpolator.interpolate(dependency.getVersion(), properties);
    String pluginGroupId =
        plugin.getGroupId() == null
            ? "org.apache.maven.plugins"
            : PropertyInterpolator.interpolate(plugin.getGroupId(), properties);
    String pluginArtifactId = PropertyInterpolator.interpolate(plugin.getArtifactId(), properties);
    if (hasUnresolvedValue(groupId)
        || hasUnresolvedValue(artifactId)
        || hasUnresolvedValue(version)
        || hasUnresolvedValue(pluginGroupId)
        || hasUnresolvedValue(pluginArtifactId)) {
      return Optional.empty();
    }

    String declaredVersion = dependency.getVersion();
    Matcher propertyReference = EXACT_PROPERTY_REFERENCE.matcher(declaredVersion);
    if (propertyReference.matches()) {
      String propertyName = propertyReference.group(1);
      if (root.getProperties() != null && root.getProperties().containsKey(propertyName)) {
        return Optional.of(
            PluginDependencyDeclaration.property(
                groupId,
                artifactId,
                version,
                propertyName,
                pluginGroupId,
                pluginArtifactId,
                declaredIn));
      }
      return Optional.empty();
    }
    if (declaredVersion.contains("${")) {
      return Optional.empty();
    }
    return Optional.of(
        PluginDependencyDeclaration.literal(
            groupId, artifactId, version, pluginGroupId, pluginArtifactId, declaredIn));
  }

  /**
   * Collects directly-editable, non-import entries from the root POM's {@code
   * <dependencyManagement>}. An exact {@code ${property}} reference is actionable only when that
   * property is declared by the root POM itself; inherited and compound expressions have no safe,
   * unambiguous edit target in the input file.
   */
  private static List<ManagedDeclaration> extractRootManagedDeclarations(
      Model root, Map<String, String> properties) {
    if (root.getDependencyManagement() == null) {
      return List.of();
    }
    return root.getDependencyManagement().getDependencies().stream()
        .filter(d -> !("import".equals(d.getScope()) && "pom".equals(d.getType())))
        .map(d -> toManagedDeclaration(d, root, properties))
        .flatMap(Optional::stream)
        .toList();
  }

  private static Optional<ManagedDeclaration> toManagedDeclaration(
      Dependency dependency, Model root, Map<String, String> properties) {
    String groupId = PropertyInterpolator.interpolate(dependency.getGroupId(), properties);
    String artifactId = PropertyInterpolator.interpolate(dependency.getArtifactId(), properties);
    String version = PropertyInterpolator.interpolate(dependency.getVersion(), properties);
    if (hasUnresolvedValue(groupId)
        || hasUnresolvedValue(artifactId)
        || hasUnresolvedValue(version)) {
      return Optional.empty();
    }

    String declaredVersion = dependency.getVersion();
    Matcher propertyReference = EXACT_PROPERTY_REFERENCE.matcher(declaredVersion);
    if (propertyReference.matches()) {
      String propertyName = propertyReference.group(1);
      if (root.getProperties() != null && root.getProperties().containsKey(propertyName)) {
        return Optional.of(ManagedDeclaration.property(groupId, artifactId, version, propertyName));
      }
      return Optional.empty();
    }
    if (declaredVersion.contains("${")) {
      return Optional.empty();
    }
    return Optional.of(ManagedDeclaration.literal(groupId, artifactId, version));
  }

  private static boolean hasUnresolvedValue(String value) {
    return value == null || value.isBlank() || value.contains("${");
  }

  /**
   * Collects BOM coordinates imported directly by {@code root}'s {@code <dependencyManagement>} via
   * {@code <scope>import</scope><type>pom</type>}. Transitively-imported BOMs are intentionally
   * excluded — only what's in the user's POM file is user-controllable.
   */
  private static List<MavenCoordinate> extractRootBomImports(
      Model root, Map<String, String> properties) {
    if (root.getDependencyManagement() == null) {
      return List.of();
    }
    return root.getDependencyManagement().getDependencies().stream()
        .filter(d -> "import".equals(d.getScope()) && "pom".equals(d.getType()))
        .map(d -> interpolateBomCoord(d, properties))
        .filter(Objects::nonNull)
        .toList();
  }

  private static MavenCoordinate interpolateBomCoord(Dependency d, Map<String, String> properties) {
    String g = PropertyInterpolator.interpolate(d.getGroupId(), properties);
    String a = PropertyInterpolator.interpolate(d.getArtifactId(), properties);
    String v = PropertyInterpolator.interpolate(d.getVersion(), properties);
    if (g == null || a == null || v == null) {
      return null;
    }
    return MavenCoordinate.of(g, a, v);
  }

  /**
   * Resolves {@code pomXml} with a bundle of sideloaded POMs available to the parent /
   * dependencyManagement / BOM-import walks. The sideloaded POMs are tried first; the injected
   * {@link PomFetcher} (typically {@code MavenCentralPomFetcher}) serves as the fallback.
   */
  public EffectivePomResult resolve(String pomXml, List<String> sideloadedPoms) {
    if (sideloadedPoms == null || sideloadedPoms.isEmpty()) {
      return resolve(pomXml);
    }
    PomFetcher composite =
        new CompositePomFetcher(List.of(InMemoryPomFetcher.fromXml(sideloadedPoms), this.fetcher));
    return new EffectivePomResolver(composite).resolve(pomXml);
  }

  /**
   * Resolves every POM in the bundle as a primary POM, with all other POMs in the bundle available
   * as sideloaded context. Each result is independent — order matches the input list. Use this for
   * aggregator-level analysis of a multi-module project.
   */
  public List<EffectivePomResult> resolveAll(List<String> poms) {
    Objects.requireNonNull(poms, "poms must not be null");
    if (poms.isEmpty()) {
      return List.of();
    }
    PomFetcher composite =
        new CompositePomFetcher(List.of(InMemoryPomFetcher.fromXml(poms), this.fetcher));
    EffectivePomResolver bundleResolver = new EffectivePomResolver(composite);
    return poms.stream().map(bundleResolver::resolve).toList();
  }

  private Model parsePom(String pomXml) {
    try {
      return new MavenXpp3Reader().read(new StringReader(pomXml));
    } catch (XmlPullParserException | IOException ex) {
      throw new IllegalArgumentException("Input is not a valid POM: " + ex.getMessage(), ex);
    }
  }

  /**
   * Walks the parent chain (up to {@value MAX_PARENT_DEPTH} levels) starting from {@code root},
   * accumulating coordinates, fetched {@link Model}s, and a merged property map (closest-ancestor
   * wins). Records a warning and stops walking on the first unreachable parent.
   *
   * @return a {@link ParentContext} whose {@code chain} and {@code models} are index-aligned
   *     (closest ancestor at index 0), and whose {@code properties} are fully merged
   */
  private ParentContext walkParents(Model root, List<String> warnings) {
    List<MavenCoordinate> chain = new ArrayList<>();
    List<Model> models = new ArrayList<>();
    Map<String, String> properties = new HashMap<>();
    seedProjectProperties(root, properties);
    if (root.getProperties() != null) {
      root.getProperties().forEach((k, v) -> properties.put(k.toString(), v.toString()));
    }

    Model cursor = root;
    for (int depth = 0; depth < MAX_PARENT_DEPTH && cursor.getParent() != null; depth++) {
      var p = cursor.getParent();
      var parentCoord = MavenCoordinate.of(p.getGroupId(), p.getArtifactId(), p.getVersion());
      Optional<Model> fetched = fetcher.fetch(parentCoord);
      if (fetched.isEmpty()) {
        warnings.add("Parent " + parentCoord.toCoordinateString() + " could not be fetched");
        break;
      }
      chain.add(parentCoord);
      Model parent = fetched.get();
      models.add(parent);
      if (parent.getProperties() != null) {
        parent
            .getProperties()
            .forEach((k, v) -> properties.putIfAbsent(k.toString(), v.toString()));
      }
      cursor = parent;
    }

    if (cursor.getParent() != null) {
      warnings.add(
          "Parent chain exceeded "
              + MAX_PARENT_DEPTH
              + " levels at "
              + cursor.getParent().getGroupId()
              + ":"
              + cursor.getParent().getArtifactId()
              + ":"
              + cursor.getParent().getVersion()
              + " — deeper ancestors were not walked");
    }

    return new ParentContext(chain, models, properties);
  }

  private List<EffectiveDependency> classifyDependencies(
      Model root,
      Map<String, String> properties,
      Map<ManagementKey, ManagedEntry> managed,
      List<String> warnings) {
    return root.getDependencies().stream()
        .map(d -> classifySingleDependency(d, properties, managed, warnings))
        .flatMap(Optional::stream)
        .toList();
  }

  private Optional<EffectiveDependency> classifySingleDependency(
      Dependency d,
      Map<String, String> properties,
      Map<ManagementKey, ManagedEntry> managed,
      List<String> warnings) {
    ManagementKey key = ManagementKey.from(d);
    ManagedEntry mgmt = managed.get(key);
    String declared = d.getVersion();
    if (declared == null || declared.isBlank()) {
      return classifyManagedDependency(d, key, mgmt, warnings);
    }
    return classifyDeclaredDependency(d, key, mgmt, declared, properties, warnings);
  }

  private static Optional<EffectiveDependency> classifyManagedDependency(
      Dependency d, ManagementKey key, ManagedEntry mgmt, List<String> warnings) {
    if (mgmt == null) {
      warnings.add("No version for " + key.display() + " and no managed entry found — skipped");
      return Optional.empty();
    }
    return Optional.of(
        new EffectiveDependency(
            d.getGroupId(),
            d.getArtifactId(),
            mgmt.version(),
            Source.MANAGED,
            Optional.of(mgmt.managedBy()),
            mgmt.losingCandidates()));
  }

  private static Optional<EffectiveDependency> classifyDeclaredDependency(
      Dependency d,
      ManagementKey key,
      ManagedEntry mgmt,
      String declared,
      Map<String, String> properties,
      List<String> warnings) {
    String resolved = PropertyInterpolator.interpolate(declared, properties);
    // Heuristic: residual "${" means interpolation left a placeholder unresolved — no real Maven
    // version string contains it.
    if (resolved.isBlank() || resolved.contains("${")) {
      warnings.add("Could not resolve version for " + key.display() + " (raw: " + declared + ")");
      return Optional.empty();
    }
    Source source = mgmt == null ? Source.EXPLICIT : Source.EXPLICIT_OVERRIDE;
    Optional<MavenCoordinate> managedBy =
        mgmt == null ? Optional.empty() : Optional.of(mgmt.managedBy());
    // For EXPLICIT_OVERRIDE, surface BOTH the winning managed entry AND its losers so the caller
    // can see every candidate version their override is choosing against. For EXPLICIT (no managed
    // entry at all) conflicts is empty.
    List<ManagedAlternative> conflicts = mgmt == null ? List.of() : prependWinnerToConflicts(mgmt);
    return Optional.of(
        new EffectiveDependency(
            d.getGroupId(), d.getArtifactId(), resolved, source, managedBy, conflicts));
  }

  /**
   * Walks the root POM's {@code <dependencyManagement>} and each parent's, accumulating version
   * constraints keyed by "groupId:artifactId". Closest-ancestor (and the root POM itself) wins on
   * collision. BOM imports ({@code <scope>import</scope><type>pom</type>}) are resolved recursively
   * via {@link #importBom}.
   *
   * <p>Parents are consumed directly from {@code parents.models()} — no second fetch round is
   * needed; {@link #walkParents} already loaded each ancestor exactly once.
   */
  private Map<ManagementKey, ManagedEntry> buildManagedVersionMap(
      Model root, ParentContext parents, List<String> warnings, Set<String> visitedBoms) {
    Map<ManagementKey, ManagedEntry> managed = new HashMap<>();
    MavenCoordinate rootCoord = rootCoordinate(root);
    recordManagedFrom(root, rootCoord, parents.properties(), managed, warnings, visitedBoms);
    for (int i = 0; i < parents.models().size(); i++) {
      recordManagedFrom(
          parents.models().get(i),
          parents.chain().get(i),
          parents.properties(),
          managed,
          warnings,
          visitedBoms);
    }
    return managed;
  }

  private void recordManagedFrom(
      Model model,
      MavenCoordinate source,
      Map<String, String> properties,
      Map<ManagementKey, ManagedEntry> sink,
      List<String> warnings,
      Set<String> visitedBoms) {
    if (model.getDependencyManagement() == null) {
      return;
    }
    for (Dependency d : model.getDependencyManagement().getDependencies()) {
      processManagementEntry(d, source, properties, sink, warnings, visitedBoms);
    }
  }

  private void processManagementEntry(
      Dependency d,
      MavenCoordinate source,
      Map<String, String> properties,
      Map<ManagementKey, ManagedEntry> sink,
      List<String> warnings,
      Set<String> visitedBoms) {
    if ("import".equals(d.getScope()) && "pom".equals(d.getType())) {
      importBom(d, properties, sink, warnings, visitedBoms);
      return;
    }
    ManagementKey key = ManagementKey.from(d);
    String version = PropertyInterpolator.interpolate(d.getVersion(), properties);
    if (version == null || version.isBlank() || version.contains("${")) {
      warnings.add(
          "Managed version for "
              + key.display()
              + " from "
              + source.toCoordinateString()
              + " could not be resolved (raw: "
              + d.getVersion()
              + ")");
      return;
    }
    mergeManagedEntry(sink, key, new ManagedEntry(version, source, List.of()));
  }

  /**
   * Inserts {@code candidate} into {@code sink}, or — when the key already has a winner — appends
   * the candidate to the winner's {@code losingCandidates} list. Preserves closest-wins /
   * first-declared semantics (the existing entry's version + managedBy never change), and surfaces
   * every losing parent / BOM so a caller can detect multi-BOM disagreements.
   */
  private static void mergeManagedEntry(
      Map<ManagementKey, ManagedEntry> sink, ManagementKey key, ManagedEntry candidate) {
    sink.merge(key, candidate, EffectivePomResolver::resolveManagedCollision);
  }

  private static ManagedEntry resolveManagedCollision(
      ManagedEntry existing, ManagedEntry candidate) {
    if (existing.version().equals(candidate.version())
        && existing.managedBy().equals(candidate.managedBy())) {
      // Same source supplied the same value (e.g., a BOM walked via its parent chain handing the
      // same entry back). Not a meaningful conflict; keep the existing entry.
      return existing;
    }
    List<ManagedAlternative> losers =
        new ArrayList<>(
            existing.losingCandidates().size() + 1 + candidate.losingCandidates().size());
    losers.addAll(existing.losingCandidates());
    losers.add(new ManagedAlternative(candidate.version(), candidate.managedBy()));
    losers.addAll(candidate.losingCandidates());
    return new ManagedEntry(existing.version(), existing.managedBy(), List.copyOf(losers));
  }

  /**
   * Builds the {@code conflicts} list for an {@code EXPLICIT_OVERRIDE} dependency: the winning
   * managed candidate (what the BOM/parent would have supplied) followed by every losing candidate
   * — so the caller sees every version their explicit override is choosing against.
   */
  private static List<ManagedAlternative> prependWinnerToConflicts(ManagedEntry mgmt) {
    List<ManagedAlternative> all = new ArrayList<>(mgmt.losingCandidates().size() + 1);
    all.add(new ManagedAlternative(mgmt.version(), mgmt.managedBy()));
    all.addAll(mgmt.losingCandidates());
    return all;
  }

  /**
   * Returns the effective {@code <dependencyManagement>} map for an imported BOM, including entries
   * inherited from the BOM's own parent chain and its own properties merged on top of the importing
   * POM's properties (importer wins).
   */
  private Map<ManagementKey, ManagedEntry> effectiveManagementForImportedBom(
      Model bomModel,
      Map<String, String> importerProperties,
      List<String> warnings,
      Set<String> visitedBoms) {
    ParentContext bomParents = walkParents(bomModel, warnings);

    // Importer's user-defined bindings win on collision (so an importer can legitimately override
    // a BOM property like ${spring-ai.version}). EXCEPT for the built-in project.* coordinates —
    // those are context-bound to the local POM in Maven semantics: an entry like
    // <version>${project.version}</version> inside an imported BOM means THAT BOM's version, not
    // the importer's. Always let the BOM's project.* override.
    Map<String, String> mergedProperties = new HashMap<>(importerProperties);
    bomParents
        .properties()
        .forEach(
            (k, v) -> {
              if (k.startsWith("project.")) {
                mergedProperties.put(k, v);
              } else {
                mergedProperties.putIfAbsent(k, v);
              }
            });

    // Reuse the BOM's parent walk (chain + models) but with merged properties for interpolation.
    ParentContext mergedContext =
        new ParentContext(bomParents.chain(), bomParents.models(), mergedProperties);
    return buildManagedVersionMap(bomModel, mergedContext, warnings, visitedBoms);
  }

  /**
   * Resolves a {@code <scope>import</scope><type>pom</type>} entry by fetching the BOM, walking its
   * own parent chain (so inherited properties and managed entries are included), and merging all
   * effective managed entries into {@code sink} with closest-wins semantics.
   *
   * <p>Failed BOM fetches emit a warning — BOMs are not part of the parent chain walked by {@link
   * #walkParents}, so there is no prior warning to deduplicate. Callers receive a best-effort
   * result; an unresolvable BOM simply means its managed entries are absent from the merged map,
   * which may surface as unresolved dependency-version warnings downstream in {@link
   * #classifyDependencies}.
   *
   * <p>Cycle-safe: a {@code visitedBoms} set tracks BOM coordinates seen during this resolution. If
   * a BOM is encountered again — either because the same BOM is imported via two paths, or because
   * BOMs reference each other cyclically — its contents are skipped on re-entry. The first
   * encounter wins per Maven semantics, and the recursion can no longer overflow on pathological
   * input.
   */
  private void importBom(
      Dependency bomDep,
      Map<String, String> properties,
      Map<ManagementKey, ManagedEntry> sink,
      List<String> warnings,
      Set<String> visitedBoms) {
    String groupId = PropertyInterpolator.interpolate(bomDep.getGroupId(), properties);
    String artifactId = PropertyInterpolator.interpolate(bomDep.getArtifactId(), properties);
    String version = PropertyInterpolator.interpolate(bomDep.getVersion(), properties);
    if (groupId == null || artifactId == null || version == null) {
      return;
    }
    MavenCoordinate bomCoord = MavenCoordinate.of(groupId, artifactId, version);
    if (!visitedBoms.add(bomCoord.toCoordinateString())) {
      // Already processed this BOM earlier in the resolution (either via another path or
      // because of a cyclic import). First encounter wins; safely short-circuit.
      return;
    }
    Optional<Model> bom = fetcher.fetch(bomCoord);
    if (bom.isEmpty()) {
      warnings.add("Imported BOM " + bomCoord.toCoordinateString() + " could not be fetched");
      return;
    }
    Map<ManagementKey, ManagedEntry> effective =
        effectiveManagementForImportedBom(bom.get(), properties, warnings, visitedBoms);
    effective.forEach((k, v) -> mergeManagedEntry(sink, k, v));
  }

  private static MavenCoordinate rootCoordinate(Model root) {
    String groupId = root.getGroupId();
    if (groupId == null && root.getParent() != null) {
      groupId = root.getParent().getGroupId();
    }
    String version = root.getVersion();
    if (version == null && root.getParent() != null) {
      version = root.getParent().getVersion();
    }
    return MavenCoordinate.of(groupId, root.getArtifactId(), version);
  }

  /**
   * Seeds Maven's well-known {@code project.*} properties into the property map so that dependency
   * versions like {@code ${project.version}} or {@code ${project.parent.version}} interpolate
   * against the actual root POM coordinates.
   *
   * <p>Six bindings are produced where applicable: {@code project.groupId}, {@code
   * project.artifactId}, {@code project.version}, and the {@code project.parent.*} trio when the
   * root POM declares a {@code <parent>} block.
   */
  private static void seedProjectProperties(Model root, Map<String, String> sink) {
    MavenCoordinate rootCoord = rootCoordinate(root);
    if (rootCoord.groupId() != null) {
      sink.put("project.groupId", rootCoord.groupId());
    }
    if (rootCoord.artifactId() != null) {
      sink.put("project.artifactId", rootCoord.artifactId());
    }
    if (rootCoord.version() != null) {
      sink.put("project.version", rootCoord.version());
    }
    var p = root.getParent();
    if (p != null) {
      if (p.getGroupId() != null) {
        sink.put("project.parent.groupId", p.getGroupId());
      }
      if (p.getArtifactId() != null) {
        sink.put("project.parent.artifactId", p.getArtifactId());
      }
      if (p.getVersion() != null) {
        sink.put("project.parent.version", p.getVersion());
      }
    }
  }

  /**
   * Captured state from a single walk of the root POM's parent chain: the resolved parent
   * coordinates (closest-first), the corresponding {@link Model}s (matched index-for-index with
   * {@code chain}), and the accumulated property map (root's own properties seeded first, then each
   * ancestor's properties merged with {@code putIfAbsent}).
   *
   * <p>Used to avoid re-fetching the parent chain when {@code buildManagedVersionMap} later needs
   * to walk it for {@code <dependencyManagement>} entries.
   */
  private record ParentContext(
      List<MavenCoordinate> chain, List<Model> models, Map<String, String> properties) {}

  /**
   * A {@code <dependencyManagement>} entry that has been resolved, with its source POM and any
   * losing candidates from BOMs / parents that would have supplied a different version but lost to
   * the closer-ancestor / first-declared semantics.
   */
  private record ManagedEntry(
      String version, MavenCoordinate managedBy, List<ManagedAlternative> losingCandidates) {}

  /**
   * Composite key for {@code <dependencyManagement>} entries. Per Maven semantics, the same {@code
   * groupId:artifactId} can be managed at different versions for different {@code <type>}/{@code
   * <classifier>} combinations (e.g., {@code jar} vs {@code test-jar}). The string {@code
   * "groupId:artifactId"} alone is too coarse.
   */
  private record ManagementKey(String groupId, String artifactId, String type, String classifier) {

    /** Builds the key from a {@link Dependency}, normalising default type and classifier. */
    static ManagementKey from(Dependency d) {
      String type = (d.getType() == null || d.getType().isBlank()) ? "jar" : d.getType();
      String classifier = d.getClassifier() == null ? "" : d.getClassifier();
      return new ManagementKey(d.getGroupId(), d.getArtifactId(), type, classifier);
    }

    /** Short {@code "groupId:artifactId"} display form for warnings. */
    String display() {
      return groupId + ":" + artifactId;
    }
  }
}
