package com.arvindand.mcp.maven.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Service for handling JSON serialization of responses. Provides standardized JSON response
 * formatting for MCP tool responses with error handling and snake_case property naming.
 *
 * @author Arvind Menon
 * @since 0.1.0
 */
@Service
public class JsonResponseService {

  private static final Logger logger = LoggerFactory.getLogger(JsonResponseService.class);
  private final ObjectMapper objectMapper;

  /**
   * Constructs a new JsonResponseService with the specified ObjectMapper.
   *
   * @param objectMapper the Jackson ObjectMapper for JSON serialization
   */
  public JsonResponseService(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
    // Configure ObjectMapper to use snake_case for property names to match test expectations
    this.objectMapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
  }

  /**
   * Converts an object to JSON string.
   *
   * @param object the object to serialize
   * @return JSON string representation or error message
   */
  public String toJson(Object object) {
    try {
      return objectMapper.writeValueAsString(object);
    } catch (JsonProcessingException e) {
      logger.error("Error serializing object to JSON: {}", e.getMessage(), e);
      return createErrorResponse("Error serializing response: " + e.getMessage());
    }
  }

  /**
   * Creates a standardized error response.
   *
   * @param errorMessage the error message
   * @return JSON error response
   */
  public String createErrorResponse(String errorMessage) {
    try {
      return objectMapper.writeValueAsString(new ErrorResponse("error", errorMessage));
    } catch (JsonProcessingException _) {
      // Fallback to manual JSON if ObjectMapper fails
      return String.format(
          "{\"status\":\"error\",\"message\":\"%s\"}", errorMessage.replace("\"", "\\\""));
    }
  }

  /**
   * Creates a standardized not found response.
   *
   * @param message the not found message
   * @return JSON not found response
   */
  public String createNotFoundResponse(String message) {
    try {
      return objectMapper.writeValueAsString(new ErrorResponse("not_found", message));
    } catch (JsonProcessingException _) {
      return String.format(
          "{\"status\":\"not_found\",\"message\":\"%s\"}", message.replace("\"", "\\\""));
    }
  }

  /**
   * Error response record for standardized error formatting.
   *
   * @param status the response status
   * @param message the error message
   */
  private record ErrorResponse(String status, String message) {}
}
