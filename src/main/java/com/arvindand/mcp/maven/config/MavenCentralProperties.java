package com.arvindand.mcp.maven.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration properties for Maven Central API integration. Provides configurable settings for
 * API calls to Maven Central search.
 *
 * @param baseUrl the base URL for Maven Central search API
 * @param timeout the timeout duration for API calls
 * @param maxResults the maximum number of results to retrieve per request
 * @author Arvind Menon
 * @since 0.1.0
 */
@ConfigurationProperties(prefix = "maven.central")
public record MavenCentralProperties(
    @DefaultValue("https://search.maven.org/solrsearch/select") String baseUrl,
    @DefaultValue("10s") Duration timeout,
    @DefaultValue("100") int maxResults) {}
