package com.arvindand.mcp.maven.model;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Response for dependency age analysis with optional Context7 guidance hints.
 *
 * @param dependency the dependency coordinate
 * @param latestVersion the latest version analyzed
 * @param ageClassification age classification (fresh/current/aging/stale)
 * @param daysSinceLastRelease days since the latest version was released
 * @param lastReleaseDate when the latest version was released
 * @param ageDescription human-readable age description
 * @param recommendation suggested action based on age analysis
 * @param context7Guidance optional Context7 guidance for modernization
 * @author Arvind Menon
 * @since 1.2.0
 */
public record DependencyAgeResponse(
    String dependency,
    String latestVersion,
    DependencyAgeAnalysis.AgeClassification ageClassification,
    long daysSinceLastRelease,
    LocalDateTime lastReleaseDate,
    String ageDescription,
    String recommendation,
    Optional<Context7Guidance> context7Guidance) {

  /** Creates a response from a basic DependencyAgeAnalysis with Context7 guidance. */
  public static DependencyAgeResponse from(
      DependencyAgeAnalysis analysis, boolean context7Enabled) {
    // Add Context7 guidance for aging/stale dependencies when Context7 is enabled
    Optional<Context7Guidance> guidance =
        (context7Enabled
                && (analysis.ageClassification() == DependencyAgeAnalysis.AgeClassification.AGING
                    || analysis.ageClassification()
                        == DependencyAgeAnalysis.AgeClassification.STALE))
            ? Optional.of(
                Context7Guidance.forModernization(
                    analysis.dependency(), analysis.ageClassification().getName()))
            : Optional.empty();

    return new DependencyAgeResponse(
        analysis.dependency(),
        analysis.latestVersion(),
        analysis.ageClassification(),
        analysis.daysSinceLastRelease(),
        analysis.lastReleaseDate(),
        analysis.ageDescription(),
        analysis.recommendation(),
        guidance);
  }
}
