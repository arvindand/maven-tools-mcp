package com.arvindand.mcp.maven.model;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Analysis of dependency age and freshness classification.
 *
 * @param dependency the dependency coordinate
 * @param latestVersion the latest version analyzed
 * @param ageClassification age classification (fresh/current/aging/stale)
 * @param daysSinceLastRelease days since the latest version was released
 * @param lastReleaseDate when the latest version was released
 * @param ageDescription human-readable age description
 * @param recommendation suggested action based on age analysis
 * @author Arvind Menon
 * @since 1.1.0
 */
public record DependencyAgeAnalysis(
    String dependency,
    String latestVersion,
    AgeClassification ageClassification,
    long daysSinceLastRelease,
    LocalDateTime lastReleaseDate,
    String ageDescription,
    String recommendation) {

  /** Age classification categories for dependencies. */
  public enum AgeClassification {
    FRESH("fresh", "Released within the last 30 days"),
    CURRENT("current", "Released within the last 6 months"),
    AGING("aging", "Released 6 months to 2 years ago"),
    STALE("stale", "Released more than 2 years ago");

    private final String name;
    private final String description;

    AgeClassification(String name, String description) {
      this.name = name;
      this.description = description;
    }

    public String getName() {
      return name;
    }

    public String getDescription() {
      return description;
    }

    /**
     * Classify dependency age based on days since last release.
     *
     * @param daysSinceRelease days since the latest version was released
     * @return appropriate age classification
     */
    public static AgeClassification classify(long daysSinceRelease) {
      if (daysSinceRelease <= 30) {
        return FRESH;
      } else if (daysSinceRelease <= 180) {
        return CURRENT;
      } else if (daysSinceRelease <= 730) {
        return AGING;
      } else {
        return STALE;
      }
    }
  }

  /**
   * Create age analysis from Maven Central timestamp.
   *
   * @param dependency the dependency coordinate
   * @param latestVersion the latest version
   * @param timestamp Maven Central timestamp (milliseconds)
   * @return dependency age analysis
   */
  public static DependencyAgeAnalysis fromTimestamp(
      String dependency, String latestVersion, long timestamp) {
    LocalDateTime releaseDate = LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneOffset.UTC);
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    long daysSinceRelease = java.time.Duration.between(releaseDate, now).toDays();
    
    AgeClassification classification = AgeClassification.classify(daysSinceRelease);
    String ageDescription = formatAgeDescription(daysSinceRelease);
    String recommendation = generateRecommendation(classification, daysSinceRelease);

    return new DependencyAgeAnalysis(
        dependency,
        latestVersion,
        classification,
        daysSinceRelease,
        releaseDate,
        ageDescription,
        recommendation);
  }

  private static String formatAgeDescription(long days) {
    if (days <= 1) {
      return "Released today or yesterday";
    } else if (days <= 7) {
      return "Released " + days + " days ago";
    } else if (days <= 30) {
      return "Released " + (days / 7) + " weeks ago";
    } else if (days <= 365) {
      return "Released " + (days / 30) + " months ago";
    } else {
      return "Released " + (days / 365) + " years ago";
    }
  }

  private static String generateRecommendation(AgeClassification classification, long days) {
    return switch (classification) {
      case FRESH -> "Recently updated - safe to use latest version";
      case CURRENT -> "Actively maintained - consider updating if needed";
      case AGING -> "Consider checking for updates or alternatives";
      case STALE -> "Review for continued maintenance and consider alternatives";
    };
  }
}