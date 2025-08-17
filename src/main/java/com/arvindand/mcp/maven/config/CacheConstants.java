package com.arvindand.mcp.maven.config;

/**
 * Cache names used throughout the Maven Tools MCP application.
 *
 * @author Arvind Menon
 * @since 1.3.0
 */
public final class CacheConstants {

  // Maven Central cache names
  public static final String MAVEN_VERSION_CHECKS = "maven-version-checks";
  public static final String MAVEN_ALL_VERSIONS = "maven-all-versions";
  public static final String MAVEN_ACCURATE_HISTORICAL_DATA = "maven-accurate-historical-data";

  private CacheConstants() {
    // Utility class
  }
}
