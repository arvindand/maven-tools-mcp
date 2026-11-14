package com.arvindand.mcp.maven.service;

import static com.arvindand.mcp.maven.TestHelpers.getSuccessData;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.arvindand.mcp.maven.config.Context7Properties;
import com.arvindand.mcp.maven.model.MavenArtifact;
import com.arvindand.mcp.maven.model.MavenCoordinate;
import com.arvindand.mcp.maven.model.ProjectHealthAnalysis;
import com.arvindand.mcp.maven.model.StabilityFilter;
import com.arvindand.mcp.maven.model.VersionComparison;
import com.arvindand.mcp.maven.model.license.LicenseInfo;
import com.arvindand.mcp.maven.model.security.SecurityAssessment;
import com.arvindand.mcp.maven.pom.EffectivePomResolver;
import com.arvindand.mcp.maven.util.VersionComparator;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Unit tests for version-aware {@code analyze_project_health} behaviour. */
class MavenDependencyToolsHealthUnitTest {

  private static final String G = "org.apache.logging.log4j";
  private static final String A = "log4j-core";

  private MavenCentralService maven;
  private VulnerabilityService vulns;

  @BeforeEach
  void setUp() {
    maven = mock(MavenCentralService.class);
    vulns = mock(VulnerabilityService.class);
    when(vulns.scanBulk(any())).thenReturn(Map.of());
  }

  private MavenDependencyTools tools(boolean context7) {
    return new MavenDependencyTools(
        maven,
        new VersionComparator(),
        new Context7Properties(context7, null),
        vulns,
        mock(EffectivePomResolver.class));
  }

  private static MavenArtifact art(String g, String a, String v, long daysAgo) {
    return new MavenArtifact(
        g + ":" + a + ":" + v,
        g,
        a,
        v,
        "jar",
        Instant.now().minus(daysAgo, ChronoUnit.DAYS).toEpochMilli());
  }

  private void stubLog4j() {
    when(maven.getAllVersions(any())).thenReturn(List.of("3.0.0-beta3", "2.25.3", "2.14.1"));
    when(maven.getRecentVersionsWithAccurateTimestamps(any(), anyInt()))
        .thenReturn(
            List.of(
                art(G, A, "3.0.0-beta3", 10), art(G, A, "2.25.3", 40), art(G, A, "2.14.1", 1900)));
    when(maven.getLicenses(any()))
        .thenReturn(List.of(LicenseInfo.fromPom("Apache-2.0", "https://x")));
  }

  private ProjectHealthAnalysis run(String deps, StabilityFilter filter) {
    return getSuccessData(tools(false).analyze_project_health(deps, null, filter, true, true));
  }

  @Test
  void versionedInputScansAndLicensesThatVersion() {
    stubLog4j();
    SecurityAssessment unknown = SecurityAssessment.unknown("test");
    when(vulns.scanBulk(any())).thenReturn(Map.of(G + ":" + A + ":2.14.1", unknown));

    ProjectHealthAnalysis result = run(G + ":" + A + ":2.14.1", null);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<MavenCoordinate>> captor = ArgumentCaptor.forClass(List.class);
    verify(vulns).scanBulk(captor.capture());
    assertThat(captor.getValue()).containsExactly(MavenCoordinate.of(G, A, "2.14.1"));
    verify(maven).getLicenses(MavenCoordinate.of(G, A, "2.14.1"));

    var dep = result.dependencies().getFirst();
    assertThat(dep.status()).isEqualTo("success");
    assertThat(dep.currentVersion()).contains("2.14.1");
    assertThat(dep.scannedVersion()).contains("2.14.1");
    assertThat(dep.latestVersion()).isEqualTo("2.25.3");
    assertThat(dep.updateAvailable()).contains(true);
    assertThat(dep.updateType()).contains("minor");
    assertThat(dep.currentVersionAgeDays())
        .hasValueSatisfying(d -> assertThat(d).isBetween(1899L, 1901L));
    assertThat(dep.security()).contains(unknown);
  }

