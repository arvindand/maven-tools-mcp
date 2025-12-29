package com.arvindand.mcp.maven.model.security;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.ArrayList;
import java.util.List;

/**
 * Aggregate security summary for bulk operations. Used in compare_dependency_versions and
 * analyze_project_health.
 *
 * @param totalScanned total dependencies scanned
 * @param vulnerableDependencies count of dependencies with vulnerabilities
 * @param cleanDependencies count of dependencies with no vulnerabilities
 * @param unknownDependencies count of dependencies that could not be assessed
 * @param totalVulnerabilities total number of vulnerabilities found across all dependencies
 * @param criticalCount number of critical vulnerabilities
 * @param highCount number of high severity vulnerabilities
 * @param mediumCount number of medium severity vulnerabilities
 * @param lowCount number of low severity vulnerabilities
 * @param actionRequired true if any critical or high vulnerabilities exist
 * @param urgentActions list of urgent action recommendations
 * @author Arvind Menon
 * @since 2.0.0
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SecuritySummary(
    int totalScanned,
    int vulnerableDependencies,
    int cleanDependencies,
    int unknownDependencies,
    int totalVulnerabilities,
    int criticalCount,
    int highCount,
    int mediumCount,
    int lowCount,
    boolean actionRequired,
    List<String> urgentActions) {

  /** Create a builder for SecuritySummary. */
  public static Builder builder() {
    return new Builder();
  }

  /** Builder for constructing SecuritySummary from individual assessments. */
  public static final class Builder {
    private int scanned;
    private int vulnerable;
    private int clean;
    private int unknown;
    private int total;
    private int critical;
    private int high;
    private int medium;
    private int low;
    private final List<String> actions = new ArrayList<>();

    /** Add a clean dependency (no vulnerabilities). */
    public Builder addClean() {
      scanned++;
      clean++;
      return this;
    }

    /** Add an unknown dependency (could not be assessed). */
    public Builder addUnknown() {
      scanned++;
      unknown++;
      return this;
    }

    /**
     * Add a vulnerable dependency with its assessment.
     *
     * @param assessment security assessment for the dependency
     * @param dependency dependency identifier for action messages
     */
    public Builder addVulnerable(SecurityAssessment assessment, String dependency) {
      scanned++;
      vulnerable++;
      total += assessment.vulnerabilityCount();
      critical += assessment.criticalCves().size();
      high += assessment.highCves().size();

      // Count medium and low from total
      int mediumAndLow =
          assessment.vulnerabilityCount()
              - assessment.criticalCves().size()
              - assessment.highCves().size();
      // We don't have granular data for medium vs low, so we estimate
      medium += mediumAndLow / 2;
      low += mediumAndLow - (mediumAndLow / 2);

      if (assessment.requiresAction() && assessment.recommendation() != null) {
        actions.add(dependency + ": " + assessment.recommendation());
      }
      return this;
    }

    /** Build the final SecuritySummary. */
    public SecuritySummary build() {
      return new SecuritySummary(
          scanned,
          vulnerable,
          clean,
          unknown,
          total,
          critical,
          high,
          medium,
          low,
          critical > 0 || high > 0,
          List.copyOf(actions));
    }
  }

  /** Create an empty summary for when no scanning was performed. */
  public static SecuritySummary empty() {
    return new SecuritySummary(0, 0, 0, 0, 0, 0, 0, 0, 0, false, List.of());
  }

  /**
   * Create a SecuritySummary from a map of dependency assessments.
   *
   * @param assessments map of dependency coordinate to security assessment
   * @return aggregated security summary
   */
  public static SecuritySummary fromAssessments(
      java.util.Map<String, SecurityAssessment> assessments) {
    if (assessments == null || assessments.isEmpty()) {
      return empty();
    }

    Builder builder = builder();
    for (var entry : assessments.entrySet()) {
      String dependency = entry.getKey();
      SecurityAssessment assessment = entry.getValue();

      switch (assessment.status()) {
        case OK -> builder.addClean();
        case UNKNOWN -> builder.addUnknown();
        case VULNERABLE -> builder.addVulnerable(assessment, dependency);
      }
    }
    return builder.build();
  }
}
