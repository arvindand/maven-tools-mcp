package com.arvindand.mcp.maven.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Enhanced version timeline with temporal analysis and patterns.
 *
 * @param dependency the dependency coordinate
 * @param totalVersions total number of versions available
 * @param versionsReturned number of versions in this response
 * @param timeSpanMonths time span covered by the timeline
 * @param versionTimeline chronological list of versions with analysis
 * @param releaseVelocityTrend how release velocity has changed over time
 * @param stabilityPattern pattern of stable vs pre-release versions
 * @param recentActivity summary of recent release activity
 * @param insights key insights from timeline analysis
 * @author Arvind Menon
 * @since 1.1.0
 */
public record VersionTimelineAnalysis(
    String dependency,
    int totalVersions,
    int versionsReturned,
    int timeSpanMonths,
    List<TimelineEntry> versionTimeline,
    VelocityTrend releaseVelocityTrend,
    StabilityPattern stabilityPattern,
    RecentActivity recentActivity,
    List<String> insights) {

  /**
   * Individual version entry in the timeline.
   *
   * @param version the version string
   * @param versionType type classification of this version
   * @param releaseDate when this version was released
   * @param relativeTime human-readable relative time (e.g., "2 months ago")
   * @param daysSincePrevious days since the previous version
   * @param isBreakingChange whether this appears to be a breaking change
   * @param releaseGap gap classification for this release
   */
  public record TimelineEntry(
      String version,
      VersionInfo.VersionType versionType,
      LocalDateTime releaseDate,
      String relativeTime,
      Long daysSincePrevious,
      boolean isBreakingChange,
      ReleaseGap releaseGap) {

    /** Classification of gaps between releases. */
    public enum ReleaseGap {
      RAPID("rapid", "Released very quickly after previous"),
      NORMAL("normal", "Normal time interval"),
      SLOW("slow", "Longer than usual interval"),
      MAJOR_GAP("major_gap", "Significant gap in releases");

      private final String name;
      private final String description;

      ReleaseGap(String name, String description) {
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
       * Classify release gap based on days since previous and average interval.
       */
      public static ReleaseGap classify(long daysSincePrevious, double averageInterval) {
        if (averageInterval == 0 || daysSincePrevious <= 0) {
          return NORMAL;
        }

        double ratio = daysSincePrevious / averageInterval;
        if (ratio <= 0.3) {
          return RAPID;
        } else if (ratio <= 1.5) {
          return NORMAL;
        } else if (ratio <= 3.0) {
          return SLOW;
        } else {
          return MAJOR_GAP;
        }
      }
    }
  }

  /**
   * Analysis of how release velocity has changed over time.
   *
   * @param trend the overall trend direction
   * @param description human-readable description of the trend
   * @param recentVelocity releases per month in recent period
   * @param historicalVelocity releases per month historically
   * @param changePercentage percentage change in velocity
   */
  public record VelocityTrend(
      TrendDirection trend,
      String description,
      double recentVelocity,
      double historicalVelocity,
      double changePercentage) {

    public enum TrendDirection {
      ACCELERATING("accelerating", "Release frequency is increasing"),
      STABLE("stable", "Release frequency is consistent"),
      DECLINING("declining", "Release frequency is decreasing"),
      ERRATIC("erratic", "Release frequency varies significantly");

      private final String name;
      private final String description;

      TrendDirection(String name, String description) {
        this.name = name;
        this.description = description;
      }

      public String getName() {
        return name;
      }

      public String getDescription() {
        return description;
      }
    }
  }

  /**
   * Pattern analysis of stable vs pre-release versions.
   *
   * @param stablePercentage percentage of versions that are stable
   * @param prereleasePattern how pre-releases are typically used
   * @param stableReleasePattern typical pattern for stable releases
   * @param recommendation recommendation based on stability patterns
   */
  public record StabilityPattern(
      double stablePercentage,
      String prereleasePattern,
      String stableReleasePattern,
      String recommendation) {}

  /**
   * Summary of recent release activity.
   *
   * @param releasesLastMonth number of releases in the last month
   * @param releasesLastQuarter number of releases in the last quarter
   * @param activityLevel classified activity level
   * @param lastReleaseAge days since the last release
   * @param activityDescription human-readable activity description
   */
  public record RecentActivity(
      int releasesLastMonth,
      int releasesLastQuarter,
      ActivityLevel activityLevel,
      long lastReleaseAge,
      String activityDescription) {

    public enum ActivityLevel {
      VERY_ACTIVE("very_active", "Multiple releases per month"),
      ACTIVE("active", "Regular monthly releases"),
      MODERATE("moderate", "Quarterly releases"),
      LOW("low", "Infrequent releases"),
      DORMANT("dormant", "No recent releases");

      private final String name;
      private final String description;

      ActivityLevel(String name, String description) {
        this.name = name;
        this.description = description;
      }

      public String getName() {
        return name;
      }

      public String getDescription() {
        return description;
      }

      public static ActivityLevel classify(int releasesLastMonth, int releasesLastQuarter) {
        if (releasesLastMonth >= 3) {
          return VERY_ACTIVE;
        } else if (releasesLastMonth >= 1) {
          return ACTIVE;
        } else if (releasesLastQuarter >= 2) {
          return MODERATE;
        } else if (releasesLastQuarter >= 1) {
          return LOW;
        } else {
          return DORMANT;
        }
      }
    }
  }

  /**
   * Format relative time description.
   */
  public static String formatRelativeTime(LocalDateTime releaseDate, LocalDateTime now) {
    long days = java.time.Duration.between(releaseDate, now).toDays();
    
    if (days == 0) {
      return "today";
    } else if (days == 1) {
      return "yesterday";
    } else if (days <= 7) {
      return days + " days ago";
    } else if (days <= 30) {
      return (days / 7) + " weeks ago";
    } else if (days <= 365) {
      return (days / 30) + " months ago";
    } else {
      long years = days / 365;
      long months = (days % 365) / 30;
      if (months == 0) {
        return years + " years ago";
      } else {
        return years + " years, " + months + " months ago";
      }
    }
  }
}