package com.arvindand.mcp.maven.model;

import java.util.List;
import java.util.Objects;

/**
 * The {@code recommend_pom_upgrades} response shape.
 *
 * <p>Split into two lists with different consumers:
 *
 * <ul>
 *   <li>{@link #deterministicActions()} — applied mechanically by a non-LLM agent (e.g., the
 *       dogfood self-update workflow). Each action is a precise version-field or property edit the
 *       agent can make without judgment.
 *   <li>{@link #needsAttention()} — consumed by a human-driven LLM review (Copilot major-review
 *       mode, or a user prompt in Claude / Copilot). Each entry carries enough context for the
 *       model to reason about the right call against the surrounding code.
 * </ul>
 *
 * <p>The resolver's warnings are passed through so callers see every silent-drop site (unreachable
 * parents, unresolvable property placeholders, failed BOM fetches, parent depth cap, etc.).
 *
 * @param deterministicActions safe upgrades the agent can apply mechanically
 * @param needsAttention upgrades that need human / LLM review
 * @param warnings non-fatal issues surfaced during POM resolution
 * @author Arvind Menon
 * @since 3.0.0
 */
public record PomUpgradeRecommendation(
    List<UpgradeAction> deterministicActions,
    List<NeedsAttention> needsAttention,
    List<String> warnings) {

  public PomUpgradeRecommendation {
    Objects.requireNonNull(deterministicActions, "deterministicActions must not be null");
    Objects.requireNonNull(needsAttention, "needsAttention must not be null");
    Objects.requireNonNull(warnings, "warnings must not be null");
    deterministicActions = List.copyOf(deterministicActions);
    needsAttention = List.copyOf(needsAttention);
    warnings = List.copyOf(warnings);
  }
}
