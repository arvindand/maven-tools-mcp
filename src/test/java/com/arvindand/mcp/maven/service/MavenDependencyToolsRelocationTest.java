package com.arvindand.mcp.maven.service;

import static com.arvindand.mcp.maven.TestHelpers.getSuccessData;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.arvindand.mcp.maven.config.Context7Properties;
import com.arvindand.mcp.maven.model.BulkCheckResult;
import com.arvindand.mcp.maven.model.MavenArtifact;
import com.arvindand.mcp.maven.model.MavenCoordinate;
import com.arvindand.mcp.maven.model.NeedsAttention;
import com.arvindand.mcp.maven.model.PomUpgradeRecommendation;
import com.arvindand.mcp.maven.model.ProjectHealthAnalysis;
import com.arvindand.mcp.maven.model.Relocation;
import com.arvindand.mcp.maven.model.StabilityFilter;
import com.arvindand.mcp.maven.model.UpgradeMode;
import com.arvindand.mcp.maven.model.VersionComparison;
import com.arvindand.mcp.maven.model.VersionsByType;
import com.arvindand.mcp.maven.model.security.SecurityAssessment;
import com.arvindand.mcp.maven.model.security.VulnerabilityInfo;
import com.arvindand.mcp.maven.pom.EffectiveDependency;
import com.arvindand.mcp.maven.pom.EffectivePomResolver;
import com.arvindand.mcp.maven.pom.EffectivePomResult;
import com.arvindand.mcp.maven.pom.ManagedDeclaration;
import com.arvindand.mcp.maven.pom.Source;
import com.arvindand.mcp.maven.util.VersionComparator;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Relocated artifacts (the {@code mysql:mysql-connector-java} to {@code
 * com.mysql:mysql-connector-j} shape) in every tool that reports versions. Everything is mocked.
 */
class MavenDependencyToolsRelocationTest {

  private static final String OLD = "mysql:mysql-connector-java";
  private static final Relocation MYSQL =
      new Relocation("com.mysql", "mysql-connector-j", null, "Update the groupId and artifactId");

  private final MavenCentralService maven = mock(MavenCentralService.class);
  private final EffectivePomResolver resolver = mock(EffectivePomResolver.class);
  private final VulnerabilityService vulnerabilities = mock(VulnerabilityService.class);
  private final MavenDependencyTools tools =
      new MavenDependencyTools(
          maven,
          new VersionComparator(),
          new Context7Properties(false, null),
          vulnerabilities,
          resolver);
  private final JsonMapper json = new JsonMapper();

  MavenDependencyToolsRelocationTest() {
    when(maven.getAllVersions(any()))
        .thenAnswer(
            invocation -> {
              MavenCoordinate c = invocation.getArgument(0);
              return switch (c.groupId() + ":" + c.artifactId()) {
                case OLD -> List.of("8.0.33", "8.0.32");
                case "com.mysql:mysql-connector-j" -> List.of("9.2.0-rc1", "9.1.0", "8.4.0");
                case "org.slf4j:slf4j-api" -> List.of("2.0.17", "2.0.16");
                default -> List.of();
              };
            });
    when(maven.findRelocationOfLatest(argThat(c -> c != null && OLD.equals(ga(c)))))
        .thenReturn(Optional.of(MYSQL));
    when(maven.getRecentVersionsWithAccurateTimestamps(any(), anyInt()))
        .thenAnswer(
            invocation -> {
              MavenCoordinate c = invocation.getArgument(0);
              long now = System.currentTimeMillis();
              return List.of(
                  new MavenArtifact(
                      c.groupId() + ":" + c.artifactId() + ":x",
                      c.groupId(),
                      c.artifactId(),
                      "x",
                      "jar",
                      now - 86_400_000L));
            });
    when(vulnerabilities.scanBulk(any())).thenReturn(Map.of());
  }

  private static String ga(MavenCoordinate c) {
    return c.groupId() + ":" + c.artifactId();
  }

  // ------------------------------------------------------------ get_latest_version

  @Test
  void getLatestVersionReportsRelocationWithNewestStableAtNewCoordinates() {
    VersionsByType result = getSuccessData(tools.get_latest_version(OLD, null));

    assertThat(result.relocation()).isNotNull();
    assertThat(result.relocation().groupId()).isEqualTo("com.mysql");
    assertThat(result.relocation().artifactId()).isEqualTo("mysql-connector-j");
    assertThat(result.relocation().latestVersion()).isEqualTo("9.1.0");
    assertThat(json.writeValueAsString(result.relocation()))
        .isEqualTo(
            "{\"groupId\":\"com.mysql\",\"artifactId\":\"mysql-connector-j\","
                + "\"message\":\"Update the groupId and artifactId\",\"latestVersion\":\"9.1.0\"}");
  }

