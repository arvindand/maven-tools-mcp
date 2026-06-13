package com.arvindand.mcp.maven.config;

import com.arvindand.mcp.maven.service.MavenDependencyTools;
import io.modelcontextprotocol.client.McpSyncClient;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for Maven dependency tools. Registers MCP tools as Spring beans using the Spring AI
 * MCP Server Boot Starter pattern.
 *
 * @author Arvind Menon
 * @since 0.1.0
 */
@Configuration
@EnableConfigurationProperties({MavenCentralProperties.class, Context7Properties.class})
public class McpToolsConfig {

  /**
   * Exposes this server's tools to the MCP server auto-configuration: the native {@code @Tool}
   * methods plus — when a downstream MCP client is connected (Context7 enabled) — that client's
   * tools, re-exported as raw MCP tools.
   *
   * <p>This single provider deliberately aggregates both. Spring AI 2.0's client auto-configuration
   * registers its own {@code SyncMcpToolCallbackProvider} only via
   * {@code @ConditionalOnMissingBean( ToolCallbackProvider.class)}; because we declare our own
   * {@link ToolCallbackProvider} for the native tools, that auto-configured provider is suppressed
   * and the client (Context7) tools would otherwise never reach the server. Aggregating here keeps
   * exactly one {@code ToolCallbackProvider} bean (no ambiguity for the server's converter) while
   * restoring the Context7 tool proxying that Spring AI 1.x wired automatically. Degrades to
   * native-only when no client is connected (noc7).
   *
   * @param mavenDependencyTools the Maven dependency tools service ({@code @Tool} methods)
   * @param mcpSyncClients connected downstream MCP sync clients, if any (e.g. Context7)
   * @return a provider exposing native tools plus any connected client's tools
   */
  @Bean
  public ToolCallbackProvider mavenDependencyToolsCallbackProvider(
      MavenDependencyTools mavenDependencyTools,
      ObjectProvider<List<McpSyncClient>> mcpSyncClients) {
    ToolCallbackProvider nativeTools =
        MethodToolCallbackProvider.builder().toolObjects(mavenDependencyTools).build();

    List<McpSyncClient> clients = mcpSyncClients.getIfAvailable(List::of);
    if (clients.isEmpty()) {
      return nativeTools;
    }

    ToolCallbackProvider clientTools =
        SyncMcpToolCallbackProvider.builder().mcpClients(clients).build();
    // Resolve live on each call so the SyncMcpToolCallbackProvider can refresh on tool-change
    // events.
    return () -> {
      List<ToolCallback> all = new ArrayList<>();
      Collections.addAll(all, nativeTools.getToolCallbacks());
      Collections.addAll(all, clientTools.getToolCallbacks());
      return all.toArray(ToolCallback[]::new);
    };
  }
}
