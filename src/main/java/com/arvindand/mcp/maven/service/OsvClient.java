package com.arvindand.mcp.maven.service;

import com.arvindand.mcp.maven.model.MavenCoordinate;
import com.arvindand.mcp.maven.service.VulnerabilityService.OsvPackage;
import com.arvindand.mcp.maven.service.VulnerabilityService.OsvRequest;
import com.arvindand.mcp.maven.service.VulnerabilityService.OsvResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
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
  private final RestClient client;

  public OsvClient(RestClient.Builder builder) {
    client = builder.clone().baseUrl("https://api.osv.dev/v1/query").build();
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
