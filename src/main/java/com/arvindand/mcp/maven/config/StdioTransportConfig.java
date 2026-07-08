package com.arvindand.mcp.maven.config;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpServerTransportProviderBase;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/** Replaces the SDK stdio provider with a concurrent-response-safe decorator. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.ai.mcp.server", name = "stdio", havingValue = "true")
public class StdioTransportConfig {

  @Bean
  McpServerTransportProviderBase stdioServerTransport(
      @Qualifier("mcpServerJsonMapper") JsonMapper jsonMapper) {
    return new SerializedStdioServerTransportProvider(new JacksonMcpJsonMapper(jsonMapper));
  }
}
