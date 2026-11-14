package com.arvindand.mcp.maven.pom;

import static com.arvindand.mcp.maven.pom.EffectivePomResolverTest.parse;
import static com.arvindand.mcp.maven.pom.EffectivePomResolverTest.stub;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.apache.maven.model.Model;
import org.junit.jupiter.api.Test;

/** Property-pin detection: root properties shadowing an ancestor property used as a version. */
class EffectivePomResolverPropertyOverrideTest {

  private static final String GRANDPARENT =
      """
      <project xmlns="http://maven.apache.org/POM/4.0.0">
        <modelVersion>4.0.0</modelVersion>
        <groupId>com.example</groupId>
        <artifactId>grandparent</artifactId>
        <version>1.0.0</version>
        <properties>
          <snakeyaml.version>2.2</snakeyaml.version>
          <bom.version>5.${minor}</bom.version>
          <minor>1</minor>
          <java.version>17</java.version>
          <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        </properties>
        <dependencyManagement>
          <dependencies>
            <dependency>
              <groupId>org.yaml</groupId>
              <artifactId>snakeyaml</artifactId>
              <version>${snakeyaml.version}</version>
            </dependency>
            <dependency>
              <groupId>com.example</groupId>
              <artifactId>some-bom</artifactId>
              <version>${bom.version}</version>
              <type>pom</type>
              <scope>import</scope>
            </dependency>
          </dependencies>
        </dependencyManagement>
      </project>
      """;

  private static final String PARENT =
      """
      <project xmlns="http://maven.apache.org/POM/4.0.0">
        <modelVersion>4.0.0</modelVersion>
        <parent>
          <groupId>com.example</groupId>
          <artifactId>grandparent</artifactId>
          <version>1.0.0</version>
        </parent>
        <artifactId>parent</artifactId>
      </project>
      """;

  private static final String BOM =
      """
      <project xmlns="http://maven.apache.org/POM/4.0.0">
        <modelVersion>4.0.0</modelVersion>
        <groupId>com.example</groupId>
        <artifactId>some-bom</artifactId>
        <version>5.9</version>
        <packaging>pom</packaging>
      </project>
      """;

  private static Map<String, Model> chain() {
    return Map.of(
        "com.example:grandparent:1.0.0", parse(GRANDPARENT),
        "com.example:parent:1.0.0", parse(PARENT),
        "com.example:some-bom:5.9", parse(BOM));
  }

  private static String root(String properties) {
    return """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <parent>
            <groupId>com.example</groupId>
            <artifactId>parent</artifactId>
            <version>1.0.0</version>
          </parent>
          <artifactId>app</artifactId>
          <properties>%s</properties>
        </project>
        """
        .formatted(properties);
  }

  @Test
  void reportsPropertyDefinedInGrandparentAndUsedInItsDependencyManagement() {
    EffectivePomResult result =
        new EffectivePomResolver(stub(chain()))
            .resolve(
                root(
                    "<snakeyaml.version>1.33</snakeyaml.version><java.version>21</java.version>"
                        + "<project.build.sourceEncoding>ISO-8859-1</project.build.sourceEncoding>"
                        + "<unrelated>x</unrelated>"));

    assertThat(result.propertyOverrides())
        .singleElement()
        .satisfies(
            o -> {
              assertThat(o.propertyName()).isEqualTo("snakeyaml.version");
              assertThat(o.pinnedValue()).isEqualTo("1.33");
              assertThat(o.inheritedValue()).isEqualTo("2.2");
              assertThat(o.definedIn()).isEqualTo("com.example:grandparent:1.0.0");
              assertThat(o.affects()).containsExactly("org.yaml:snakeyaml");
              assertThat(o.affectedCount()).isEqualTo(1);
            });
  }

  @Test
  void reportsPropertyUsedAsBomImportVersionAndInterpolatesInheritedValue() {
    EffectivePomResult result =
        new EffectivePomResolver(stub(chain())).resolve(root("<bom.version>5.0</bom.version>"));

    assertThat(result.propertyOverrides())
        .singleElement()
        .satisfies(
            o -> {
              assertThat(o.propertyName()).isEqualTo("bom.version");
              assertThat(o.pinnedValue()).isEqualTo("5.0");
              assertThat(o.inheritedValue()).isEqualTo("5.1");
              assertThat(o.affects()).containsExactly("com.example:some-bom");
            });
  }

  @Test
  void ignoresRootPropertiesNotDefinedByAnyAncestor() {
    EffectivePomResult result =
        new EffectivePomResolver(stub(chain())).resolve(root("<my.own.version>1</my.own.version>"));

    assertThat(result.propertyOverrides()).isEmpty();
  }

  @Test
  void ignoresPropertiesThatAncestorsDoNotUseAsVersion() {
    EffectivePomResult result =
        new EffectivePomResolver(stub(chain()))
            .resolve(root("<java.version>21</java.version><minor>3</minor>"));

    assertThat(result.propertyOverrides()).isEmpty();
  }

  @Test
  void ignoresProfilePropertiesOfTheRoot() {
    String pom =
        """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <parent>
            <groupId>com.example</groupId>
            <artifactId>parent</artifactId>
            <version>1.0.0</version>
          </parent>
          <artifactId>app</artifactId>
          <profiles>
            <profile>
              <id>p</id>
              <properties><snakeyaml.version>1.0</snakeyaml.version></properties>
            </profile>
          </profiles>
        </project>
        """;

    assertThat(new EffectivePomResolver(stub(chain())).resolve(pom).propertyOverrides()).isEmpty();
  }

  @Test
  void capsAffectsAtTwentyButReportsTotalCount() {
    StringBuilder deps = new StringBuilder();
    for (int i = 0; i < 25; i++) {
      deps.append(
          "<dependency><groupId>g</groupId><artifactId>a%d</artifactId><version>${shared.version}</version></dependency>"
              .formatted(i));
    }
    Model grandparent =
        parse(
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId>
              <artifactId>grandparent</artifactId>
              <version>1.0.0</version>
              <properties><shared.version>1.0</shared.version></properties>
              <dependencyManagement><dependencies>%s</dependencies></dependencyManagement>
            </project>
            """
                .formatted(deps));

    EffectivePomResult result =
        new EffectivePomResolver(
                stub(
                    Map.of(
                        "com.example:grandparent:1.0.0",
                        grandparent,
                        "com.example:parent:1.0.0",
                        parse(PARENT))))
            .resolve(root("<shared.version>0.5</shared.version>"));

    assertThat(result.propertyOverrides())
        .singleElement()
        .satisfies(
            o -> {
              assertThat(o.affects()).hasSize(PropertyOverride.MAX_AFFECTS);
              assertThat(o.affectedCount()).isEqualTo(25);
            });
  }
}
