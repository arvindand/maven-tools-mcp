package com.arvindand.mcp.maven.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Analysis of dependency release patterns and maintenance activity.
 *
 * @param dependency the dependency coordinate
 * @param versionsAnalyzed number of versions included in analysis
 * @param timeSpanMonths time span of analysis in months
 * @param averageDaysBetweenReleases average time between releases
 * @param releaseVelocity releases per month on average
 * @param maintenanceLevel classified maintenance activity level
 * @param releaseConsistency how consistent the release pattern is
 * @param lastReleaseDate date of the most recent release
 * @param nextReleasePrediction predicted timeframe for next release
 * @param recentReleases list of recent releases with timestamps
 * @param recommendation maintenance-based recommendation
 * @author Arvind Menon
 * @since 1.1.0
 */
public record ReleasePatternAnalysis(
    String dependency,
    int versionsAnalyzed,
    int timeSpanMonths,
    double averageDaysBetweenReleases,
    double releaseVelocity,
    MaintenanceLevel maintenanceLevel,
    ReleaseConsistency releaseConsistency,
    LocalDateTime lastReleaseDate,
    String nextReleasePrediction,
    List<ReleaseInfo> recentReleases,
    String recommendation) {

  /** Maintenance activity level classification. */
  public enum MaintenanceLevel {
    ACTIVE("active", "Frequent releases with consistent maintenance"),
    MODERATE("moderate", "Regular releases with good maintenance"),
    SLOW("slow", "Infrequent releases but still maintained"),
    INACTIVE("inactive", "Very rare releases, possible maintenance issues");

    private final String name;
    private final String description;

    MaintenanceLevel(String name, String description) {
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
     * Classify maintenance level based on release velocity.
     *
     * @param releasesPerMonth average releases per month
     * @param daysSinceLastRelease days since most recent release
     * @return appropriate maintenance level
     */
    public static MaintenanceLevel classify(double releasesPerMonth, long daysSinceLastRelease) {
      // Factor in recency of last release
      if (daysSinceLastRelease > 365) {
        return INACTIVE;
      } else if (releasesPerMonth >= 2.0) {
        return ACTIVE;
      } else if (releasesPerMonth >= 0.5) {
        return MODERATE;
      } else if (releasesPerMonth >= 0.1) {
        return SLOW;
      } else {
        return INACTIVE;
      }
    }
  }

  /** Release pattern consistency classification. */
  public enum ReleaseConsistency {
    VERY_CONSISTENT("very_consistent", "Highly predictable release schedule"),
    CONSISTENT("consistent", "Generally predictable timing"),
    VARIABLE("variable", "Irregular but reasonable timing"),
    ERRATIC("erratic", "Unpredictable release patterns");

    private final String name;
    private final String description;

    ReleaseConsistency(String name, String description) {
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
     * Classify release consistency based on variance in release intervals.
     *
     * @param averageDays average days between releases
     * @param maxInterval longest interval between releases
     * @param minInterval shortest interval between releases
     * @return appropriate consistency classification
     */
    public static ReleaseConsistency classify(double averageDays, long maxInterval, long minInterval) {
      if (averageDays == 0) return ERRATIC;
      
      double variance = (maxInterval - minInterval) / averageDays;
      
      if (variance <= 0.5) {
        return VERY_CONSISTENT;
      } else if (variance <= 1.0) {
        return CONSISTENT;
      } else if (variance <= 2.0) {
        return VARIABLE;
      } else {
        return ERRATIC;
      }
    }
  }

  /**
   * Information about a specific release.
   *
   * @param version the version string
   * @param releaseDate when this version was released
   * @param daysSincePrevious days since the previous release
   */
  public record ReleaseInfo(
      String version,
      LocalDateTime releaseDate,
      Long daysSincePrevious) {}

  /**
   * Generate recommendation based on maintenance analysis.
   *
   * @param maintenanceLevel the classified maintenance level
   * @param daysSinceLastRelease days since most recent release
   * @param releaseVelocity releases per month
   * @return maintenance-based recommendation
   */
  public static String generateRecommendation(
      MaintenanceLevel maintenanceLevel, 
      long daysSinceLastRelease, 
      double releaseVelocity) {
    
    return switch (maintenanceLevel) {
      case ACTIVE -> "Well-maintained dependency with active development - safe to use";
      case MODERATE -> "Regularly maintained - good choice for production use";
      case SLOW -> "Slowly maintained - monitor for updates and consider alternatives for critical projects";
      case INACTIVE -> "Minimal maintenance activity - evaluate alternatives and migration plan";
    };
  }

  /**
   * Predict next release timeframe based on historical patterns.
   *
   * @param averageDaysBetweenReleases average interval between releases
   * @param daysSinceLastRelease days since most recent release
   * @param consistency how consistent the release pattern is
   * @return predicted next release timeframe
   */
  public static String predictNextRelease(
      double averageDaysBetweenReleases, 
      long daysSinceLastRelease, 
      ReleaseConsistency consistency) {
    
    if (averageDaysBetweenReleases == 0) {
      return "Unable to predict - insufficient release history";
    }

    long expectedDays = Math.round(averageDaysBetweenReleases);
    long overdue = daysSinceLastRelease - expectedDays;

    if (consistency == ReleaseConsistency.ERRATIC) {
      return "Unpredictable release schedule";
    }

    if (overdue > expectedDays / 2) {
      return "Overdue - expected " + (overdue) + " days ago";
    } else if (overdue > 0) {
      return "Due soon - typically releases every " + expectedDays + " days";
    } else {
      long remaining = expectedDays - daysSinceLastRelease;
      if (remaining <= 7) {
        return "Expected within the next week";
      } else if (remaining <= 30) {
        return "Expected in " + remaining + " days";
      } else {
        return "Expected in " + (remaining / 30) + " months";
      }
    }
  }
}