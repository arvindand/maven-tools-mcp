package com.arvindand.mcp.maven.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for JsonResponseService.
 *
 * @author Arvind Menon
 * @since 0.1.0
 */
class JsonResponseServiceTest {

  private JsonResponseService jsonResponseService;
  private ObjectMapper mockObjectMapper;
  private ObjectMapper realObjectMapper;

  @BeforeEach
  void setUp() {
    // Create real ObjectMapper for most tests
    realObjectMapper = new ObjectMapper();
    realObjectMapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    // Create mock ObjectMapper for error scenarios
    mockObjectMapper = mock(ObjectMapper.class);

    // Use real ObjectMapper by default
    jsonResponseService = new JsonResponseService(realObjectMapper);
  }

  @Test
  void testToJson_SimpleObject() {
    // Given
    Map<String, Object> testObject =
        Map.of("testField", "testValue", "numberField", 42, "booleanField", true);

    // When
    String result = jsonResponseService.toJson(testObject);

    // Then - Map keys are not converted to snake_case, only object properties
    assertThat(result).isNotNull();
    assertThat(result).contains("\"testField\":\"testValue\""); // Map keys stay as-is
    assertThat(result).contains("\"numberField\":42");
    assertThat(result).contains("\"booleanField\":true");
  }

  @Test
  void testToJson_ComplexObject() {
    // Given
    record TestRecord(String fieldName, int numberValue, Map<String, String> nestedMap) {}
    TestRecord testObject = new TestRecord("test", 123, Map.of("innerKey", "innerValue"));

    // When
    String result = jsonResponseService.toJson(testObject);

    // Then - Record properties get snake_case, but inner Map keys don't
    assertThat(result).isNotNull();
    assertThat(result).contains("\"field_name\":\"test\""); // snake_case conversion for record
    assertThat(result).contains("\"number_value\":123");
    assertThat(result).contains("\"nested_map\":");
    assertThat(result).contains("\"innerKey\":\"innerValue\""); // Map keys stay as-is
  }

  @Test
  void testToJson_NullObject() {
    // When
    String result = jsonResponseService.toJson(null);

    // Then
    assertThat(result).isEqualTo("null");
  }

  @Test
  void testToJson_SerializationError() throws JsonProcessingException {
    // Given - Setup mock to throw JsonProcessingException
    JsonResponseService serviceWithMock = new JsonResponseService(mockObjectMapper);
    when(mockObjectMapper.writeValueAsString(any()))
        .thenThrow(new JsonProcessingException("Test error") {});
    when(mockObjectMapper.setPropertyNamingStrategy(any())).thenReturn(mockObjectMapper);

    Object testObject = Map.of("test", "value");

    // When
    String result = serviceWithMock.toJson(testObject);

    // Then - Should return error response
    assertThat(result).contains("\"status\":\"error\"");
    assertThat(result).contains("\"message\":\"Error serializing response: Test error\"");
  }

  @Test
  void testCreateErrorResponse_SimpleMessage() {
    // Given
    String errorMessage = "Something went wrong";

    // When
    String result = jsonResponseService.createErrorResponse(errorMessage);

    // Then
    assertThat(result).isNotNull();
    assertThat(result).contains("\"status\":\"error\"");
    assertThat(result).contains("\"message\":\"Something went wrong\"");
  }

  @Test
  void testCreateErrorResponse_MessageWithQuotes() {
    // Given
    String errorMessage = "Error with \"quotes\" in message";

    // When
    String result = jsonResponseService.createErrorResponse(errorMessage);

    // Then
    assertThat(result).isNotNull();
    assertThat(result).contains("\"status\":\"error\"");
    assertThat(result).contains("\"message\":");
    // Should properly escape quotes
    assertThat(result).contains("\\\"quotes\\\"");
  }

  @Test
  void testCreateErrorResponse_EmptyMessage() {
    // Given
    String errorMessage = "";

    // When
    String result = jsonResponseService.createErrorResponse(errorMessage);

    // Then
    assertThat(result).isNotNull();
    assertThat(result).contains("\"status\":\"error\"");
    assertThat(result).contains("\"message\":\"\"");
  }

  @Test
  void testCreateErrorResponse_NullMessage() {
    // Given
    String errorMessage = null;

    // When
    String result = jsonResponseService.createErrorResponse(errorMessage);

    // Then
    assertThat(result).isNotNull();
    assertThat(result).contains("\"status\":\"error\"");
    assertThat(result).contains("\"message\":null");
  }

  @Test
  void testCreateErrorResponse_SerializationFailure() throws JsonProcessingException {
    // Given - Setup mock to fail on both writeValueAsString calls
    JsonResponseService serviceWithMock = new JsonResponseService(mockObjectMapper);
    when(mockObjectMapper.writeValueAsString(any()))
        .thenThrow(new JsonProcessingException("Mock error") {});
    when(mockObjectMapper.setPropertyNamingStrategy(any())).thenReturn(mockObjectMapper);

    String errorMessage = "Test error";

    // When
    String result = serviceWithMock.createErrorResponse(errorMessage);

    // Then - Should fall back to manual JSON construction
    assertThat(result).isEqualTo("{\"status\":\"error\",\"message\":\"Test error\"}");
  }

