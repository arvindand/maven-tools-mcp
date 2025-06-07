package com.arvindand.mcp.maven;

import com.arvindand.mcp.maven.config.MavenCentralProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;

/**
 * Main Spring Boot application class for the Maven MCP Server. This application provides Model
 * Context Protocol (MCP) tools for Maven dependency management, including latest version lookup and
 * version existence checks with caching support.
 *
 * @author Arvind Menon
 * @since 0.1.0
 */
@SpringBootApplication
@EnableCaching
@EnableConfigurationProperties(MavenCentralProperties.class)
public class MavenMcpServerApplication {

  /**
   * Main method to start the Maven MCP Server application.
   *
   * @param args command line arguments
   */
  public static void main(String[] args) {
    SpringApplication.run(MavenMcpServerApplication.class, args);
  }
}
