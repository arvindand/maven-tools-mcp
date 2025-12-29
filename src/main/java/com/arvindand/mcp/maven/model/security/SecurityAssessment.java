package com.arvindand.mcp.maven.model.security;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.Comparator;
import java.util.List;

/**
 * Security assessment for a single dependency version. Designed for embedding in existing response
 * models.
 *
 * @param status assessment status (OK, VULNERABLE, UNKNOWN)
 * @param vulnerabilityCount total number of vulnerabilities found
 * @param maxSeverity highest severity among all vulnerabilities
 * @param criticalCves list of critical vulnerability IDs
 * @param highCves list of high severity vulnerability IDs
 * @param fixedInVersion minimum version that fixes all vulnerabilities
 * @param recommendation actionable recommendation based on findings
 * @author Arvind Menon
 * @since 2.0.0
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SecurityAssessment(
    Status status,
    int vulnerabilityCount,
    Severity maxSeverity,
    List<String> criticalCves,
    List<String> highCves,
    String fixedInVersion,
    String recommendation) {

  /** Security assessment status. */
  public enum Status {
    OK,
    VULNERABLE,
    UNKNOWN
  }

  /** Severity levels for JSON output clarity. */
  public enum Severity {
    CRITICAL,
    HIGH,
    MEDIUM,
    LOW,
    UNKNOWN
  }

  /** Factory for clean dependencies with no vulnerabilities. */
  public static SecurityAssessment clean() {
    return new SecurityAssessment(Status.OK, 0, null, List.of(), List.of(), null, null);
  }

  /**
   * Factory for unknown/error cases.
   *
   * @param reason explanation of why assessment is unknown
   */
  public static SecurityAssessment unknown(String reason) {
    return new SecurityAssessment(Status.UNKNOWN, 0, null, List.of(), List.of(), null, reason);
  }

  /**
   * Build assessment from vulnerability list.
   *
   * @param vulns list of vulnerabilities found
   * @param lowestFixedVersion minimum version that fixes all vulnerabilities
   * @return security assessment based on findings
   */
  public static SecurityAssessment fromVulnerabilities(
      List<VulnerabilityInfo> vulns, String lowestFixedVersion) {

    if (vulns == null || vulns.isEmpty()) {
      return clean();
    }

    var criticals =
        vulns.stream()
            .filter(v -> v.severity() == VulnerabilityInfo.Severity.CRITICAL)
            .map(VulnerabilityInfo::id)
            .toList();

    var highs =
        vulns.stream()
            .filter(v -> v.severity() == VulnerabilityInfo.Severity.HIGH)
            .map(VulnerabilityInfo::id)
            .toList();

    var maxSeverity =
        vulns.stream()
            .map(VulnerabilityInfo::severity)
            .max(Comparator.comparingInt(VulnerabilityInfo.Severity::weight))
            .orElse(VulnerabilityInfo.Severity.UNKNOWN);

    String recommendation = buildRecommendation(maxSeverity, lowestFixedVersion);

    return new SecurityAssessment(
        Status.VULNERABLE,
        vulns.size(),
        mapSeverity(maxSeverity),
        criticals,
        highs,
        lowestFixedVersion,
        recommendation);
  }

  private static Severity mapSeverity(VulnerabilityInfo.Severity s) {
    return switch (s) {
      case CRITICAL -> Severity.CRITICAL;
      case HIGH -> Severity.HIGH;
      case MEDIUM -> Severity.MEDIUM;
      case LOW -> Severity.LOW;
      case UNKNOWN -> Severity.UNKNOWN;
    };
  }

  private static String buildRecommendation(
      VulnerabilityInfo.Severity severity, String fixedVersion) {
    return switch (severity) {
      case CRITICAL ->
          fixedVersion != null
              ? "URGENT: Update immediately to " + fixedVersion
              : "URGENT: Critical vulnerability with no fix - evaluate alternatives";
      case HIGH ->
          fixedVersion != null
              ? "Update recommended to " + fixedVersion
              : "High severity vulnerability - monitor for fixes";
      case MEDIUM, LOW ->
          fixedVersion != null
              ? "Consider updating to " + fixedVersion
              : "Minor vulnerability - update when convenient";
      case UNKNOWN -> "Unable to assess severity";
    };
  }

  /** Check if this assessment requires immediate action. */
  public boolean requiresAction() {
    return status == Status.VULNERABLE
        && maxSeverity != null
        && (maxSeverity == Severity.CRITICAL || maxSeverity == Severity.HIGH);
  }
}
