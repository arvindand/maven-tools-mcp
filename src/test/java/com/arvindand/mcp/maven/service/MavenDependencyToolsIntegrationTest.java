package com.arvindand.mcp.maven.service;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration test class for bulk operations and POM analysis features. Validates the functionality
 * of bulk dependency checking and POM analysis operations in the Maven MCP server.
 *
 * <p>This is an integration test because it: - Uses @SpringBootTest to start the full application
 * context - Makes real HTTP calls to Maven Central API - Tests the complete flow from service layer
 * to external API
 *
 * @author Arvind Menon
 * @since 0.1.0
 */
@SpringBootTest
@ActiveProfiles("test")
class MavenDependencyToolsIntegrationTest {

  @Autowired private MavenDependencyTools mavenDependencyTools;

  /** Tests the bulk check latest functionality with multiple dependencies. */
  @Test
  void testMavenBulkCheckLatest() {
    String dependencies = "org.springframework:spring-core,junit:junit";
    String result = mavenDependencyTools.maven_bulk_check_latest(dependencies);

    assertNotNull(result);
    assertTrue(result.startsWith("["));
    assertTrue(result.endsWith("]"));
    assertTrue(result.contains("org.springframework:spring-core"));
    assertTrue(result.contains("junit:junit"));
    assertTrue(result.contains("\"status\":\"found\""));
  }

  /** Tests the bulk check stable functionality with multiple dependencies. */
  @Test
  void testMavenBulkCheckStable() {
    String dependencies = "org.springframework:spring-core,com.fasterxml.jackson.core:jackson-core";
    String result = mavenDependencyTools.maven_bulk_check_stable(dependencies);

    assertNotNull(result);
    assertTrue(result.startsWith("["));
    assertTrue(result.endsWith("]"));
    assertTrue(result.contains("org.springframework:spring-core"));
    assertTrue(result.contains("jackson-core"));
    assertTrue(result.contains("\"type\":\"stable\""));
  }

  /** Tests POM analysis functionality with a sample POM content. */
  @Test
  void testMavenAnalyzePom() {
    String pomContent =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <project xmlns="http://maven.apache.org/POM/4.0.0">
            <dependencies>
                <dependency>
                    <groupId>org.springframework</groupId>
                    <artifactId>spring-core</artifactId>
                    <version>6.0.0</version>
                </dependency>
                <dependency>
                    <groupId>junit</groupId>
                    <artifactId>junit</artifactId>
                    <version>4.12</version>
                    <scope>test</scope>
                </dependency>
            </dependencies>
        </project>
        """;
    String result = mavenDependencyTools.maven_analyze_pom(pomContent);

    assertNotNull(result);
    assertTrue(result.startsWith("{"));
    assertTrue(result.endsWith("}"));
    assertTrue(result.contains("total_dependencies"));
    assertTrue(result.contains("org.springframework:spring-core"));
    assertTrue(result.contains("junit:junit"));
    assertTrue(result.contains("summary"));
  }

  @Test
  void testMavenCompareVersions() {
    String currentDependencies = "org.springframework:spring-core:6.0.0,junit:junit:4.12";
    String result = mavenDependencyTools.maven_compare_versions(currentDependencies);

    assertNotNull(result);
    assertTrue(result.startsWith("{"));
    assertTrue(result.endsWith("}"));
    assertTrue(result.contains("comparison_date"));
    assertTrue(result.contains("update_summary"));
    assertTrue(result.contains("current_version"));
    assertTrue(result.contains("latest_version"));
  }

  @Test
  void testBulkCheckWithInvalidDependency() {
    String dependencies = "invalid:dependency,org.springframework:spring-core";
    String result = mavenDependencyTools.maven_bulk_check_latest(dependencies);

    assertNotNull(result);
    assertTrue(
        result.contains("\"status\":\"error\"") || result.contains("\"status\":\"not_found\""));
    assertTrue(result.contains("org.springframework:spring-core"));
  }

  /** Tests maven_get_latest returns all version types in the response. */
  @Test
  void testMavenGetLatestAllTypes() {
    // This dependency is likely to have multiple release types (adjust as needed)
    String dependency = "org.springframework:spring-core";
    String result = mavenDependencyTools.maven_get_latest(dependency);

    assertNotNull(result);
    assertTrue(result.startsWith("{") && result.endsWith("}"));
    // Should always have dependency and total_versions
    assertTrue(result.contains("\"dependency\""));
    assertTrue(result.contains("\"total_versions\""));
    // At least one of the type fields should be present
    boolean hasAnyType =
        result.contains("latest_stable")
            || result.contains("latest_rc")
            || result.contains("latest_beta")
            || result.contains("latest_alpha")
            || result.contains("latest_milestone")
            || result.contains("latest_snapshot");
    assertTrue(hasAnyType);
  }
}
