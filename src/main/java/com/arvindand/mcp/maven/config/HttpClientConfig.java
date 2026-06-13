package com.arvindand.mcp.maven.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.RetryRegistry;
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
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
  }

  @Bean
  RestClient.Builder restClientBuilder(
      MavenCentralProperties properties, HttpClient mavenCentralHttpClient) {
    JdkClientHttpRequestFactory requestFactory =
        new JdkClientHttpRequestFactory(mavenCentralHttpClient);
    // Preserve the previous read-timeout margin (connect timeout + 2s).
    requestFactory.setReadTimeout(properties.timeout().plusSeconds(2));

    RestClient.Builder builder = RestClient.builder().requestFactory(requestFactory);

    if (properties.auth() != null
        && properties.auth().type() != MavenCentralProperties.Auth.AuthType.NONE) {
      builder.requestInterceptor(repositoryAuthInterceptor(properties.auth()));
      log.info("Repository authentication enabled (type={})", properties.auth().type().name());
    }

    return builder;
  }

  @Bean
  RestClient mavenCentralRestClient(RestClient.Builder restClientBuilder) {
    return restClientBuilder.build();
  }

  @Bean
  CircuitBreakerRegistry circuitBreakerRegistry() {
    return CircuitBreakerRegistry.ofDefaults();
  }

  @Bean
  RetryRegistry retryRegistry() {
    return RetryRegistry.ofDefaults();
  }

  @Bean
  RateLimiterRegistry rateLimiterRegistry() {
    return RateLimiterRegistry.ofDefaults();
  }

  private ClientHttpRequestInterceptor repositoryAuthInterceptor(MavenCentralProperties.Auth auth) {
    return (request, body, execution) -> {
      switch (auth.type()) {
        case BASIC -> request.getHeaders().setBasicAuth(auth.username(), auth.password());
        case BEARER -> request.getHeaders().setBearerAuth(auth.token());
        default -> {}
      }
      return execution.execute(request, body);
    };
  }
}