  @Test
  void getLatestVersionOmitsRelocationWhenNone() {
    VersionsByType result = getSuccessData(tools.get_latest_version("org.slf4j:slf4j-api", null));

    assertThat(result.relocation()).isNull();
    assertThat(json.writeValueAsString(result)).doesNotContain("relocation");
  }

  @Test
  void relocationLookupFailureNeverFailsTheTool() {
    when(maven.findRelocationOfLatest(any()))
        .thenThrow(
            new MavenCentralException(
                MavenCentralException.Reason.RATE_LIMITED, "limited", 54161, null));

    VersionsByType result = getSuccessData(tools.get_latest_version(OLD, null));

    assertThat(result.relocation()).isNull();
    assertThat(json.writeValueAsString(result)).doesNotContain("relocation");
  }

  @Test
  void relocationWithoutLookupableLatestStillReportsTheNewCoordinates() {
    doThrow(new MavenCentralException("down"))
        .when(maven)
        .getAllVersions(argThat(c -> c != null && "mysql-connector-j".equals(c.artifactId())));

    VersionsByType result = getSuccessData(tools.get_latest_version(OLD, null));

    assertThat(result.relocation()).isNotNull();
    assertThat(result.relocation().latestVersion()).isNull();
    assertThat(json.writeValueAsString(result.relocation())).doesNotContain("latestVersion");
  }

  // ------------------------------------------------------------ bulk tools

  @Test
  void checkMultipleDependenciesReportsRelocationPerDependency() {
    for (StabilityFilter filter : StabilityFilter.values()) {
      List<BulkCheckResult> results =
          getSuccessData(tools.check_multiple_dependencies(OLD + ",org.slf4j:slf4j-api", filter));

      assertThat(results.get(0).relocation()).as(filter.name()).isNotNull();
      assertThat(results.get(0).relocation().latestVersion()).isEqualTo("9.1.0");
      assertThat(results.get(1).relocation()).as(filter.name()).isNull();
      assertThat(json.writeValueAsString(results.get(1))).doesNotContain("relocation");
    }
  }

  @Test
  void bulkToolsLookUpEachDistinctArtifactOncePerCall() {
    tools.check_multiple_dependencies(OLD + ":8.0.33," + OLD + ":8.0.32," + OLD, null);
    verify(maven, times(1)).findRelocationOfLatest(any());

    tools.compare_dependency_versions(OLD + ":8.0.30," + OLD + ":8.0.31", null, false);
    verify(maven, times(2)).findRelocationOfLatest(any());
  }

  @Test
  void compareDependencyVersionsReportsRelocationEvenWhenUpToDate() {
    VersionComparison comparison =
        getSuccessData(
            tools.compare_dependency_versions(
                OLD + ":8.0.33,org.slf4j:slf4j-api:2.0.17", null, false));

    var mysql = comparison.dependencies().get(0);
    assertThat(mysql.updateAvailable()).isFalse();
    assertThat(mysql.relocation()).isNotNull();
    assertThat(mysql.relocation().coordinate()).isEqualTo("com.mysql:mysql-connector-j");
    var slf4j = comparison.dependencies().get(1);
    assertThat(slf4j.relocation()).isNull();
    assertThat(json.writeValueAsString(slf4j)).doesNotContain("relocation");
  }

  @Test
  void bulkCallsStopStartingRelocationLookupsOnceTheirBudgetIsSpent() {
    tools.setBulkRelocationBudget(Duration.ZERO);

    VersionComparison comparison =
        getSuccessData(
            tools.compare_dependency_versions(
                OLD + ":8.0.33,org.slf4j:slf4j-api:2.0.17", null, false));

    assertThat(comparison.dependencies())
        .as("version results are complete; relocation is omitted, not an error")
        .hasSize(2)
        .allSatisfy(
            dependency -> {
              assertThat(dependency.status()).isEqualTo("success");
              assertThat(dependency.relocation()).isNull();
            });
    verify(maven, never()).findRelocationOfLatest(any());
  }

  @Test
  void singleLookupsAreNotSubjectToTheBulkRelocationBudget() {
    tools.setBulkRelocationBudget(Duration.ZERO);

    VersionsByType result = getSuccessData(tools.get_latest_version(OLD, null));

    assertThat(result.relocation()).isNotNull();
  }

