package com.arvindand.mcp.maven.service;

import static com.arvindand.mcp.maven.config.CacheConstants.*;

import com.arvindand.mcp.maven.config.MavenCentralProperties;
import com.arvindand.mcp.maven.model.MavenArtifact;
import com.arvindand.mcp.maven.model.MavenCoordinate;
import com.arvindand.mcp.maven.model.MavenMetadata;
import com.arvindand.mcp.maven.util.VersionComparator;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Service for interacting with Maven Central via direct repository metadata access. Fetches
 * maven-metadata.xml files directly for accurate version information.
 *
 * @author Arvind Menon
 * @since 0.1.0
 */
@Service
public class MavenCentralService {

  private static final Logger logger = LoggerFactory.getLogger(MavenCentralService.class);
  private static final String METADATA_FETCH_ERROR_MSG =
      "Repository metadata fetch failed for {}:{}";
  private static final int ACCURATE_TIMESTAMP_VERSION_LIMIT = 30;
  private final RestClient restClient;
  private final XmlMapper xmlMapper;
  private final MavenCentralProperties properties;
  private final VersionComparator versionComparator;
  private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

  public MavenCentralService(MavenCentralProperties properties) {
    this.properties = properties;
    this.restClient = createRestClient();
    this.xmlMapper = new XmlMapper();
    this.versionComparator = new VersionComparator();
  }

  /**
   * Gets the latest version for a Maven coordinate. Leverages cached results from getAllVersions()
   * for efficiency.
   *
   * @param coordinate the Maven coordinate
   * @return the latest version or null if not found
   */
  public String getLatestVersion(MavenCoordinate coordinate) {
    List<String> versions = getAllVersions(coordinate);
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
      value = MAVEN_VERSION_CHECKS,
      key =
          "#coordinate.groupId() + ':' + #coordinate.artifactId() + ':' + #version + ':' +"
              + " (#coordinate.packaging() ?: 'jar')")
  public boolean checkVersionExists(MavenCoordinate coordinate, String version) {
    try {
      Optional<MavenMetadata> metadata = fetchRepositoryMetadata(coordinate);
      if (metadata.isPresent() && metadata.get().hasValidVersioning()) {
        return metadata.get().versioning().getVersionStrings().contains(version);
      }
      return false;
    } catch (Exception e) {
      logger.debug(METADATA_FETCH_ERROR_MSG, coordinate.groupId(), coordinate.artifactId(), e);
      return false;
    }
  }

  /**
   * Gets all available versions for a Maven coordinate.
   *
   * @param coordinate the Maven coordinate
   * @return list of all versions, sorted by version descending
   */
  @Cacheable(
      value = MAVEN_ALL_VERSIONS,
      key =
          "#coordinate.groupId() + ':' + #coordinate.artifactId() + ':' + (#coordinate.packaging()"
              + " ?: 'jar')")
  public List<String> getAllVersions(MavenCoordinate coordinate) {
    return fetchAllVersionsInternal(coordinate);
  }

  /**
   * Gets all available versions with accurate timestamps for the most recent versions.
   *
   * @param coordinate the Maven coordinate
   * @return list of artifacts with accurate timestamp information for recent versions
   */
  public List<MavenArtifact> getAllVersionsWithTimestamps(MavenCoordinate coordinate) {
    return getRecentVersionsWithAccurateTimestamps(coordinate, ACCURATE_TIMESTAMP_VERSION_LIMIT);
  }

  /**
   * Gets version information with accurate timestamps for the specified number of recent versions.
   *
   * @param coordinate the Maven coordinate
   * @param maxVersions maximum number of versions to retrieve
   * @return list of recent artifacts with accurate timestamp information
   */
  @Cacheable(
      value = MAVEN_ACCURATE_HISTORICAL_DATA,
      key =
          "#coordinate.groupId() + ':' + #coordinate.artifactId() + ':' + #maxVersions + ':' +"
              + " (#coordinate.packaging() ?: 'jar')")
  public List<MavenArtifact> getRecentVersionsWithAccurateTimestamps(
      MavenCoordinate coordinate, int maxVersions) {
    List<String> allVersions = getAllVersions(coordinate);
    List<String> recentVersions = allVersions.stream().limit(maxVersions).toList();

    List<CompletableFuture<MavenArtifact>> futures =
        recentVersions.stream()
            .map(
                version ->
                    CompletableFuture.supplyAsync(
                        () -> fetchArtifactWithTimestamp(coordinate, version),
                        virtualThreadExecutor))
            .toList();

    return futures.stream()
        .map(CompletableFuture::join)
        .filter(java.util.Objects::nonNull)
        .sorted((a, b) -> versionComparator.reversed().compare(a.version(), b.version()))
        .toList();
  }

