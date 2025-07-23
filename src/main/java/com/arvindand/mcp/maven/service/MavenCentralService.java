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
 * Service for interacting with Maven Central search API.
 *
 * @author Arvind Menon
 * @since 0.1.0
 */
@Service
public class MavenCentralService {

  private static final Logger logger = LoggerFactory.getLogger(MavenCentralService.class);
  private final RestClient restClient;
  private final MavenCentralProperties properties;

  public MavenCentralService(MavenCentralProperties properties) {
    this.properties = properties;
    this.restClient = createRestClient();
  }

  /**
   * Gets the latest version for a Maven coordinate.
   *
   * @param coordinate the Maven coordinate
   * @return the latest version or null if not found
   */
  @Cacheable(
      value = "maven-latest-versions",
      key =
          "#coordinate.groupId() + ':' + #coordinate.artifactId() + ':' + (#coordinate.packaging()"
              + " ?: 'jar')")
  public String getLatestVersion(MavenCoordinate coordinate) {
    List<String> versions = fetchVersions(coordinate, null, properties.maxResults());
    return versions.isEmpty() ? null : versions.get(0);
  }

  /**
   * Checks if a specific version exists for a Maven coordinate.
   *
   * @param coordinate the Maven coordinate
   * @param version the version to check
   * @return true if the version exists
   */
  @Cacheable(
      value = "maven-version-checks",
      key =
          "#coordinate.groupId() + ':' + #coordinate.artifactId() + ':' + #version + ':' +"
              + " (#coordinate.packaging() ?: 'jar')")
  public boolean checkVersionExists(MavenCoordinate coordinate, String version) {
    try {
      MavenSearchResponse response = searchMavenCentral(coordinate, version, 1);
      return response != null && !response.response().docs().isEmpty();
    } catch (RestClientResponseException e) {
      throw new MavenCentralException("Maven Central API error: " + e.getMessage(), e);
    }
  }

  /**
   * Gets all available versions for a Maven coordinate.
   *
   * @param coordinate the Maven coordinate
   * @return list of all versions, sorted by version descending
   */
  @Cacheable(
      value = "maven-all-versions",
      key =
          "#coordinate.groupId() + ':' + #coordinate.artifactId() + ':' + (#coordinate.packaging()"
              + " ?: 'jar')")
  public List<String> getAllVersions(MavenCoordinate coordinate) {
    return fetchVersions(coordinate, null, properties.maxResults());
  }

  /**
   * Gets all available versions with their timestamps for enhanced analysis.
   *
   * @param coordinate the Maven coordinate
   * @return list of artifacts with version and timestamp information
   */
  @Cacheable(
      value = "maven-versions-with-timestamps",
      key =
          "#coordinate.groupId() + ':' + #coordinate.artifactId() + ':' + (#coordinate.packaging()"
              + " ?: 'jar')")
  public List<MavenSearchResponse.MavenArtifact> getAllVersionsWithTimestamps(MavenCoordinate coordinate) {
    return fetchVersionsWithTimestamps(coordinate, null, properties.maxResults());
  }

  /**
   * Gets version information with timestamps for the specified number of recent versions.
   *
   * @param coordinate the Maven coordinate
   * @param maxVersions maximum number of versions to retrieve
   * @return list of artifacts with version and timestamp information
   */
  @Cacheable(
      value = "maven-recent-versions-with-timestamps",
      key =
          "#coordinate.groupId() + ':' + #coordinate.artifactId() + ':' + #maxVersions + ':' + (#coordinate.packaging()"
              + " ?: 'jar')")
  public List<MavenSearchResponse.MavenArtifact> getRecentVersionsWithTimestamps(
      MavenCoordinate coordinate, int maxVersions) {
    return fetchVersionsWithTimestamps(coordinate, null, maxVersions);
  }

  private List<String> fetchVersions(
      MavenCoordinate coordinate, String specificVersion, int maxResults) {
    try {
      MavenSearchResponse response = searchMavenCentral(coordinate, specificVersion, maxResults);

      if (response == null || response.response().docs().isEmpty()) {
        String errorMessage =
            "No versions found for artifact %s:%s"
                .formatted(coordinate.groupId(), coordinate.artifactId());
        logger.warn(errorMessage);
        throw new MavenCentralException(errorMessage);
      }

      return response.response().docs().stream()
          .map(MavenSearchResponse.MavenArtifact::version)
          .distinct()
          .sorted(new VersionComparator().reversed())
          .toList();

    } catch (RestClientResponseException e) {
      throw new MavenCentralException("Maven Central API error: " + e.getMessage(), e);
    }
  }

  private List<MavenSearchResponse.MavenArtifact> fetchVersionsWithTimestamps(
      MavenCoordinate coordinate, String specificVersion, int maxResults) {
    try {
      MavenSearchResponse response = searchMavenCentral(coordinate, specificVersion, maxResults);

      if (response == null || response.response().docs().isEmpty()) {
        String errorMessage =
            "No versions found for artifact %s:%s"
                .formatted(coordinate.groupId(), coordinate.artifactId());
        logger.warn(errorMessage);
        throw new MavenCentralException(errorMessage);
      }

      return response.response().docs().stream()
          .sorted((a, b) -> new VersionComparator().reversed().compare(a.version(), b.version()))
          .toList();

    } catch (RestClientResponseException e) {
      throw new MavenCentralException("Maven Central API error: " + e.getMessage(), e);
    }
  }

  private MavenSearchResponse searchMavenCentral(
      MavenCoordinate coordinate, String version, int rows) {
    String query = buildQuery(coordinate, version);

    return restClient
        .get()
        .uri(
            uriBuilder ->
                uriBuilder
                    .queryParam("q", query)
                    .queryParam("core", "gav")
                    .queryParam("rows", rows)
                    .queryParam("wt", "json")
                    .build())
        .retrieve()
        .body(MavenSearchResponse.class);
  }

  private String buildQuery(MavenCoordinate coordinate, String version) {
    StringBuilder query =
        new StringBuilder()
            .append("g:\"")
            .append(coordinate.groupId())
            .append("\"")
            .append(" AND a:\"")
            .append(coordinate.artifactId())
            .append("\"");

    if (version != null) {
      query.append(" AND v:\"").append(version).append("\"");
    }

    if (coordinate.packaging() != null) {
      query.append(" AND p:\"").append(coordinate.packaging()).append("\"");
    }

    return query.toString();
  }

  private RestClient createRestClient() {
    var requestFactory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
    int timeoutMs = (int) properties.timeout().toMillis();
    requestFactory.setConnectTimeout(timeoutMs);
    requestFactory.setReadTimeout(timeoutMs);

    return RestClient.builder()
        .baseUrl(properties.baseUrl())
        .defaultHeader("User-Agent", "Maven-Tools-MCP/1.1.0")
        .requestFactory(requestFactory)
        .build();
  }
}