  @Test
  void bulkToolsSucceedWithoutRelocationWhenLookupThrows() {
    when(maven.findRelocationOfLatest(any())).thenThrow(new IllegalStateException("boom"));

    List<BulkCheckResult> bulk = getSuccessData(tools.check_multiple_dependencies(OLD, null));
    VersionComparison comparison =
        getSuccessData(tools.compare_dependency_versions(OLD + ":8.0.33", null, false));
    ProjectHealthAnalysis health =
        getSuccessData(tools.analyze_project_health(OLD + ":8.0.33", null, null, false, false));

    assertThat(bulk.get(0).status()).isEqualTo("found");
    assertThat(bulk.get(0).relocation()).isNull();
    assertThat(comparison.dependencies().get(0).status()).isEqualTo("success");
    assertThat(comparison.dependencies().get(0).relocation()).isNull();
    assertThat(health.dependencies().get(0).status()).isEqualTo("success");
    assertThat(health.dependencies().get(0).relocation()).isNull();
    assertThat(health.recommendations()).noneMatch(r -> r.contains("relocated"));
  }

  @Test
  void analyzeProjectHealthReportsRelocationAndAddsRecommendation() {
    ProjectHealthAnalysis health =
        getSuccessData(
            tools.analyze_project_health(
                OLD + ":8.0.33,org.slf4j:slf4j-api:2.0.17", null, null, false, false));

    var mysql = health.dependencies().get(0);
    assertThat(mysql.relocation()).isNotNull();
    assertThat(mysql.relocation().coordinate()).isEqualTo("com.mysql:mysql-connector-j");
    assertThat(health.dependencies().get(1).relocation()).isNull();
    assertThat(health.recommendations())
        .anySatisfy(
            r ->
                assertThat(r)
                    .contains("1 dependency is relocated")
                    .contains("mysql:mysql-connector-java -> com.mysql:mysql-connector-j"));
    assertThat(json.writeValueAsString(health.dependencies().get(1))).doesNotContain("relocation");
  }

  @Test
  void healthWithoutRelocationHasNoRelocationRecommendation() {
    ProjectHealthAnalysis health =
        getSuccessData(
            tools.analyze_project_health("org.slf4j:slf4j-api:2.0.17", null, null, false, false));

    assertThat(health.recommendations()).noneMatch(r -> r.contains("relocated"));
  }

  // ------------------------------------------------------------ recommend_pom_upgrades

  private PomUpgradeRecommendation recommend(EffectivePomResult resolved, boolean scan) {
    when(resolver.resolve("<pom/>")).thenReturn(resolved);
    return getSuccessData(
        tools.recommend_pom_upgrades("<pom/>", UpgradeMode.MINOR_PATCH, null, scan, null));
  }

  private static EffectiveDependency dep(String g, String a, String v, Source source) {
    return new EffectiveDependency(g, a, v, source, Optional.empty(), List.of());
  }

  private static EffectiveDependency managed(String g, String a, String v) {
    return new EffectiveDependency(
        g,
        a,
        v,
        Source.MANAGED,
        Optional.of(
            MavenCoordinate.of("org.springframework.boot", "spring-boot-dependencies", "3.3.0")),
        List.of());
  }

  @Test
  void explicitDependencyYieldsRelocatedEntryAndNoAction() {
    PomUpgradeRecommendation rec =
        recommend(
            new EffectivePomResult(
                List.of(dep("mysql", "mysql-connector-java", "8.0.33", Source.EXPLICIT)),
                List.of(),
                List.of(),
                List.of()),
            false);

    assertThat(rec.deterministicActions()).isEmpty();
    assertThat(rec.needsAttention())
        .singleElement()
        .isInstanceOfSatisfying(
            NeedsAttention.Relocated.class,
            r -> {
              assertThat(r.kind()).isEqualTo("relocated");
              assertThat(r.groupId()).isEqualTo("mysql");
              assertThat(r.artifactId()).isEqualTo("mysql-connector-java");
              assertThat(r.current()).isEqualTo("8.0.33");
              assertThat(r.source()).isEqualTo("EXPLICIT");
              assertThat(r.relocatedTo()).isEqualTo("com.mysql:mysql-connector-j");
              assertThat(r.relocatedToLatest()).isEqualTo("9.1.0");
              assertThat(r.message()).isEqualTo("Update the groupId and artifactId");
            });
    assertThat(rec.incomplete()).isNull();
    assertThat(json.writeValueAsString(rec.needsAttention().get(0)))
        .isEqualTo(
            "{\"groupId\":\"mysql\",\"artifactId\":\"mysql-connector-java\",\"current\":\"8.0.33\","
                + "\"source\":\"EXPLICIT\",\"relocatedTo\":\"com.mysql:mysql-connector-j\","
                + "\"relocatedToLatest\":\"9.1.0\",\"message\":\"Update the groupId and artifactId\","
                + "\"kind\":\"relocated\"}");
  }

