package com.arvindand.mcp.maven.pom;

import static org.assertj.core.api.Assertions.assertThat;

import com.arvindand.mcp.maven.model.MavenCoordinate;
import java.util.List;
import org.junit.jupiter.api.Test;

class InMemoryPomFetcherTest {

  @Test
  void resolvesPomFromSelfDeclaredGav() {
    String pom =
        """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <groupId>com.example</groupId>
          <artifactId>lib</artifactId>
          <version>1.0.0</version>
        </project>
        """;

    var fetcher = InMemoryPomFetcher.fromXml(List.of(pom));

    assertThat(fetcher.fetch(MavenCoordinate.of("com.example", "lib", "1.0.0"))).isPresent();
    assertThat(fetcher.fetch(MavenCoordinate.of("com.example", "lib", "2.0.0"))).isEmpty();
  }

  @Test
  void inheritsGroupIdAndVersionFromParentBlockWhenMissing() {
    String pom =
        """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <parent>
            <groupId>com.example</groupId>
            <artifactId>parent</artifactId>
            <version>1.0.0</version>
          </parent>
          <artifactId>child</artifactId>
        </project>
        """;

    var fetcher = InMemoryPomFetcher.fromXml(List.of(pom));

    assertThat(fetcher.fetch(MavenCoordinate.of("com.example", "child", "1.0.0"))).isPresent();
  }

  @Test
  void skipsUnparseablePomsButLoadsTheRest() {
    String validPom =
        """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <groupId>com.example</groupId>
          <artifactId>good</artifactId>
          <version>1.0.0</version>
        </project>
        """;
    String malformed = "<not valid xml";

    var fetcher = InMemoryPomFetcher.fromXml(List.of(validPom, malformed));

    assertThat(fetcher.fetch(MavenCoordinate.of("com.example", "good", "1.0.0"))).isPresent();
  }

  @Test
  void emptyBundleReturnsEmpty() {
    var fetcher = InMemoryPomFetcher.fromXml(List.of());
    assertThat(fetcher.fetch(MavenCoordinate.of("com.example", "anything", "1.0.0"))).isEmpty();
  }
}