  @Test
  void unversionedPreferStablePicksNewestStableNotBeta() {
    stubLog4j();

    ProjectHealthAnalysis result = run(G + ":" + A, StabilityFilter.PREFER_STABLE);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<MavenCoordinate>> captor = ArgumentCaptor.forClass(List.class);
    verify(vulns).scanBulk(captor.capture());
    assertThat(captor.getValue()).containsExactly(MavenCoordinate.of(G, A, "2.25.3"));
    verify(maven).getLicenses(MavenCoordinate.of(G, A, "2.25.3"));

    var dep = result.dependencies().getFirst();
    assertThat(dep.latestVersion()).isEqualTo("2.25.3");
    assertThat(dep.scannedVersion()).contains("2.25.3");
    assertThat(dep.currentVersion()).isEmpty();
    assertThat(dep.updateAvailable()).isEmpty();
    assertThat(dep.daysSinceRelease()).isBetween(39L, 41L);
  }

  @Test
  void unversionedDefaultFilterIsPreferStableAndAllKeepsNewest() {
    stubLog4j();
    assertThat(run(G + ":" + A, null).dependencies().getFirst().scannedVersion())
        .contains("2.25.3");
    assertThat(run(G + ":" + A, StabilityFilter.ALL).dependencies().getFirst().scannedVersion())
        .contains("3.0.0-beta3");
  }

  @Test
  void stableOnlyWithoutStableReleaseIsReportedAndNothingScanned() {
    when(maven.getAllVersions(any())).thenReturn(List.of("1.0.0-beta2", "1.0.0-beta1"));

    ProjectHealthAnalysis result = run("com.example:lib", StabilityFilter.STABLE_ONLY);

    var dep = result.dependencies().getFirst();
    assertThat(dep.status()).isEqualTo("no_stable_version");
    assertThat(dep.error()).hasValueSatisfying(e -> assertThat(e).contains("STABLE_ONLY"));
    assertThat(dep.scannedVersion()).isEmpty();
    assertThat(result.failedAnalysis()).isEqualTo(1);
    verify(vulns, never()).scanBulk(any());
    verify(maven, never()).getLicenses(any());
  }

  @Test
  void preferStableFallsBackToPreReleaseWithNote() {
    when(maven.getAllVersions(any())).thenReturn(List.of("1.0.0-beta2", "1.0.0-beta1"));
    when(maven.getRecentVersionsWithAccurateTimestamps(any(), anyInt()))
        .thenReturn(List.of(art("com.example", "lib", "1.0.0-beta2", 5)));

    var dep = run("com.example:lib", StabilityFilter.PREFER_STABLE).dependencies().getFirst();

    assertThat(dep.status()).isEqualTo("success");
    assertThat(dep.scannedVersion()).contains("1.0.0-beta2");
    assertThat(dep.note()).hasValueSatisfying(n -> assertThat(n).contains("pre-release"));
  }

  @Test
  void nonexistentSuppliedVersionIsAnErrorAndNotReplacedByAnotherVersion() {
    stubLog4j();

    ProjectHealthAnalysis result = run(G + ":" + A + ":9.9.9", null);

    var dep = result.dependencies().getFirst();
    assertThat(dep.status()).isEqualTo("error");
    assertThat(dep.error()).hasValueSatisfying(e -> assertThat(e).contains("9.9.9"));
    assertThat(dep.currentVersion()).contains("9.9.9");
    assertThat(dep.scannedVersion()).isEmpty();
    verify(vulns, never()).scanBulk(any());
    verify(maven, never()).getLicenses(any());
    assertThat(result.successfulAnalysis()).isZero();
  }

  @Test
  void mixedListScansEachDependencyAtItsOwnVersion() {
    stubLog4j();
    when(maven.getAllVersions(eq(MavenCoordinate.of("com.example", "lib"))))
        .thenReturn(List.of("2.0.0-RC1", "1.5.0", "1.4.0"));
    when(maven.getRecentVersionsWithAccurateTimestamps(
            eq(MavenCoordinate.of("com.example", "lib")), anyInt()))
        .thenReturn(
            List.of(
                art("com.example", "lib", "2.0.0-RC1", 3), art("com.example", "lib", "1.5.0", 20)));

    ProjectHealthAnalysis result =
        run(G + ":" + A + ":2.14.1\ncom.example:lib", StabilityFilter.PREFER_STABLE);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<MavenCoordinate>> captor = ArgumentCaptor.forClass(List.class);
    verify(vulns).scanBulk(captor.capture());
    assertThat(captor.getValue())
        .containsExactlyInAnyOrder(
            MavenCoordinate.of(G, A, "2.14.1"), MavenCoordinate.of("com.example", "lib", "1.5.0"));
    assertThat(result.successfulAnalysis()).isEqualTo(2);
    assertThat(result.dependencies())
        .extracting(d -> d.scannedVersion().orElse(null))
        .containsExactly("2.14.1", "1.5.0");
  }

