package com.arvindand.mcp.maven.service;

import static com.arvindand.mcp.maven.config.CacheConstants.MAVEN_ACCURATE_HISTORICAL_DATA;
import static com.arvindand.mcp.maven.config.CacheConstants.MAVEN_ALL_VERSIONS;
import static com.arvindand.mcp.maven.config.CacheConstants.MAVEN_VERSION_CHECKS;

import com.arvindand.mcp.maven.MavenToolsConstants;
import com.arvindand.mcp.maven.config.MavenCentralProperties;
import com.arvindand.mcp.maven.model.MavenArtifact;
import com.arvindand.mcp.maven.model.MavenCoordinate;
import com.arvindand.mcp.maven.model.MavenMetadata;
import com.arvindand.mcp.maven.model.license.LicenseInfo;
import com.arvindand.mcp.maven.util.VersionComparator;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

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

  private final ObjectProvider<MavenCentralService> selfProvider;

  // Cache for per-artifact timestamps (avoids repeated HEAD requests across calls)
  private final Cache<String, Long> timestampCache;

  public MavenCentralService(
      MavenCentralProperties properties,
      RestClient mavenCentralRestClient,
      ObjectProvider<MavenCentralService> selfProvider) {
    this.properties = properties;
    this.restClient = mavenCentralRestClient;
    this.xmlMapper = new XmlMapper();
    this.versionComparator = new VersionComparator();
    this.selfProvider = selfProvider;

    this.timestampCache =
        Caffeine.newBuilder()
            .maximumSize(MavenToolsConstants.MAX_CACHE_SIZE)
            .expireAfterWrite(Duration.ofHours(MavenToolsConstants.TIMESTAMP_CACHE_HOURS))
            .build();
  }

  /**
   * Gets the latest version for a Maven coordinate. Leverages cached results from getAllVersions()
   * for efficiency.
   *
   * @param coordinate the Maven coordinate
   * @return the latest version or null if not found
   */
  public String getLatestVersion(MavenCoordinate coordinate) {
    List<String> versions = self().getAllVersions(coordinate);
    return versions.isEmpty() ? null : versions.get(0);
  }

  /**
   * Checks if a specific version exists for a Maven coordinate.
   *
   * <p>Note: Returns false for both "version not found" and "service error" cases. Service errors
   * are logged at debug level. Transient failures are handled by resilience patterns on {@link
   * #fetchRepositoryMetadata}.
   *
   * @param coordinate the Maven coordinate
   * @param version the version to check
   * @return true if the version exists, false otherwise
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
  @CircuitBreaker(name = "maven-central", fallbackMethod = "getAllVersionsFallback")
  @Retry(name = "maven-central")
  @RateLimiter(name = "maven-central")
  public List<String> getAllVersions(MavenCoordinate coordinate) {
    return fetchAllVersionsInternal(coordinate);
  }

  @SuppressWarnings("unused") // Used via @CircuitBreaker fallbackMethod
  private List<String> getAllVersionsFallback(MavenCoordinate coordinate, Exception ex) {
    logger.warn(
        "Circuit breaker fallback for {}:{} - {}",
        coordinate.groupId(),
        coordinate.artifactId(),
        ex.getMessage());
    return Collections.emptyList();
  }

  /**
   * Gets all available versions with accurate timestamps for the most recent versions.
   *
   * @param coordinate the Maven coordinate
   * @return list of artifacts with accurate timestamp information for recent versions
   */
  public List<MavenArtifact> getAllVersionsWithTimestamps(MavenCoordinate coordinate) {
    return self()
        .getRecentVersionsWithAccurateTimestamps(coordinate, ACCURATE_TIMESTAMP_VERSION_LIMIT);
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
    List<String> allVersions = self().getAllVersions(coordinate);
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
    String cacheKey = coordinate.groupId() + ":" + coordinate.artifactId() + ":" + version;
    try {
      long timestamp =
          timestampCache.get(
              cacheKey,
              _ ->
                  restClient
                      .head()
                      .uri(pomUrl)
                      .retrieve()
                      .toBodilessEntity()
                      .getHeaders()
                      .getLastModified());

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
  @CircuitBreaker(name = "maven-central")
  @Retry(name = "maven-central")
  @RateLimiter(name = "maven-central")
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
    } catch (RestClientException | IOException e) {
      logger.debug(
          "Failed to fetch metadata for {}:{}: {}",
          coordinate.groupId(),
          coordinate.artifactId(),
          e.getMessage(),
          e);
      return Optional.empty();
    }
  }

  private MavenCentralService self() {
    return selfProvider.getObject();
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

  /**
   * Fetch license information from POM file.
   *
   * @param coordinate the Maven coordinate with version
   * @return list of licenses found in the POM, empty list if none found or error
   */
  public List<LicenseInfo> getLicenses(MavenCoordinate coordinate) {
    if (coordinate.version() == null || coordinate.version().isBlank()) {
      return List.of();
    }

    String pomUrl = buildPomUrl(coordinate, coordinate.version());

    try {
      String pomXml = restClient.get().uri(pomUrl).retrieve().body(String.class);
      if (pomXml == null || pomXml.isBlank()) {
        return List.of();
      }
      return parseLicensesFromPom(pomXml);
    } catch (Exception e) {
      logger.debug(
          "Could not fetch licenses for {}: {}", coordinate.toCoordinateString(), e.getMessage());
      return List.of();
    }
  }

  private static final Pattern LICENSE_PATTERN =
      Pattern.compile(
          "<license>\\s*<name>([^<]*)</name>(?:\\s*<url>([^<]*)</url>)?",
          Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

  /**
   * Parse license elements from POM XML using regex.
   *
   * @param pomXml POM XML content
   * @return list of parsed licenses
   */
  private List<LicenseInfo> parseLicensesFromPom(String pomXml) {
    List<LicenseInfo> licenses = new ArrayList<>();
    Matcher matcher = LICENSE_PATTERN.matcher(pomXml);

    while (matcher.find()) {
      String name = matcher.group(1).trim();
      String url = matcher.group(2) != null ? matcher.group(2).trim() : null;
      if (!name.isEmpty()) {
        licenses.add(LicenseInfo.fromPom(name, url));
      }
    }

    return licenses;
  }
}
