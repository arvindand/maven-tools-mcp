package com.arvindand.mcp.maven.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.arvindand.mcp.maven.service.MavenCentralException;
import io.github.resilience4j.core.functions.Either;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/** Unit tests for the bounded retry predicate and backoff of the maven-central retry. */
class MavenCentralRetryConfigTest {

  private static HttpClientErrorException status(HttpStatus status, String retryAfter) {
    HttpHeaders headers = new HttpHeaders();
    if (retryAfter != null) {
      headers.add(HttpHeaders.RETRY_AFTER, retryAfter);
    }
    return HttpClientErrorException.create(
        status, status.getReasonPhrase(), headers, "x".getBytes(StandardCharsets.UTF_8), null);
  }

  @Test
  void retriesRateLimitingWithoutOrWithShortRetryAfter() {
    assertThat(MavenCentralRetryConfig.shouldRetry(status(HttpStatus.TOO_MANY_REQUESTS, null)))
        .isTrue();
    assertThat(MavenCentralRetryConfig.shouldRetry(status(HttpStatus.TOO_MANY_REQUESTS, "2")))
        .isTrue();
    assertThat(MavenCentralRetryConfig.shouldRetry(status(HttpStatus.TOO_MANY_REQUESTS, "3")))
        .isFalse();
  }

  @Test
  void throttledNotFoundBehavesLikeRateLimiting() {
    assertThat(MavenCentralRetryConfig.shouldRetry(throttled(54161))).isFalse();
    assertThat(MavenCentralRetryConfig.shouldRetry(throttled(3))).isFalse();
    assertThat(MavenCentralRetryConfig.shouldRetry(throttled(2))).isTrue();
    assertThat(
            MavenCentralRetryConfig.shouldRetry(
                new MavenCentralException(
                    MavenCentralException.Reason.INVALID_RESPONSE, "bad", null, null)))
        .isFalse();
    assertThat(MavenCentralRetryConfig.shouldRetry(status(HttpStatus.NOT_FOUND, "54161")))
        .isFalse();
  }

  private static MavenCentralException throttled(int seconds) {
    return new MavenCentralException(
        MavenCentralException.Reason.RATE_LIMITED, "limited", seconds, null);
  }

  @Test
  void retriesTransientFailuresButNeverNotFound() {
    assertThat(MavenCentralRetryConfig.shouldRetry(new ResourceAccessException("io"))).isTrue();
    assertThat(
            MavenCentralRetryConfig.shouldRetry(
                HttpServerErrorException.create(
                    HttpStatus.BAD_GATEWAY, "bad", new HttpHeaders(), new byte[0], null)))
        .isTrue();
    assertThat(MavenCentralRetryConfig.shouldRetry(status(HttpStatus.NOT_FOUND, null))).isFalse();
    assertThat(MavenCentralRetryConfig.shouldRetry(new IllegalArgumentException())).isFalse();
  }

  @Test
  void backoffIsExponentialAndBoundedAndRespectsShortRetryAfter() {
    var fn = MavenCentralRetryConfig.intervalFunction();
    Either<Throwable, Object> plain = Either.left(status(HttpStatus.TOO_MANY_REQUESTS, null));
    assertThat(fn.apply(1, plain)).isEqualTo(500L);
    assertThat(fn.apply(2, plain)).isEqualTo(1000L);
    Either<Throwable, Object> twoSeconds = Either.left(status(HttpStatus.TOO_MANY_REQUESTS, "2"));
    assertThat(fn.apply(1, twoSeconds)).isEqualTo(2000L);
    // Backoff never shrinks below the exponential schedule.
    Either<Throwable, Object> oneSecond = Either.left(status(HttpStatus.TOO_MANY_REQUESTS, "1"));
    assertThat(fn.apply(2, oneSecond)).isEqualTo(1000L);
    // worst case: 2 retries x 2s
    assertThat(fn.apply(1, twoSeconds) + fn.apply(2, twoSeconds)).isLessThanOrEqualTo(4000L);
  }
}
