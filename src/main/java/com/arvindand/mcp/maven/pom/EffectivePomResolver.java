package com.arvindand.mcp.maven.pom;

import com.arvindand.mcp.maven.model.MavenCoordinate;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
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
 *
 * <p>See {@code package-info.java} for design notes and attribution.
 */
@Service
public class EffectivePomResolver {

  private static final int MAX_PARENT_DEPTH = 10;

  private final PomFetcher fetcher;

  public EffectivePomResolver(PomFetcher fetcher) {
    this.fetcher = fetcher;
  }

  /**
   * Resolves the effective POM for the given POM XML string.
   *
   * @param pomXml the raw POM XML content
   * @return the resolved effective POM result
   * @throws IllegalArgumentException if the input is not valid POM XML
   */
  public EffectivePomResult resolve(String pomXml) {
    Model root = parsePom(pomXml);
    List<String> warnings = new ArrayList<>();
    ParentContext parents = walkParents(root, warnings);
    Map<ManagementKey, ManagedEntry> managed = buildManagedVersionMap(root, parents, warnings);
    List<EffectiveDependency> deps =
        classifyDependencies(root, parents.properties(), managed, warnings);
    return new EffectivePomResult(deps, parents.chain(), warnings);
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
    List<EffectiveDependency> deps = new ArrayList<>();
    for (Dependency d : root.getDependencies()) {
      ManagementKey key = ManagementKey.from(d);
      ManagedEntry mgmt = managed.get(key);
      String declared = d.getVersion();
      if (declared == null || declared.isBlank()) {
        if (mgmt == null) {
          warnings.add("No version for " + key.display() + " and no managed entry found — skipped");
          continue;
        }
        deps.add(
            new EffectiveDependency(
                d.getGroupId(),
                d.getArtifactId(),
                mgmt.version(),
                Source.MANAGED,
                Optional.of(mgmt.managedBy())));
      } else {
        String resolved = PropertyInterpolator.interpolate(declared, properties);
        // Heuristic: residual "${" means interpolation left a placeholder unresolved —
        // no real Maven version string contains it.
        if (resolved.isBlank() || resolved.contains("${")) {
          warnings.add(
              "Could not resolve version for " + key.display() + " (raw: " + declared + ")");
          continue;
        }
        Source source = mgmt == null ? Source.EXPLICIT : Source.EXPLICIT_OVERRIDE;
        Optional<MavenCoordinate> managedBy =
            mgmt == null ? Optional.empty() : Optional.of(mgmt.managedBy());
        deps.add(
            new EffectiveDependency(
                d.getGroupId(), d.getArtifactId(), resolved, source, managedBy));
      }
    }
    return deps;
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
      Model root, ParentContext parents, List<String> warnings) {
    Map<ManagementKey, ManagedEntry> managed = new HashMap<>();
    MavenCoordinate rootCoord = rootCoordinate(root);
    recordManagedFrom(root, rootCoord, parents.properties(), managed, warnings);
    for (int i = 0; i < parents.models().size(); i++) {
      recordManagedFrom(
          parents.models().get(i), parents.chain().get(i), parents.properties(), managed, warnings);
    }
    return managed;
  }

  private void recordManagedFrom(
      Model model,
      MavenCoordinate source,
      Map<String, String> properties,
      Map<ManagementKey, ManagedEntry> sink,
      List<String> warnings) {
    if (model.getDependencyManagement() == null) {
      return;
    }
    for (Dependency d : model.getDependencyManagement().getDependencies()) {
      if ("import".equals(d.getScope()) && "pom".equals(d.getType())) {
        importBom(d, properties, sink, warnings);
        continue;
      }
      ManagementKey key = ManagementKey.from(d);
      if (sink.containsKey(key)) {
        // Closer ancestor (or the root POM itself) already won.
        continue;
      }
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
        continue;
      }
      sink.put(key, new ManagedEntry(version, source));
    }
  }

  /**
   * Returns the effective {@code <dependencyManagement>} map for an imported BOM, including entries
   * inherited from the BOM's own parent chain and its own properties merged on top of the importing
   * POM's properties (importer wins).
   */
  private Map<ManagementKey, ManagedEntry> effectiveManagementForImportedBom(
      Model bomModel, Map<String, String> importerProperties, List<String> warnings) {
    ParentContext bomParents = walkParents(bomModel, warnings);

    // Importer's bindings win; BOM's own properties (including inherited) fill gaps.
    Map<String, String> mergedProperties = new HashMap<>(importerProperties);
    bomParents.properties().forEach(mergedProperties::putIfAbsent);

    // Reuse the BOM's parent walk (chain + models) but with merged properties for interpolation.
    ParentContext mergedContext =
        new ParentContext(bomParents.chain(), bomParents.models(), mergedProperties);
    return buildManagedVersionMap(bomModel, mergedContext, warnings);
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
   */
  private void importBom(
      Dependency bomDep,
      Map<String, String> properties,
      Map<ManagementKey, ManagedEntry> sink,
      List<String> warnings) {
    String groupId = PropertyInterpolator.interpolate(bomDep.getGroupId(), properties);
    String artifactId = PropertyInterpolator.interpolate(bomDep.getArtifactId(), properties);
    String version = PropertyInterpolator.interpolate(bomDep.getVersion(), properties);
    if (groupId == null || artifactId == null || version == null) {
      return;
    }
    MavenCoordinate bomCoord = MavenCoordinate.of(groupId, artifactId, version);
    Optional<Model> bom = fetcher.fetch(bomCoord);
    if (bom.isEmpty()) {
      warnings.add("Imported BOM " + bomCoord.toCoordinateString() + " could not be fetched");
      return;
    }
    // TODO: cyclic BOM imports (A imports B imports A) are not detected; recursion is
    // bounded in practice by Maven Central's rejection of such cycles, but a visited-set
    // threaded through importBom would harden against pathological / malicious input.
    Map<ManagementKey, ManagedEntry> effective =
        effectiveManagementForImportedBom(bom.get(), properties, warnings);
    effective.forEach(sink::putIfAbsent);
  }

  private static MavenCoordinate rootCoordinate(Model root) {
    String groupId =
        root.getGroupId() != null
            ? root.getGroupId()
            : (root.getParent() != null ? root.getParent().getGroupId() : null);
    String version =
        root.getVersion() != null
            ? root.getVersion()
            : (root.getParent() != null ? root.getParent().getVersion() : null);
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

  /** A {@code <dependencyManagement>} entry that has been resolved, with its source POM. */
  private record ManagedEntry(String version, MavenCoordinate managedBy) {}

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
