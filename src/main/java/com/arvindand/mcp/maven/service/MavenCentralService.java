package com.arvindand.mcp.maven.service;

import com.arvindand.mcp.maven.config.MavenCentralProperties;
import com.arvindand.mcp.maven.model.MavenCoordinate;
import com.arvindand.mcp.maven.model.MavenSearchResponse;
import com.arvindand.mcp.maven.util.VersionComparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Service for interacting with Maven Central search API. Provides cached methods for retrieving
 * Maven artifact information.
 *
 * @author Arvind Menon
 * @since 0.1.0
 */
@Service
public class MavenCentralService {

  private static final Logger logger = LoggerFactory.getLogger(MavenCentralService.class);

  private final RestClient restClient;
  private final MavenCentralProperties properties;

  /**
   * Constructs a new MavenCentralService with the specified properties.
   *
   * @param properties the Maven Central API configuration properties
   */
  public MavenCentralService(MavenCentralProperties properties) {
    this.properties = properties;
    this.restClient =
        RestClient.builder()
            .baseUrl(properties.baseUrl())
            .defaultHeader(
                "User-Agent", //
                "Maven-Tools-MCP/0.1.0 (https://github.com/arvindand)")
            .build();
  }

  /**
   * Gets the latest version of a Maven artifact. Results are cached for improved performance.
   *
   * @param coordinate the Maven coordinate to search for
   * @return the latest version string, or null if not found
   * @throws MavenCentralException if there's an error communicating with Maven Central
   */
  @Cacheable(
      value = "maven-latest-versions",
      key =
          "#coordinate.groupId() + ':' + #coordinate.artifactId() + ':' + (#coordinate.packaging()"
              + " ?: 'jar')")
  public String getLatestVersion(MavenCoordinate coordinate) {
    logger.debug(
        "Fetching latest version for {}:{}", coordinate.groupId(), coordinate.artifactId());
    try {
      String query = buildQuery(coordinate, null);
      MavenSearchResponse response =
          restClient
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .queryParam("q", query)
                          .queryParam("core", "gav")
                          .queryParam("rows", properties.maxResults())
                          .queryParam("wt", "json")
                          .build())
              .retrieve()
              .body(MavenSearchResponse.class);
      if (response == null || response.response().docs().isEmpty()) {
        String errorMessage =
            String.format(
                "No versions found for artifact %s:%s",
                coordinate.groupId(), coordinate.artifactId());
        logger.warn(errorMessage);
        throw new MavenCentralException(errorMessage);
      }
      List<String> versions =
          response.response().docs().stream()
              .map(MavenSearchResponse.MavenArtifact::version)
              .distinct()
              .toList();

      String latest = VersionComparator.getLatest(versions.toArray(String[]::new));
      logger.debug(
          "Latest version for {}:{} is {}", coordinate.groupId(), coordinate.artifactId(), latest);
      return latest;

    } catch (RestClientResponseException e) {
      throw new MavenCentralException("Maven Central API error: " + e.getMessage(), e);
    }
  }

  /**
   * Checks if a specific version of a Maven artifact exists. Results are cached for improved
   * performance.
   *
   * @param coordinate the Maven coordinate to search for
   * @param version the specific version to check
   * @return true if the version exists, false otherwise
   * @throws MavenCentralException if there's an error communicating with Maven Central
   */
  @Cacheable(
      value = "maven-version-checks",
      key =
          "#coordinate.groupId() + ':' + #coordinate.artifactId() + ':' + #version + ':' +"
              + " (#coordinate.packaging() ?: 'jar')")
  public boolean checkVersionExists(MavenCoordinate coordinate, String version) {
    logger.debug(
        "Checking if version {} exists for {}:{}",
        version,
        coordinate.groupId(),
        coordinate.artifactId());

    try {
      String query = buildQuery(coordinate, version);
      MavenSearchResponse response =
          restClient
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .queryParam("q", query)
                          .queryParam("core", "gav")
                          .queryParam("rows", 1)
                          .queryParam("wt", "json")
                          .build())
              .retrieve()
              .body(MavenSearchResponse.class);
      boolean exists = response != null && !response.response().docs().isEmpty();
      logger.debug(
          "Version {} for {}:{} exists: {}",
          version,
          coordinate.groupId(),
          coordinate.artifactId(),
          exists);

      return exists;

    } catch (RestClientResponseException e) {
      throw new MavenCentralException("Maven Central API error: " + e.getMessage(), e);
    } catch (RuntimeException e) {
      throw new MavenCentralException("Failed to check version existence: " + e.getMessage(), e);
    }
  }

  /**
   * Gets all available versions for a Maven artifact. Results are cached for improved performance.
   *
   * @param coordinate the Maven coordinate to search for
   * @return list of all available versions, sorted by version comparator
   * @throws MavenCentralException if there's an error communicating with Maven Central
   */
  @Cacheable(
      value = "maven-all-versions",
      key =
          "#coordinate.groupId() + ':' + #coordinate.artifactId() + ':' + (#coordinate.packaging()"
              + " ?: 'jar')")
  public List<String> getAllVersions(MavenCoordinate coordinate) {
    logger.debug("Fetching all versions for {}:{}", coordinate.groupId(), coordinate.artifactId());
    try {
      String query = buildQuery(coordinate, null);
      MavenSearchResponse response =
          restClient
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .queryParam("q", query)
                          .queryParam("core", "gav")
                          .queryParam("rows", properties.maxResults())
                          .queryParam("wt", "json")
                          .build())
              .retrieve()
              .body(MavenSearchResponse.class);
      if (response == null || response.response().docs().isEmpty()) {
        String errorMessage =
            String.format(
                "No versions found for artifact %s:%s",
                coordinate.groupId(), coordinate.artifactId());
        logger.warn(errorMessage);
        throw new MavenCentralException(errorMessage);
      }
      List<String> versions =
          response.response().docs().stream()
              .map(MavenSearchResponse.MavenArtifact::version)
              .distinct()
              .sorted(new VersionComparator().reversed())
              .toList();

      logger.debug(
          "Found {} versions for {}:{}",
          versions.size(),
          coordinate.groupId(),
          coordinate.artifactId());
      return versions;

    } catch (RestClientResponseException e) {
      throw new MavenCentralException("Maven Central API error: " + e.getMessage(), e);
    } catch (RuntimeException e) {
      throw new MavenCentralException("Failed to fetch all versions: " + e.getMessage(), e);
    }
  }

  /**
   * Builds a Solr query string for Maven Central search.
   *
   * @param coordinate the Maven coordinate
   * @param version the specific version (optional)
   * @return the formatted query string
   */
  private String buildQuery(MavenCoordinate coordinate, String version) {
    StringBuilder query = new StringBuilder();
    query.append("g:\"").append(coordinate.groupId()).append("\"");
    query.append(" AND a:\"").append(coordinate.artifactId()).append("\"");

    if (version != null) {
      query.append(" AND v:\"").append(version).append("\"");
    }

    if (coordinate.packaging() != null) {
      query.append(" AND p:\"").append(coordinate.packaging()).append("\"");
    }

    return query.toString();
  }
}
