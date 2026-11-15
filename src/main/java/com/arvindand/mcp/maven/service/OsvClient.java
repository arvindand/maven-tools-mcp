package com.arvindand.mcp.maven.service;

import com.arvindand.mcp.maven.model.MavenCoordinate;
import com.arvindand.mcp.maven.service.VulnerabilityService.OsvBatchRequest;
import com.arvindand.mcp.maven.service.VulnerabilityService.OsvBatchResponse;
import com.arvindand.mcp.maven.service.VulnerabilityService.OsvBatchResult;
import com.arvindand.mcp.maven.service.VulnerabilityService.OsvPackage;
import com.arvindand.mcp.maven.service.VulnerabilityService.OsvRequest;
import com.arvindand.mcp.maven.service.VulnerabilityService.OsvResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Caches successful OSV responses and exposes transport failures to the resilience boundary.
 *
 * @author Arvind Menon
 * @since 3.2.2
 */
@Service
public class OsvClient {
  /** Chunk size for querybatch requests; bulk scans are capped well below this. */
  static final int MAX_BATCH_QUERIES = 1000;

  private final RestClient client;
  private final CacheManager cacheManager;

  public OsvClient(RestClient.Builder builder, CacheManager cacheManager) {
    // The injected builder never carries repository credentials; only the Maven repository client
    // adds an authentication interceptor.
    client = builder.clone().baseUrl("https://api.osv.dev/v1").build();
    this.cacheManager = cacheManager;
  }

  private static String cacheKey(MavenCoordinate c) {
    return c.groupId() + ":" + c.artifactId() + ":" + c.version();
  }

  private Cache responseCache() {
    return cacheManager == null
        ? null
        : cacheManager.getCache(com.arvindand.mcp.maven.config.CacheConstants.OSV_RESPONSES);
  }

  /**
   * Prefilters coordinates through OSV's batch endpoint. A coordinate is reported clean only when
   * its batch result has no vulnerability ids and no pagination token; clean results seed the
   * {@code query} cache. Coordinates that already have a cached response are never sent.
   *
   * @param coordinates distinct, valid coordinates with versions
   * @return the coordinates confirmed to have no OSV records
   * @throws IllegalStateException when the response is missing or misaligned with the request
   */
  @CircuitBreaker(name = "osv")
  @RateLimiter(name = "osv")
  public Set<MavenCoordinate> queryBatchClean(List<MavenCoordinate> coordinates) {
    Cache cache = responseCache();
    List<MavenCoordinate> pending = new ArrayList<>();
    for (MavenCoordinate c : coordinates) {
      if (cache == null || cache.get(cacheKey(c)) == null) pending.add(c);
    }
    Set<MavenCoordinate> clean = new LinkedHashSet<>();
    for (int from = 0; from < pending.size(); from += MAX_BATCH_QUERIES) {
      List<MavenCoordinate> chunk =
          pending.subList(from, Math.min(pending.size(), from + MAX_BATCH_QUERIES));
      OsvBatchResponse response =
          client
              .post()
              .uri("/querybatch")
              .contentType(MediaType.APPLICATION_JSON)
              .body(
                  new OsvBatchRequest(
                      chunk.stream()
                          .map(
                              c ->
                                  new OsvRequest(
                                      new OsvPackage("Maven", c.groupId() + ":" + c.artifactId()),
                                      c.version()))
                          .toList()))
              .retrieve()
              .body(OsvBatchResponse.class);
      if (response == null
          || response.results() == null
          || response.results().size() != chunk.size()) {
        throw new IllegalStateException("OSV returned an incomplete batch response");
      }
      for (int i = 0; i < chunk.size(); i++) {
        OsvBatchResult result = response.results().get(i);
        boolean noVulns = result == null || result.vulns() == null || result.vulns().isEmpty();
        boolean noPage =
            result == null || result.nextPageToken() == null || result.nextPageToken().isBlank();
        if (result != null && noVulns && noPage) {
          clean.add(chunk.get(i));
          if (cache != null) cache.putIfAbsent(cacheKey(chunk.get(i)), new OsvResponse(null, null));
        }
      }
    }
    return clean;
  }

  /**
   * Queries OSV without repository credentials and rejects incomplete responses.
   *
   * @param coordinate complete Maven coordinate
   * @return the successful OSV response
   */
  @Cacheable(
      value = com.arvindand.mcp.maven.config.CacheConstants.OSV_RESPONSES,
      key = "#coordinate.groupId() + ':' + #coordinate.artifactId() + ':' + #coordinate.version()")
  @CircuitBreaker(name = "osv")
  @RateLimiter(name = "osv")
  public OsvResponse query(MavenCoordinate coordinate) {
    OsvResponse response =
        client
            .post()
            .uri("/query")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                new OsvRequest(
                    new OsvPackage("Maven", coordinate.groupId() + ":" + coordinate.artifactId()),
                    coordinate.version()))
            .retrieve()
            .body(OsvResponse.class);
    if (response == null
        || (response.nextPageToken() != null && !response.nextPageToken().isBlank())) {
      throw new IllegalStateException("OSV returned an incomplete response");
    }
    if (response.vulns() != null
        && response.vulns().stream()
            .anyMatch(
                vulnerability ->
                    vulnerability == null
                        || vulnerability.id() == null
                        || vulnerability.id().isBlank())) {
      throw new IllegalStateException("OSV returned an invalid vulnerability record");
    }
    return response;
  }
}
