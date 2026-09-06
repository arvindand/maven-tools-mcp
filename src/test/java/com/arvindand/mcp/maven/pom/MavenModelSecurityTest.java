package com.arvindand.mcp.maven.pom;

import static org.assertj.core.api.Assertions.*;

import com.arvindand.mcp.maven.model.MavenCoordinate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Verifies Maven precedence and isolation from attacker-selected repositories and host state.
 *
 * @author Arvind Menon
 * @since 3.2.2
 */
class MavenModelSecurityTest {
  private static String pom(String body) {
    return "<project><modelVersion>4.0.0</modelVersion><groupId>g</groupId>"
        + "<artifactId>app</artifactId><version>1</version>"
        + body
        + "</project>";
  }

  @Test
  void directManagementAfterImportWinsAndCoordinatesAreInterpolated() {
    String bom =
        pom("""
        <dependencyManagement><dependencies><dependency>
        <groupId>g</groupId><artifactId>lib</artifactId><version>1</version>
        </dependency></dependencies></dependencyManagement>
        """)
            .replace("<artifactId>app</artifactId>", "<artifactId>bom</artifactId>");
    PomFetcher fetcher = InMemoryPomFetcher.fromXml(List.of(bom));
    String root =
        pom(
            """
        <properties><dependency.group>g</dependency.group><dependency.name>lib</dependency.name></properties>
        <dependencyManagement><dependencies>
        <dependency><groupId>g</groupId><artifactId>bom</artifactId><version>1</version>
        <type>pom</type><scope>import</scope></dependency>
        <dependency><groupId>g</groupId><artifactId>lib</artifactId><version>2</version></dependency>
        </dependencies></dependencyManagement>
        <dependencies><dependency><groupId>${dependency.group}</groupId>
        <artifactId>${dependency.name}</artifactId></dependency></dependencies>
        """);
    EffectivePomResult result = new EffectivePomResolver(fetcher).resolve(root);
    assertThat(result.dependencies())
        .singleElement()
        .satisfies(
            dependency -> {
              assertThat(dependency.groupId()).isEqualTo("g");
              assertThat(dependency.artifactId()).isEqualTo("lib");
              assertThat(dependency.effectiveVersion()).isEqualTo("2");
              assertThat(dependency.managedBy()).contains(MavenCoordinate.of("g", "app", "1"));
            });
  }

  @Test
  void evaluatesDefaultProfilesButNotServerEnvironmentProfiles() {
    String root =
        pom(
            """
        <profiles><profile><id>default</id><activation><activeByDefault>true</activeByDefault></activation>
        <properties><lib.version>2</lib.version></properties>
        <dependencies><dependency><groupId>g</groupId><artifactId>lib</artifactId>
        <version>${lib.version}</version></dependency></dependencies></profile>
        <profile><id>host-file</id><activation><file><exists>/etc/passwd</exists></file></activation>
        <properties><lib.version>99</lib.version></properties></profile></profiles>
        """);
    EffectivePomResult result = new EffectivePomResolver(_ -> Optional.empty()).resolve(root);
    assertThat(result.dependencies())
        .singleElement()
        .extracting(EffectiveDependency::effectiveVersion)
        .isEqualTo("2");
    assertThat(result.warnings()).anyMatch(warning -> warning.contains("host-file"));
  }

  @Test
  void rejectsExpansionBombsBeforeLargeAllocations() {
    Map<String, String> properties = new java.util.HashMap<>();
    properties.put("p0", "xxxxxxxxxxxxxxxx");
    for (int index = 1; index <= 20; index++) {
      properties.put("p" + index, ("${p" + (index - 1) + "}").repeat(4));
    }
    assertThatThrownBy(() -> PropertyInterpolator.interpolate("${p20}", properties))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("expansion");
    assertThat(PropertyInterpolator.interpolate("${a}", Map.of("a", "${a}${a}${a}${a}")))
        .hasSizeLessThan(100);
  }

  @Test
  void boundsModelObjectInterpolationAsWellAsProperties() {
    String root =
        pom(
            "<name>"
                + "${project.description}".repeat(20)
                + "</name>"
                + "<description>"
                + "x".repeat(16_384)
                + "</description>"
                + "<properties><value>"
                + "${project.name}".repeat(20)
                + "</value></properties>");
    EffectivePomResolver resolver = new EffectivePomResolver(_ -> Optional.empty());
    assertThatThrownBy(() -> resolver.resolve(root)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsDoctypeDeepXmlAndOversizedBundles() {
    String doctype =
        "<!DOCTYPE project [<!ENTITY x SYSTEM 'file:///etc/passwd'>]>" + pom("<name>&x;</name>");
    String deepXml = "<x>".repeat(65) + "</x>".repeat(65);
    java.util.List<String> oversizedBundle = java.util.Collections.nCopies(65, pom(""));
    assertThatThrownBy(() -> PomInputLimits.check(doctype))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PomInputLimits.check(deepXml))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PomInputLimits.checkBundle(oversizedBundle))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void ignoresPomRepositoryAndRelativeParentLocations() {
    java.util.List<MavenCoordinate> fetched = new java.util.ArrayList<>();
    PomFetcher fetcher =
        coordinate -> {
          fetched.add(coordinate);
          return Optional.empty();
        };
    String root =
        pom(
            """
        <parent><groupId>remote</groupId><artifactId>parent</artifactId><version>1</version>
        <relativePath>/etc/passwd</relativePath></parent>
        <repositories><repository><id>attacker</id><url>http://169.254.169.254/</url></repository></repositories>
        """);
    new EffectivePomResolver(fetcher).resolve(root);
    assertThat(fetched).containsExactly(MavenCoordinate.of("remote", "parent", "1"));
  }
}
