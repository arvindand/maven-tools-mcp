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

    List<MavenCoordinate> parentChain = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    Map<String, String> properties = buildPropertyMap(root, parentChain, warnings);
    Map<String, ManagedEntry> managed = buildManagedVersionMap(root, parentChain, properties);

    List<EffectiveDependency> deps = classifyDependencies(root, properties, managed, warnings);
    return new EffectivePomResult(deps, parentChain, warnings);
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
   * Walks the parent chain (up to 10 levels) starting from {@code root}, accumulating properties
   * closest-ancestor-wins. Appends each successfully fetched parent coordinate to {@code
   * parentChain} (closest-first). Records a warning and stops walking on the first unreachable
   * parent.
   */
  private Map<String, String> buildPropertyMap(
      Model root, List<MavenCoordinate> parentChain, List<String> warnings) {
    Map<String, String> properties = new HashMap<>();
    seedProjectProperties(root, properties);
    if (root.getProperties() != null) {
      root.getProperties().forEach((k, v) -> properties.put(k.toString(), v.toString()));
    }
    Model cursor = root;
    for (int depth = 0; depth < 10 && cursor.getParent() != null; depth++) {
      var p = cursor.getParent();
      var parentCoord = MavenCoordinate.of(p.getGroupId(), p.getArtifactId(), p.getVersion());
      Optional<Model> fetched = fetcher.fetch(parentCoord);
      if (fetched.isEmpty()) {
        warnings.add("Parent " + parentCoord.toCoordinateString() + " could not be fetched");
        break;
      }
      parentChain.add(parentCoord);
      Model parent = fetched.get();
      if (parent.getProperties() != null) {
        parent
            .getProperties()
            .forEach((k, v) -> properties.putIfAbsent(k.toString(), v.toString()));
      }
      cursor = parent;
    }
    return properties;
  }

  private List<EffectiveDependency> classifyDependencies(
      Model root,
      Map<String, String> properties,
      Map<String, ManagedEntry> managed,
      List<String> warnings) {
    List<EffectiveDependency> deps = new ArrayList<>();
    for (Dependency d : root.getDependencies()) {
      String key = d.getGroupId() + ":" + d.getArtifactId();
      ManagedEntry mgmt = managed.get(key);
      String declared = d.getVersion();
      if (declared == null || declared.isBlank()) {
        if (mgmt == null) {
          warnings.add("No version for " + key + " and no managed entry found — skipped");
          continue;
        }
        deps.add(
            new EffectiveDependency(
                MavenCoordinate.of(d.getGroupId(), d.getArtifactId(), null),
                mgmt.version(),
                Source.MANAGED,
                Optional.of(mgmt.managedBy())));
      } else {
        String resolved = PropertyInterpolator.interpolate(declared, properties);
        // Heuristic: residual "${" means interpolation left a placeholder unresolved —
        // no real Maven version string contains it.
        if (resolved.isBlank() || resolved.contains("${")) {
          warnings.add("Could not resolve version for " + key + " (raw: " + declared + ")");
          continue;
        }
        Source source = mgmt == null ? Source.EXPLICIT : Source.EXPLICIT_OVERRIDE;
        Optional<MavenCoordinate> managedBy =
            mgmt == null ? Optional.empty() : Optional.of(mgmt.managedBy());
        deps.add(
            new EffectiveDependency(
                MavenCoordinate.of(d.getGroupId(), d.getArtifactId(), null),
                resolved,
                source,
                managedBy));
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
   * <p>Managed-entry resolution is silent-drop by design: {@link #buildPropertyMap} already warned
   * about any unreachable parent earlier in the same {@link #resolve} call, so emitting a second
   * warning for the same parent here would be noise.
   */
  private Map<String, ManagedEntry> buildManagedVersionMap(
      Model root, List<MavenCoordinate> parentChain, Map<String, String> properties) {
    Map<String, ManagedEntry> managed = new HashMap<>();
    MavenCoordinate rootCoord = rootCoordinate(root);
    recordManagedFrom(root, rootCoord, properties, managed);
    for (MavenCoordinate parentCoord : parentChain) {
      // A failed fetch here would mean buildPropertyMap already warned about this parent —
      // no second warning is emitted; we simply skip its managed entries.
      fetcher
          .fetch(parentCoord)
          .ifPresent(parent -> recordManagedFrom(parent, parentCoord, properties, managed));
    }
    return managed;
  }

  private void recordManagedFrom(
      Model model,
      MavenCoordinate source,
      Map<String, String> properties,
      Map<String, ManagedEntry> sink) {
    if (model.getDependencyManagement() == null) {
      return;
    }
    for (Dependency d : model.getDependencyManagement().getDependencies()) {
      if ("import".equals(d.getScope()) && "pom".equals(d.getType())) {
        importBom(d, properties, sink);
        continue;
      }
      String key = d.getGroupId() + ":" + d.getArtifactId();
      if (sink.containsKey(key)) {
        // Closer ancestor (or the root POM itself) already won.
        continue;
      }
      String version = PropertyInterpolator.interpolate(d.getVersion(), properties);
      if (version == null || version.isBlank() || version.contains("${")) {
        continue;
      }
      sink.put(key, new ManagedEntry(version, source));
    }
  }

  /**
   * Resolves a {@code <scope>import</scope><type>pom</type>} entry by fetching the BOM, merging its
   * properties (the importing POM's properties take precedence), and recursing into {@link
   * #recordManagedFrom} so the BOM's managed entries are accumulated. Failed BOM fetches are
   * silent-drop — same reasoning as {@link #buildManagedVersionMap}: warnings about unreachable
   * POMs are emitted once by {@code buildPropertyMap}.
   */
  private void importBom(
      Dependency bomDep, Map<String, String> properties, Map<String, ManagedEntry> sink) {
    String groupId = PropertyInterpolator.interpolate(bomDep.getGroupId(), properties);
    String artifactId = PropertyInterpolator.interpolate(bomDep.getArtifactId(), properties);
    String version = PropertyInterpolator.interpolate(bomDep.getVersion(), properties);
    if (groupId == null || artifactId == null || version == null) {
      return;
    }
    MavenCoordinate bomCoord = MavenCoordinate.of(groupId, artifactId, version);
    Optional<Model> bom = fetcher.fetch(bomCoord);
    if (bom.isEmpty()) {
      return;
    }
    Model bomModel = bom.get();
    Map<String, String> mergedProps = new HashMap<>(properties);
    if (bomModel.getProperties() != null) {
      bomModel
          .getProperties()
          .forEach((k, v) -> mergedProps.putIfAbsent(k.toString(), v.toString()));
    }
    recordManagedFrom(bomModel, bomCoord, mergedProps, sink);
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

  /** A {@code <dependencyManagement>} entry that has been resolved, with its source POM. */
  private record ManagedEntry(String version, MavenCoordinate managedBy) {}
}
