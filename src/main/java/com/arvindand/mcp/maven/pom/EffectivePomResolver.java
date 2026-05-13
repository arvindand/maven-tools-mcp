package com.arvindand.mcp.maven.pom;

import com.arvindand.mcp.maven.model.MavenCoordinate;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * <p>BOM imports ({@code <scope>import</scope><type>pom</type>}) are the only remaining gap; they
 * arrive in Task 10.
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
   * collision. BOM imports ({@code <scope>import</scope><type>pom</type>}) are skipped here; they
   * arrive in Task 10.
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
      // BOM imports handled in Task 10
      if ("import".equals(d.getScope()) && "pom".equals(d.getType())) {
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
