package com.arvindand.mcp.maven.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * HTTP client configuration with OkHttp for improved performance and HTTP/2 support.
 *
 * <p>Configures connection pooling, timeouts, and protocols for Maven Central API access.
 *
 * @author Arvind Menon
 * @since 1.5.0
 */
@Configuration
public class HttpClientConfig {

  @Bean
  OkHttpClient okHttpClient(
      @Value("${maven.central.connection-pool-size:50}") int poolSize,
      @Value("${maven.central.timeout:8s}") Duration timeout) {

    ConnectionPool connectionPool =
        new ConnectionPool(
            poolSize, // maxIdleConnections
            24, // keepAliveDuration
            TimeUnit.HOURS);

    return new OkHttpClient.Builder()
        .connectionPool(connectionPool)
        .connectTimeout(timeout)
        .readTimeout(timeout.plusSeconds(2))
        .writeTimeout(timeout)
        .protocols(List.of(Protocol.HTTP_2, Protocol.HTTP_1_1))
        .retryOnConnectionFailure(true)
        .build();
  }

  @Bean
  RestClient mavenCentralRestClient(OkHttpClient okHttpClient) {
    return RestClient.builder().build();
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
}
