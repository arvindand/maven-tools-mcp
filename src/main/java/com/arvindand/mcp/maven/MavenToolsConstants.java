package com.arvindand.mcp.maven;

/**
 * Centralized constants to reduce magic numbers across the codebase.
 *
 * <p>Keep this focused: only add values that are shared across multiple components.
 *
 * @author Arvind Menon
 * @since 2.0.0
 */
public final class MavenToolsConstants {

  private MavenToolsConstants() {
    // Prevent instantiation
  }

  // Batch operations
  public static final int MAX_CONCURRENT_REQUESTS = 10;
  public static final int DEFAULT_BATCH_TIMEOUT_SECONDS = 30;

  // Caching
  public static final int TIMESTAMP_CACHE_HOURS = 24;
  public static final int MAX_CACHE_SIZE = 10_000;

  // Security scoring weights
  public static final int SECURITY_WEIGHT_CRITICAL = 40;
  public static final int SECURITY_WEIGHT_HIGH = 25;
  public static final int SECURITY_WEIGHT_MEDIUM = 10;
  public static final int SECURITY_WEIGHT_LOW = 5;
}
