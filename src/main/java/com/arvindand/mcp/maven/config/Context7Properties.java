package com.arvindand.mcp.maven.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for Context7 MCP integration.
 *
 * @param enabled whether Context7 integration is enabled (exposes raw Context7 MCP tools and
 *     includes guidance hints)
 * @author Arvind Menon
 * @since 1.2.0
 */
@ConfigurationProperties(prefix = "context7")
public record Context7Properties(boolean enabled) {

  // Spring Boot will use this constructor and bind properties
  // The default value is set in application.yaml
}
