package com.arvindand.mcp.maven;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.senor14.mcptestkit.McpTestClient;
import io.github.senor14.mcptestkit.client.HttpMcpTestClient;
import io.github.senor14.mcptestkit.client.StdioMcpTestClient;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Exercises the server's feature set against a running artifact rather than in-process beans, so a
 * packaging problem (most importantly a missing native-image reflection hint for a response record)
 * fails here instead of in a user's client.
 *
 * <p>The target is chosen with system properties:
 *
 * <ul>
 *   <li>{@code maven.tools.test.http.url}: a streamable HTTP endpoint, e.g. the {@code -http} image
 *       at {@code http://localhost:8080/mcp}
 *   <li>{@code maven.tools.test.image}: a stdio image, started with {@code docker run --rm -i}
 *   <li>neither: the repackaged jar over stdio
 * </ul>
 *
 * <p>{@code maven.tools.test.expected.version} asserts the version the server reports, which is how
 * a published image is shown to contain the release it is tagged with. {@code
 * maven.tools.test.context7=absent} asserts the Context7 tools are not exposed ({@code -noc7}).
 *
 * <p>Assertions use data that does not change: the last 3.x release of commons-collections, the
 * MySQL connector relocation, and a plugin version that is long superseded.
 */
class McpImageFeaturesIT {

  private static final String POM =
      """
      <project><modelVersion>4.0.0</modelVersion><groupId>local</groupId><artifactId>app</artifactId>
      <version>1</version><dependencies>
      <dependency><groupId>commons-collections</groupId><artifactId>commons-collections</artifactId>
      <version>3.2.1</version></dependency>
      <dependency><groupId>mysql</groupId><artifactId>mysql-connector-java</artifactId>
      <version>8.0.33</version></dependency>
      </dependencies><build><plugins><plugin><groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-shade-plugin</artifactId><version>3.2.0</version></plugin></plugins></build>
      </project>
      """;

  private static McpTestClient client;
  private static AutoCloseable closer;

  @BeforeAll
  static void connect() {
    Duration timeout = Duration.ofSeconds(90);
    String httpUrl = System.getProperty("maven.tools.test.http.url");
    if (httpUrl != null) {
      HttpMcpTestClient http = HttpMcpTestClient.connect(URI.create(httpUrl), Map.of(), timeout);
      client = http;
      closer = http::close;
      return;
    }
    String image = System.getProperty("maven.tools.test.image");
    String[] command;
    if (image != null) {
      command = new String[] {"docker", "run", "--rm", "-i", image};
    } else {
      Path jar =
          Path.of(
              System.getProperty("project.build.directory", "target"),
              System.getProperty("project.build.finalName", "maven-tools-mcp") + ".jar");
      assertThat(jar)
          .as("repackaged jar must exist by the integration-test phase; run via mvn verify")
          .exists();
      command = new String[] {"java", "-jar", jar.toAbsolutePath().toString()};
    }
    StdioMcpTestClient stdio = StdioMcpTestClient.connect(command, Map.of(), timeout);
    client = stdio;
    closer = stdio::close;
  }

  @AfterAll
  static void disconnect() throws Exception {
    if (closer != null) {
      closer.close();
    }
  }

  @Test
  void reportsTheExpectedVersion() {
    String expected = System.getProperty("maven.tools.test.expected.version");
    assumeTrue(expected != null, "no expected version given");
    assertThat(client.serverVersion()).isEqualTo(expected);
  }

  @Test
  void exposesEveryNativeTool() {
    assertThat(client.toolNames())
        .contains(
            "get_latest_version",
            "check_version_exists",
            "check_multiple_dependencies",
            "compare_dependency_versions",
            "analyze_dependency_age",
            "analyze_release_patterns",
            "analyze_project_health",
            "analyze_pom_dependencies",
            "recommend_pom_upgrades",
            "scan_transitive_vulnerabilities");
    if ("absent".equals(System.getProperty("maven.tools.test.context7"))) {
      assertThat(client.toolNames()).doesNotContain("resolve_library_id", "query_docs");
    }
  }

  @Test
  void recommendsPluginRelocationAndSecurityAwareUpgrades() {
    String result = call("recommend_pom_upgrades", Map.of("pomXml", POM));
    assertThat(result)
        .as("plugin versions are covered")
        .contains("plugin_bump")
        .contains("maven-shade-plugin");
    assertThat(result)
        .as("relocated artifacts are reported")
        .contains("relocated")
        .contains("mysql-connector-j");
    assertThat(result).as("the security scan ran").contains("securitySummary");
    assertThat(result)
        .as("the same-major fix is offered and the 2004 date-stamped build is not")
        .contains("3.2.2")
        .doesNotContain("20040616");
  }

  @Test
  void healthAuditScansTheSuppliedVersion() {
    String result =
        call(
            "analyze_project_health",
            Map.of("dependencies", "org.apache.logging.log4j:log4j-core:2.14.1"));
    assertThat(result)
        .contains("scannedVersion")
        .contains("2.14.1")
        .contains("overall_health")
        .contains("average_health_score");
  }

  @Test
  void transitiveScanResolvesADependencyGraph() {
    String result =
        call(
            "scan_transitive_vulnerabilities",
            Map.of("dependencies", "org.springframework.boot:spring-boot-starter-web:3.3.0"));
    assertThat(result).contains("rootsResolved").contains("transitiveNodes");
  }

  @Test
  void latestVersionReportsARelocation() {
    String result = call("get_latest_version", Map.of("dependency", "mysql:mysql-connector-java"));
    assertThat(result).contains("relocation").contains("mysql-connector-j");
  }

  private static String call(String tool, Map<String, Object> arguments) {
    Object result = client.callTool(tool, arguments);
    assertThat(result).as(tool + " response").isNotNull();
    return result.toString();
  }
}
