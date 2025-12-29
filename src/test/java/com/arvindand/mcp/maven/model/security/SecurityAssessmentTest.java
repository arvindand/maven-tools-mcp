package com.arvindand.mcp.maven.model.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for SecurityAssessment.
 *
 * @author Arvind Menon
 * @since 2.0.0
 */
class SecurityAssessmentTest {

  @Test
  void clean_ReturnsOkStatus() {
    SecurityAssessment result = SecurityAssessment.clean();

    assertThat(result.status()).isEqualTo(SecurityAssessment.Status.OK);
    assertThat(result.vulnerabilityCount()).isZero();
    assertThat(result.maxSeverity()).isNull();
    assertThat(result.criticalCves()).isEmpty();
    assertThat(result.highCves()).isEmpty();
    assertThat(result.fixedInVersion()).isNull();
    assertThat(result.recommendation()).isNull();
  }

  @Test
  void unknown_ReturnsUnknownStatusWithReason() {
    String reason = "OSV service unavailable";

    SecurityAssessment result = SecurityAssessment.unknown(reason);

    assertThat(result.status()).isEqualTo(SecurityAssessment.Status.UNKNOWN);
    assertThat(result.vulnerabilityCount()).isZero();
    assertThat(result.recommendation()).isEqualTo(reason);
  }

  @Test
  void fromVulnerabilities_WithNullList_ReturnsClean() {
    SecurityAssessment result = SecurityAssessment.fromVulnerabilities(null, null);

    assertThat(result.status()).isEqualTo(SecurityAssessment.Status.OK);
  }

  @Test
  void fromVulnerabilities_WithEmptyList_ReturnsClean() {
    SecurityAssessment result = SecurityAssessment.fromVulnerabilities(List.of(), null);

    assertThat(result.status()).isEqualTo(SecurityAssessment.Status.OK);
  }

  @Test
  void fromVulnerabilities_WithCriticalVuln_ReturnsVulnerableWithCriticalSeverity() {
    VulnerabilityInfo criticalVuln =
        new VulnerabilityInfo(
            "CVE-2021-44228",
            VulnerabilityInfo.Severity.CRITICAL,
            10.0,
            "Log4Shell RCE vulnerability",
            "2.17.0",
            "https://nvd.nist.gov/vuln/detail/CVE-2021-44228");

    SecurityAssessment result =
        SecurityAssessment.fromVulnerabilities(List.of(criticalVuln), "2.17.0");

    assertThat(result.status()).isEqualTo(SecurityAssessment.Status.VULNERABLE);
    assertThat(result.vulnerabilityCount()).isEqualTo(1);
    assertThat(result.maxSeverity()).isEqualTo(SecurityAssessment.Severity.CRITICAL);
    assertThat(result.criticalCves()).containsExactly("CVE-2021-44228");
    assertThat(result.highCves()).isEmpty();
    assertThat(result.fixedInVersion()).isEqualTo("2.17.0");
    assertThat(result.recommendation()).contains("URGENT").contains("2.17.0");
  }

  @Test
  void fromVulnerabilities_WithHighVuln_ReturnsVulnerableWithHighSeverity() {
    VulnerabilityInfo highVuln =
        new VulnerabilityInfo(
            "GHSA-1234-5678-90ab",
            VulnerabilityInfo.Severity.HIGH,
            7.5,
            "Denial of service vulnerability",
            "1.5.0",
            "https://github.com/advisories/GHSA-1234-5678-90ab");

    SecurityAssessment result = SecurityAssessment.fromVulnerabilities(List.of(highVuln), "1.5.0");

    assertThat(result.status()).isEqualTo(SecurityAssessment.Status.VULNERABLE);
    assertThat(result.maxSeverity()).isEqualTo(SecurityAssessment.Severity.HIGH);
    assertThat(result.criticalCves()).isEmpty();
    assertThat(result.highCves()).containsExactly("GHSA-1234-5678-90ab");
    assertThat(result.recommendation()).contains("Update recommended");
  }

  @Test
  void fromVulnerabilities_WithMultipleVulns_AggregatesCorrectly() {
    VulnerabilityInfo criticalVuln =
        new VulnerabilityInfo(
            "CVE-2021-44228",
            VulnerabilityInfo.Severity.CRITICAL,
            10.0,
            "Critical vuln",
            "2.17.0",
            null);
    VulnerabilityInfo highVuln =
        new VulnerabilityInfo(
            "CVE-2021-45046", VulnerabilityInfo.Severity.HIGH, 8.0, "High vuln", "2.17.0", null);
    VulnerabilityInfo mediumVuln =
        new VulnerabilityInfo(
            "CVE-2021-45105",
            VulnerabilityInfo.Severity.MEDIUM,
            5.0,
            "Medium vuln",
            "2.17.1",
            null);

    SecurityAssessment result =
        SecurityAssessment.fromVulnerabilities(
            List.of(criticalVuln, highVuln, mediumVuln), "2.17.1");

    assertThat(result.vulnerabilityCount()).isEqualTo(3);
    assertThat(result.maxSeverity()).isEqualTo(SecurityAssessment.Severity.CRITICAL);
    assertThat(result.criticalCves()).containsExactly("CVE-2021-44228");
    assertThat(result.highCves()).containsExactly("CVE-2021-45046");
  }

  @Test
  void fromVulnerabilities_WithNoFix_ReturnsAlternativeRecommendation() {
    VulnerabilityInfo unfixedVuln =
        new VulnerabilityInfo(
            "CVE-2023-00000",
            VulnerabilityInfo.Severity.CRITICAL,
            9.5,
            "No fix available",
            null,
            null);

    SecurityAssessment result = SecurityAssessment.fromVulnerabilities(List.of(unfixedVuln), null);

    assertThat(result.fixedInVersion()).isNull();
    assertThat(result.recommendation()).contains("evaluate alternatives");
  }

  @Test
  void requiresAction_WithCriticalSeverity_ReturnsTrue() {
    VulnerabilityInfo criticalVuln =
        new VulnerabilityInfo(
            "CVE-TEST", VulnerabilityInfo.Severity.CRITICAL, 9.0, "Test", null, null);

    SecurityAssessment result = SecurityAssessment.fromVulnerabilities(List.of(criticalVuln), null);

    assertThat(result.requiresAction()).isTrue();
  }

  @Test
  void requiresAction_WithHighSeverity_ReturnsTrue() {
    VulnerabilityInfo highVuln =
        new VulnerabilityInfo("CVE-TEST", VulnerabilityInfo.Severity.HIGH, 7.5, "Test", null, null);

    SecurityAssessment result = SecurityAssessment.fromVulnerabilities(List.of(highVuln), null);

    assertThat(result.requiresAction()).isTrue();
  }

  @Test
  void requiresAction_WithMediumSeverity_ReturnsFalse() {
    VulnerabilityInfo mediumVuln =
        new VulnerabilityInfo(
            "CVE-TEST", VulnerabilityInfo.Severity.MEDIUM, 5.0, "Test", null, null);

    SecurityAssessment result = SecurityAssessment.fromVulnerabilities(List.of(mediumVuln), null);

    assertThat(result.requiresAction()).isFalse();
  }

  @Test
  void requiresAction_WithCleanAssessment_ReturnsFalse() {
    SecurityAssessment result = SecurityAssessment.clean();

    assertThat(result.requiresAction()).isFalse();
  }
}
