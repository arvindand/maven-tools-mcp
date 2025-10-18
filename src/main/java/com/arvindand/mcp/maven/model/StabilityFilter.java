package com.arvindand.mcp.maven.model;

/**
 * Filter for controlling version stability preferences across MCP tools.
 *
 * <p>Provides unified parameter semantics for stability filtering, replacing the previous
 * inconsistent parameters (preferStable, stableOnly, onlyStableTargets).
 *
 * @author Arvind Menon
 * @since 1.5.0
 */
public enum StabilityFilter {

  /**
   * Include all version types (stable, RC, beta, alpha, milestone, snapshot).
   *
   * <p>Use when you want comprehensive version information regardless of stability.
   */
  ALL,

  /**
   * Only include production-ready stable versions (excludes RC, beta, alpha, milestone, snapshot).
   *
   * <p>Use when you need only proven, production-ready releases.
   */
  STABLE_ONLY,

  /**
   * Prioritize stable versions in results, but include other types if no stable exists.
   *
   * <p>Use when you prefer stable but want fallback options for libraries without stable releases.
   */
  PREFER_STABLE
}
