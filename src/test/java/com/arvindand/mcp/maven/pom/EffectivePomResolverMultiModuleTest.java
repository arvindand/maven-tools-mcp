package com.arvindand.mcp.maven.pom;

import static org.assertj.core.api.Assertions.assertThat;

import com.arvindand.mcp.maven.model.MavenCoordinate;
import java.util.List;
import java.util.Optional;
import org.apache.maven.model.Model;
import org.junit.jupiter.api.Test;

class EffectivePomResolverMultiModuleTest {

  /** Always-empty fetcher — forces the resolver to find parents in the sideloaded bundle. */
  private static final PomFetcher EMPTY = coord -> Optional.empty();

  @Test
  void resolveWithSideloadedParentResolvesFromBundleWhenCentralUnavailable() {
    String parentPom =
        """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <groupId>com.example</groupId>
          <artifactId>parent</artifactId>
          <version>1.0.0-SNAPSHOT</version>
          <properties>
            <jackson.version>2.19.2</jackson.version>
          </properties>
        </project>
        """;

    String childPom =
        """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <parent>
            <groupId>com.example</groupId>
            <artifactId>parent</artifactId>
            <version>1.0.0-SNAPSHOT</version>
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

    EffectivePomResult result =
        new EffectivePomResolver(EMPTY).resolve(childPom, List.of(parentPom));

    assertThat(result.warnings()).isEmpty();
    assertThat(result.parentChain())
        .singleElement()
        .isEqualTo(MavenCoordinate.of("com.example", "parent", "1.0.0-SNAPSHOT"));
    assertThat(result.dependencies())
        .singleElement()
        .satisfies(d -> assertThat(d.effectiveVersion()).isEqualTo("2.19.2"));
  }

  @Test
  void resolveAllReturnsOneResultPerPomInBundle() {
    String aggregator =
        """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <groupId>com.example</groupId>
          <artifactId>root</artifactId>
          <version>1.0.0</version>
          <packaging>pom</packaging>
          <modules>
            <module>module-a</module>
            <module>module-b</module>
          </modules>
        </project>
        """;

    String moduleA =
        """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <parent>
            <groupId>com.example</groupId>
            <artifactId>root</artifactId>
            <version>1.0.0</version>
          </parent>
          <artifactId>module-a</artifactId>
          <dependencies>
            <dependency>
              <groupId>com.example</groupId>
              <artifactId>module-b</artifactId>
              <version>${project.version}</version>
            </dependency>
          </dependencies>
        </project>
        """;

    String moduleB =
        """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <parent>
            <groupId>com.example</groupId>
            <artifactId>root</artifactId>
            <version>1.0.0</version>
          </parent>
          <artifactId>module-b</artifactId>
        </project>
        """;

    List<EffectivePomResult> results =
        new EffectivePomResolver(EMPTY).resolveAll(List.of(aggregator, moduleA, moduleB));

    assertThat(results).hasSize(3);

    // Aggregator: no dependencies
    assertThat(results.get(0).dependencies()).isEmpty();

    // module-a: depends on module-b at project.version (1.0.0 inherited from parent)
    assertThat(results.get(1).dependencies())
        .singleElement()
        .satisfies(
            d -> {
              assertThat(d.groupId()).isEqualTo("com.example");
              assertThat(d.artifactId()).isEqualTo("module-b");
              assertThat(d.effectiveVersion()).isEqualTo("1.0.0");
              assertThat(d.source()).isEqualTo(Source.EXPLICIT);
            });

    // module-b: no dependencies
    assertThat(results.get(2).dependencies()).isEmpty();
    // module-b's parent (the aggregator) was found in the bundle, not Maven Central
    assertThat(results.get(2).warnings()).isEmpty();
  }

  @Test
  void resolveAllFallsBackToInjectedFetcherForPomsNotInBundle() {
    Model externalParent = new Model();
    externalParent.setGroupId("com.external");
    externalParent.setArtifactId("external-parent");
    externalParent.setVersion("9.9.9");
    PomFetcher external =
        coord ->
            "com.external:external-parent:9.9.9".equals(coord.toCoordinateString())
                ? Optional.of(externalParent)
                : Optional.empty();

    String localChild =
        """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <parent>
            <groupId>com.external</groupId>
            <artifactId>external-parent</artifactId>
            <version>9.9.9</version>
          </parent>
          <artifactId>local-child</artifactId>
        </project>
        """;

    List<EffectivePomResult> results =
        new EffectivePomResolver(external).resolveAll(List.of(localChild));

    assertThat(results)
        .singleElement()
        .satisfies(
            r -> {
              assertThat(r.warnings()).isEmpty();
              assertThat(r.parentChain())
                  .singleElement()
                  .isEqualTo(MavenCoordinate.of("com.external", "external-parent", "9.9.9"));
            });
  }

  @Test
  void resolvesRealisticMonorepoMixingLocalParentSiblingAndCentralFallback() {
    // Parent POM exists only in the bundle (e.g., not yet released to Central)
    String parentPom =
        """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <groupId>com.acme</groupId>
          <artifactId>acme-parent</artifactId>
          <version>0.1.0-SNAPSHOT</version>
          <packaging>pom</packaging>
          <properties>
            <jackson.version>2.19.2</jackson.version>
          </properties>
          <dependencyManagement>
            <dependencies>
              <dependency>
                <groupId>com.fasterxml.jackson.core</groupId>
                <artifactId>jackson-databind</artifactId>
                <version>${jackson.version}</version>
              </dependency>
            </dependencies>
          </dependencyManagement>
        </project>
        """;

    // Sibling module also in the bundle
    String siblingPom =
        """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <parent>
            <groupId>com.acme</groupId>
            <artifactId>acme-parent</artifactId>
            <version>0.1.0-SNAPSHOT</version>
          </parent>
          <artifactId>acme-core</artifactId>
        </project>
        """;

    // Primary module: declares jackson without a version (expects MANAGED via the parent's
    // dependencyManagement after ${jackson.version} interpolates) and a sibling acme-core
    // referenced by ${project.version} (expects EXPLICIT against the inherited parent version).
    String primaryPom =
        """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <parent>
            <groupId>com.acme</groupId>
            <artifactId>acme-parent</artifactId>
            <version>0.1.0-SNAPSHOT</version>
          </parent>
          <artifactId>acme-app</artifactId>
          <dependencies>
            <dependency>
              <groupId>com.fasterxml.jackson.core</groupId>
              <artifactId>jackson-databind</artifactId>
            </dependency>
            <dependency>
              <groupId>com.acme</groupId>
              <artifactId>acme-core</artifactId>
              <version>${project.version}</version>
            </dependency>
          </dependencies>
        </project>
        """;

    EffectivePomResult result =
        new EffectivePomResolver(EMPTY).resolve(primaryPom, List.of(parentPom, siblingPom));

    assertThat(result.warnings()).isEmpty();
    assertThat(result.parentChain())
        .singleElement()
        .isEqualTo(MavenCoordinate.of("com.acme", "acme-parent", "0.1.0-SNAPSHOT"));
    assertThat(result.dependencies())
        .hasSize(2)
        .satisfies(
            deps -> {
              EffectiveDependency jackson =
                  deps.stream()
                      .filter(d -> "jackson-databind".equals(d.artifactId()))
                      .findFirst()
                      .orElseThrow();
              assertThat(jackson.effectiveVersion()).isEqualTo("2.19.2");
              assertThat(jackson.source()).isEqualTo(Source.MANAGED);
              assertThat(jackson.managedBy())
                  .contains(MavenCoordinate.of("com.acme", "acme-parent", "0.1.0-SNAPSHOT"));

              EffectiveDependency core =
                  deps.stream()
                      .filter(d -> "acme-core".equals(d.artifactId()))
                      .findFirst()
                      .orElseThrow();
              assertThat(core.effectiveVersion()).isEqualTo("0.1.0-SNAPSHOT");
              assertThat(core.source()).isEqualTo(Source.EXPLICIT);
            });
  }
}
