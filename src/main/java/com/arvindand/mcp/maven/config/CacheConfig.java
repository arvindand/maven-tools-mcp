package com.arvindand.mcp.maven.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Cache configuration for Maven Tools MCP.
 *
 * <p>Configures cache regions for Maven Central API results with long TTL (24h) for stable data.
 *
 * @author Arvind Menon
 * @since 1.2.0
 */
@Configuration
@EnableCaching
public class CacheConfig {

  // Maven Central cache names
  private static final String MAVEN_LATEST_VERSIONS_CACHE = "maven-latest-versions";
  private static final String MAVEN_VERSION_CHECKS_CACHE = "maven-version-checks";
  private static final String MAVEN_ALL_VERSIONS_CACHE = "maven-all-versions";
  private static final String MAVEN_VERSIONS_WITH_TIMESTAMPS_CACHE =
      "maven-versions-with-timestamps";
  private static final String MAVEN_RECENT_VERSIONS_WITH_TIMESTAMPS_CACHE =
      "maven-recent-versions-with-timestamps";

  @Bean
  public CacheManager cacheManager() {
    CaffeineCacheManager cacheManager = new CaffeineCacheManager();

    // Maven Central caches - long TTL since data is stable
    cacheManager.registerCustomCache(MAVEN_LATEST_VERSIONS_CACHE, mavenCentralCache());
    cacheManager.registerCustomCache(MAVEN_VERSION_CHECKS_CACHE, mavenCentralCache());
    cacheManager.registerCustomCache(MAVEN_ALL_VERSIONS_CACHE, mavenCentralCache());
    cacheManager.registerCustomCache(MAVEN_VERSIONS_WITH_TIMESTAMPS_CACHE, mavenCentralCache());
    cacheManager.registerCustomCache(
        MAVEN_RECENT_VERSIONS_WITH_TIMESTAMPS_CACHE, mavenCentralCache());

    return cacheManager;
  }

  private com.github.benmanes.caffeine.cache.Cache<Object, Object> mavenCentralCache() {
    return Caffeine.newBuilder().maximumSize(2000).expireAfterWrite(Duration.ofHours(24)).build();
  }
}
