package com.arvindand.mcp.maven.pom;

import static org.assertj.core.api.Assertions.assertThat;

import com.arvindand.mcp.maven.model.MavenCoordinate;
import java.util.Map;
import java.util.Optional;
import org.apache.maven.model.Model;
import org.junit.jupiter.api.Test;

class EffectivePomResolverTest {

  /** Stub PomFetcher backed by an in-memory map keyed by groupId:artifactId:version. */
  static PomFetcher stub(Map<String, Model> models) {
    return coord -> Optional.ofNullable(models.get(coord.toCoordinateString()));
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
}
