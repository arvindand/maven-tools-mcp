package com.arvindand.mcp.maven;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

import io.github.senor14.mcptestkit.McpAssertions;
import io.github.senor14.mcptestkit.McpServerTest;
import io.github.senor14.mcptestkit.McpTestClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Wire-protocol conformance tests for the MCP endpoint: boots the server with the Streamable HTTP
 * transport on a random port and verifies what an MCP client actually observes on the wire — the
 * initialize handshake, the declared capabilities, and the published tool list.
 *
 * <p>Complements {@link MavenMcpServerIT}, which exercises the underlying services directly rather
 * than the MCP protocol surface.
 *
 * <p>The tool names are asserted exactly, not just counted: the 3.1.0 regression was the two
 * Context7 tools silently vanishing from {@code tools/list}, which every shape-only assertion
 * (unique names, descriptions present, valid schemas) passes straight through. The client is
 * disabled under the {@code test} profile, so this covers the native nine; the aggregation with a
 * connected client is guarded in {@code McpToolsConfigTest}.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@ActiveProfiles({"test", "http"})
@McpServerTest(url = "spring:/mcp")
class McpProtocolConformanceIT {

  /** Every {@code @Tool} method on {@code MavenDependencyTools}, as published on the wire. */
  private static final String[] NATIVE_TOOLS = {
    "get_latest_version",
    "check_version_exists",
    "check_multiple_dependencies",
    "compare_dependency_versions",
    "analyze_dependency_age",
    "analyze_release_patterns",
    "analyze_project_health",
    "analyze_pom_dependencies",
    "recommend_pom_upgrades"
  };

  @Test
  void mcpEndpointConformsToProtocol(McpTestClient client) {
    McpAssertions.assertThat(client)
        .initializesSuccessfully()
        .declaresToolsCapability()
        .hasTools()
        .toolNamesAreUnique()
        .toolsHaveDescriptions()
        .toolSchemasAreValid();
  }

  @Test
  void publishesExactlyTheNativeTools(McpTestClient client) {
    assertThat(client.toolNames())
        .as("tools/list over streamable http, client disabled under the test profile")
        .containsExactlyInAnyOrder(NATIVE_TOOLS);
  }

  @Test
  void reportsServerIdentity(McpTestClient client) {
    assertThat(client.serverName()).isEqualTo("maven-tools-mcp");
    assertThat(client.serverVersion()).isNotBlank();
  }
}
