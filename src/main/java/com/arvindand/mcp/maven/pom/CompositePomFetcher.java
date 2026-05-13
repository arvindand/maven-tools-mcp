package com.arvindand.mcp.maven.pom;

import com.arvindand.mcp.maven.model.MavenCoordinate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.apache.maven.model.Model;

/**
 * Chains multiple {@link PomFetcher}s. Returns the first non-empty result for a given coordinate.
 * Typical use: {@code new CompositePomFetcher(List.of(localBundleFetcher, mavenCentralFetcher))} —
 * local POMs win, Maven Central is the fallback.
 */
public final class CompositePomFetcher implements PomFetcher {

  private final List<PomFetcher> fetchers;

  public CompositePomFetcher(List<PomFetcher> fetchers) {
    Objects.requireNonNull(fetchers, "fetchers must not be null");
    fetchers.forEach(f -> Objects.requireNonNull(f, "fetchers must not contain null elements"));
    this.fetchers = List.copyOf(fetchers);
  }

  @Override
  public Optional<Model> fetch(MavenCoordinate coordinate) {
    for (PomFetcher fetcher : fetchers) {
      Optional<Model> hit = fetcher.fetch(coordinate);
      if (hit.isPresent()) {
        return hit;
      }
    }
    return Optional.empty();
  }
}
