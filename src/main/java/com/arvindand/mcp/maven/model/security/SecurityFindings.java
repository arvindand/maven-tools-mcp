package com.arvindand.mcp.maven.model.security;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;

/**
 * Aggregated security findings for project health analysis.
 *
 * @param vulnerableCount number of dependencies with vulnerabilities
 * @param cleanCount number of clean dependencies
 * @param unknownCount number of dependencies that could not be scanned
 * @param worstSeverity highest severity found across all dependencies
 * @param actionItems list of recommended actions
 * @author Arvind Menon
 * @since 2.0.0
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SecurityFindings(
    int vulnerableCount,
    int cleanCount,
    int unknownCount,
    SecurityAssessment.Severity worstSeverity,
    List<String> actionItems) {

  /** Create findings from security summary. */
  public static SecurityFindings fromSummary(SecuritySummary summary) {
    SecurityAssessment.Severity worstSeverity = null;
    if (summary.criticalCount() > 0) {
      worstSeverity = SecurityAssessment.Severity.CRITICAL;
    } else if (summary.highCount() > 0) {
      worstSeverity = SecurityAssessment.Severity.HIGH;
    } else if (summary.mediumCount() > 0) {
      worstSeverity = SecurityAssessment.Severity.MEDIUM;
    } else if (summary.lowCount() > 0) {
      worstSeverity = SecurityAssessment.Severity.LOW;
    }

    return new SecurityFindings(
        summary.vulnerableDependencies(),
        summary.cleanDependencies(),
        summary.unknownDependencies(),
        worstSeverity,
        summary.urgentActions());
  }

  /** Create empty findings for when no security scanning was performed. */
  public static SecurityFindings empty() {
    return new SecurityFindings(0, 0, 0, null, List.of());
  }

  /** Check if any vulnerabilities require immediate action. */
  public boolean requiresAction() {
    return worstSeverity == SecurityAssessment.Severity.CRITICAL
        || worstSeverity == SecurityAssessment.Severity.HIGH;
  }
}
