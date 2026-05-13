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
 * blocks closest-ancestor-wins, and interpolates {@code ${name}} placeholders in dependency
 * versions via {@link PropertyInterpolator}. Unresolved placeholders and unreachable parents
 * surface as warnings on {@link EffectivePomResult}; resolution still produces a result rather than
 * aborting.
 *
 * <p>{@code <dependencyManagement>} merge and {@code <scope>import</scope>} BOM resolution arrive
 * in Tasks 9 and 10.
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

    List<EffectiveDependency> deps = classifyDependencies(root, properties, warnings);
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
      Model root, Map<String, String> properties, List<String> warnings) {
    List<EffectiveDependency> deps = new ArrayList<>();
    for (Dependency d : root.getDependencies()) {
      if (d.getVersion() == null || d.getVersion().isBlank()) {
        continue;
      }
      String resolved = PropertyInterpolator.interpolate(d.getVersion(), properties);
      // Heuristic: residual "${" means interpolation left a placeholder unresolved —
      // no real Maven version string contains it.
      if (resolved.isBlank() || resolved.contains("${")) {
        warnings.add(
            "Could not resolve version for "
                + d.getGroupId()
                + ":"
                + d.getArtifactId()
                + " (raw: "
                + d.getVersion()
                + ")");
        continue;
      }
      deps.add(
          new EffectiveDependency(
              MavenCoordinate.of(d.getGroupId(), d.getArtifactId(), null),
              resolved,
              Source.EXPLICIT,
              Optional.empty()));
    }
    return deps;
  }
}
