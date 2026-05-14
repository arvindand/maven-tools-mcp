package com.arvindand.mcp.maven.config;

import static com.arvindand.mcp.maven.config.CacheConstants.*;

import com.github.benmanes.caffeine.cache.Cache;
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

  @Bean
  public CacheManager cacheManager() {
    CaffeineCacheManager cacheManager = new CaffeineCacheManager();

    // Maven Central caches - long TTL since data is stable
    cacheManager.registerCustomCache(MAVEN_VERSION_CHECKS, mavenCentralCache());
    cacheManager.registerCustomCache(MAVEN_ALL_VERSIONS, mavenCentralCache());
    cacheManager.registerCustomCache(MAVEN_ACCURATE_HISTORICAL_DATA, mavenCentralCache());
    cacheManager.registerCustomCache(MAVEN_POM_XML, mavenCentralCache());

    // Effective POM cache — keyed by raw pomXml. Smaller capacity since each entry holds a
    // full resolver result and POMs are larger than the other coordinate-based keys.
    cacheManager.registerCustomCache(MAVEN_EFFECTIVE_POM, effectivePomCache());

    return cacheManager;
  }

  private Cache<Object, Object> mavenCentralCache() {
    return Caffeine.newBuilder().maximumSize(2000).expireAfterWrite(Duration.ofHours(24)).build();
  }

  private Cache<Object, Object> effectivePomCache() {
    return Caffeine.newBuilder().maximumSize(256).expireAfterWrite(Duration.ofHours(1)).build();
  }
}