  @Test
  void summaryHasIsoTimestampOverallHealthAndAverageScore() throws Exception {
    stubLog4j();

    ProjectHealthAnalysis result = run(G + ":" + A + ":2.14.1", null);

    assertThat(Instant.parse(result.analysisDate()))
        .isCloseTo(Instant.now(), org.assertj.core.api.Assertions.within(1, ChronoUnit.MINUTES));
    assertThat(result.overallHealth()).isIn("excellent", "good", "fair", "poor");
    assertThat(result.averageHealthScore())
        .isEqualTo(result.dependencies().getFirst().healthScore());

    JsonNode json = new JsonMapper().readTree(new JsonMapper().writeValueAsString(result));
    assertThat(json.has("analysis_date")).isTrue();
    assertThat(json.get("overall_health").asString()).isEqualTo(result.overallHealth());
    assertThat(json.get("average_health_score").isInt()).isTrue();
  }

  @Test
  void summaryWithNoSuccessfulDependenciesHasNoAverage() {
    stubLog4j();

    ProjectHealthAnalysis result = run(G + ":" + A + ":9.9.9", null);

    assertThat(result.overallHealth()).isEqualTo("unknown");
    assertThat(result.averageHealthScore()).isNull();
    assertThat(Instant.parse(result.analysisDate())).isNotNull();
  }

  @Test
  void healthGuidanceIsEmittedOnceWithShortPerDependencyQueries() throws Exception {
    stubLog4j();
    when(maven.getAllVersions(eq(MavenCoordinate.of("com.example", "lib", "1.0.0"))))
        .thenReturn(List.of("1.1.0", "1.0.0"));
    when(maven.getRecentVersionsWithAccurateTimestamps(
            eq(MavenCoordinate.of("com.example", "lib", "1.0.0")), anyInt()))
        .thenReturn(
            List.of(art("com.example", "lib", "1.1.0", 5), art("com.example", "lib", "1.0.0", 50)));

    ProjectHealthAnalysis result =
        getSuccessData(
            tools(true)
                .analyze_project_health(
                    G + ":" + A + ":2.14.1,com.example:lib:1.0.0", null, null, false, false));

    assertThat(result.context7Guidance()).isPresent();
    assertThat(result.dependencies()).allSatisfy(d -> assertThat(d.context7Query()).isPresent());
    String json = new JsonMapper().writeValueAsString(result);
    assertThat(json.split("resolve-library-id", -1)).hasSize(2);
    assertThat(json).contains("\"context7Query\":\"log4j-core upgrade guide\"");
  }

  @Test
  void healthEmitsNoContext7WhenDisabled() throws Exception {
    stubLog4j();

    ProjectHealthAnalysis result = run(G + ":" + A + ":2.14.1", null);

    assertThat(result.context7Guidance()).isEmpty();
    assertThat(result.dependencies().getFirst().context7Query()).isEmpty();
    assertThat(new JsonMapper().writeValueAsString(result)).doesNotContain("resolve-library-id");
  }

  @Test
  void compareEmitsGuidanceOnceAndNothingWhenDisabled() throws Exception {
    when(maven.getAllVersions(any())).thenReturn(List.of("2.0.0", "1.1.0", "1.0.0"));
    String deps = "a:one:1.0.0,b:two:1.0.0";

    VersionComparison enabled =
        getSuccessData(tools(true).compare_dependency_versions(deps, StabilityFilter.ALL, false));
    assertThat(enabled.context7Guidance()).isPresent();
    assertThat(enabled.dependencies()).allSatisfy(d -> assertThat(d.context7Query()).isPresent());
    assertThat(new JsonMapper().writeValueAsString(enabled).split("resolve-library-id", -1))
        .hasSize(2);

    VersionComparison disabled =
        getSuccessData(tools(false).compare_dependency_versions(deps, StabilityFilter.ALL, false));
    assertThat(disabled.context7Guidance()).isEmpty();
    assertThat(disabled.dependencies()).allSatisfy(d -> assertThat(d.context7Query()).isEmpty());
  }
}
