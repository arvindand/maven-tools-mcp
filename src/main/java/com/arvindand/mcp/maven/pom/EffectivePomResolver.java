package com.arvindand.mcp.maven.pom;

import static com.arvindand.mcp.maven.config.CacheConstants.MAVEN_EFFECTIVE_POM;

import com.arvindand.mcp.maven.model.MavenCoordinate;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.InputLocation;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.building.ModelBuildingResult;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * Resolves the effective version of each declared dependency in a POM.
 *
 * <p>Apache Maven Model Builder resolves inheritance, profiles, interpolation and dependency
 * management. This adapter classifies the root declarations and retains their editable targets and
 * management provenance. Model fetching is isolated from local files and POM-supplied repositories.
 *
 * <p>See {@code package-info.java} for design notes and attribution.
 *
 * @author Arvind Menon
 * @since 3.0.0
 */
@Service
public class EffectivePomResolver {

  private static final String SCOPE_IMPORT = "import";
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
  @Cacheable(value = MAVEN_EFFECTIVE_POM, key = "#pomXml", unless = "!#result.warnings().isEmpty()")
  public EffectivePomResult resolve(String pomXml) {
    Model root = parsePom(pomXml);
    List<String> warnings = new ArrayList<>();
    MavenModelSession session = new MavenModelSession(fetcher, warnings);
    ModelBuildingResult built = session.build(pomXml);
    Model effective = built.getEffectiveModel();
    Map<String, String> properties = new HashMap<>();
    effective
        .getProperties()
        .forEach((key, value) -> properties.put(key.toString(), value.toString()));
    seedProjectProperties(effective, properties);
    Map<ManagementKey, ManagedEntry> managed = effectiveManagement(effective, session);
    Model declarations = root.clone();
    built
        .getActivePomProfiles(built.getModelIds().getFirst())
        .forEach(profile -> declarations.getDependencies().addAll(profile.getDependencies()));
    List<EffectiveDependency> deps =
        classifyDependencies(declarations, effective, properties, managed, warnings).stream()
            .map(dependency -> withEditability(dependency, root))
            .distinct()
            .toList();
    List<MavenCoordinate> parents =
        built.getModelIds().stream()
            .skip(1)
            .map(built::getRawModel)
            .filter(Objects::nonNull)
            .filter(model -> model.getArtifactId() != null)
            .map(EffectivePomResolver::rootCoordinate)
            .toList();
    return new EffectivePomResult(
        deps,
        parents,
        extractRootBomImports(root, properties),
        extractRootManagedDeclarations(root, properties),
        extractRootPluginDependencyDeclarations(root, properties),
        warnings);
  }

  private static EffectiveDependency withEditability(EffectiveDependency dependency, Model root) {
    List<Dependency> matches =
        root.getDependencies().stream()
            .filter(
                raw ->
                    dependency.groupId().equals(raw.getGroupId())
                        && dependency.artifactId().equals(raw.getArtifactId()))
            .toList();
    boolean editable =
        matches.size() == 1
            && hasEditableVersion(matches.getFirst(), root)
            && "jar".equals(matches.getFirst().getType())
            && matches.getFirst().getClassifier() == null;
    return new EffectiveDependency(
        dependency.groupId(),
        dependency.artifactId(),
        dependency.effectiveVersion(),
        dependency.source(),
        dependency.managedBy(),
        dependency.conflicts(),
        editable);
  }

  private static boolean hasEditableVersion(Dependency declaration, Model root) {
    String version = declaration.getVersion();
    if (version == null) return false;
    if (!version.contains("${")) return true;
    Matcher reference = EXACT_PROPERTY_REFERENCE.matcher(version);
    return reference.matches()
        && root.getProperties().containsKey(reference.group(1))
        && !root.getProperties().getProperty(reference.group(1)).contains("${");
  }

  private static Map<ManagementKey, ManagedEntry> effectiveManagement(
      Model effective, MavenModelSession session) {
    Map<ManagementKey, ManagedEntry> managed = new HashMap<>();
    if (effective.getDependencyManagement() == null) return managed;
    for (Dependency dependency : effective.getDependencyManagement().getDependencies()) {
      if (hasUnresolvedValue(dependency.getVersion())) continue;
      MavenCoordinate source = managementSource(dependency, effective);
      List<ManagedAlternative> alternatives =
          session.candidates(effective).stream()
              .filter(
                  candidate -> ManagementKey.from(candidate).equals(ManagementKey.from(dependency)))
              .filter(candidate -> !hasUnresolvedValue(candidate.getVersion()))
              .map(
                  candidate ->
                      new ManagedAlternative(
                          candidate.getVersion(), managementSource(candidate, effective)))
              .filter(
                  candidate ->
                      !candidate.version().equals(dependency.getVersion())
                          || !candidate.managedBy().equals(source))
              .distinct()
              .toList();
      managed.put(
          ManagementKey.from(dependency),
          new ManagedEntry(dependency.getVersion(), source, alternatives));
    }
    return managed;
  }

