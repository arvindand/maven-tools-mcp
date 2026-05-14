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
  public static final String MAVEN_POM_XML = "maven-pom-xml";
  public static final String MAVEN_EFFECTIVE_POM = "maven-effective-pom";

  private CacheConstants() {
    // Utility class
  }
}
