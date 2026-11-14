package com.arvindand.mcp.maven.service;

import static com.arvindand.mcp.maven.config.CacheConstants.MAVEN_ACCURATE_HISTORICAL_DATA;
import static com.arvindand.mcp.maven.config.CacheConstants.MAVEN_ALL_VERSIONS;
import static com.arvindand.mcp.maven.config.CacheConstants.MAVEN_POM_XML;
import static com.arvindand.mcp.maven.config.CacheConstants.MAVEN_VERSION_CHECKS;

import com.arvindand.mcp.maven.config.MavenCentralProperties;
import com.arvindand.mcp.maven.model.MavenArtifact;
import com.arvindand.mcp.maven.model.MavenCoordinate;
import com.arvindand.mcp.maven.model.Relocation;
import com.arvindand.mcp.maven.model.license.LicenseInfo;
import com.arvindand.mcp.maven.util.BoundedBatch;
import com.arvindand.mcp.maven.util.MavenCoordinateParser;
import com.arvindand.mcp.maven.util.VersionComparator;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import java.io.StringReader;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Semaphore;
import org.apache.maven.artifact.repository.metadata.Metadata;
import org.apache.maven.artifact.repository.metadata.io.xpp3.MetadataXpp3Reader;
import org.apache.maven.model.DistributionManagement;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
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
  private static final int ACCURATE_TIMESTAMP_VERSION_LIMIT = 30;

  /** Maximum number of {@code <parent>} hops followed when looking for inherited licenses. */
  static final int MAX_PARENT_DEPTH = 5;

  private final RestClient restClient;

  private final MavenCentralProperties properties;
  private final VersionComparator versionComparator;
  private final Semaphore timestampPermits = new Semaphore(10);

  private final ObjectProvider<MavenCentralService> selfProvider;

  public MavenCentralService(
      MavenCentralProperties properties,
      RestClient mavenCentralRestClient,
      ObjectProvider<MavenCentralService> selfProvider) {
    this.properties = properties;
    this.restClient = mavenCentralRestClient;

    this.versionComparator = new VersionComparator();
    this.selfProvider = selfProvider;
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
   * <p>Transient failures propagate so they are retried and never cached as missing versions.
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
    return self().getAllVersions(coordinate).contains(version);
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
  @CircuitBreaker(name = "maven-central")
  @Retry(name = "maven-central")
  @RateLimiter(name = "maven-central")
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

    return BoundedBatch.map(
            recentVersions,
            version -> self().fetchArtifactWithTimestamp(coordinate, version),
            timestampPermits,
            Duration.ofSeconds(30))
        .stream()
        .filter(java.util.Objects::nonNull)
        .toList();
  }

  /**
   * Fetches the publication timestamp through the same cache and resilience boundary as metadata.
   *
   * @param coordinate artifact coordinate
   * @param version version to inspect
   * @return artifact with the repository's last-modified timestamp
   */
  @Cacheable(
      value = com.arvindand.mcp.maven.config.CacheConstants.MAVEN_ARTIFACT_TIMESTAMPS,
      key = "#coordinate.toCoordinateString() + ':' + #version")
  @CircuitBreaker(name = "maven-central")
  @Retry(name = "maven-central")
  @RateLimiter(name = "maven-central")
  public MavenArtifact fetchArtifactWithTimestamp(MavenCoordinate coordinate, String version) {
    String pomUrl = buildPomUrl(coordinate, version);
    long timestamp;
    try {
      timestamp =
          restClient
              .head()
              .uri(java.net.URI.create(pomUrl))
              .retrieve()
              .toBodilessEntity()
              .getHeaders()
              .getLastModified();
    } catch (HttpClientErrorException.NotFound notFound) {
      if (MavenCentralException.hasRetryAfter(notFound.getResponseHeaders())) {
        throw MavenCentralException.throttledNotFound(notFound);
      }
      throw notFound;
    }
    return new MavenArtifact(
        coordinate.groupId() + ":" + coordinate.artifactId() + ":" + version,
        coordinate.groupId(),
        coordinate.artifactId(),
        version,
        coordinate.packaging() != null ? coordinate.packaging() : "jar",
        timestamp);
  }

  /**
   * Internal method to fetch all versions without caching (used by cacheable public methods).
   *
   * @param coordinate the Maven coordinate
   * @return list of all versions, sorted by version descending
   */
  private List<String> fetchAllVersionsInternal(MavenCoordinate coordinate) {
    MavenCoordinateParser.validateRepositoryCoordinate(coordinate);
    try {
      String xml =
          restClient
              .get()
              .uri(java.net.URI.create(buildMetadataUrl(coordinate)))
              .retrieve()
              .body(String.class);
      if (xml == null || xml.isBlank()) {
        throw new MavenCentralException(
            MavenCentralException.Reason.INVALID_RESPONSE,
            "Empty repository metadata response",
            null,
            null);
      }
      Metadata metadata = new MetadataXpp3Reader().read(new StringReader(xml));
      if (metadata.getVersioning() == null) {
        throw new MavenCentralException(
            MavenCentralException.Reason.INVALID_RESPONSE,
            "Repository metadata has no versioning",
            null,
            null);
      }
      return metadata.getVersioning().getVersions().stream()
          .distinct()
          .sorted(versionComparator.reversed())
          .toList();
    } catch (HttpClientErrorException.NotFound notFound) {
      if (MavenCentralException.hasRetryAfter(notFound.getResponseHeaders())) {
        throw MavenCentralException.throttledNotFound(notFound);
      }
      return List.of();
    } catch (java.io.IOException | org.codehaus.plexus.util.xml.pull.XmlPullParserException ex) {
      throw new MavenCentralException(
          MavenCentralException.Reason.INVALID_RESPONSE, "Invalid repository metadata", null, ex);
    }
  }

  /**
   * Fetches the raw POM XML for a Maven coordinate from the configured repository. Used by the POM
   * resolver (see {@link com.arvindand.mcp.maven.pom.MavenCentralPomFetcher}) to walk parent chains
   * and BOM imports.
   *
   * <p>Returns an empty {@link Optional} on 404. Transient failures propagate to callers and are
   * not cached as missing POMs.
   *
   * @param coordinate must have a non-null version
   */
  @Cacheable(
      value = MAVEN_POM_XML,
      key = "#coordinate.groupId() + ':' + #coordinate.artifactId() + ':' + #coordinate.version()")
  @CircuitBreaker(name = "maven-central")
  @Retry(name = "maven-central")
  @RateLimiter(name = "maven-central")
  public Optional<String> fetchPomXml(MavenCoordinate coordinate) {
    if (coordinate == null || coordinate.version() == null || coordinate.version().isBlank()) {
      throw new IllegalArgumentException("coordinate.version() must be set to fetch a POM");
    }
    String url = buildPomUrl(coordinate, coordinate.version());
    try {
      String xml = restClient.get().uri(java.net.URI.create(url)).retrieve().body(String.class);
      return Optional.ofNullable(xml);
    } catch (HttpClientErrorException.NotFound notFound) {
      if (MavenCentralException.hasRetryAfter(notFound.getResponseHeaders())) {
        // A 404 with Retry-After is CDN throttling, not a missing POM: never cache it as missing.
        throw MavenCentralException.throttledNotFound(notFound);
      }
      // A plain 404 is a legitimate "POM doesn't exist for this coord" — not transient,
      // don't retry, don't trip the circuit breaker.
      logger.debug("POM not found for {}", coordinate.toCoordinateString());
      return Optional.empty();
    } catch (RestClientException ex) {
      // Transient (5xx, network, timeout). Let @Retry + @CircuitBreaker handle it.
      logger.debug(
          "POM fetch failed for {} (rethrowing for resilience4j): {}",
          coordinate.toCoordinateString(),
          ex.getMessage());
      throw ex;
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
    MavenCoordinateParser.validateRepositoryCoordinate(coordinate);
    String groupPath = coordinate.groupId().replace('.', '/');
    return String.format(
        "%s/%s/%s/maven-metadata.xml",
        properties.repositoryBaseUrl(), groupPath, coordinate.artifactId());
  }

  private String buildPomUrl(MavenCoordinate coordinate, String version) {
    MavenCoordinateParser.validateRepositoryCoordinate(
        MavenCoordinate.of(coordinate.groupId(), coordinate.artifactId(), version));
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
   * Fetch license information for an artifact version. When the artifact's own POM declares no
   * licenses, the {@code <parent>} chain is followed (bounded by {@link #MAX_PARENT_DEPTH}, cycle
   * safe) and the first non-empty license list is returned, mirroring Maven's inheritance (e.g.
   * commons-lang3 inherits Apache-2.0 from commons-parent / apache).
   *
   * @param coordinate the Maven coordinate with version
   * @return licenses found, empty list if none found or on any error (never throws)
   */
  public List<LicenseInfo> getLicenses(MavenCoordinate coordinate) {
    if (coordinate.version() == null || coordinate.version().isBlank()) {
      return List.of();
    }

    try {
      Set<String> visited = new HashSet<>();
      MavenCoordinate current = coordinate;
      for (int depth = 0; depth <= MAX_PARENT_DEPTH && current != null; depth++) {
        if (!visited.add(
            current.groupId() + ":" + current.artifactId() + ":" + current.version())) {
          break;
        }
        String pomXml = self().fetchPomXml(current).orElse(null);
        if (pomXml == null || pomXml.isBlank()) {
          return List.of();
        }
        Model model = new MavenXpp3Reader().read(new StringReader(pomXml));
        List<LicenseInfo> licenses = toLicenseInfos(model);
        if (!licenses.isEmpty()) {
          return licenses;
        }
        current = parentCoordinate(model);
      }
      return List.of();
    } catch (Exception e) {
      logger.debug(
          "Could not fetch licenses for {}: {}", coordinate.toCoordinateString(), e.getMessage());
      return List.of();
    }
  }

  private static MavenCoordinate parentCoordinate(Model model) {
    Parent parent = model.getParent();
    if (parent == null
        || isBlank(parent.getGroupId())
        || isBlank(parent.getArtifactId())
        || isBlank(parent.getVersion())) {
      return null;
    }
    return MavenCoordinate.of(
        parent.getGroupId().trim(), parent.getArtifactId().trim(), parent.getVersion().trim());
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  /**
   * Looks up a relocation published by the given artifact version, i.e. the POM's {@code
   * distributionManagement/relocation} (for example {@code mysql:mysql-connector-java:8.0.33}
   * relocates to {@code com.mysql:mysql-connector-j}).
   *
   * <p>Per Maven semantics an omitted groupId/artifactId in the relocation defaults to the original
   * coordinate's value; an omitted version is returned as null and means "same version".
   *
   * <p>Returns empty when the POM has no relocation, when the POM does not exist (404), or when the
   * relocation points to the artifact itself. Upstream failures (429, 5xx, open circuit, malformed
   * POM) propagate as the underlying exception, exactly like {@link #fetchPomXml}; callers can use
   * {@link MavenCentralException#classify(Throwable)} to turn them into a precise error.
   *
   * @param coordinateWithVersion coordinate with a non-blank version
   * @return the relocation target, or empty
   * @throws IllegalArgumentException if the version is missing
   */
  public Optional<Relocation> findRelocation(MavenCoordinate coordinateWithVersion) {
    String pomXml = self().fetchPomXml(coordinateWithVersion).orElse(null);
    if (pomXml == null || pomXml.isBlank()) {
      return Optional.empty();
    }
    Model model;
    try {
      model = new MavenXpp3Reader().read(new StringReader(pomXml));
    } catch (java.io.IOException | org.codehaus.plexus.util.xml.pull.XmlPullParserException ex) {
      throw new MavenCentralException(
          MavenCentralException.Reason.INVALID_RESPONSE, "Invalid POM XML", null, ex);
    }
    DistributionManagement distribution = model.getDistributionManagement();
    if (distribution == null || distribution.getRelocation() == null) {
      return Optional.empty();
    }
    org.apache.maven.model.Relocation raw = distribution.getRelocation();
    String groupId =
        isBlank(raw.getGroupId()) ? coordinateWithVersion.groupId() : raw.getGroupId().trim();
    String artifactId =
        isBlank(raw.getArtifactId())
            ? coordinateWithVersion.artifactId()
            : raw.getArtifactId().trim();
    String version = isBlank(raw.getVersion()) ? null : raw.getVersion().trim();
    String message = isBlank(raw.getMessage()) ? null : raw.getMessage().trim();
    boolean sameCoordinates =
        groupId.equals(coordinateWithVersion.groupId())
            && artifactId.equals(coordinateWithVersion.artifactId());
    if (sameCoordinates && (version == null || version.equals(coordinateWithVersion.version()))) {
      return Optional.empty();
    }
    return Optional.of(new Relocation(groupId, artifactId, version, message));
  }

  /**
   * Convenience: checks whether the newest published version of an artifact carries a relocation.
   * Uses {@link #getAllVersions} (cached) then {@link #findRelocation}.
   *
   * @param coordinate coordinate without version
   * @return the relocation of the newest version, or empty (also when the artifact is unknown)
   */
  public Optional<Relocation> findRelocationOfLatest(MavenCoordinate coordinate) {
    String latest = getLatestVersion(coordinate);
    if (latest == null) {
      return Optional.empty();
    }
    return findRelocation(
        MavenCoordinate.of(coordinate.groupId(), coordinate.artifactId(), latest));
  }

  private List<LicenseInfo> toLicenseInfos(Model model) {
    return model.getLicenses().stream()
        .filter(license -> license.getName() != null && !license.getName().isBlank())
        .map(license -> LicenseInfo.fromPom(license.getName().trim(), license.getUrl()))
        .toList();
  }
}
