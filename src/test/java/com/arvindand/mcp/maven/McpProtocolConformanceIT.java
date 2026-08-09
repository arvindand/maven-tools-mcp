package com.arvindand.mcp.maven;

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
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@ActiveProfiles({"test", "http"})
@McpServerTest(url = "spring:/mcp")
class McpProtocolConformanceIT {

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
}
