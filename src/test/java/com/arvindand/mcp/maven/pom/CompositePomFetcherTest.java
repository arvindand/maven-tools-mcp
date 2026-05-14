package com.arvindand.mcp.maven.pom;

import static org.assertj.core.api.Assertions.assertThat;

import com.arvindand.mcp.maven.model.MavenCoordinate;
import java.util.List;
import java.util.Optional;
import org.apache.maven.model.Model;
import org.junit.jupiter.api.Test;

class CompositePomFetcherTest {

  @Test
  void returnsFirstNonEmptyHit() {
    Model modelA = new Model();
    Model modelB = new Model();
    PomFetcher empty = coord -> Optional.empty();
    PomFetcher hitsA = coord -> Optional.of(modelA);
    PomFetcher hitsB = coord -> Optional.of(modelB);

    var composite = new CompositePomFetcher(List.of(empty, hitsA, hitsB));

    assertThat(composite.fetch(MavenCoordinate.of("g", "a", "1"))).contains(modelA);
  }

  @Test
  void returnsEmptyWhenAllFetchersReturnEmpty() {
    PomFetcher empty = coord -> Optional.empty();
    var composite = new CompositePomFetcher(List.of(empty, empty));

    assertThat(composite.fetch(MavenCoordinate.of("g", "a", "1"))).isEmpty();
  }

  @Test
  void preservesFetcherOrder() {
    Model first = new Model();
    Model second = new Model();
    PomFetcher firstFetcher = coord -> Optional.of(first);
    PomFetcher secondFetcher = coord -> Optional.of(second);

    var composite = new CompositePomFetcher(List.of(firstFetcher, secondFetcher));

    assertThat(composite.fetch(MavenCoordinate.of("g", "a", "1"))).contains(first);
  }
}
