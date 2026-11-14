package com.arvindand.mcp.maven.service;

import static com.arvindand.mcp.maven.TestHelpers.getSuccessData;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.arvindand.mcp.maven.config.Context7Properties;
import com.arvindand.mcp.maven.model.MavenCoordinate;
import com.arvindand.mcp.maven.model.NeedsAttention;
import com.arvindand.mcp.maven.model.PomUpgradeRecommendation;
import com.arvindand.mcp.maven.model.UpgradeAction;
import com.arvindand.mcp.maven.model.UpgradeMode;
import com.arvindand.mcp.maven.model.security.SecurityAssessment;
import com.arvindand.mcp.maven.model.security.VulnerabilityInfo;
import com.arvindand.mcp.maven.pom.EffectiveDependency;
import com.arvindand.mcp.maven.pom.EffectivePomResolver;
import com.arvindand.mcp.maven.pom.EffectivePomResult;
import com.arvindand.mcp.maven.pom.ManagedDeclaration;
import com.arvindand.mcp.maven.pom.PluginDependencyDeclaration;
import com.arvindand.mcp.maven.pom.PropertyOverride;
import com.arvindand.mcp.maven.pom.Source;
import com.arvindand.mcp.maven.util.VersionComparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests for same-major updates alongside a newer major, property-pin entries, and the security
 * awareness of {@code recommend_pom_upgrades}. Mocks every network-facing collaborator.
 */
class RecommendPomUpgradesSecurityAndPinsTest {

  private final EffectivePomResolver resolver = mock(EffectivePomResolver.class);
  private final MavenCentralService maven = mock(MavenCentralService.class);
  private final VulnerabilityService vulnerabilities = mock(VulnerabilityService.class);
  private final Map<String, List<String>> versions = new HashMap<>();
  private final Map<String, SecurityAssessment> assessments = new HashMap<>();
  private final MavenDependencyTools tools =
      new MavenDependencyTools(
          maven,
          new VersionComparator(),
          new Context7Properties(false, null),
          vulnerabilities,
          resolver);

  RecommendPomUpgradesSecurityAndPinsTest() {
    when(maven.getAllVersions(any()))
        .thenAnswer(
            invocation -> {
              MavenCoordinate c = invocation.getArgument(0);
              return versions.getOrDefault(c.groupId() + ":" + c.artifactId(), List.of());
            });
    when(vulnerabilities.scanBulk(any()))
        .thenAnswer(
            invocation -> {
              List<MavenCoordinate> requested = invocation.getArgument(0);
              Map<String, SecurityAssessment> result = new HashMap<>();
              for (MavenCoordinate c : requested) {
                result.put(
                    c.toCoordinateString(),
                    assessments.getOrDefault(c.toCoordinateString(), SecurityAssessment.clean()));
              }
              return result;
            });
  }

  // ---- Task 1: a newer major must not block same-major updates ----

  @Test
  void parentWithNewerMajorAlsoGetsSameMajorBomBump() {
    when(resolver.resolve("<pom/>"))
        .thenReturn(
            new EffectivePomResult(
                List.of(),
                List.of(MavenCoordinate.of("org.springframework.boot", "parent", "3.3.0")),
                List.of(),
                List.of()));
    versions.put("org.springframework.boot:parent", List.of("4.1.2", "3.5.16", "3.3.0"));

    PomUpgradeRecommendation rec = recommend(UpgradeMode.MINOR_PATCH, false);

    assertThat(rec.deterministicActions())
        .singleElement()
        .satisfies(
            a -> {
              assertThat(a.kind()).isEqualTo(UpgradeAction.KIND_BOM_BUMP);
              assertThat(a.target()).isEqualTo("3.5.16");
              assertThat(a.updateType()).isEqualTo("minor");
            });
    assertThat(rec.needsAttention())
        .singleElement()
        .isInstanceOfSatisfying(
            NeedsAttention.MajorAvailable.class,
            m -> {
              assertThat(m.currentMajorLatest()).isEqualTo("3.5.16");
              assertThat(m.latestStable()).isEqualTo("4.1.2");
            });
  }

