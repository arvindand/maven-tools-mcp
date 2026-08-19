package com.arvindand.mcp.maven;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.senor14.mcptestkit.client.StdioMcpTestClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Wire-protocol conformance over stdio, launching the repackaged jar exactly the way {@code
 * docs/setup.md} tells a user to and speaking JSON-RPC on its stdin/stdout.
 *
 * <p>This is the transport issue #15 was about: {@code spring.ai.mcp.server.stdio} was only set in
 * the docker profile, so the documented {@code java -jar} invocation started cleanly and then
 * answered nothing at all. Nothing caught that, because every other test either drives the services
 * directly or boots the Streamable HTTP endpoint. stdio is also the default for Claude Desktop and
 * both published images, so it is the path most users are actually on.
 *
 * <p>Runs against {@code target/<finalName>.jar}, whose path failsafe passes in — the jar exists by
 * the {@code integration-test} phase because {@code package} has already run.
 */
class McpStdioConformanceIT {

  private static StdioMcpTestClient client;

  @BeforeAll
  static void launchServer() {
    Path jar =
        Path.of(
            System.getProperty("project.build.directory", "target"),
            System.getProperty("project.build.finalName", "maven-tools-mcp") + ".jar");
    assertThat(jar)
        .as("repackaged jar must exist by the integration-test phase; run via mvn verify")
        .exists();

    client =
        StdioMcpTestClient.connect(
            new String[] {"java", "-jar", jar.toAbsolutePath().toString()},
            Map.of(),
            Duration.ofSeconds(60));
  }

  @AfterAll
  static void stopServer() {
    if (client != null) {
      client.close();
    }
  }

  @Test
  void bareJarAnswersOverStdio() {
    assertThat(client.initialized())
        .as("a bare `java -jar` must speak stdio without extra flags (#15)")
        .isTrue();
    assertThat(client.serverName()).isEqualTo("maven-tools-mcp");
    assertThat(client.protocolVersion()).isNotBlank();
  }

  @Test
  void publishesTheNativeToolsOverStdio() {
    assertThat(client.toolNames())
        .as("tools/list over stdio")
        .contains(
            "get_latest_version",
            "check_version_exists",
            "check_multiple_dependencies",
            "compare_dependency_versions",
            "analyze_dependency_age",
            "analyze_release_patterns",
            "analyze_project_health",
            "analyze_pom_dependencies",
            "recommend_pom_upgrades");
  }

  @Test
  void toolsAreCallableOverStdio() {
    var result =
        client.callTool("get_latest_version", Map.of("dependency", "com.google.guava:guava"));
    assertThat(result).as("get_latest_version response").isNotNull();
    assertThat(result.toString()).contains("guava");
  }
}