  private static MavenCoordinate managementSource(Dependency dependency, Model fallback) {
    InputLocation location = dependency.getLocation("version");
    if (location != null
        && location.getSource() != null
        && location.getSource().getModelId() != null) {
      String[] parts = location.getSource().getModelId().split(":");
      if (parts.length == 3) return MavenCoordinate.of(parts[0], parts[1], parts[2]);
      if (parts.length == 4) return MavenCoordinate.of(parts[0], parts[1], parts[3]);
    }
    return rootCoordinate(fallback);
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
        .filter(d -> !(SCOPE_IMPORT.equals(d.getScope()) && "pom".equals(d.getType())))
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
        .filter(d -> SCOPE_IMPORT.equals(d.getScope()) && "pom".equals(d.getType()))
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
    PomInputLimits.checkBundle(sideloadedPoms);
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
    PomInputLimits.checkBundle(poms);
    if (poms.isEmpty()) {
      return List.of();
    }
    PomFetcher composite =
        new CompositePomFetcher(List.of(InMemoryPomFetcher.fromXml(poms), this.fetcher));
    EffectivePomResolver bundleResolver = new EffectivePomResolver(composite);
    return poms.stream().map(bundleResolver::resolve).toList();
  }

  private Model parsePom(String pomXml) {
    PomInputLimits.check(pomXml);
    try {
      return new MavenXpp3Reader().read(new StringReader(pomXml));
    } catch (XmlPullParserException | IOException ex) {
      throw new IllegalArgumentException("Input is not a valid POM: " + ex.getMessage(), ex);
    }
  }

  private List<EffectiveDependency> classifyDependencies(
      Model root,
      Model effective,
      Map<String, String> properties,
      Map<ManagementKey, ManagedEntry> managed,
      List<String> warnings) {
    Map<ManagementKey, String> versions = new HashMap<>();
    effective.getDependencies().stream()
        .filter(d -> d.getVersion() != null)
        .forEach(d -> versions.put(ManagementKey.from(d), d.getVersion()));
    return root.getDependencies().stream()
        .map(d -> classifySingleDependency(d, properties, managed, versions, warnings))
        .flatMap(Optional::stream)
        .toList();
  }

  private Optional<EffectiveDependency> classifySingleDependency(
      Dependency d,
      Map<String, String> properties,
      Map<ManagementKey, ManagedEntry> managed,
      Map<ManagementKey, String> versions,
      List<String> warnings) {
    d = d.clone();
    d.setGroupId(PropertyInterpolator.interpolate(d.getGroupId(), properties));
    d.setArtifactId(PropertyInterpolator.interpolate(d.getArtifactId(), properties));
    d.setType(PropertyInterpolator.interpolate(d.getType(), properties));
    d.setClassifier(PropertyInterpolator.interpolate(d.getClassifier(), properties));
    if (hasUnresolvedValue(d.getGroupId()) || hasUnresolvedValue(d.getArtifactId())) {
      warnings.add("Could not resolve dependency coordinates");
      return Optional.empty();
    }
    ManagementKey key = ManagementKey.from(d);
    ManagedEntry mgmt = managed.get(key);
    String declared = d.getVersion();
    if (declared == null || declared.isBlank()) {
      return classifyManagedDependency(d, key, mgmt, warnings);
    }
    return classifyDeclaredDependency(
        d, key, mgmt, versions.getOrDefault(key, declared), properties, warnings);
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
    if (resolved == null || resolved.isBlank() || resolved.contains("${")) {
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

  private static List<ManagedAlternative> prependWinnerToConflicts(ManagedEntry mgmt) {
    List<ManagedAlternative> all = new ArrayList<>(mgmt.losingCandidates().size() + 1);
    all.add(new ManagedAlternative(mgmt.version(), mgmt.managedBy()));
    all.addAll(mgmt.losingCandidates());
    return all;
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
    Parent p = root.getParent();
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
