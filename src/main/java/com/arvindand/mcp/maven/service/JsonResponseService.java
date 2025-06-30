package com.arvindand.mcp.maven.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Service for converting objects to JSON responses with error handling.
 *
 * @author Arvind Menon
 * @since 0.1.0
 */
@Service
public class JsonResponseService {

  private static final Logger logger = LoggerFactory.getLogger(JsonResponseService.class);
  private final ObjectMapper objectMapper;

  public JsonResponseService(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
    this.objectMapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
  }

  /**
   * Converts an object to JSON string representation.
   *
   * @param object the object to serialize
   * @return JSON string representation
   */
  public String toJson(Object object) {
    try {
      return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(object);
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
    return createResponse("error", errorMessage);
  }

  /**
   * Creates a standardized not found response.
   *
   * @param message the not found message
   * @return JSON not found response
   */
  public String createNotFoundResponse(String message) {
    return createResponse("not_found", message);
  }

  private String createResponse(String status, String message) {
    try {
      return objectMapper.writeValueAsString(new Response(status, message));
    } catch (JsonProcessingException _) {
      return "{\"status\":\"%s\",\"message\":\"%s\"}".formatted(status, escapeJson(message));
    }
  }

  private String escapeJson(String input) {
    return input.replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
  }

  private record Response(String status, String message) {}
}
