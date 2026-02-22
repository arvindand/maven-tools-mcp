package com.arvindand.mcp.maven.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * Configuration properties for Context7 MCP integration.
 *
 * @param enabled whether Context7 integration is enabled (exposes raw Context7 MCP tools and
 *     includes guidance hints)
 * @param apiKey optional Context7 API key used for outbound MCP client requests to Context7
 * @author Arvind Menon
 * @since 1.2.0
 */
@ConfigurationProperties(prefix = "context7")
public record Context7Properties(boolean enabled, String apiKey) {
  public boolean hasApiKey() {
    return StringUtils.hasText(apiKey);
  }
}
