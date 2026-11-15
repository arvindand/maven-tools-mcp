package com.arvindand.mcp.maven.pom;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** Resolver coverage for {@code rootPluginDeclarations}. */
class EffectivePomResolverPluginDeclarationsTest {

  private static EffectivePomResult resolve(String pom) {
    return new EffectivePomResolver(EffectivePomResolverTest.stub(Map.of())).resolve(pom);
  }

  @Test
  void exposesLiteralAndPropertyPluginsFromPluginsAndPluginManagement() {
    EffectivePomResult result =
        resolve(
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId>
              <artifactId>app</artifactId>
              <version>1.0.0</version>
              <properties><fmt.version>2.21</fmt.version></properties>
              <build>
                <plugins>
                  <plugin>
                    <groupId>com.google.cloud.tools</groupId>
                    <artifactId>jib-maven-plugin</artifactId>
                    <version>3.5.2</version>
                  </plugin>
                  <plugin>
                    <groupId>com.spotify.fmt</groupId>
                    <artifactId>fmt-maven-plugin</artifactId>
                    <version>${fmt.version}</version>
                  </plugin>
                </plugins>
                <pluginManagement>
                  <plugins>
                    <plugin>
                      <artifactId>maven-shade-plugin</artifactId>
                      <version>3.2.0</version>
                    </plugin>
                  </plugins>
                </pluginManagement>
              </build>
            </project>
            """);

    assertThat(result.rootPluginDeclarations())
        .containsExactly(
            PluginDeclaration.literal(
                "com.google.cloud.tools",
                "jib-maven-plugin",
                "3.5.2",
                PluginDeclaration.BUILD_PLUGINS),
            PluginDeclaration.property(
                "com.spotify.fmt",
                "fmt-maven-plugin",
                "2.21",
                "fmt.version",
                PluginDeclaration.BUILD_PLUGINS),
            PluginDeclaration.literal(
                "org.apache.maven.plugins",
                "maven-shade-plugin",
                "3.2.0",
                PluginDeclaration.PLUGIN_MANAGEMENT));
  }

  @Test
  void skipsUnversionedInheritedCompoundProfileAndReportingPlugins() {
    EffectivePomResult result =
        resolve(
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId>
              <artifactId>app</artifactId>
              <version>1.0.0</version>
              <build>
                <plugins>
                  <plugin><artifactId>no-version-plugin</artifactId></plugin>
                  <plugin>
                    <artifactId>inherited-plugin</artifactId>
                    <version>${project.version}</version>
                  </plugin>
                  <plugin>
                    <artifactId>compound-plugin</artifactId>
                    <version>${project.version}-x</version>
                  </plugin>
                  <plugin>
                    <artifactId>range-plugin</artifactId>
                    <version>[1.0,2.0)</version>
                  </plugin>
                </plugins>
              </build>
              <reporting>
                <plugins>
                  <plugin>
                    <artifactId>maven-javadoc-plugin</artifactId>
                    <version>3.0.0</version>
                  </plugin>
                </plugins>
              </reporting>
              <profiles>
                <profile>
                  <id>p</id>
                  <build>
                    <plugins>
                      <plugin>
                        <artifactId>maven-jar-plugin</artifactId>
                        <version>3.0.0</version>
                      </plugin>
                    </plugins>
                  </build>
                </profile>
              </profiles>
            </project>
            """);

    assertThat(result.rootPluginDeclarations()).isEmpty();
  }
}