  @Test
  void explicitDependencyWithNewerMajorAlsoGetsPatchBump() {
    when(resolver.resolve("<pom/>"))
        .thenReturn(
            new EffectivePomResult(
                List.of(explicit("commons-collections", "commons-collections", "3.2.1")),
                List.of(),
                List.of(),
                List.of()));
    versions.put("commons-collections:commons-collections", List.of("4.5.0", "3.2.2", "3.2.1"));

    PomUpgradeRecommendation rec = recommend(UpgradeMode.MINOR_PATCH, false);

    assertThat(rec.deterministicActions())
        .singleElement()
        .satisfies(
            a -> {
              assertThat(a.kind()).isEqualTo(UpgradeAction.KIND_EXPLICIT_BUMP);
              assertThat(a.target()).isEqualTo("3.2.2");
              assertThat(a.updateType()).isEqualTo("patch");
            });
    assertThat(rec.needsAttention())
        .singleElement()
        .isInstanceOf(NeedsAttention.MajorAvailable.class);
  }

  @Test
  void managedDeclarationAndPluginDependencyKeepEditMetadataForSameMajorAction() {
    when(resolver.resolve("<pom/>"))
        .thenReturn(
            new EffectivePomResult(
                List.of(),
                List.of(),
                List.of(),
                List.of(ManagedDeclaration.property("g", "managed", "1.0.0", "managed.version")),
                List.of(
                    PluginDependencyDeclaration.literal(
                        "g",
                        "plugin-lib",
                        "1.0.0",
                        "org.apache.maven.plugins",
                        "maven-x-plugin",
                        PluginDependencyDeclaration.BUILD_PLUGINS)),
                List.of()));
    versions.put("g:managed", List.of("2.0.0", "1.1.0", "1.0.0"));
    versions.put("g:plugin-lib", List.of("2.0.0", "1.0.5", "1.0.0"));

    PomUpgradeRecommendation rec = recommend(UpgradeMode.MINOR_PATCH, false);

    assertThat(rec.deterministicActions())
        .anySatisfy(
            a -> {
              assertThat(a.kind()).isEqualTo(UpgradeAction.KIND_MANAGED_DECL_BUMP);
              assertThat(a.target()).isEqualTo("1.1.0");
              assertThat(a.editTarget()).isEqualTo("property");
              assertThat(a.propertyName()).isEqualTo("managed.version");
            })
        .anySatisfy(
            a -> {
              assertThat(a.kind()).isEqualTo(UpgradeAction.KIND_PLUGIN_DEP_BUMP);
              assertThat(a.target()).isEqualTo("1.0.5");
              assertThat(a.ownerArtifactId()).isEqualTo("maven-x-plugin");
            });
    assertThat(rec.needsAttention())
        .hasSize(2)
        .allMatch(n -> n instanceof NeedsAttention.MajorAvailable);
  }

  // ---- Task 2: property pins ----

