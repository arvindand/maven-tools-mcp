package com.arvindand.mcp.maven.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.arvindand.mcp.maven.config.HttpClientConfig;
import com.arvindand.mcp.maven.config.MavenCentralProperties;
import com.arvindand.mcp.maven.config.MavenCentralRetryConfig;
import com.arvindand.mcp.maven.model.MavenCoordinate;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.springboot.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import io.github.resilience4j.springboot.ratelimiter.autoconfigure.RateLimiterAutoConfiguration;
import io.github.resilience4j.springboot.retry.autoconfigure.RetryAutoConfiguration;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Exercises the real annotation stack (cache, circuit breaker, retry, rate limiter) of {@link
 * MavenCentralService} against a local HTTP server, using the retry/breaker/limiter settings from
 * the shipped {@code application.yaml}. No network access.
 */
class MavenCentralServiceResilienceTest {

  private static final String METADATA =
      "<metadata><versioning><versions><version>1.0</version><version>2.0</version>"
          + "</versions></versioning></metadata>";

  private HttpServer server;
  private final AtomicInteger hits = new AtomicInteger();
  private volatile IntFunction<Response> responder = n -> new Response(200, METADATA, null);

  record Response(int status, String body, String retryAfter) {}

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          Response r = responder.apply(hits.incrementAndGet());
          byte[] body = r.body().getBytes(StandardCharsets.UTF_8);
          if (r.retryAfter() != null) {
            exchange.getResponseHeaders().add("Retry-After", r.retryAfter());
          }
          exchange.sendResponseHeaders(r.status(), body.length == 0 ? -1 : body.length);
          if (body.length > 0) {
            exchange.getResponseBody().write(body);
          }
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Configuration(proxyBeanMethods = false)
  @EnableCaching
  @Import({HttpClientConfig.class, MavenCentralRetryConfig.class, MavenCentralService.class})
  static class TestConfig {
    @Bean
    CacheManager cacheManager() {
      return new ConcurrentMapCacheManager();
    }
  }

  private ApplicationContextRunner runner() {
    return new ApplicationContextRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withPropertyValues(
            "maven.central.repository-base-url=http://127.0.0.1:" + server.getAddress().getPort(),
            "maven.central.timeout=3s",
            "maven.central.max-results=100")
        .withConfiguration(
            AutoConfigurations.of(
                AopAutoConfiguration.class,
                RetryAutoConfiguration.class,
                CircuitBreakerAutoConfiguration.class,
                RateLimiterAutoConfiguration.class))
        .withUserConfiguration(TestConfig.class, PropsConfig.class);
  }

  @Configuration(proxyBeanMethods = false)
  @org.springframework.boot.context.properties.EnableConfigurationProperties(
      MavenCentralProperties.class)
  static class PropsConfig {}

  private static final MavenCoordinate COORD = MavenCoordinate.of("org.example", "lib");

  @Test
  void rateLimitedThenSuccessReturnsDataWithinRetryBudget() {
    responder =
        n -> n <= 1 ? new Response(429, "slow down", null) : new Response(200, METADATA, null);
    runner()
        .run(
            ctx -> {
              long start = System.nanoTime();
              List<String> versions = ctx.getBean(MavenCentralService.class).getAllVersions(COORD);
              Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
              assertThat(versions).containsExactly("2.0", "1.0");
              assertThat(hits).hasValue(2);
              assertThat(elapsed).isLessThan(Duration.ofSeconds(4));
            });
  }

