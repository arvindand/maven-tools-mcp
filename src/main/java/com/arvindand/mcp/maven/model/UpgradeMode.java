package com.arvindand.mcp.maven.model;

/**
 * Controls which upgrades the {@code recommend_pom_upgrades} tool emits as deterministic actions.
 *
 * <ul>
 *   <li>{@link #MINOR_PATCH} (default) - only same-major minor / patch upgrades go to {@code
 *       deterministicActions}; majors are routed to {@code needsAttention}.
 *   <li>{@link #ALL} - majors also count as deterministic. Use with care; major upgrades almost
 *       always need human / LLM review.
 * </ul>
 *
 * @author Arvind Menon
 * @since 3.0.0
 */
public enum UpgradeMode {
  MINOR_PATCH,
  ALL
}
