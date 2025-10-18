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
  public static McpError mavenCentralUnavailable(String message, int retryAfter) {
    return new McpError(
        EXTERNAL_SERVICE_UNAVAILABLE, message, Map.of("service", "Maven Central"), retryAfter);
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
}
