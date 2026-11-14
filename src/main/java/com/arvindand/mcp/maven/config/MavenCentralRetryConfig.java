package com.arvindand.mcp.maven.config;

import com.arvindand.mcp.maven.service.MavenCentralException;
import io.github.resilience4j.common.retry.configuration.RetryConfigCustomizer;
import io.github.resilience4j.core.IntervalBiFunction;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.core.functions.Either;
import java.time.Duration;
import java.util.function.Predicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Bounded retry policy for the {@code maven-central} Resilience4j retry instance.
 *
 * <p>YAML cannot express a {@code Retry-After}-aware wait, so the retry predicate and the interval
 * function live here and are applied on top of the YAML settings (max-attempts: 3). Retried:
 * network errors, 5xx, and HTTP 429 when the server asks for a short wait (or says nothing). Waits:
 * exponential 500ms, 1s; a {@code Retry-After} of up to 2s raises that wait to the requested value.
 * A {@code Retry-After} above 2s is not retried at all, because waiting would stall the 30s batch
 * deadline; the failure surfaces immediately as rate limited. Worst case added latency per call is
 * 4s.
 *
 * @since 3.2.5
 */
@Configuration(proxyBeanMethods = false)
public class MavenCentralRetryConfig {

  static final String INSTANCE_NAME = "maven-central";
  static final Duration BASE_WAIT = Duration.ofMillis(500);
  static final double MULTIPLIER = 2.0;

  /** Longest {@code Retry-After} the retry will honour; anything longer is not retried. */
  static final int MAX_HONOURED_RETRY_AFTER_SECONDS = 2;

  @Bean
  RetryConfigCustomizer mavenCentralRetryCustomizer() {
    Predicate<Throwable> predicate = MavenCentralRetryConfig::shouldRetry;
    return RetryConfigCustomizer.of(
        INSTANCE_NAME,
        builder -> builder.retryOnException(predicate).intervalBiFunction(intervalFunction()));
  }

  /**
   * Decides whether a failed attempt is retried.
   *
   * @param failure the failure of the attempt
   * @return true to retry
   */
  static boolean shouldRetry(Throwable failure) {
    if (failure instanceof HttpClientErrorException.TooManyRequests tooMany) {
      int retryAfter = MavenCentralException.parseRetryAfterSeconds(tooMany.getResponseHeaders());
      return retryAfter <= MAX_HONOURED_RETRY_AFTER_SECONDS;
    }
    return failure instanceof ResourceAccessException
        || failure instanceof HttpServerErrorException;
  }

  /**
   * Builds the wait function: exponential backoff, raised to a short {@code Retry-After}.
   *
   * @return interval function in milliseconds
   */
  static IntervalBiFunction<Object> intervalFunction() {
    IntervalFunction backoff = IntervalFunction.ofExponentialBackoff(BASE_WAIT, MULTIPLIER);
    return (attempt, outcome) -> waitMillis(backoff.apply(attempt), outcome);
  }

  private static long waitMillis(long backoffMillis, Either<Throwable, Object> outcome) {
    if (outcome != null
        && outcome.isLeft()
        && outcome.getLeft() instanceof HttpClientErrorException.TooManyRequests tooMany) {
      int retryAfter = MavenCentralException.parseRetryAfterSeconds(tooMany.getResponseHeaders());
      if (retryAfter > 0) {
        return Math.max(backoffMillis, Duration.ofSeconds(retryAfter).toMillis());
      }
    }
    return backoffMillis;
  }
}