  private MavenArtifact fetchArtifactWithTimestamp(MavenCoordinate coordinate, String version) {
    String pomUrl = buildPomUrl(coordinate, version);
    try {
      long timestamp =
          restClient
              .head()
              .uri(pomUrl)
              .retrieve()
              .toBodilessEntity()
              .getHeaders()
              .getLastModified();

      return new MavenArtifact(
          coordinate.groupId() + ":" + coordinate.artifactId() + ":" + version,
          coordinate.groupId(),
          coordinate.artifactId(),
          version,
          coordinate.packaging() != null ? coordinate.packaging() : "jar",
          timestamp);
    } catch (Exception e) {
      logger.debug("Failed to fetch timestamp for {}:{}", pomUrl, e.getMessage());
      return null;
    }
  }

  /**
   * Internal method to fetch all versions without caching (used by cacheable public methods).
   *
   * @param coordinate the Maven coordinate
   * @return list of all versions, sorted by version descending
   */
  private List<String> fetchAllVersionsInternal(MavenCoordinate coordinate) {
    try {
      Optional<MavenMetadata> metadata = fetchRepositoryMetadata(coordinate);
      if (metadata.isPresent() && metadata.get().hasValidVersioning()) {
        List<String> versions = metadata.get().versioning().getVersionStrings();
        return versions.stream()
            .sorted(versionComparator.reversed())
            .limit(properties.maxResults())
            .toList();
      }
      return Collections.emptyList();
    } catch (Exception e) {
      logger.debug(METADATA_FETCH_ERROR_MSG, coordinate.groupId(), coordinate.artifactId(), e);
      return Collections.emptyList();
    }
  }

  /**
   * Fetches maven-metadata.xml from the repository for the given coordinate.
   *
   * @param coordinate the Maven coordinate
   * @return optional containing metadata if found and parseable
   */
  private Optional<MavenMetadata> fetchRepositoryMetadata(MavenCoordinate coordinate) {
    try {
      String metadataUrl = buildMetadataUrl(coordinate);
      logger.debug("Fetching metadata from: {}", metadataUrl);

      String xmlContent = restClient.get().uri(metadataUrl).retrieve().body(String.class);

      if (xmlContent != null && !xmlContent.trim().isEmpty()) {
        MavenMetadata metadata = xmlMapper.readValue(xmlContent, MavenMetadata.class);
        return Optional.of(metadata);
      }

      return Optional.empty();
    } catch (Exception e) {
      logger.debug(
          "Failed to fetch metadata for {}:{}: {}",
          coordinate.groupId(),
          coordinate.artifactId(),
          e.getMessage());
      return Optional.empty();
    }
  }

  /**
   * Builds the URL for maven-metadata.xml for the given coordinate.
   *
   * @param coordinate the Maven coordinate
   * @return the metadata URL
   */
  private String buildMetadataUrl(MavenCoordinate coordinate) {
    String groupPath = coordinate.groupId().replace('.', '/');
    return String.format(
        "%s/%s/%s/maven-metadata.xml",
        properties.repositoryBaseUrl(), groupPath, coordinate.artifactId());
  }

  private String buildPomUrl(MavenCoordinate coordinate, String version) {
    String groupPath = coordinate.groupId().replace('.', '/');
    return String.format(
        "%s/%s/%s/%s/%s-%s.pom",
        properties.repositoryBaseUrl(),
        groupPath,
        coordinate.artifactId(),
        version,
        coordinate.artifactId(),
        version);
  }

  private RestClient createRestClient() {
    var requestFactory = new SimpleClientHttpRequestFactory();
    int timeoutMs = (int) properties.timeout().toMillis();
    requestFactory.setConnectTimeout(timeoutMs);
    requestFactory.setReadTimeout(timeoutMs);

    return RestClient.builder()
        .baseUrl(properties.repositoryBaseUrl())
        .defaultHeader("User-Agent", "Maven-Tools-MCP/1.4.0")
        .requestFactory(requestFactory)
        .build();
  }
}
