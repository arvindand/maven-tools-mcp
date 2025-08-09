package com.arvindand.mcp.maven.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;
import java.util.Optional;

/**
 * Comprehensive dependency age information with analysis and guidance.
 *
 * @param dependency the Maven coordinate analyzed
 * @param latestVersion the latest version found
 * @param daysSinceLastRelease days since the latest version was released
 * @param lastReleaseDate ISO formatted date of the last release
 * @param ageClassification age classification (fresh/current/aging/stale)
 * @param ageDescription human-readable description of the dependency's age status
 * @param recommendation actionable recommendation based on age analysis
 * @param context7Guidance optional Context7 guidance for deeper integration insights
 * @param maxAgeInDays the configured threshold for age classification
 * @author Arvind Menon
 * @since 1.3.0
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DependencyAge(
    String dependency,
    String latestVersion,
    DependencyAgeAnalysis.AgeClassification ageClassification,
    long daysSinceLastRelease,
    Instant lastReleaseDate,
    String ageDescription,
    String recommendation,
    Optional<Context7Guidance> context7Guidance) {

  /** Creates a response from a basic DependencyAgeAnalysis with Context7 guidance. */
  public static DependencyAge from(DependencyAgeAnalysis analysis, boolean context7Enabled) {
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

    return new DependencyAge(
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