  @Test
  void emitsPropertyOverrideAttentionWithRelationAndNoDeterministicAction() {
    when(resolver.resolve("<pom/>"))
        .thenReturn(
            resultWithOverrides(
                new PropertyOverride(
                    "snakeyaml.version",
                    "1.33",
                    "2.2",
                    "org.springframework.boot:spring-boot-dependencies:3.3.0",
                    List.of("org.yaml:snakeyaml"),
                    1),
                new PropertyOverride("same.version", "1.0", "1.0", "p:p:1", List.of("a:b"), 1),
                new PropertyOverride("ahead.version", "3.0", "2.0", "p:p:1", List.of("c:d"), 1),
                new PropertyOverride("odd.version", "1.0", null, "p:p:1", List.of("e:f"), 3)));
    versions.put("org.yaml:snakeyaml", List.of("2.5", "2.2", "1.33"));

    PomUpgradeRecommendation rec = recommend(UpgradeMode.MINOR_PATCH, false);

    assertThat(rec.deterministicActions()).isEmpty();
    assertThat(rec.needsAttention())
        .hasSize(4)
        .allMatch(n -> n instanceof NeedsAttention.PropertyOverride);
    NeedsAttention.PropertyOverride behind =
        (NeedsAttention.PropertyOverride) rec.needsAttention().get(0);
    assertThat(behind.kind()).isEqualTo("property_override");
    assertThat(behind.groupId()).isEqualTo("org.yaml");
    assertThat(behind.artifactId()).isEqualTo("snakeyaml");
    assertThat(behind.relation()).isEqualTo("behind_inherited");
    assertThat(behind.latestOnCentral()).isEqualTo("2.5");
    assertThat(((NeedsAttention.PropertyOverride) rec.needsAttention().get(1)).relation())
        .isEqualTo("equal_to_inherited");
    assertThat(((NeedsAttention.PropertyOverride) rec.needsAttention().get(2)).relation())
        .isEqualTo("ahead_of_inherited");
    NeedsAttention.PropertyOverride unknown =
        (NeedsAttention.PropertyOverride) rec.needsAttention().get(3);
    assertThat(unknown.relation()).isEqualTo("unknown");
    assertThat(unknown.affectedCount()).isEqualTo(3);
    assertThat(unknown.latestOnCentral()).isNull();
  }

  // ---- Task 3: security ----

  @Test
  void vulnerableExplicitDependencyWithCleanTargetCarriesSecurityAndNoVulnerableItem() {
    when(resolver.resolve("<pom/>"))
        .thenReturn(result(explicit("org.apache.logging.log4j", "log4j-core", "2.14.1")));
    versions.put("org.apache.logging.log4j:log4j-core", List.of("2.14.2", "2.14.1"));
    assessments.put(
        "org.apache.logging.log4j:log4j-core:2.14.1",
        vulnerable(VulnerabilityInfo.Severity.CRITICAL, "CVE-2021-44228"));

    PomUpgradeRecommendation rec = recommend(UpgradeMode.MINOR_PATCH, true);

    assertThat(rec.deterministicActions())
        .singleElement()
        .satisfies(
            a -> {
              assertThat(a.target()).isEqualTo("2.14.2");
              assertThat(a.security()).isNotNull();
              assertThat(a.security().maxSeverity())
                  .isEqualTo(SecurityAssessment.Severity.CRITICAL);
              assertThat(a.security().vulnerabilityCount()).isEqualTo(1);
              assertThat(a.security().vulnerabilityIds()).containsExactly("CVE-2021-44228");
              assertThat(a.security().targetStatus()).isEqualTo(SecurityAssessment.Status.OK);
            });
    assertThat(rec.needsAttention()).isEmpty();
    assertThat(rec.securitySummary().scanned()).isEqualTo(1);
    assertThat(rec.securitySummary().vulnerable()).isEqualTo(1);
    assertThat(rec.securitySummary().worstSeverity())
        .isEqualTo(SecurityAssessment.Severity.CRITICAL);
    // one scan for current versions, one for the single vulnerable action target
    verify(vulnerabilities, times(2)).scanBulk(any());
  }

  @Test
  void vulnerableTargetProducesBothSecurityActionAndVulnerableItem() {
    when(resolver.resolve("<pom/>")).thenReturn(result(explicit("g", "lib", "1.0.0")));
    versions.put("g:lib", List.of("1.0.1", "1.0.0"));
    assessments.put("g:lib:1.0.0", vulnerable(VulnerabilityInfo.Severity.HIGH, "CVE-1"));
    assessments.put("g:lib:1.0.1", vulnerable(VulnerabilityInfo.Severity.HIGH, "CVE-2"));

    PomUpgradeRecommendation rec = recommend(UpgradeMode.MINOR_PATCH, true);

    assertThat(rec.deterministicActions())
        .singleElement()
        .satisfies(
            a ->
                assertThat(a.security().targetStatus())
                    .isEqualTo(SecurityAssessment.Status.VULNERABLE));
    assertThat(rec.needsAttention())
        .singleElement()
        .isInstanceOfSatisfying(
            NeedsAttention.Vulnerable.class,
            v -> {
              assertThat(v.current()).isEqualTo("1.0.0");
              assertThat(v.source()).isEqualTo("EXPLICIT");
              assertThat(v.highIds()).containsExactly("CVE-1");
              assertThat(v.criticalIds()).isEmpty();
              assertThat(v.latestOnCentral()).isEqualTo("1.0.1");
            });
  }

