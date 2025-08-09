package com.arvindand.mcp.maven.config;

import com.arvindand.mcp.maven.service.MavenDependencyTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
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
   * Bean for Maven dependency tools registration with MCP server.
   *
   * @param mavenDependencyTools the Maven dependency tools service
   * @return list of tool callbacks for MCP server auto-configuration
   */
  @Bean
  public ToolCallbackProvider mavenDependencyToolsCallbackProvider(
      MavenDependencyTools mavenDependencyTools) {
    return MethodToolCallbackProvider.builder().toolObjects(mavenDependencyTools).build();
  }
}
