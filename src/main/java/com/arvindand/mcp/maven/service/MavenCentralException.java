package com.arvindand.mcp.maven.service;

/**
 * Exception thrown when there are issues with Maven Central API interactions. This runtime
 * exception wraps various error conditions that can occur when communicating with Maven Central's
 * search API.
 *
 * @author Arvind Menon
 * @since 0.1.0
 */
public class MavenCentralException extends RuntimeException {

  /**
   * Constructs a new MavenCentralException with the specified detail message.
   *
   * @param message the detail message
   */
  public MavenCentralException(String message) {
    super(message);
  }

  /**
   * Constructs a new MavenCentralException with the specified detail message and cause.
   *
   * @param message the detail message
   * @param cause the cause of this exception
   */
  public MavenCentralException(String message, Throwable cause) {
    super(message, cause);
  }
}
