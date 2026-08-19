package com.arvindand.mcp.maven.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.arvindand.mcp.maven.pom.EffectivePomResolver;
import com.arvindand.mcp.maven.service.MavenCentralService;
import com.arvindand.mcp.maven.service.MavenDependencyTools;
import com.arvindand.mcp.maven.service.VulnerabilityService;
import com.arvindand.mcp.maven.util.VersionComparator;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Guards the MCP tool registration in {@link McpToolsConfig}. The aggregating provider must expose
 * exactly the native {@code @Tool} methods when no downstream MCP client is connected (the noc7
 * case) — i.e. it must not break the native tools and must not invent phantom tools. The
 * connected-client path (native + client tools) is covered here too, with a stubbed {@link
 * McpSyncClient} rather than a live Context7 connection — that is the exact regression 3.1.0
 * shipped, and asserting it needs no network.
 *
 * <p>Context for the design (see {@link McpToolsConfig}): Spring AI 2.0 suppresses its own MCP
 * client tool-callback provider via {@code @ConditionalOnMissingBean(ToolCallbackProvider)}, so
 * this single bean must aggregate native + client tools itself.
 */
class McpToolsConfigTest {

  private MavenDependencyTools tools() {
    return new MavenDependencyTools(
        mock(MavenCentralService.class),
        new VersionComparator(),
        new Context7Properties(false, null),
        mock(VulnerabilityService.class),
        mock(EffectivePomResolver.class));
  }

  @SuppressWarnings("unchecked")
  private ObjectProvider<List<McpSyncClient>> noClients() {
    ObjectProvider<List<McpSyncClient>> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable(any())).thenReturn(List.of());
    return provider;
  }

  @SuppressWarnings("unchecked")
  private ObjectProvider<List<McpSyncClient>> clientExposing(String... toolNames) {
    McpSyncClient client = mock(McpSyncClient.class);
    when(client.getServerInfo()).thenReturn(new McpSchema.Implementation("context7", "1.0.0"));
    when(client.getClientCapabilities()).thenReturn(McpSchema.ClientCapabilities.builder().build());
    when(client.getClientInfo()).thenReturn(new McpSchema.Implementation("test-client", "1.0.0"));
    when(client.listTools())
        .thenReturn(
            new McpSchema.ListToolsResult(
                java.util.Arrays.stream(toolNames)
                    .map(
                        n ->
                            McpSchema.Tool.builder()
                                .name(n)
                                .description(n + " (stub)")
                                .inputSchema(Map.of("type", "object"))
                                .build())
                    .toList(),
                null));

    ObjectProvider<List<McpSyncClient>> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable(any())).thenReturn(List.of(client));
    return provider;
  }

  @Test
  void exposesNativeToolsOnlyWhenNoMcpClientConnected() {
    ToolCallbackProvider provider =
        new McpToolsConfig().mavenDependencyToolsCallbackProvider(tools(), noClients());

    // All nine @Tool methods are exposed; nothing extra when no client is connected (noc7).
    assertThat(provider.getToolCallbacks()).hasSize(9).allSatisfy(tc -> assertThat(tc).isNotNull());
  }

  /**
   * The 3.1.0 regression: the Context7 client stayed connected but its tools stopped being
   * re-exported, so {@code tools/list} returned only the native nine. Every shape-only assertion
   * passes in that state, so this one counts.
   */
  @Test
  void aggregatesConnectedClientToolsOnTopOfTheNativeOnes() {
    ToolCallbackProvider provider =
        new McpToolsConfig()
            .mavenDependencyToolsCallbackProvider(
                tools(), clientExposing("resolve_library_id", "query_docs"));

    assertThat(provider.getToolCallbacks())
        .as("native nine plus the connected client's two")
        .hasSize(11);
  }
}
