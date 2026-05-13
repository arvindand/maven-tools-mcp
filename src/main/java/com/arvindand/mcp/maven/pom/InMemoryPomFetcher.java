package com.arvindand.mcp.maven.pom;

import com.arvindand.mcp.maven.model.MavenCoordinate;
import java.io.StringReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link PomFetcher} backed by a bundle of POM XML strings supplied at construction. Indexes each
 * POM by its self-declared {@code groupId:artifactId:version}, falling back to the {@code <parent>}
 * block for inherited groupId / version. Unparseable POMs are skipped (a debug log is emitted) —
 * partial bundles still serve hits for the POMs that parsed cleanly.
 *
 * <p>If the bundle contains multiple POMs with the same {@code groupId:artifactId:version}, the
 * last occurrence wins.
 *
 * <p>Used as the local-side of a {@link CompositePomFetcher} so callers can sideload unreleased
 * monorepo POMs alongside Maven Central as the fallback.
 */
public final class InMemoryPomFetcher implements PomFetcher {

  private static final Logger logger = LoggerFactory.getLogger(InMemoryPomFetcher.class);
  private final Map<String, Model> byGav;

  private InMemoryPomFetcher(Map<String, Model> byGav) {
    this.byGav = byGav;
  }

  public static InMemoryPomFetcher fromXml(List<String> pomXmls) {
    Objects.requireNonNull(pomXmls, "pomXmls must not be null");
    Map<String, Model> byGav = new HashMap<>();
    for (String xml : pomXmls) {
      Objects.requireNonNull(xml, "pomXmls must not contain null elements");
      try {
        Model model = new MavenXpp3Reader().read(new StringReader(xml));
        String key = gavKey(model);
        if (key != null) {
          byGav.put(key, model);
        }
      } catch (Exception ex) {
        logger.debug("Skipping unparseable sideloaded POM: {}", ex.getMessage());
      }
    }
    return new InMemoryPomFetcher(Map.copyOf(byGav));
  }

  @Override
  public Optional<Model> fetch(MavenCoordinate coordinate) {
    return Optional.ofNullable(byGav.get(coordinate.toCoordinateString()));
  }

  private static String gavKey(Model model) {
    String groupId = model.getGroupId();
    String version = model.getVersion();
    if (model.getParent() != null) {
      if (groupId == null) {
        groupId = model.getParent().getGroupId();
      }
      if (version == null) {
        version = model.getParent().getVersion();
      }
    }
    String artifactId = model.getArtifactId();
    if (groupId == null || artifactId == null || version == null) {
      return null;
    }
    return groupId + ":" + artifactId + ":" + version;
  }
}
