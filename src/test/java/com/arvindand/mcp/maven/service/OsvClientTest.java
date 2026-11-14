package com.arvindand.mcp.maven.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.arvindand.mcp.maven.config.CacheConstants;
import com.arvindand.mcp.maven.model.MavenCoordinate;
import com.arvindand.mcp.maven.service.VulnerabilityService.OsvResponse;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Verifies OSV batch request shape, order mapping and cache seeding without network access. */
class OsvClientTest {
  private final MavenCoordinate a = MavenCoordinate.of("g", "a", "1.0");
  private final MavenCoordinate b = MavenCoordinate.of("g", "b", "2.0");
  private final MavenCoordinate c = MavenCoordinate.of("g", "c", "3.0");
  private ConcurrentMapCacheManager caches;
  private MockRestServiceServer server;
  private OsvClient client;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder();
    server = MockRestServiceServer.bindTo(builder).build();
    caches = new ConcurrentMapCacheManager(CacheConstants.OSV_RESPONSES);
    client = new OsvClient(builder, caches);
  }

  @Test
  void mapsResultsByPositionAndSeedsCacheForCleanOnly() {
    server
        .expect(requestTo("https://api.osv.dev/v1/querybatch"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(
            content()
                .json(
                    """
                    {"queries":[
                      {"package":{"ecosystem":"Maven","name":"g:a"},"version":"1.0"},
                      {"package":{"ecosystem":"Maven","name":"g:b"},"version":"2.0"},
                      {"package":{"ecosystem":"Maven","name":"g:c"},"version":"3.0"}]}
                    """))
        .andRespond(
            withSuccess(
                """
                {"results":[{}, {"vulns":[{"id":"X","modified":"2024"}]}, {"next_page_token":"t"}]}
                """,
                MediaType.APPLICATION_JSON));

    assertThat(client.queryBatchClean(List.of(a, b, c))).containsExactly(a);
    assertThat(caches.getCache(CacheConstants.OSV_RESPONSES).get("g:a:1.0", OsvResponse.class))
        .isNotNull();
    assertThat(caches.getCache(CacheConstants.OSV_RESPONSES).get("g:b:2.0")).isNull();
    assertThat(caches.getCache(CacheConstants.OSV_RESPONSES).get("g:c:3.0")).isNull();
    server.verify();
  }

  @Test
  void doesNotResendCachedCoordinates() {
    caches.getCache(CacheConstants.OSV_RESPONSES).put("g:a:1.0", new OsvResponse(null, null));
    server
        .expect(requestTo("https://api.osv.dev/v1/querybatch"))
        .andExpect(content().json("{\"queries\":[{\"version\":\"2.0\"}]}"))
        .andRespond(withSuccess("{\"results\":[{}]}", MediaType.APPLICATION_JSON));

    assertThat(client.queryBatchClean(List.of(a, b))).containsExactly(b);
    server.verify();
  }

  @Test
  void misalignedResponseIsAnError() {
    server
        .expect(requestTo("https://api.osv.dev/v1/querybatch"))
        .andRespond(withSuccess("{\"results\":[{}]}", MediaType.APPLICATION_JSON));

    assertThatThrownBy(() -> client.queryBatchClean(List.of(a, b)))
        .isInstanceOf(IllegalStateException.class);
  }
}