  @Test
  void explicitOverrideAndRootManagementDeclarationsAreCovered() {
    PomUpgradeRecommendation rec =
        recommend(
            new EffectivePomResult(
                List.of(dep("mysql", "mysql-connector-java", "8.0.33", Source.EXPLICIT_OVERRIDE)),
                List.of(),
                List.of(),
                List.of(ManagedDeclaration.literal("mysql", "mysql-connector-java", "8.0.30")),
                List.of(),
                List.of()),
            false);

    assertThat(rec.needsAttention())
        .filteredOn(NeedsAttention.Relocated.class::isInstance)
        .extracting(a -> ((NeedsAttention.Relocated) a).source())
        .containsExactly("EXPLICIT_OVERRIDE", "MANAGED_DECLARATION");
    // one lookup per distinct groupId:artifactId even though it is declared twice
    verify(maven, times(1)).findRelocationOfLatest(any());
  }

  @Test
  void bomManagedDependenciesAreNotLookedUp() {
    PomUpgradeRecommendation rec =
        recommend(
            new EffectivePomResult(
                List.of(managed("mysql", "mysql-connector-java", "8.0.33")),
                List.of(),
                List.of(),
                List.of()),
            false);

    assertThat(rec.needsAttention()).noneMatch(NeedsAttention.Relocated.class::isInstance);
    verify(maven, never()).findRelocationOfLatest(any());
  }

  @Test
  void relocatedEntriesComeAfterVulnerableAndBeforeEveryOtherKind() {
    doReturn(List.of("2.0.0", "1.0.0"))
        .when(maven)
        .getAllVersions(argThat(c -> c != null && "major".equals(c.artifactId())));
    doReturn(List.of("1.0.0"))
        .when(maven)
        .getAllVersions(argThat(c -> c != null && "vuln".equals(c.artifactId())));
    SecurityAssessment vulnerable =
        SecurityAssessment.fromVulnerabilities(
            List.of(
                new VulnerabilityInfo(
                    "CVE-1", VulnerabilityInfo.Severity.CRITICAL, 9.8, "s", null, "http://x")),
            null);
    when(vulnerabilities.scanBulk(any()))
        .thenAnswer(
            invocation -> {
              List<MavenCoordinate> requested = invocation.getArgument(0);
              Map<String, SecurityAssessment> out = new java.util.HashMap<>();
              for (MavenCoordinate c : requested) {
                out.put(
                    c.toCoordinateString(),
                    "vuln".equals(c.artifactId()) ? vulnerable : SecurityAssessment.clean());
              }
              return out;
            });

    PomUpgradeRecommendation rec =
        recommend(
            new EffectivePomResult(
                List.of(
                    dep("g", "major", "1.0.0", Source.EXPLICIT),
                    dep("mysql", "mysql-connector-java", "8.0.33", Source.EXPLICIT),
                    dep("g", "vuln", "1.0.0", Source.EXPLICIT)),
                List.of(),
                List.of(),
                List.of()),
            true);

    assertThat(rec.needsAttention())
        .extracting(NeedsAttention::kind)
        .containsExactly("vulnerable", "relocated", "major_available");
  }

  @Test
  void relocationLookupFailureDoesNotMarkTheRunIncompleteOrAddWarnings() {
    when(maven.findRelocationOfLatest(any()))
        .thenThrow(
            new MavenCentralException(
                MavenCentralException.Reason.RATE_LIMITED, "limited", 54161, null));

    PomUpgradeRecommendation rec =
        recommend(
            new EffectivePomResult(
                List.of(dep("mysql", "mysql-connector-java", "8.0.33", Source.EXPLICIT)),
                List.of(),
                List.of(),
                List.of()),
            false);

    assertThat(rec.needsAttention()).noneMatch(NeedsAttention.Relocated.class::isInstance);
    assertThat(rec.incomplete()).isNull();
    assertThat(rec.warnings()).isEmpty();
  }
}
