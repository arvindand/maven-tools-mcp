package com.arvindand.mcp.maven.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * Base interface for all MCP tool responses. This allows type-safe tool return types while
 * supporting both success and error responses.
 *
 * @author Arvind Menon
 * @since 1.3.0
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public sealed interface ToolResponse permits ToolResponse.Success, ToolResponse.Error {

  /** Success response containing the actual tool data. */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  record Success<T>(String status, T data) implements ToolResponse {
    public static <T> Success<T> of(T data) {
      return new Success<>("success", data);
    }
  }

  /** Error response with status and message. */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  record Error(String status, String message) implements ToolResponse {
    public static Error of(String message) {
      return new Error("error", message);
    }

    public static Error notFound(String message) {
      return new Error("not_found", message);
    }
  }
}
