package com.arvindand.mcp.maven.config;

/**
 * Cache names used throughout the Maven Tools MCP application.
 *
 * @author Arvind Menon
 * @since 1.3.0
 */
public final class CacheConstants {

  // Maven Central cache names
  public static final String MAVEN_LATEST_VERSIONS = "maven-latest-versions";
  public static final String MAVEN_VERSION_CHECKS = "maven-version-checks";
  public static final String MAVEN_ALL_VERSIONS = "maven-all-versions";
  public static final String MAVEN_VERSIONS_WITH_TIMESTAMPS = "maven-versions-with-timestamps";
  public static final String MAVEN_RECENT_VERSIONS_WITH_TIMESTAMPS =
      "maven-recent-versions-with-timestamps";

  private CacheConstants() {
    // Utility class
  }
}
