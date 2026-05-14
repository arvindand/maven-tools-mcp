package com.arvindand.mcp.maven.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * An entry that requires human or LLM judgment rather than a mechanical edit. The {@code
 * recommend_pom_upgrades} tool routes major upgrades, multi-BOM conflicts, and explicit overrides
 * here so the deterministic agent path stays clean while a Copilot- or Claude-driven review session
 * gets enough context to reason against the surrounding code (PR descriptions, CHANGELOG entries,
 * code comments) without needing follow-up {@code compare_dependency_versions} calls.
 *
 * <p>Each variant carries the Maven Central latest version where applicable, so an LLM has the
 * complete picture in a single tool round-trip.
 *
 * @author Arvind Menon
 * @since 3.0.0
 */
public sealed interface NeedsAttention
    permits NeedsAttention.MajorAvailable,
        NeedsAttention.Conflict,
        NeedsAttention.ExplicitOverride {

  /**
   * Discriminator field — {@code "major_available"}, {@code "conflict"}, or {@code
   * "explicit_override"}.
   */
  String kind();

  /**
   * A same-major upgrade isn't sufficient because a major version is available. The model can also
   * reason about staying on the current major — {@code currentMajorLatest} is the safe same-major
   * fallback.
   *
   * @param groupId the coordinate's groupId
   * @param artifactId the coordinate's artifactId
   * @param current the currently-resolved version
   * @param currentMajorLatest the highest stable version sharing {@code current}'s major (may equal
   *     {@code current} if already at the latest of that major)
   * @param latestStable the highest stable version on Maven Central regardless of major
   * @param source where the current version came from ({@code EXPLICIT}, {@code MANAGED}, {@code
   *     EXPLICIT_OVERRIDE}) — drives whether the model should propose editing the dep's version or
   *     the managing BOM's version
   * @param managedByCoordinate the BOM or parent coordinate that supplies {@code current} when
   *     {@code source} is {@code MANAGED} or {@code EXPLICIT_OVERRIDE}; null for {@code EXPLICIT}
   */
  record MajorAvailable(
      String groupId,
      String artifactId,
      String current,
      String currentMajorLatest,
      String latestStable,
      String source,
      String managedByCoordinate)
      implements NeedsAttention {

    @Override
    @JsonProperty("kind")
    public String kind() {
      return "major_available";
    }
  }

  /**
   * Multiple BOMs imported at the same level disagree on the version of this coordinate. Maven
   * first-declared semantics resolve the conflict deterministically, but the disagreement is almost
   * always worth a human / LLM look — pinning an explicit version is the typical fix.
   *
   * @param groupId the coordinate's groupId
   * @param artifactId the coordinate's artifactId
   * @param currentlyResolvesTo the version that wins the arbitration
   * @param currentlyManagedBy the BOM coordinate that supplies the winning version
   * @param candidates every BOM that wanted to manage this coordinate (the winner plus losing
   *     candidates); each entry includes the version that BOM would have supplied
   * @param latestOnCentral the latest stable version on Maven Central — full context for the LLM in
   *     one round-trip
   */
  record Conflict(
      String groupId,
      String artifactId,
      String currentlyResolvesTo,
      String currentlyManagedBy,
      List<Candidate> candidates,
      String latestOnCentral)
      implements NeedsAttention {

    @Override
    @JsonProperty("kind")
    public String kind() {
      return "conflict";
    }
  }

  /**
   * The POM declares an explicit version for a coordinate that some BOM or parent would otherwise
   * have managed. The override is preserved (the explicit value wins), but the existence of
   * competing managed candidates is worth surfacing so the model can decide whether the override is
   * still needed.
   *
   * @param groupId the coordinate's groupId
   * @param artifactId the coordinate's artifactId
   * @param currentExplicit the version explicitly declared in the POM
   * @param managingCandidates every BOM / parent that would have managed this coordinate if the
   *     explicit version weren't declared
   * @param latestOnCentral the latest stable version on Maven Central
   */
  record ExplicitOverride(
      String groupId,
      String artifactId,
      String currentExplicit,
      List<Candidate> managingCandidates,
      String latestOnCentral)
      implements NeedsAttention {

    @Override
    @JsonProperty("kind")
    public String kind() {
      return "explicit_override";
    }
  }

  /**
   * A candidate version that a BOM / parent would supply or did supply for a coordinate.
   *
   * @param version the version this candidate would supply (already interpolated)
   * @param managedByCoordinate the BOM / parent coordinate the candidate came from
   */
  record Candidate(String version, String managedByCoordinate) {}
}
