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
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Guards the MCP tool registration in {@link McpToolsConfig}. The aggregating provider must expose
 * exactly the native {@code @Tool} methods when no downstream MCP client is connected (the noc7
 * case) — i.e. it must not break the native tools and must not invent phantom tools. The
 * Context7-connected aggregation path (native + client tools) needs a live client and is covered by
 * the native stdio smoke, not here.
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

  @Test
  void exposesNativeToolsOnlyWhenNoMcpClientConnected() {
    ToolCallbackProvider provider =
        new McpToolsConfig().mavenDependencyToolsCallbackProvider(tools(), noClients());

    // All nine @Tool methods are exposed; nothing extra when no client is connected (noc7).
    assertThat(provider.getToolCallbacks()).hasSize(9).allSatisfy(tc -> assertThat(tc).isNotNull());
  }
}