  @Test
  void vulnerableManagedDependencyGetsVulnerableItemWithManagedBy() {
    MavenCoordinate boot = MavenCoordinate.of("org.springframework.boot", "parent", "3.3.0");
    when(resolver.resolve("<pom/>"))
        .thenReturn(
            new EffectivePomResult(
                List.of(
                    new EffectiveDependency(
                        "org.yaml",
                        "snakeyaml",
                        "1.33",
                        Source.MANAGED,
                        Optional.of(boot),
                        List.of())),
                List.of(),
                List.of(),
                List.of()));
    versions.put("org.yaml:snakeyaml", List.of("2.5", "1.33"));
    assessments.put(
        "org.yaml:snakeyaml:1.33", withFix(VulnerabilityInfo.Severity.HIGH, "CVE-9", "2.0"));

    PomUpgradeRecommendation rec = recommend(UpgradeMode.MINOR_PATCH, true);

    assertThat(rec.deterministicActions()).isEmpty();
    assertThat(rec.needsAttention())
        .singleElement()
        .isInstanceOfSatisfying(
            NeedsAttention.Vulnerable.class,
            v -> {
              assertThat(v.source()).isEqualTo("MANAGED");
              assertThat(v.managedByCoordinate()).isEqualTo(boot.toCoordinateString());
              assertThat(v.fixedInVersion()).isEqualTo("2.0");
              assertThat(v.latestOnCentral()).isEqualTo("2.5");
            });
    // no actions -> only the first scan
    verify(vulnerabilities, times(1)).scanBulk(any());
  }

  @Test
  void disabledScanMakesNoCallsAndOmitsSummary() {
    when(resolver.resolve("<pom/>")).thenReturn(result(explicit("g", "lib", "1.0.0")));
    versions.put("g:lib", List.of("1.0.1", "1.0.0"));
    assessments.put("g:lib:1.0.0", vulnerable(VulnerabilityInfo.Severity.CRITICAL, "CVE-1"));

    PomUpgradeRecommendation rec = recommend(UpgradeMode.MINOR_PATCH, false);

    verify(vulnerabilities, never()).scanBulk(any());
    assertThat(rec.securitySummary()).isNull();
    assertThat(rec.deterministicActions())
        .singleElement()
        .satisfies(a -> assertThat(a.security()).isNull());
    assertThat(rec.needsAttention()).isEmpty();
    assertThat(new JsonMapper().writeValueAsString(rec))
        .doesNotContain("securitySummary", "security\"");
  }

  @Test
  void unknownAssessmentsAreCountedWarnedAndNeverClean() {
    when(resolver.resolve("<pom/>"))
        .thenReturn(result(explicit("g", "a", "1.0.0"), explicit("g", "b", "1.0.0")));
    org.mockito.Mockito.doReturn(Map.of("g:a:1.0.0", SecurityAssessment.unknown("OSV down")))
        .when(vulnerabilities)
        .scanBulk(any());

    PomUpgradeRecommendation rec = recommend(UpgradeMode.MINOR_PATCH, true);

    assertThat(rec.securitySummary().unknown()).isEqualTo(2);
    assertThat(rec.securitySummary().clean()).isZero();
    assertThat(rec.warnings()).contains("Security scan unavailable for 2 dependencies");
    assertThat(rec.needsAttention()).isEmpty();
  }

  @Test
  void scanFailureNeverFailsTheTool() {
    when(resolver.resolve("<pom/>")).thenReturn(result(explicit("g", "a", "1.0.0")));
    org.mockito.Mockito.doThrow(new IllegalStateException("boom"))
        .when(vulnerabilities)
        .scanBulk(any());

    PomUpgradeRecommendation rec = recommend(UpgradeMode.MINOR_PATCH, true);

    assertThat(rec.securitySummary().unknown()).isEqualTo(1);
    assertThat(rec.warnings()).contains("Security scan unavailable for 1 dependencies");
  }

