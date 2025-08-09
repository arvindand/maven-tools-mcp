package com.arvindand.mcp.maven;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.arvindand.mcp.maven.model.ToolResponse;

/**
 * Shared test utility methods for integration tests.
 *
 * @author Arvind Menon
 * @since 1.3.0
 */
public final class TestHelpers {

  private TestHelpers() {
    // Prevent instantiation of utility class
  }

  /**
   * Extracts success data from ToolResponse for test assertions.
   *
   * @param <T> the expected type of the success data
   * @param response the ToolResponse to extract data from
   * @return the success data
   * @throws AssertionError if the response is not a success response
   */
  @SuppressWarnings("unchecked")
  public static <T> T getSuccessData(ToolResponse response) {
    assertInstanceOf(
        ToolResponse.Success.class, response, "Expected success response but got: " + response);
    return (T) ((ToolResponse.Success<?>) response).data();
  }
}
