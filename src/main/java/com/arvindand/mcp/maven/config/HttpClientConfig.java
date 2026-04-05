package com.arvindand.mcp.maven.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.ConnectionPool;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.OkHttp3ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * HTTP client configuration with OkHttp for improved performance and HTTP/2 support.
 *
 * <p>Configures connection pooling, timeouts, and protocols for Maven Central and OSV API access.
 * OkHttp3ClientHttpRequestFactory is deprecated but still functional; we use it for superior HTTP/2
 * support and connection pooling until Spring provides a better alternative.
 *
 * @author Arvind Menon
 * @since 1.5.0
 */
@Configuration
public class HttpClientConfig {

  private static final Logger log = LoggerFactory.getLogger(HttpClientConfig.class);

  @Bean
  OkHttpClient okHttpClient(
      MavenCentralProperties properties,
      @Value("${maven.central.connection-pool-size:50}") int poolSize,
      @Value("${maven.central.timeout:8s}") Duration timeout) {

    ConnectionPool connectionPool =
        new ConnectionPool(
            poolSize, // maxIdleConnections
            24, // keepAliveDuration
            TimeUnit.HOURS);

    OkHttpClient.Builder builder =
        new OkHttpClient.Builder()
            .connectionPool(connectionPool)
            .connectTimeout(timeout)
            .readTimeout(timeout.plusSeconds(2))
            .writeTimeout(timeout)
            .protocols(List.of(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .retryOnConnectionFailure(true);

    if (properties.auth() != null
        && properties.auth().type() != MavenCentralProperties.Auth.AuthType.NONE) {
      builder.addInterceptor(repositoryAuthInterceptor(properties.auth()));
      log.info("Repository authentication enabled (type={})", properties.auth().type().name());
    }

    return builder.build();
  }

  @Bean
  @SuppressWarnings("removal") // OkHttp3ClientHttpRequestFactory deprecated but still best option
  RestClient.Builder restClientBuilder(OkHttpClient okHttpClient) {
    return RestClient.builder().requestFactory(new OkHttp3ClientHttpRequestFactory(okHttpClient));
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

  private Interceptor repositoryAuthInterceptor(MavenCentralProperties.Auth auth) {
    return chain -> {
      okhttp3.Request original = chain.request();
      okhttp3.Request.Builder requestBuilder = original.newBuilder();

      switch (auth.type()) {
        case BASIC -> {
          String credentials =
              Base64.getEncoder()
                  .encodeToString((auth.username() + ":" + auth.password()).getBytes());
          requestBuilder.header("Authorization", "Basic " + credentials);
        }
        case BEARER -> requestBuilder.header("Authorization", "Bearer " + auth.token());
        default -> {}
      }

      return chain.proceed(requestBuilder.build());
    };
  }
}
