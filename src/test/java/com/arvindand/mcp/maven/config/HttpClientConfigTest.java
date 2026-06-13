package com.arvindand.mcp.maven.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.arvindand.mcp.maven.config.MavenCentralProperties.Auth;
import com.arvindand.mcp.maven.config.MavenCentralProperties.Auth.AuthType;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Verifies the JDK {@link HttpClient}-backed {@link HttpClientConfig}: repository authentication
 * headers, the configured timeout reaching the client, and a request following the same {@link
 * RestClient} call path used by {@code MavenCentralService}. Uses a local {@link HttpServer} on an
 * ephemeral port so the real interceptor and request factory are exercised without network access.
 */
class HttpClientConfigTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(8);

  private final HttpClientConfig config = new HttpClientConfig();
  private final List<String> capturedAuthHeaders = new CopyOnWriteArrayList<>();
  private HttpServer server;
  private String baseUrl;

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          // Records null when the header is absent.
          capturedAuthHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
          byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  private MavenCentralProperties props(Auth auth) {
    return new MavenCentralProperties(baseUrl, TIMEOUT, 100, auth);
  }

  private RestClient restClient(MavenCentralProperties properties) {
    HttpClient client = config.mavenCentralHttpClient(properties);
    return config.mavenCentralRestClient(config.restClientBuilder(properties, client));
  }

  private String get(MavenCentralProperties properties) {
    return restClient(properties).get().uri(baseUrl + "/metadata").retrieve().body(String.class);
  }

  @Test
  void noAuthorizationHeaderWhenAuthAbsent() {
    assertThat(get(props(null))).isEqualTo("ok");
    assertThat(capturedAuthHeaders).containsExactly((String) null);
  }

  @Test
  void noAuthorizationHeaderWhenAuthTypeNone() {
    assertThat(get(props(new Auth(AuthType.NONE, null, null, null)))).isEqualTo("ok");
    assertThat(capturedAuthHeaders).containsExactly((String) null);
  }

  @Test
  void basicAuthorizationHeaderForUsernameAndPassword() {
    get(props(new Auth(AuthType.BASIC, "user", "pass", null)));
    String expected =
        "Basic " + Base64.getEncoder().encodeToString("user:pass".getBytes(StandardCharsets.UTF_8));
    assertThat(capturedAuthHeaders).containsExactly(expected);
  }

  @Test
  void bearerAuthorizationHeaderForToken() {
    get(props(new Auth(AuthType.BEARER, null, null, "tok-123")));
    assertThat(capturedAuthHeaders).containsExactly("Bearer tok-123");
  }

  @Test
  void configuredTimeoutReachesHttpClient() {
    HttpClient client = config.mavenCentralHttpClient(props(null));
    assertThat(client.connectTimeout()).contains(TIMEOUT);
  }
}
