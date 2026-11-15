package com.arvindand.mcp.maven.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.arvindand.mcp.maven.config.CacheConfig;
import com.arvindand.mcp.maven.model.MavenCoordinate;
import com.arvindand.mcp.maven.model.transitive.DepsDevGraph;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** deps.dev client: URL shape, response mapping, 404 handling and cache behaviour. */
class DepsDevClientTest {
  private static final String BASE =
      "https://api.deps.dev/v3/systems/maven/packages/org.springframework.boot%3Aspring-boot-starter-web/versions/3.3.0:dependencies";
  private static final MavenCoordinate COORDINATE =
      MavenCoordinate.of("org.springframework.boot", "spring-boot-starter-web", "3.3.0");

  private static final String BODY =
      """
      {"nodes":[
        {"versionKey":{"system":"MAVEN","name":"org.springframework.boot:spring-boot-starter-web","version":"3.3.0"},"bundled":false,"relation":"SELF","errors":[]},
        {"versionKey":{"system":"MAVEN","name":"ch.qos.logback:logback-core","version":"1.5.6"},"bundled":false,"relation":"INDIRECT","errors":["boom"]}
      ],
      "edges":[{"fromNode":0,"toNode":1,"requirement":"1.5.6"}],
      "error":"",
      "somethingNew":true}
      """;

  private MockRestServiceServer server;

  private DepsDevClient client() {
    RestClient.Builder builder = RestClient.builder();
    server = MockRestServiceServer.bindTo(builder).build();
    return new DepsDevClient(builder);
  }

  @Test
  void encodesColonInPackageNameAndMapsResponse() {
    DepsDevClient client = client();
    server
        .expect(requestTo(BASE))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON));

    Optional<DepsDevGraph> graph = client.fetchGraph(COORDINATE);

    server.verify();
    assertThat(graph).isPresent();
    assertThat(graph.get().nodes()).hasSize(2);
    assertThat(graph.get().nodes().get(0).relation()).isEqualTo("SELF");
    assertThat(graph.get().nodes().get(1).versionKey().name())
        .isEqualTo("ch.qos.logback:logback-core");
    assertThat(graph.get().nodes().get(1).errors()).containsExactly("boom");
    assertThat(graph.get().edges()).containsExactly(new DepsDevGraph.Edge(0, 1));
  }

  @Test
  void notFoundIsEmptyNotAnError() {
    DepsDevClient client = client();
    server.expect(requestTo(BASE)).andRespond(withResourceNotFound());

    assertThat(client.fetchGraph(COORDINATE)).isEmpty();
  }

  @Test
  void serverErrorPropagates() {
    DepsDevClient client = client();
    server.expect(requestTo(BASE)).andRespond(withServerError());

    assertThatThrownBy(() -> client.fetchGraph(COORDINATE)).isInstanceOf(RuntimeException.class);
  }

  @Configuration
  @org.springframework.context.annotation.Import(CacheConfig.class)
  static class CachingConfig {
    static RestClient.Builder builder = RestClient.builder();

    @Bean
    DepsDevClient depsDevClient() {
      return new DepsDevClient(builder);
    }
  }

  @Test
  void cachesSuccessesButNotNotFoundOrFailures() {
    MockRestServiceServer mock = MockRestServiceServer.bindTo(CachingConfig.builder).build();
    try (var context = new AnnotationConfigApplicationContext(CachingConfig.class)) {
      DepsDevClient cached = context.getBean(DepsDevClient.class);

      // failure, then 404, then success: none of the first two may be cached.
      mock.expect(requestTo(BASE)).andRespond(withServerError());
      mock.expect(requestTo(BASE)).andRespond(withResourceNotFound());
      mock.expect(requestTo(BASE)).andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON));
      assertThatThrownBy(() -> cached.fetchGraph(COORDINATE)).isInstanceOf(RuntimeException.class);
      assertThat(cached.fetchGraph(COORDINATE)).isEmpty();
      assertThat(cached.fetchGraph(COORDINATE)).isPresent();

      // Served from cache: no further request is expected.
      assertThat(cached.fetchGraph(COORDINATE)).isPresent();
      mock.verify();
    }
  }
}
