package com.arvindand.mcp.maven.service;

import com.arvindand.mcp.maven.config.CacheConstants;
import com.arvindand.mcp.maven.model.MavenCoordinate;
import com.arvindand.mcp.maven.model.transitive.DepsDevGraph;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import java.util.Optional;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Fetches resolved dependency graphs from deps.dev. Successful graphs are cached; "not found" and
 * transport failures are not, and failures surface to the resilience boundary.
 *
 * <p>Built from the plain {@link RestClient.Builder}, so no repository credentials are ever sent to
 * deps.dev, and the builder's response-size limit applies.
 *
 * @author Arvind Menon
 * @since 3.3.0
 */
@Service
public class DepsDevClient {
  private final RestClient client;

  public DepsDevClient(RestClient.Builder builder) {
    client = builder.clone().baseUrl("https://api.deps.dev").build();
  }

  /**
   * Fetches the resolved dependency graph of one Maven version.
   *
   * @param coordinate complete coordinate (version required)
   * @return the graph, or empty when deps.dev does not know the version
   * @throws IllegalStateException when deps.dev returns an unusable response
   */
  @Cacheable(
      value = CacheConstants.DEPS_DEV_GRAPHS,
      key = "#coordinate.groupId() + ':' + #coordinate.artifactId() + ':' + #coordinate.version()",
      unless = "#result == null")
  @CircuitBreaker(name = "depsdev")
  @RateLimiter(name = "depsdev")
  public Optional<DepsDevGraph> fetchGraph(MavenCoordinate coordinate) {
    try {
      DepsDevGraph graph =
          client
              .get()
              .uri(
                  "/v3/systems/maven/packages/{name}/versions/{version}:dependencies",
                  coordinate.groupId() + ":" + coordinate.artifactId(),
                  coordinate.version())
              .accept(MediaType.APPLICATION_JSON)
              .retrieve()
              .body(DepsDevGraph.class);
      if (graph == null) {
        throw new IllegalStateException("deps.dev returned an empty response");
      }
      return Optional.of(graph);
    } catch (HttpClientErrorException.NotFound _) {
      return Optional.empty();
    }
  }
}
