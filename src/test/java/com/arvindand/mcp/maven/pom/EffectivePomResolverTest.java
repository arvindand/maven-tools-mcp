package com.arvindand.mcp.maven.pom;

import static org.assertj.core.api.Assertions.assertThat;

import com.arvindand.mcp.maven.model.MavenCoordinate;
import java.util.Map;
import java.util.Optional;
import org.apache.maven.model.Model;
import org.junit.jupiter.api.Test;

class EffectivePomResolverTest {

  /** Stub PomFetcher backed by an in-memory map keyed by "groupId:artifactId:version". */
  static PomFetcher stub(Map<String, Model> models) {
    return coord -> Optional.ofNullable(models.get(coord.toCoordinateString()));
  }

  /** Helper that parses a POM XML literal into a {@link Model}. */
  static Model parse(String xml) {
    try {
      return new org.apache.maven.model.io.xpp3.MavenXpp3Reader()
          .read(new java.io.StringReader(xml));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void resolvesExplicitVersionWithNoParent() {
    String pom =
        """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <groupId>com.example</groupId>
          <artifactId>app</artifactId>
          <version>1.0.0</version>
          <dependencies>
            <dependency>
              <groupId>com.fasterxml.jackson.core</groupId>
              <artifactId>jackson-databind</artifactId>
              <version>2.19.2</version>
            </dependency>
          </dependencies>
        </project>
        """;

    EffectivePomResult result = new EffectivePomResolver(stub(Map.of())).resolve(pom);

    assertThat(result.parentChain()).isEmpty();
    assertThat(result.warnings()).isEmpty();
    assertThat(result.dependencies())
        .singleElement()
        .satisfies(
            d -> {
              assertThat(d.coordinate())
                  .isEqualTo(
                      MavenCoordinate.of(
                          "com.fasterxml.jackson.core", "jackson-databind", null));
              assertThat(d.effectiveVersion()).isEqualTo("2.19.2");
              assertThat(d.source()).isEqualTo(Source.EXPLICIT);
              assertThat(d.managedBy()).isEmpty();
            });
  }

  @Test
  void resolvesVersionFromParentProperty() {
    Model parent =
        parse(
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId>
              <artifactId>parent</artifactId>
              <version>1.0.0</version>
              <properties>
                <jackson.version>2.19.2</jackson.version>
              </properties>
            </project>
            """);
    // Stub key format: "groupId:artifactId:version" — coordinate must have version set.
    PomFetcher fetcher = stub(Map.of("com.example:parent:1.0.0", parent));

    String childPom =
        """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <parent>
            <groupId>com.example</groupId>
            <artifactId>parent</artifactId>
            <version>1.0.0</version>
          </parent>
          <artifactId>child</artifactId>
          <dependencies>
            <dependency>
              <groupId>com.fasterxml.jackson.core</groupId>
              <artifactId>jackson-databind</artifactId>
              <version>${jackson.version}</version>
            </dependency>
          </dependencies>
        </project>
        """;

    EffectivePomResult result = new EffectivePomResolver(fetcher).resolve(childPom);

    assertThat(result.parentChain())
        .singleElement()
        .isEqualTo(MavenCoordinate.of("com.example", "parent", "1.0.0"));
    assertThat(result.dependencies())
        .singleElement()
        .satisfies(d -> assertThat(d.effectiveVersion()).isEqualTo("2.19.2"));
  }

  @Test
  void warnsWhenParentCannotBeFetched() {
    String childPom =
        """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <parent>
            <groupId>com.example</groupId>
            <artifactId>missing-parent</artifactId>
            <version>1.0.0</version>
          </parent>
          <artifactId>child</artifactId>
        </project>
        """;

    EffectivePomResult result = new EffectivePomResolver(stub(Map.of())).resolve(childPom);

    assertThat(result.warnings()).anySatisfy(w -> assertThat(w).contains("missing-parent"));
  }
}
