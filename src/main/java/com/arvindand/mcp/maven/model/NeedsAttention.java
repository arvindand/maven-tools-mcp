package com.arvindand.mcp.maven.model;

import com.arvindand.mcp.maven.model.security.SecurityAssessment;
import com.fasterxml.jackson.annotation.JsonInclude;
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
        NeedsAttention.ExplicitOverride,
        NeedsAttention.PropertyOverride,
        NeedsAttention.Vulnerable {

  /**
   * Discriminator field — {@code "major_available"}, {@code "conflict"}, {@code
   * "explicit_override"}, {@code "property_override"}, or {@code "vulnerable"}.
   */
  String kind();

  /** Coordinate groupId — shared across all variants. */
  String groupId();

  /** Coordinate artifactId — shared across all variants. */
  String artifactId();

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
   *     EXPLICIT_OVERRIDE}, {@code MANAGED_DECLARATION}, {@code PLUGIN_DEPENDENCY}, or {@code
   *     PLUGIN}) — drives whether the model should propose editing the dep's version, its owning
   *     declaration, or the managing BOM
   * @param managedByCoordinate the BOM or parent coordinate that supplies {@code current} when
   *     {@code source} is {@code MANAGED} or {@code EXPLICIT_OVERRIDE}; null for {@code EXPLICIT}
   *     {@code MANAGED_DECLARATION}, and {@code PLUGIN_DEPENDENCY}
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
   * The root POM pins a property that an ancestor defines and uses as a version (the Spring Boot
   * way of overriding a managed version). Never mechanical: a pin exists for a reason.
   *
   * @param groupId groupId of the first affected coordinate
   * @param artifactId artifactId of the first affected coordinate
   * @param propertyName the shadowing property, for example {@code snakeyaml.version}
   * @param pinnedValue the value pinned in the root POM
   * @param inheritedValue the value the nearest ancestor defines; null when unresolvable
   * @param definedIn the ancestor coordinate that defines the property
   * @param affects {@code groupId:artifactId} coordinates controlled by the property (capped)
   * @param affectedCount total number of coordinates controlled by the property
   * @param relation {@code behind_inherited} (the pin holds versions back), {@code
   *     ahead_of_inherited}, {@code equal_to_inherited} (the pin is redundant) or {@code unknown}
   * @param latestOnCentral latest stable version of the first affected artifact; null when the
   *     lookup failed
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  record PropertyOverride(
      String groupId,
      String artifactId,
      String propertyName,
      String pinnedValue,
      String inheritedValue,
      String definedIn,
      List<String> affects,
      int affectedCount,
      String relation,
      String latestOnCentral)
      implements NeedsAttention {

    public static final String BEHIND = "behind_inherited";
    public static final String AHEAD = "ahead_of_inherited";
    public static final String EQUAL = "equal_to_inherited";
    public static final String UNKNOWN = "unknown";

    @Override
    @JsonProperty("kind")
    public String kind() {
      return "property_override";
    }
  }

  /**
   * The currently-resolved version of a declared dependency has known vulnerabilities and no
   * deterministic action is guaranteed to fix it.
   *
   * @param groupId the coordinate's groupId
   * @param artifactId the coordinate's artifactId
   * @param current the effective version
   * @param source {@code EXPLICIT}, {@code MANAGED} or {@code EXPLICIT_OVERRIDE}
   * @param managedByCoordinate the BOM / parent supplying the version, when managed
   * @param maxSeverity highest severity among the findings
   * @param vulnerabilityCount number of findings
   * @param criticalIds critical vulnerability ids
   * @param highIds high severity vulnerability ids
   * @param fixedInVersion verified fixed version, when known
   * @param latestOnCentral latest stable version on Maven Central, when known
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Vulnerable(
      String groupId,
      String artifactId,
      String current,
      String source,
      String managedByCoordinate,
      SecurityAssessment.Severity maxSeverity,
      int vulnerabilityCount,
      List<String> criticalIds,
      List<String> highIds,
      String fixedInVersion,
      String latestOnCentral)
      implements NeedsAttention {

    @Override
    @JsonProperty("kind")
    public String kind() {
      return "vulnerable";
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
