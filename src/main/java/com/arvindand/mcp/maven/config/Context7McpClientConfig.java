package com.arvindand.mcp.maven.config;

import io.modelcontextprotocol.client.transport.customizer.McpAsyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import java.net.URI;
import java.net.http.HttpRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Mono;

/**
 * Outbound MCP client customization for Context7.
 *
 * <p>Adds the Context7 API key header when this server acts as an MCP client to Context7.
 *
 * @author Arvind Menon
 * @since 2.0.4
 */
@Configuration
public class Context7McpClientConfig {

  private static final String CONTEXT7_HOST = "mcp.context7.com";
  private static final Logger log = LoggerFactory.getLogger(Context7McpClientConfig.class);

  @Bean
  @Primary
  McpSyncHttpClientRequestCustomizer context7ApiKeyRequestCustomizer(Context7Properties context7) {
    return (builder, method, endpoint, body, transportContext) -> {
      applyContext7Headers(builder, endpoint, context7);
    };
  }

  @Bean
  @Primary
  McpAsyncHttpClientRequestCustomizer context7ApiKeyAsyncRequestCustomizer(Context7Properties context7) {
    return (builder, method, endpoint, body, transportContext) -> {
      applyContext7Headers(builder, endpoint, context7);
      return Mono.just(builder);
    };
  }

  private static boolean isContext7Endpoint(URI endpoint) {
    String host = endpoint.getHost();
    return host != null && (host.equalsIgnoreCase(CONTEXT7_HOST) || host.endsWith(".context7.com"));
  }

  private static void applyContext7Headers(
      HttpRequest.Builder builder, URI endpoint, Context7Properties context7) {
    if (!isContext7Endpoint(endpoint) || !context7.hasApiKey()) {
      return;
    }

    // Context7 currently accepts multiple API-key header styles; send the common variants
    // plus Authorization for compatibility with different MCP clients/server revisions.
    builder.header("Context7-API-Key", context7.apiKey());
    builder.header("X-Context7-API-Key", context7.apiKey());
    builder.header("Authorization", "Bearer " + context7.apiKey());
    log.debug("Applied Context7 MCP auth headers for endpoint {}", endpoint);
  }
}
