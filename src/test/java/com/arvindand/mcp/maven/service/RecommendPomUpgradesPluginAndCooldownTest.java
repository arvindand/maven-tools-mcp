package com.arvindand.mcp.maven.service;

import static com.arvindand.mcp.maven.TestHelpers.getSuccessData;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.arvindand.mcp.maven.config.Context7Properties;
import com.arvindand.mcp.maven.model.MavenArtifact;
import com.arvindand.mcp.maven.model.NeedsAttention;
import com.arvindand.mcp.maven.model.PomUpgradeRecommendation;
import com.arvindand.mcp.maven.model.UpgradeAction;
import com.arvindand.mcp.maven.model.UpgradeMode;
import com.arvindand.mcp.maven.pom.EffectiveDependency;
import com.arvindand.mcp.maven.pom.EffectivePomResolver;
import com.arvindand.mcp.maven.pom.EffectivePomResult;
import com.arvindand.mcp.maven.pom.PluginDeclaration;
import com.arvindand.mcp.maven.pom.Source;
import com.arvindand.mcp.maven.util.VersionComparator;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tool-level tests for {@code plugin_bump}, the {@code minReleaseAgeDays} cooldown and the
 * failed-lookup reporting of {@code recommend_pom_upgrades}. Everything is mocked; no network.
 */
class RecommendPomUpgradesPluginAndCooldownTest {

  private static final long DAY = TimeUnit.DAYS.toMillis(1);

  // ---------------------------------------------------------------- plugin_bump

  @Test
  void emitsPluginBumpForLiteralPluginWithDefaultGroupAndEditMetadata() {
    Fixture f = new Fixture();
    f.pom(
        plugins(
            PluginDeclaration.literal(
                "org.apache.maven.plugins",
                "maven-shade-plugin",
                "3.2.0",
                PluginDeclaration.BUILD_PLUGINS)));
    f.versions("maven-shade-plugin", "3.6.1", "3.2.5", "3.2.0");

    PomUpgradeRecommendation rec = f.recommend(UpgradeMode.MINOR_PATCH, null);

    assertThat(rec.deterministicActions())
        .singleElement()
        .satisfies(
            a -> {
              assertThat(a.kind()).isEqualTo(UpgradeAction.KIND_PLUGIN_BUMP);
              assertThat(a.groupId()).isEqualTo("org.apache.maven.plugins");
              assertThat(a.artifactId()).isEqualTo("maven-shade-plugin");
              assertThat(a.current()).isEqualTo("3.2.0");
              assertThat(a.target()).isEqualTo("3.6.1");
              assertThat(a.updateType()).isEqualTo("minor");
              assertThat(a.editTarget()).isEqualTo("literal_version");
              assertThat(a.propertyName()).isNull();
              assertThat(a.declaredIn()).isEqualTo(PluginDeclaration.BUILD_PLUGINS);
              assertThat(a.ownerArtifactId()).isNull();
              assertThat(a.security()).isNull();
            });
    assertThat(rec.warnings()).isEmpty();
    assertThat(rec.incomplete()).isNull();
  }

  @Test
  void pluginMajorGoesToNeedsAttentionWithPluginSourceAlongsideSameMajorAction() {
    Fixture f = new Fixture();
    f.pom(
        plugins(
            PluginDeclaration.property(
                "org.jacoco",
                "jacoco-maven-plugin",
                "0.8.5",
                "jacoco.version",
                PluginDeclaration.BUILD_PLUGINS)));
    f.versions("jacoco-maven-plugin", "1.0.0", "0.8.14", "0.8.5");

    PomUpgradeRecommendation rec = f.recommend(UpgradeMode.MINOR_PATCH, null);

    assertThat(rec.deterministicActions())
        .singleElement()
        .satisfies(
            a -> {
              assertThat(a.kind()).isEqualTo(UpgradeAction.KIND_PLUGIN_BUMP);
              assertThat(a.target()).isEqualTo("0.8.14");
              assertThat(a.editTarget()).isEqualTo("property");
              assertThat(a.propertyName()).isEqualTo("jacoco.version");
            });
    assertThat(rec.needsAttention())
        .singleElement()
        .isInstanceOfSatisfying(
            NeedsAttention.MajorAvailable.class,
            major -> {
              assertThat(major.source()).isEqualTo("PLUGIN");
              assertThat(major.latestStable()).isEqualTo("1.0.0");
              assertThat(major.currentMajorLatest()).isEqualTo("0.8.14");
            });
  }

