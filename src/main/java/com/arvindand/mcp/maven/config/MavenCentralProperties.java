package com.arvindand.mcp.maven.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration properties for Maven Central integration. Uses direct repository metadata access
 * for accurate version information.
 *
 * @param repositoryBaseUrl the base URL for direct Maven repository access
 * @param timeout the timeout duration for API calls
 * @param maxResults the maximum number of results to retrieve per request
 * @param auth optional authentication configuration for private repositories
 * @author Arvind Menon
 * @since 0.1.0
 */
@ConfigurationProperties(prefix = "maven.central")
public record MavenCentralProperties(
    @DefaultValue("https://repo1.maven.org/maven2") String repositoryBaseUrl,
    @DefaultValue("10s") Duration timeout,
    @DefaultValue("100") int maxResults,
    Auth auth) {

  /**
   * Authentication configuration for private Maven repositories (Nexus, Artifactory, GitHub
   * Packages).
   *
   * @param type the authentication type (none, basic, or bearer)
   * @param username the username for basic authentication
   * @param password the password for basic authentication
   * @param token the token for bearer authentication
   * @since 2.1.0
   */
  public record Auth(
      @DefaultValue("none") AuthType type, String username, String password, String token) {

    public enum AuthType {
      NONE,
      BASIC,
      BEARER
    }
  }
}