  @Test
  void vulnerableEntriesComeFirstMostSevereFirstAndKeepExistingOrder() {
    when(resolver.resolve("<pom/>"))
        .thenReturn(
            new EffectivePomResult(
                List.of(
                    explicit("g", "medium", "1.0.0"),
                    explicit("g", "critical", "1.0.0"),
                    explicit("g", "major", "1.0.0")),
                List.of(),
                List.of(),
                List.of()));
    versions.put("g:medium", List.of("1.0.0"));
    versions.put("g:critical", List.of("1.0.0"));
    versions.put("g:major", List.of("2.0.0", "1.0.0"));
    assessments.put("g:medium:1.0.0", vulnerable(VulnerabilityInfo.Severity.MEDIUM, "M-1"));
    assessments.put("g:critical:1.0.0", vulnerable(VulnerabilityInfo.Severity.CRITICAL, "C-1"));

    PomUpgradeRecommendation rec = recommend(UpgradeMode.MINOR_PATCH, true);

    assertThat(rec.needsAttention())
        .extracting(NeedsAttention::kind)
        .containsExactly("vulnerable", "vulnerable", "major_available");
    assertThat(rec.needsAttention().get(0).artifactId()).isEqualTo("critical");
    assertThat(rec.needsAttention().get(1).artifactId()).isEqualTo("medium");
  }

  @Test
  void serializesNewShapesWithCamelCaseAndOmitsNulls() {
    JsonMapper mapper = new JsonMapper();
    String vulnerable =
        mapper.writeValueAsString(
            new NeedsAttention.Vulnerable(
                "g",
                "a",
                "1.0",
                "MANAGED",
                null,
                SecurityAssessment.Severity.HIGH,
                2,
                List.of(),
                List.of("CVE-1"),
                null,
                null));
    assertThat(vulnerable)
        .contains("\"kind\":\"vulnerable\"", "\"maxSeverity\":\"HIGH\"", "\"highIds\":[\"CVE-1\"]")
        .doesNotContain("managedByCoordinate", "fixedInVersion", "latestOnCentral");
    String action =
        mapper.writeValueAsString(
            UpgradeAction.explicitBump("g", "a", "1.0", "1.1", "minor")
                .withSecurity(
                    new UpgradeAction.Security(
                        SecurityAssessment.Severity.HIGH,
                        1,
                        List.of("CVE-1"),
                        SecurityAssessment.Status.OK)));
    assertThat(action)
        .contains("\"security\":{", "\"targetStatus\":\"OK\"", "\"vulnerabilityIds\":[\"CVE-1\"]");
    assertThat(
            mapper.writeValueAsString(UpgradeAction.explicitBump("g", "a", "1.0", "1.1", "minor")))
        .doesNotContain("security");
  }

  // ---- helpers ----

  private PomUpgradeRecommendation recommend(UpgradeMode mode, boolean scan) {
    return getSuccessData(tools.recommend_pom_upgrades("<pom/>", mode, null, scan, null));
  }

  private static EffectivePomResult result(EffectiveDependency... deps) {
    return new EffectivePomResult(List.of(deps), List.of(), List.of(), List.of());
  }

  private static EffectivePomResult resultWithOverrides(PropertyOverride... overrides) {
    return new EffectivePomResult(
        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(overrides), List.of());
  }

  private static SecurityAssessment vulnerable(VulnerabilityInfo.Severity severity, String id) {
    return withFix(severity, id, null);
  }

  private static SecurityAssessment withFix(
      VulnerabilityInfo.Severity severity, String id, String fixedIn) {
    return SecurityAssessment.fromVulnerabilities(
        List.of(new VulnerabilityInfo(id, severity, 9.0, "s", fixedIn, "http://x")), fixedIn);
  }

  private static EffectiveDependency explicit(String groupId, String artifactId, String version) {
    return new EffectiveDependency(
        groupId, artifactId, version, Source.EXPLICIT, Optional.empty(), List.of());
  }
}
