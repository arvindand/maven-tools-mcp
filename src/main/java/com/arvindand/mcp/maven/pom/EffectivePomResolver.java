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
 * Resolves the effective version of each declared dependency in a POM. See
 * {@code package-info.java} for design notes and attribution.
 *
 * <p>This MVP (Task 7) handles a single POM with literal version declarations only. Parent chain
 * walking, property interpolation, dependencyManagement, and BOM imports arrive in subsequent
 * commits (Tasks 8–10).
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
    Model root;
    try {
      root = new MavenXpp3Reader().read(new StringReader(pomXml));
    } catch (XmlPullParserException | IOException ex) {
      throw new IllegalArgumentException("Input is not a valid POM: " + ex.getMessage(), ex);
    }

    List<MavenCoordinate> parentChain = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    Map<String, String> properties = new HashMap<>();
    if (root.getProperties() != null) {
      root.getProperties().forEach((k, v) -> properties.put(k.toString(), v.toString()));
    }

    Model cursor = root;
    int safety = 0;
    while (cursor.getParent() != null && safety++ < 10) {
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
            .forEach(
                (k, v) -> properties.putIfAbsent(k.toString(), v.toString()));
      }
      cursor = parent;
    }

    List<EffectiveDependency> deps = new ArrayList<>();
    for (Dependency d : root.getDependencies()) {
      if (d.getVersion() == null || d.getVersion().isBlank()) {
        continue;
      }
      String resolved = PropertyInterpolator.interpolate(d.getVersion(), properties);
      if (resolved == null || resolved.isBlank() || resolved.contains("${")) {
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
    return new EffectivePomResult(deps, parentChain, warnings);
  }
}
