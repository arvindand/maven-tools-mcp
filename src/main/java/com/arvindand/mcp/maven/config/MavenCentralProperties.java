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
 * @author Arvind Menon
 * @since 0.1.0
 */
@ConfigurationProperties(prefix = "maven.central")
public record MavenCentralProperties(
    @DefaultValue("https://repo1.maven.org/maven2") String repositoryBaseUrl,
    @DefaultValue("10s") Duration timeout,
    @DefaultValue("100") int maxResults) {}
