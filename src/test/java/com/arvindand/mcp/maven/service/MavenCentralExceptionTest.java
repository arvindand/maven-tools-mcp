package com.arvindand.mcp.maven.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.arvindand.mcp.maven.model.McpError;
import com.arvindand.mcp.maven.service.MavenCentralException.Reason;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/** Unit tests for failure classification and {@code Retry-After} parsing. */
class MavenCentralExceptionTest {

  private static HttpClientErrorException tooMany(String retryAfter) {
    HttpHeaders headers = new HttpHeaders();
    if (retryAfter != null) {
      headers.add(HttpHeaders.RETRY_AFTER, retryAfter);
    }
    return HttpClientErrorException.create(
        HttpStatus.TOO_MANY_REQUESTS,
        "Too Many Requests",
        headers,
        "Your ip has exceeded rate limits".getBytes(StandardCharsets.UTF_8),
        StandardCharsets.UTF_8);
  }

  @Test
  void tooManyRequestsWithoutHeaderUsesDefault() {
    MavenCentralException e = MavenCentralException.classify(tooMany(null));
    assertThat(e.getReason()).isEqualTo(Reason.RATE_LIMITED);
    assertThat(e.getRetryAfterSeconds())
        .isEqualTo(MavenCentralException.DEFAULT_RATE_LIMIT_RETRY_SECONDS);
    assertThat(e.getMessage()).doesNotContain("exceeded rate limits");
  }

  @Test
  void tooManyRequestsReadsNumericRetryAfter() {
    assertThat(MavenCentralException.classify(tooMany("7")).getRetryAfterSeconds()).isEqualTo(7);
  }

  @Test
  void absurdRetryAfterIsCapped() {
    assertThat(MavenCentralException.classify(tooMany("86400")).getRetryAfterSeconds())
        .isEqualTo(MavenCentralException.MAX_RETRY_AFTER_SECONDS);
    assertThat(
            MavenCentralException.classify(tooMany("99999999999999999999")).getRetryAfterSeconds())
        .isEqualTo(MavenCentralException.DEFAULT_RATE_LIMIT_RETRY_SECONDS);
  }

  @Test
  void httpDateAndGarbageRetryAfterAreIgnored() {
    for (String value : new String[] {"Wed, 21 Oct 2026 07:28:00 GMT", "abc", "-3", ""}) {
      assertThat(MavenCentralException.classify(tooMany(value)).getRetryAfterSeconds())
          .as(value)
          .isEqualTo(MavenCentralException.DEFAULT_RATE_LIMIT_RETRY_SECONDS);
    }
  }

  @Test
  void parseRetryAfterHandlesNullHeaders() {
    assertThat(MavenCentralException.parseRetryAfterSeconds(null)).isEqualTo(-1);
    assertThat(MavenCentralException.parseRetryAfterSeconds(new HttpHeaders())).isEqualTo(-1);
  }

  @Test
  void circuitOpenIsClassified() {
    CircuitBreaker breaker = CircuitBreaker.ofDefaults("t");
    breaker.transitionToOpenState();
    MavenCentralException e =
        MavenCentralException.classify(
            CallNotPermittedException.createCallNotPermittedException(breaker));
    assertThat(e.getReason()).isEqualTo(Reason.CIRCUIT_OPEN);
    assertThat(e.getRetryAfterSeconds())
        .isEqualTo(MavenCentralException.DEFAULT_CIRCUIT_OPEN_RETRY_SECONDS);
  }

  @Test
  void localRateLimiterIsClassifiedAsRateLimited() {
    RequestNotPermitted ex =
        RequestNotPermitted.createRequestNotPermitted(RateLimiter.ofDefaults("t"));
    assertThat(MavenCentralException.classify(ex).getReason()).isEqualTo(Reason.RATE_LIMITED);
  }

  @Test
  void serverErrorsAndNetworkFailuresAreUpstreamErrors() {
    HttpServerErrorException server =
        HttpServerErrorException.create(
            HttpStatus.BAD_GATEWAY, "Bad Gateway", new HttpHeaders(), new byte[0], null);
    assertThat(MavenCentralException.classify(server).getReason()).isEqualTo(Reason.UPSTREAM_ERROR);
    assertThat(MavenCentralException.classify(server).getMessage()).contains("502");
    assertThat(
            MavenCentralException.classify(
                    new ResourceAccessException("timeout", new SocketTimeoutException("t")))
                .getReason())
        .isEqualTo(Reason.UPSTREAM_ERROR);
    assertThat(MavenCentralException.classify(new IOException("boom")).getReason())
        .isEqualTo(Reason.UPSTREAM_ERROR);
  }

  @Test
  void unknownAndNullFallBackToUpstreamError() {
    assertThat(MavenCentralException.classify(new IllegalStateException("x")).getReason())
        .isEqualTo(Reason.UPSTREAM_ERROR);
    assertThat(MavenCentralException.classify(null).getReason()).isEqualTo(Reason.UPSTREAM_ERROR);
  }

  @Test
  void wrappedCausesAreRecognisedAndMavenCentralExceptionIsReturnedAsIs() {
    RuntimeException wrapped = new RuntimeException("outer", tooMany("3"));
    assertThat(MavenCentralException.classify(wrapped).getReason()).isEqualTo(Reason.RATE_LIMITED);
    MavenCentralException own =
        new MavenCentralException(Reason.INVALID_RESPONSE, "bad", null, null);
    assertThat(MavenCentralException.classify(own)).isSameAs(own);
    assertThat(new MavenCentralException("legacy").getReason()).isEqualTo(Reason.UPSTREAM_ERROR);
  }

  @Test
  void mcpErrorFactoriesProduceReadableMessages() {
    McpError rate = McpError.mavenCentralRateLimited(12);
    assertThat(rate.code()).isEqualTo(McpError.RATE_LIMITED);
    assertThat(rate.message())
        .isEqualTo("Maven repository is rate limiting requests (HTTP 429). Retry in 12 seconds.");
    assertThat(rate.retryAfter()).isEqualTo(12);

    McpError open = McpError.mavenCentralCircuitOpen(30);
    assertThat(open.code()).isEqualTo(McpError.EXTERNAL_SERVICE_UNAVAILABLE);
    assertThat(open.message())
        .isEqualTo(
            "Maven repository temporarily unavailable (circuit open after repeated failures)."
                + " Retry in 30 seconds.");
    assertThat(open.retryAfter()).isEqualTo(30);
  }
}