  @Test
  void testCreateErrorResponse_SerializationFailureWithQuotes() throws JsonProcessingException {
    // Given - Setup mock to fail and test quote escaping in fallback
    JsonResponseService serviceWithMock = new JsonResponseService(mockObjectMapper);
    when(mockObjectMapper.writeValueAsString(any()))
        .thenThrow(new JsonProcessingException("Mock error") {});
    when(mockObjectMapper.setPropertyNamingStrategy(any())).thenReturn(mockObjectMapper);

    String errorMessage = "Error with \"quotes\"";

    // When
    String result = serviceWithMock.createErrorResponse(errorMessage);

    // Then - Should fall back to manual JSON with escaped quotes
    assertThat(result)
        .isEqualTo("{\"status\":\"error\",\"message\":\"Error with \\\"quotes\\\"\"}");
  }

  @Test
  void testCreateNotFoundResponse_SimpleMessage() {
    // Given
    String message = "Resource not found";

    // When
    String result = jsonResponseService.createNotFoundResponse(message);

    // Then
    assertThat(result).isNotNull();
    assertThat(result).contains("\"status\":\"not_found\"");
    assertThat(result).contains("\"message\":\"Resource not found\"");
  }

  @Test
  void testCreateNotFoundResponse_MessageWithSpecialCharacters() {
    // Given
    String message = "Artifact 'org.test:test-artifact' not found in Maven Central";

    // When
    String result = jsonResponseService.createNotFoundResponse(message);

    // Then
    assertThat(result).isNotNull();
    assertThat(result).contains("\"status\":\"not_found\"");
    assertThat(result).contains("\"message\":");
    assertThat(result).contains("org.test:test-artifact");
  }

  @Test
  void testCreateNotFoundResponse_EmptyMessage() {
    // Given
    String message = "";

    // When
    String result = jsonResponseService.createNotFoundResponse(message);

    // Then
    assertThat(result).isNotNull();
    assertThat(result).contains("\"status\":\"not_found\"");
    assertThat(result).contains("\"message\":\"\"");
  }

  @Test
  void testCreateNotFoundResponse_NullMessage() {
    // Given
    String message = null;

    // When
    String result = jsonResponseService.createNotFoundResponse(message);

    // Then
    assertThat(result).isNotNull();
    assertThat(result).contains("\"status\":\"not_found\"");
    assertThat(result).contains("\"message\":null");
  }

  @Test
  void testCreateNotFoundResponse_SerializationFailure() throws JsonProcessingException {
    // Given - Setup mock to fail
    JsonResponseService serviceWithMock = new JsonResponseService(mockObjectMapper);
    when(mockObjectMapper.writeValueAsString(any()))
        .thenThrow(new JsonProcessingException("Mock error") {});
    when(mockObjectMapper.setPropertyNamingStrategy(any())).thenReturn(mockObjectMapper);

    String message = "Not found";

    // When
    String result = serviceWithMock.createNotFoundResponse(message);

    // Then - Should fall back to manual JSON construction
    assertThat(result).isEqualTo("{\"status\":\"not_found\",\"message\":\"Not found\"}");
  }

  @Test
  void testSnakeCasePropertyNaming() {
    // Given
    record TestRecord(String camelCaseField, String anotherCamelCase, String simple) {}
    TestRecord testObject = new TestRecord("value1", "value2", "value3");

    // When
    String result = jsonResponseService.toJson(testObject);

    // Then - Verify snake_case conversion
    assertThat(result).contains("\"camel_case_field\":\"value1\"");
    assertThat(result).contains("\"another_camel_case\":\"value2\"");
    assertThat(result).contains("\"simple\":\"value3\"");

    // Should not contain camelCase versions
    assertThat(result).doesNotContain("\"camelCaseField\":");
    assertThat(result).doesNotContain("\"anotherCamelCase\":");
  }

  @Test
  void testConsistentResponseFormat() {
    // Test that error and not_found responses have consistent structure
    String errorResponse = jsonResponseService.createErrorResponse("error message");
    String notFoundResponse = jsonResponseService.createNotFoundResponse("not found message");

    // Both should have status and message fields
    assertThat(errorResponse).contains("\"status\":");
    assertThat(errorResponse).contains("\"message\":");

    assertThat(notFoundResponse).contains("\"status\":");
    assertThat(notFoundResponse).contains("\"message\":");

    // Should be valid JSON structure
    assertThat(errorResponse).startsWith("{").endsWith("}");
    assertThat(notFoundResponse).startsWith("{").endsWith("}");
  }
}
