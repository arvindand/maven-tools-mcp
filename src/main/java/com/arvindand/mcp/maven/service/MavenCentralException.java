package com.arvindand.mcp.maven.service;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Exception thrown when there are issues with Maven repository interactions.
 *
 * <p>Carries a machine-readable {@link Reason} so the tool layer can report precise errors without
 * string matching. Use {@link #classify(Throwable)} to turn any failure (HTTP 429, open circuit
 * breaker, timeouts, server errors, ...) into a {@code MavenCentralException}.
 *
 * @author Arvind Menon
 * @since 0.1.0
 */
public class MavenCentralException extends RuntimeException {

  /** Why a repository call failed. */
  public enum Reason {
    /** The repository (HTTP 429) or the local client-side limiter refused the request. */
    RATE_LIMITED,
    /** The {@code maven-central} circuit breaker is open after repeated failures. */
    CIRCUIT_OPEN,
    /** Server error, network failure, timeout or any other upstream problem. */
    UPSTREAM_ERROR,
    /** The repository answered but the content could not be used (empty or malformed XML). */
    INVALID_RESPONSE
  }

  /** Upper bound applied to any {@code Retry-After} value reported to callers. */
  public static final int MAX_RETRY_AFTER_SECONDS = 60;

  /** Retry hint used when a 429 carries no usable {@code Retry-After} header. */
  public static final int DEFAULT_RATE_LIMIT_RETRY_SECONDS = 5;

  /** Retry hint for an open circuit (matches the configured wait-duration-in-open-state). */
  public static final int DEFAULT_CIRCUIT_OPEN_RETRY_SECONDS = 30;

  private static final int DEFAULT_UPSTREAM_RETRY_SECONDS = 10;
  private static final int MAX_CAUSE_DEPTH = 10;

  private final transient Reason reason;
  private final transient Integer retryAfterSeconds;

  /**
   * Constructs an exception with reason {@link Reason#UPSTREAM_ERROR}.
   *
   * @param message the detail message
   */
  public MavenCentralException(String message) {
    this(Reason.UPSTREAM_ERROR, message, null, null);
  }

  /**
   * Constructs an exception with reason {@link Reason#UPSTREAM_ERROR}.
   *
   * @param message the detail message
   * @param cause the cause of this exception
   */
  public MavenCentralException(String message, Throwable cause) {
    this(Reason.UPSTREAM_ERROR, message, null, cause);
  }

  /**
   * Constructs an exception with an explicit reason.
   *
   * @param reason failure classification
   * @param message the detail message
   * @param retryAfterSeconds suggested retry delay, or null when none is known
   * @param cause the cause, may be null
   */
  public MavenCentralException(
      Reason reason, String message, Integer retryAfterSeconds, Throwable cause) {
    super(message, cause);
    this.reason = reason;
    this.retryAfterSeconds = retryAfterSeconds;
  }

  /**
   * Returns the failure classification.
   *
   * @return the reason, never null
   */
  public Reason getReason() {
    return reason;
  }

  /**
   * Returns the suggested retry delay in seconds.
   *
   * @return seconds to wait, or null when no hint is available
   */
  public Integer getRetryAfterSeconds() {
    return retryAfterSeconds;
  }

  /**
   * Classifies any failure raised by a repository call. Walks the cause chain, so wrapped
   * exceptions are recognised. A {@code MavenCentralException} is returned unchanged.
   *
   * @param failure the failure, may be null
   * @return a classified exception, never null
   */
  public static MavenCentralException classify(Throwable failure) {
    Throwable current = failure;
    for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
      MavenCentralException recognised = recognise(current, failure);
      if (recognised != null) {
        return recognised;
      }
      current = current.getCause() == current ? null : current.getCause();
    }
    String detail = failure == null ? "unknown error" : String.valueOf(failure.getMessage());
    return new MavenCentralException(
        Reason.UPSTREAM_ERROR,
        "Maven repository request failed: " + detail,
        DEFAULT_UPSTREAM_RETRY_SECONDS,
        failure);
  }

  private static MavenCentralException recognise(Throwable current, Throwable original) {
    if (current instanceof MavenCentralException mce) {
      return mce;
    }
    if (current instanceof HttpClientErrorException.TooManyRequests tooMany) {
      int seconds = parseRetryAfterSeconds(tooMany.getResponseHeaders());
      return new MavenCentralException(
          Reason.RATE_LIMITED,
          "Maven repository is rate limiting requests (HTTP 429)",
          seconds > 0 ? seconds : DEFAULT_RATE_LIMIT_RETRY_SECONDS,
          original);
    }
    if (current instanceof RequestNotPermitted) {
      return new MavenCentralException(
          Reason.RATE_LIMITED,
          "Local request rate limit reached for the Maven repository",
          1,
          original);
    }
    if (current instanceof CallNotPermittedException) {
      return new MavenCentralException(
          Reason.CIRCUIT_OPEN,
          "Maven repository temporarily unavailable (circuit open after repeated failures)",
          DEFAULT_CIRCUIT_OPEN_RETRY_SECONDS,
          original);
    }
    if (current instanceof HttpServerErrorException serverError) {
      return new MavenCentralException(
          Reason.UPSTREAM_ERROR,
          "Maven repository returned HTTP " + serverError.getStatusCode().value(),
          DEFAULT_UPSTREAM_RETRY_SECONDS,
          original);
    }
    if (current instanceof HttpClientErrorException clientError) {
      return new MavenCentralException(
          Reason.UPSTREAM_ERROR,
          "Maven repository returned HTTP " + clientError.getStatusCode().value(),
          null,
          original);
    }
    if (current instanceof ResourceAccessException || current instanceof IOException) {
      return new MavenCentralException(
          Reason.UPSTREAM_ERROR,
          "Maven repository unreachable or timed out",
          DEFAULT_UPSTREAM_RETRY_SECONDS,
          original);
    }
    return null;
  }

  /**
   * Parses a numeric {@code Retry-After} header (delta-seconds). The HTTP-date form and invalid
   * values are ignored.
   *
   * @param headers response headers, may be null
   * @return seconds between 0 and {@link #MAX_RETRY_AFTER_SECONDS}, or -1 when absent or unusable
   */
  public static int parseRetryAfterSeconds(HttpHeaders headers) {
    if (headers == null) {
      return -1;
    }
    String value = headers.getFirst(HttpHeaders.RETRY_AFTER);
    if (value == null) {
      return -1;
    }
    try {
      long seconds = Long.parseLong(value.trim());
      if (seconds < 0) {
        return -1;
      }
      return (int) Math.min(seconds, MAX_RETRY_AFTER_SECONDS);
    } catch (NumberFormatException _) {
      return -1;
    }
  }
}