  @Test
  void shortRetryAfterIsHonouredButBounded() {
    responder =
        n -> n <= 1 ? new Response(429, "slow down", "1") : new Response(200, METADATA, null);
    runner()
        .run(
            ctx -> {
              long start = System.nanoTime();
              ctx.getBean(MavenCentralService.class).getAllVersions(COORD);
              Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
              assertThat(hits).hasValue(2);
              assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofMillis(950));
              assertThat(elapsed).isLessThan(Duration.ofSeconds(4));
            });
  }

  @Test
  void persistentRateLimitingSurfacesRateLimitedReasonAfterBoundedAttempts() {
    responder = n -> new Response(429, "Your ip has exceeded rate limits", null);
    runner()
        .run(
            ctx -> {
              MavenCentralService service = ctx.getBean(MavenCentralService.class);
              long start = System.nanoTime();
              Throwable thrown = catchThrowable(() -> service.getAllVersions(COORD));
              Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
              MavenCentralException classified = MavenCentralException.classify(thrown);
              assertThat(classified.getReason())
                  .isEqualTo(MavenCentralException.Reason.RATE_LIMITED);
              assertThat(hits).hasValue(3);
              assertThat(elapsed).isLessThan(Duration.ofSeconds(4));
            });
  }

  @Test
  void longRetryAfterIsNotRetriedAndIsReportedWithItsValue() {
    responder = n -> new Response(429, "limited", "30");
    runner()
        .run(
            ctx -> {
              MavenCentralService service = ctx.getBean(MavenCentralService.class);
              Throwable thrown = catchThrowable(() -> service.getAllVersions(COORD));
              MavenCentralException classified = MavenCentralException.classify(thrown);
              assertThat(classified.getReason())
                  .isEqualTo(MavenCentralException.Reason.RATE_LIMITED);
              assertThat(classified.getRetryAfterSeconds()).isEqualTo(30);
              assertThat(hits).hasValue(1);
            });
  }

  @Test
  void rateLimitedResponsesAreNeverCached() {
    responder =
        n -> n <= 1 ? new Response(429, "limited", "30") : new Response(200, METADATA, null);
    runner()
        .run(
            ctx -> {
              MavenCentralService service = ctx.getBean(MavenCentralService.class);
              assertThatThrownBy(() -> service.getAllVersions(COORD)).isNotNull();
              assertThat(service.getAllVersions(COORD)).containsExactly("2.0", "1.0");
              assertThat(hits).hasValue(2);
              // Success is cached: no further request.
              service.getAllVersions(COORD);
              assertThat(hits).hasValue(2);
            });
  }

  @Test
  void pomFetchFailuresAreNeverCachedAndNotFoundIsEmptyWithoutRetry() {
    MavenCoordinate pom = MavenCoordinate.of("org.example", "lib", "1.0");
    responder =
        n -> n <= 1 ? new Response(429, "limited", "30") : new Response(200, "<project/>", null);
    runner()
        .run(
            ctx -> {
              MavenCentralService service = ctx.getBean(MavenCentralService.class);
              assertThatThrownBy(() -> service.fetchPomXml(pom)).isNotNull();
              assertThat(service.fetchPomXml(pom)).contains("<project/>");
              assertThat(hits).hasValue(2);
            });
  }

  @Test
  void notFoundIsEmptyNeverRetriedAndDoesNotCountAsBreakerFailure() {
    responder = n -> new Response(404, "", null);
    runner()
        .run(
            ctx -> {
              MavenCentralService service = ctx.getBean(MavenCentralService.class);
              for (int i = 0; i < 15; i++) {
                assertThat(service.fetchPomXml(MavenCoordinate.of("org.example", "lib", "v" + i)))
                    .isEmpty();
              }
              assertThat(hits).hasValue(15);
              assertThat(
                      ctx.getBean(CircuitBreakerRegistry.class)
                          .circuitBreaker("maven-central")
                          .getState())
                  .isEqualTo(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.CLOSED);
            });
  }

  @Test
  void notFoundWithRetryAfterIsRateLimitedNotMissingAndNeverCached() {
    MavenCoordinate pom = MavenCoordinate.of("org.example", "lib", "1.0");
    responder =
        n ->
            n <= 2
                ? new Response(404, "", "54161")
                : n == 3
                    ? new Response(200, METADATA, null)
                    : new Response(200, "<project/>", null);
    runner()
        .run(
            ctx -> {
              MavenCentralService service = ctx.getBean(MavenCentralService.class);
              Throwable versions = catchThrowable(() -> service.getAllVersions(COORD));
              MavenCentralException first = MavenCentralException.classify(versions);
              assertThat(first.getReason()).isEqualTo(MavenCentralException.Reason.RATE_LIMITED);
              assertThat(first.getRetryAfterSeconds()).isEqualTo(54161);
              // Long hint: surfaced immediately, no retry loop.
              assertThat(hits).hasValue(1);
              Throwable fetchPom = catchThrowable(() -> service.fetchPomXml(pom));
              assertThat(MavenCentralException.classify(fetchPom).getReason())
                  .isEqualTo(MavenCentralException.Reason.RATE_LIMITED);
              assertThat(hits).hasValue(2);
              // Nothing was cached: the following 200s return real data.
              assertThat(service.getAllVersions(COORD)).containsExactly("2.0", "1.0");
              assertThat(service.fetchPomXml(pom)).contains("<project/>");
              assertThat(hits).hasValue(4);
            });
  }

  @Test
  void throttledHeadRequestIsRateLimited() {
    responder = n -> new Response(404, "", "54161");
    runner()
        .run(
            ctx -> {
              MavenCentralService service = ctx.getBean(MavenCentralService.class);
              Throwable thrown =
                  catchThrowable(() -> service.fetchArtifactWithTimestamp(COORD, "1.0"));
              assertThat(MavenCentralException.classify(thrown).getReason())
                  .isEqualTo(MavenCentralException.Reason.RATE_LIMITED);
              assertThat(hits).hasValue(1);
            });
  }

  @Test
  void sustainedThrottledNotFoundOpensTheBreakerButPlainNotFoundDoesNot() {
    responder = n -> new Response(404, "", "54161");
    runner()
        .run(
            ctx -> {
              MavenCentralService service = ctx.getBean(MavenCentralService.class);
              for (int i = 0; i < 12; i++) {
                int idx = i;
                catchThrowable(
                    () -> service.fetchPomXml(MavenCoordinate.of("org.example", "lib", "v" + idx)));
              }
              assertThat(
                      ctx.getBean(CircuitBreakerRegistry.class)
                          .circuitBreaker("maven-central")
                          .getState())
                  .isEqualTo(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN);
              Throwable open = catchThrowable(() -> service.getAllVersions(COORD));
              assertThat(MavenCentralException.classify(open).getReason())
                  .isEqualTo(MavenCentralException.Reason.CIRCUIT_OPEN);
            });
  }

  @Test
  void plainNotFoundForMetadataStaysEmptyAndBreakerStaysClosed() {
    responder = n -> new Response(404, "", null);
    runner()
        .run(
            ctx -> {
              MavenCentralService service = ctx.getBean(MavenCentralService.class);
              for (int i = 0; i < 12; i++) {
                assertThat(service.getAllVersions(MavenCoordinate.of("org.example", "a" + i)))
                    .isEmpty();
              }
              assertThat(hits).hasValue(12);
              assertThat(
                      ctx.getBean(CircuitBreakerRegistry.class)
                          .circuitBreaker("maven-central")
                          .getState())
                  .isEqualTo(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.CLOSED);
            });
  }

  @Test
  void openCircuitIsClassifiedAsCircuitOpen() {
    responder = n -> new Response(429, "limited", "30");
    runner()
        .run(
            ctx -> {
              MavenCentralService service = ctx.getBean(MavenCentralService.class);
              ctx.getBean(CircuitBreakerRegistry.class)
                  .circuitBreaker("maven-central")
                  .transitionToOpenState();
              int before = hits.get();
              Throwable thrown = catchThrowable(() -> service.getAllVersions(COORD));
              assertThat(MavenCentralException.classify(thrown).getReason())
                  .isEqualTo(MavenCentralException.Reason.CIRCUIT_OPEN);
              assertThat(hits.get()).isEqualTo(before);
            });
  }

  private static Throwable catchThrowable(Runnable action) {
    try {
      action.run();
      return null;
    } catch (Throwable t) {
      return t;
    }
  }
}
