package com.arvindand.mcp.maven.model;

import java.util.Map;

/**
 * Structured error response for MCP tools following MCP specification patterns.
 *
 * <p>Provides error classification, human-readable messages, contextual data, and retry guidance
 * for better error handling and LLM interpretation.
 *
 * @param code Error classification code (INVALID_INPUT, EXTERNAL_SERVICE_UNAVAILABLE, etc.)
 * @param message Human-readable error description
 * @param data Additional contextual information (coordinate, expected format, service name, etc.)
 * @param retryAfter Suggested retry delay in seconds (null if no retry recommended)
 * @author Arvind Menon
 * @since 1.5.0
 */
public record McpError(String code, String message, Map<String, Object> data, Integer retryAfter) {

  // Error code constants
  public static final String INVALID_INPUT = "INVALID_INPUT";
  public static final String PARSE_ERROR = "PARSE_ERROR";
  public static final String EXTERNAL_SERVICE_UNAVAILABLE = "EXTERNAL_SERVICE_UNAVAILABLE";
  public static final String INTERNAL_ERROR = "INTERNAL_ERROR";
  public static final String SECURITY_CHECK_FAILED = "SECURITY_CHECK_FAILED";
  public static final String LICENSE_CHECK_FAILED = "LICENSE_CHECK_FAILED";
  public static final String RATE_LIMITED = "RATE_LIMITED";

  // Data field constants
  private static final String SUGGESTION = "suggestion";
  private static final String SERVICE_KEY = "service";
  private static final String MAVEN_CENTRAL = "Maven Central";

  /**
   * Create error for invalid input parameters.
   *
   * @param message Human-readable error description
   * @param data Contextual data about the invalid input
   * @return McpError with INVALID_INPUT code
   */
  public static McpError invalidInput(String message, Map<String, Object> data) {
    return new McpError(INVALID_INPUT, message, data, null);
  }

  /**
   * Create error for Maven coordinate parsing failures.
   *
   * @param coordinate The invalid coordinate string
   * @param expectedFormat Expected format description
   * @return McpError with PARSE_ERROR code and helpful context
   */
  public static McpError parseError(String coordinate, String expectedFormat) {
    return new McpError(
        PARSE_ERROR,
        "Invalid Maven coordinate format",
        Map.of(
            "coordinate", coordinate,
            "expected_format", expectedFormat,
            "example", "org.springframework.boot:spring-boot-starter"),
        null);
  }

  /**
   * Create error for Maven Central API unavailability.
   *
   * @param message Description of the failure
   * @param retryAfter Suggested retry delay in seconds
   * @return McpError with EXTERNAL_SERVICE_UNAVAILABLE code
   */
  public static McpError mavenCentralUnavailable(String message, Integer retryAfter) {
    return new McpError(
        EXTERNAL_SERVICE_UNAVAILABLE, message, Map.of(SERVICE_KEY, MAVEN_CENTRAL), retryAfter);
  }

  /**
   * The server's own time budget for a bulk call ran out before every dependency was answered.
   * Lookups already in flight finish and are cached, so an immediate retry makes progress.
   *
   * @return McpError with EXTERNAL_SERVICE_UNAVAILABLE code and reason BATCH_TIMEOUT
   */
  public static McpError batchTimeout() {
    return new McpError(
        EXTERNAL_SERVICE_UNAVAILABLE,
        "The request did not finish within its time budget: the batch is large or the repository"
            + " is slow. Completed lookups are cached, so retry now, or send fewer dependencies.",
        Map.of("reason", "BATCH_TIMEOUT"),
        2);
  }

  /**
   * Renders a retry delay for humans: seconds up to two minutes, then minutes, then hours.
   *
   * @param seconds delay in seconds
   * @return for example {@code "12 seconds"}, {@code "~5 min"} or {@code "~15h"}
   */
  public static String describeRetry(int seconds) {
    if (seconds < 120) {
      return seconds + " seconds";
    }
    if (seconds < 7200) {
      return "~" + Math.round(seconds / 60.0) + " min";
    }
    return "~" + Math.round(seconds / 3600.0) + "h";
  }

  private static String retryPhrase(int seconds) {
    return seconds < 120
        ? "Retry in " + seconds + " seconds."
        : "Retry in " + describeRetry(seconds) + " (" + seconds + " seconds).";
  }

  /**
   * Create error for unexpected internal errors.
   *
   * @param message Error description
   * @return McpError with INTERNAL_ERROR code
   */
  public static McpError internalError(String message) {
    return new McpError(INTERNAL_ERROR, message, Map.of(), null);
  }

  /**
   * Create error for vulnerability service failures.
   *
   * @param dependency The dependency that could not be scanned
   * @param reason Explanation of the failure
   * @return McpError with SECURITY_CHECK_FAILED code
   */
  public static McpError securityCheckError(String dependency, String reason) {
    return new McpError(
        SECURITY_CHECK_FAILED,
        "Could not check vulnerabilities: " + reason,
        Map.of("dependency", dependency, SUGGESTION, "Verify manually at https://osv.dev"),
        null);
  }

  /**
   * Create error for license extraction failures.
   *
   * @param dependency The dependency that could not be analyzed
   * @param reason Explanation of the failure
   * @return McpError with LICENSE_CHECK_FAILED code
   */
  public static McpError licenseCheckError(String dependency, String reason) {
    return new McpError(
        LICENSE_CHECK_FAILED,
        "Could not extract license: " + reason,
        Map.of(
            "dependency",
            dependency,
            SUGGESTION,
            "Check license manually in the dependency's POM file"),
        null);
  }

  /**
   * Create error for rate limit exceeded.
   *
   * @param service The service that rate limited the request
   * @return McpError with RATE_LIMITED code
   */
  public static McpError rateLimitError(String service) {
    return new McpError(
        RATE_LIMITED,
        service + " rate limit exceeded",
        Map.of(SUGGESTION, "Try again in a few seconds or reduce batch size"),
        5);
  }

  /**
   * Create error for HTTP 429 from the Maven repository (after bounded backoff was exhausted).
   *
   * @param retryAfterSeconds suggested retry delay in seconds
   * @return McpError with RATE_LIMITED code
   */
  public static McpError mavenCentralRateLimited(int retryAfterSeconds) {
    return new McpError(
        RATE_LIMITED,
        "Maven repository is rate limiting requests (HTTP 429). " + retryPhrase(retryAfterSeconds),
        Map.of(
            SERVICE_KEY,
            MAVEN_CENTRAL,
            SUGGESTION,
            "Wait and retry, reduce batch size, or point maven.central.repository-base-url at a"
                + " mirror or repository manager"),
        retryAfterSeconds);
  }

  /**
   * Create error for an open Maven repository circuit breaker.
   *
   * @param retryAfterSeconds suggested retry delay in seconds
   * @return McpError with EXTERNAL_SERVICE_UNAVAILABLE code
   */
  public static McpError mavenCentralCircuitOpen(int retryAfterSeconds) {
    return new McpError(
        EXTERNAL_SERVICE_UNAVAILABLE,
        "Maven repository temporarily unavailable (circuit open after repeated failures)."
            + " "
            + retryPhrase(retryAfterSeconds),
        Map.of(SERVICE_KEY, MAVEN_CENTRAL, "reason", "CIRCUIT_OPEN"),
        retryAfterSeconds);
  }
}