  @Test
  void sharedPropertyInPluginsAndPluginManagementYieldsOneAction() {
    Fixture f = new Fixture();
    f.pom(
        plugins(
            PluginDeclaration.property(
                "g", "p", "1.0.0", "p.version", PluginDeclaration.BUILD_PLUGINS),
            PluginDeclaration.property(
                "g", "p", "1.0.0", "p.version", PluginDeclaration.PLUGIN_MANAGEMENT)));
    f.versions("p", "1.1.0", "1.0.0");

    PomUpgradeRecommendation rec = f.recommend(UpgradeMode.MINOR_PATCH, null);

    assertThat(rec.deterministicActions()).hasSize(1);
  }

  @Test
  void differentLiteralVersionsYieldOneActionPerDeclaration() {
    Fixture f = new Fixture();
    f.pom(
        plugins(
            PluginDeclaration.literal("g", "p", "1.0.0", PluginDeclaration.BUILD_PLUGINS),
            PluginDeclaration.literal("g", "p", "1.0.1", PluginDeclaration.PLUGIN_MANAGEMENT)));
    f.versions("p", "1.1.0", "1.0.1", "1.0.0");

    PomUpgradeRecommendation rec = f.recommend(UpgradeMode.MINOR_PATCH, null);

    assertThat(rec.deterministicActions())
        .extracting(UpgradeAction::declaredIn, UpgradeAction::current, UpgradeAction::target)
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple(PluginDeclaration.BUILD_PLUGINS, "1.0.0", "1.1.0"),
            org.assertj.core.groups.Tuple.tuple(
                PluginDeclaration.PLUGIN_MANAGEMENT, "1.0.1", "1.1.0"));
  }

  @Test
  void modeAllPromotesPluginMajor() {
    Fixture f = new Fixture();
    f.pom(plugins(PluginDeclaration.literal("g", "p", "1.0.0", PluginDeclaration.BUILD_PLUGINS)));
    f.versions("p", "2.0.0", "1.0.0");

    PomUpgradeRecommendation rec = f.recommend(UpgradeMode.ALL, null);

    assertThat(rec.deterministicActions())
        .singleElement()
        .satisfies(a -> assertThat(a.updateType()).isEqualTo("major"));
    assertThat(rec.needsAttention()).isEmpty();
  }

  // ---------------------------------------------------------------- cooldown

  @Test
  void absentOrNonPositiveCooldownPerformsNoTimestampLookups() {
    for (Integer days : new Integer[] {null, 0, -3}) {
      Fixture f = new Fixture();
      f.pom(dependency("lib", "1.0.0"));
      f.versions("lib", "1.0.2", "1.0.1", "1.0.0");

      PomUpgradeRecommendation rec = f.recommend(UpgradeMode.MINOR_PATCH, days);

      assertThat(rec.deterministicActions())
          .singleElement()
          .satisfies(
              a -> {
                assertThat(a.target()).isEqualTo("1.0.2");
                assertThat(a.heldBackFrom()).isNull();
              });
      verify(f.maven, never()).fetchArtifactWithTimestamp(any(), any());
    }
  }

  @Test
  void oldEnoughTargetIsUnchanged() {
    Fixture f = new Fixture();
    f.pom(dependency("lib", "1.0.0"));
    f.versions("lib", "1.0.2", "1.0.1", "1.0.0");
    f.publishedDaysAgo("lib", "1.0.2", 30);

    PomUpgradeRecommendation rec = f.recommend(UpgradeMode.MINOR_PATCH, 7);

    assertThat(rec.deterministicActions())
        .singleElement()
        .satisfies(
            a -> {
              assertThat(a.target()).isEqualTo("1.0.2");
              assertThat(a.heldBackFrom()).isNull();
            });
    assertThat(rec.warnings()).isEmpty();
  }

  @Test
  void tooYoungNewestFallsBackToOlderEligibleVersionAndReportsHeldBackFrom() {
    Fixture f = new Fixture();
    f.pom(dependency("lib", "1.0.0"));
    f.versions("lib", "1.0.3", "1.0.2", "1.0.1", "1.0.0");
    f.publishedDaysAgo("lib", "1.0.3", 1);
    f.publishedDaysAgo("lib", "1.0.2", 2);
    f.publishedDaysAgo("lib", "1.0.1", 20);

    PomUpgradeRecommendation rec = f.recommend(UpgradeMode.MINOR_PATCH, 7);

    assertThat(rec.deterministicActions())
        .singleElement()
        .satisfies(
            a -> {
              assertThat(a.target()).isEqualTo("1.0.1");
              assertThat(a.updateType()).isEqualTo("patch");
              assertThat(a.heldBackFrom()).isEqualTo("1.0.3");
            });
    assertThat(rec.warnings()).isEmpty();
  }

  @Test
  void fallbackNeverCrossesAMajorInMinorPatchMode() {
    Fixture f = new Fixture();
    f.pom(dependency("lib", "1.0.0"));
    f.versions("lib", "2.0.0", "1.5.0", "1.4.0", "1.0.0");
    f.publishedDaysAgo("lib", "1.5.0", 1);
    f.publishedDaysAgo("lib", "1.4.0", 50);
    f.publishedDaysAgo("lib", "2.0.0", 90);

    PomUpgradeRecommendation rec = f.recommend(UpgradeMode.MINOR_PATCH, 7);

    assertThat(rec.deterministicActions())
        .singleElement()
        .satisfies(
            a -> {
              assertThat(a.target()).isEqualTo("1.4.0");
              assertThat(a.heldBackFrom()).isEqualTo("1.5.0");
            });
    assertThat(rec.needsAttention())
        .singleElement()
        .isInstanceOfSatisfying(
            NeedsAttention.MajorAvailable.class,
            major -> {
              // informational fields are never filtered by the cooldown
              assertThat(major.latestStable()).isEqualTo("2.0.0");
              assertThat(major.currentMajorLatest()).isEqualTo("1.5.0");
            });
  }

  @Test
  void modeAllActionsAreSubjectToCooldown() {
    Fixture f = new Fixture();
    f.pom(dependency("lib", "1.0.0"));
    f.versions("lib", "2.0.0", "1.5.0", "1.0.0");
    f.publishedDaysAgo("lib", "2.0.0", 1);
    f.publishedDaysAgo("lib", "1.5.0", 40);

    PomUpgradeRecommendation rec = f.recommend(UpgradeMode.ALL, 7);

    assertThat(rec.deterministicActions())
        .singleElement()
        .satisfies(
            a -> {
              assertThat(a.target()).isEqualTo("1.5.0");
              assertThat(a.updateType()).isEqualTo("minor");
              assertThat(a.heldBackFrom()).isEqualTo("2.0.0");
            });
  }

  @Test
  void nothingEligibleEmitsNoActionAndOneAggregatedWarning() {
    Fixture f = new Fixture();
    f.pom(dependency("one", "1.0.0"), dependency("two", "2.0.0"));
    f.versions("one", "1.0.1", "1.0.0");
    f.versions("two", "2.0.1", "2.0.0");
    f.publishedDaysAgo("one", "1.0.1", 0);
    f.publishedDaysAgo("two", "2.0.1", 2);

    PomUpgradeRecommendation rec = f.recommend(UpgradeMode.MINOR_PATCH, 7);

    assertThat(rec.deterministicActions()).isEmpty();
    assertThat(rec.warnings())
        .singleElement()
        .asString()
        .contains("Release cooldown", "minReleaseAgeDays=7", "2 coordinates")
        .contains("com.example:one:1.0.1", "com.example:two:2.0.1");
    assertThat(rec.incomplete()).isNull();
  }

  @Test
  void timestampLookupFailureFailsClosedAndMarksRunIncomplete() {
    Fixture f = new Fixture();
    f.pom(dependency("lib", "1.0.0"));
    f.versions("lib", "1.0.1", "1.0.0");
    when(f.maven.fetchArtifactWithTimestamp(any(), eq("1.0.1")))
        .thenThrow(new MavenCentralException("HEAD failed"));

    PomUpgradeRecommendation rec = f.recommend(UpgradeMode.MINOR_PATCH, 7);

    assertThat(rec.deterministicActions()).isEmpty();
    assertThat(rec.warnings())
        .anySatisfy(
            w -> assertThat(w).contains("Release cooldown").contains("com.example:lib:1.0.1"))
        .anySatisfy(
            w -> assertThat(w).contains("Version lookup failed").contains("com.example:lib"));
    assertThat(rec.incomplete()).isTrue();
  }

  @Test
  void missingOrNonPositiveTimestampFailsClosed() {
    for (long timestamp : new long[] {0L, -1L}) {
      Fixture f = new Fixture();
      f.pom(dependency("lib", "1.0.0"));
      f.versions("lib", "1.0.1", "1.0.0");
      when(f.maven.fetchArtifactWithTimestamp(any(), eq("1.0.1")))
          .thenReturn(new MavenArtifact("id", "com.example", "lib", "1.0.1", "jar", timestamp));

      PomUpgradeRecommendation rec = f.recommend(UpgradeMode.MINOR_PATCH, 7);

      assertThat(rec.deterministicActions()).isEmpty();
      assertThat(rec.warnings())
          .singleElement()
          .asString()
          .contains("Release cooldown", "com.example:lib:1.0.1");
    }
  }

  @Test
  void timestampLookupsPerCoordinateAreBounded() {
    Fixture f = new Fixture();
    f.pom(dependency("lib", "1.0.0"));
    List<String> versions = new ArrayList<>();
    for (int patch = 12; patch >= 0; patch--) {
      versions.add("1.0." + patch);
      if (patch > 0) {
        f.publishedDaysAgo("lib", "1.0." + patch, 1);
      }
    }
    f.versions("lib", versions.toArray(String[]::new));

    PomUpgradeRecommendation rec = f.recommend(UpgradeMode.MINOR_PATCH, 7);

    assertThat(rec.deterministicActions()).isEmpty();
    verify(f.maven, times(5)).fetchArtifactWithTimestamp(any(), any());
  }

  @Test
  void cooldownAppliesToPluginBumpsToo() {
    Fixture f = new Fixture();
    f.pom(plugins(PluginDeclaration.literal("g", "p", "1.0.0", PluginDeclaration.BUILD_PLUGINS)));
    f.versions("p", "1.1.0", "1.0.5", "1.0.0");
    f.publishedDaysAgo("p", "1.1.0", 1, "g");
    f.publishedDaysAgo("p", "1.0.5", 100, "g");

    PomUpgradeRecommendation rec = f.recommend(UpgradeMode.MINOR_PATCH, 14);

    assertThat(rec.deterministicActions())
        .singleElement()
        .satisfies(
            a -> {
              assertThat(a.kind()).isEqualTo(UpgradeAction.KIND_PLUGIN_BUMP);
              assertThat(a.target()).isEqualTo("1.0.5");
              assertThat(a.heldBackFrom()).isEqualTo("1.1.0");
            });
  }

  // ---------------------------------------------------------------- failed lookups

  @Test
  void upstreamFailureIsReportedAndMarksRunIncomplete() {
    Fixture f = new Fixture();
    f.pom(dependency("a", "1.0.0"), dependency("b", "1.0.0"));
    when(f.maven.getAllVersions(any())).thenThrow(new MavenCentralException("HTTP 429"));

    PomUpgradeRecommendation rec = f.recommend(UpgradeMode.MINOR_PATCH, null);

    assertThat(rec.deterministicActions()).isEmpty();
    assertThat(rec.incomplete()).isTrue();
    assertThat(rec.warnings())
        .singleElement()
        .asString()
        .isEqualTo(
            "Version lookup failed for 2 coordinates (upstream error): com.example:a,"
                + " com.example:b");
  }

  @Test
  void uncheckedUpstreamExceptionsAreAlsoReported() {
    Fixture f = new Fixture();
    f.pom(dependency("a", "1.0.0"));
    when(f.maven.getAllVersions(any())).thenThrow(new IllegalStateException("circuit open"));

    PomUpgradeRecommendation rec = f.recommend(UpgradeMode.MINOR_PATCH, null);

    assertThat(rec.incomplete()).isTrue();
    assertThat(rec.warnings())
        .singleElement()
        .asString()
        .startsWith("Version lookup failed for 1 coordinate (upstream error)");
  }

  @Test
  void emptyVersionListIsReportedAsNotFoundWithoutIncomplete() {
    Fixture f = new Fixture();
    f.pom(dependency("internal", "1.0.0"));
    when(f.maven.getAllVersions(any())).thenReturn(List.of());

    PomUpgradeRecommendation rec = f.recommend(UpgradeMode.MINOR_PATCH, null);

    assertThat(rec.deterministicActions()).isEmpty();
    assertThat(rec.incomplete()).isNull();
    assertThat(rec.warnings())
        .singleElement()
        .isEqualTo("Not found in the configured repository (skipped): com.example:internal");
  }

  @Test
  void listedCoordinatesAreCapped() {
    Fixture f = new Fixture();
    List<EffectiveDependency> deps = new ArrayList<>();
    for (int i = 0; i < 12; i++) {
      deps.add(explicit("com.example", "lib" + i, "1.0.0"));
    }
    f.resolved = new EffectivePomResult(deps, List.of(), List.of(), List.of());
    when(f.maven.getAllVersions(any())).thenReturn(List.of());

    PomUpgradeRecommendation rec = f.recommend(UpgradeMode.MINOR_PATCH, null);

    assertThat(rec.warnings())
        .singleElement()
        .asString()
        .contains("com.example:lib9")
        .doesNotContain("com.example:lib10")
        .endsWith(" and 2 more");
  }

  @Test
  void healthyRunHasNoNewWarningsAndSerializesWithoutIncompleteField() {
    Fixture f = new Fixture();
    f.pom(dependency("lib", "1.0.0"));
    f.versions("lib", "1.0.1", "1.0.0");

    PomUpgradeRecommendation rec = f.recommend(UpgradeMode.MINOR_PATCH, null);

    assertThat(rec.warnings()).isEmpty();
    assertThat(rec.incomplete()).isNull();
    String json = new JsonMapper().writeValueAsString(rec);
    assertThat(json).doesNotContain("incomplete").doesNotContain("heldBackFrom");
  }

  @Test
  void serializesNewShapes() {
    JsonMapper mapper = new JsonMapper();
    UpgradeAction action =
        UpgradeAction.pluginBump(
                "org.apache.maven.plugins",
                "maven-shade-plugin",
                "3.2.0",
                "3.5.0",
                "minor",
                "literal_version",
                null,
                PluginDeclaration.BUILD_PLUGINS)
            .withHeldBackFrom("3.6.1");
    PomUpgradeRecommendation rec =
        new PomUpgradeRecommendation(List.of(action), List.of(), List.of("w"), null, Boolean.TRUE);

    String json = mapper.writeValueAsString(rec);

    assertThat(json)
        .contains("\"kind\":\"plugin_bump\"")
        .contains("\"heldBackFrom\":\"3.6.1\"")
        .contains("\"declaredIn\":\"build.plugins.plugin\"")
        .contains("\"incomplete\":true")
        .doesNotContain("propertyName");
  }

  // ---------------------------------------------------------------- helpers

  private static EffectiveDependency explicit(String groupId, String artifactId, String version) {
    return new EffectiveDependency(
        groupId, artifactId, version, Source.EXPLICIT, Optional.empty(), List.of());
  }

  private static EffectivePomResult dependency(String artifactId, String version) {
    return new EffectivePomResult(
        List.of(explicit("com.example", artifactId, version)), List.of(), List.of(), List.of());
  }

  private static EffectivePomResult plugins(PluginDeclaration... declarations) {
    return new EffectivePomResult(
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(declarations),
        List.of(),
        List.of());
  }

  /** Mocked resolver + repository with per-artifact version and publish-time stubbing. */
  private static final class Fixture {
    final MavenCentralService maven = mock(MavenCentralService.class);
    final EffectivePomResolver resolver = mock(EffectivePomResolver.class);
    EffectivePomResult resolved;

    void pom(EffectivePomResult result) {
      this.resolved = result;
    }

    void pom(EffectivePomResult first, EffectivePomResult second) {
      List<EffectiveDependency> deps = new ArrayList<>(first.dependencies());
      deps.addAll(second.dependencies());
      this.resolved = new EffectivePomResult(deps, List.of(), List.of(), List.of());
    }

    void versions(String artifactId, String... newestFirst) {
      when(maven.getAllVersions(
              org.mockito.ArgumentMatchers.argThat(
                  c -> c != null && artifactId.equals(c.artifactId()))))
          .thenReturn(List.of(newestFirst));
    }

    void publishedDaysAgo(String artifactId, String version, long days) {
      publishedDaysAgo(artifactId, version, days, "com.example");
    }

    void publishedDaysAgo(String artifactId, String version, long days, String groupId) {
      when(maven.fetchArtifactWithTimestamp(
              org.mockito.ArgumentMatchers.argThat(
                  c -> c != null && artifactId.equals(c.artifactId())),
              eq(version)))
          .thenReturn(
              new MavenArtifact(
                  groupId + ":" + artifactId + ":" + version,
                  groupId,
                  artifactId,
                  version,
                  "jar",
                  System.currentTimeMillis() - days * DAY));
    }

    PomUpgradeRecommendation recommend(UpgradeMode mode, Integer minReleaseAgeDays) {
      when(resolver.resolve("<pom/>")).thenReturn(resolved);
      MavenDependencyTools tools =
          new MavenDependencyTools(
              maven,
              new VersionComparator(),
              new Context7Properties(false, null),
              mock(VulnerabilityService.class),
              resolver);
      return getSuccessData(
          tools.recommend_pom_upgrades("<pom/>", mode, null, false, minReleaseAgeDays));
    }
  }
}
