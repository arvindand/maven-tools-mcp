package com.arvindand.mcp.maven.config;

import com.arvindand.mcp.maven.util.BoundedResponseInterceptor;
import java.net.URI;
import java.net.http.HttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * HTTP client configuration backed by the JDK {@link HttpClient} for HTTP/2 support.
 *
 * <p>Configures timeouts and protocol for Maven Central and OSV API access, plus optional BASIC /
 * BEARER authentication for private repositories. Replaces the previous OkHttp transport, which
 * relied on Spring Framework's removed {@code OkHttp3ClientHttpRequestFactory}. Connection reuse is
 * managed internally by the JDK client; the application-level Resilience4j retry policy remains the
 * retry mechanism.
 *
 * @author Arvind Menon
 * @since 1.5.0
 */
@Configuration
public class HttpClientConfig {

  private static final Logger log = LoggerFactory.getLogger(HttpClientConfig.class);

  @Bean
  HttpClient mavenCentralHttpClient(MavenCentralProperties properties) {
    return HttpClient.newBuilder()
        .connectTimeout(properties.timeout())
        .version(HttpClient.Version.HTTP_2)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
  }

  @Bean
  RestClient.Builder restClientBuilder(
      MavenCentralProperties properties, HttpClient mavenCentralHttpClient) {
    JdkClientHttpRequestFactory requestFactory =
        new JdkClientHttpRequestFactory(mavenCentralHttpClient);
    // Preserve the previous read-timeout margin (connect timeout + 2s).
    requestFactory.setReadTimeout(properties.timeout().plusSeconds(2));

    return RestClient.builder()
        .requestFactory(requestFactory)
        .requestInterceptor(new BoundedResponseInterceptor());
  }

  @Bean
  RestClient mavenCentralRestClient(
      RestClient.Builder restClientBuilder, MavenCentralProperties properties) {
    RestClient.Builder repository = restClientBuilder.clone();
    if (properties.auth() != null
        && properties.auth().type() != MavenCentralProperties.Auth.AuthType.NONE) {
      repository.requestInterceptor(
          repositoryAuthInterceptor(properties.auth(), URI.create(properties.repositoryBaseUrl())));
      log.info("Repository authentication enabled (type={})", properties.auth().type().name());
    }
    return repository.build();
  }

  private ClientHttpRequestInterceptor repositoryAuthInterceptor(
      MavenCentralProperties.Auth auth, URI origin) {
    return (request, body, execution) -> {
      if (!sameOrigin(origin, request.getURI())) {
        throw new IllegalArgumentException(
            "Repository client cannot send credentials to another origin");
      }
      switch (auth.type()) {
        case BASIC -> request.getHeaders().setBasicAuth(auth.username(), auth.password());
        case BEARER -> request.getHeaders().setBearerAuth(auth.token());
        default -> {
          // NONE: anonymous access, no authorization header to add.
        }
      }
      return execution.execute(request, body);
    };
  }

  private static boolean sameOrigin(URI expected, URI actual) {
    return expected.getScheme().equalsIgnoreCase(actual.getScheme())
        && expected.getHost().equalsIgnoreCase(actual.getHost())
        && effectivePort(expected) == effectivePort(actual);
  }

  private static int effectivePort(URI uri) {
    if (uri.getPort() >= 0) {
      return uri.getPort();
    }
    return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
  }
}
