package com.arvindand.mcp.maven.service;

import static com.arvindand.mcp.maven.TestHelpers.getSuccessData;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.arvindand.mcp.maven.config.Context7Properties;
import com.arvindand.mcp.maven.model.StabilityFilter;
import com.arvindand.mcp.maven.model.ToolResponse;
import com.arvindand.mcp.maven.model.VersionComparison;
import com.arvindand.mcp.maven.model.VersionsByType;
import com.arvindand.mcp.maven.pom.EffectivePomResolver;
import com.arvindand.mcp.maven.util.VersionComparator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Focused unit tests for server-side dependency comparison behavior.
 *
 * @author Arvind Menon
 */
class MavenDependencyToolsUnitTest {

  @Test
  void compareDependencyVersionsIncludesSameMajorStableFallbackForMajorStableUpgrade() {
    MavenCentralService mavenCentralService = mock(MavenCentralService.class);
    VulnerabilityService vulnerabilityService = mock(VulnerabilityService.class);
    VersionComparator versionComparator = new VersionComparator();
    Context7Properties context7Properties = new Context7Properties(false, null);
    EffectivePomResolver pomResolver = mock(EffectivePomResolver.class);
    MavenDependencyTools tools =
        new MavenDependencyTools(
            mavenCentralService,
            versionComparator,
            context7Properties,
            vulnerabilityService,
            pomResolver);

    when(mavenCentralService.getAllVersions(any()))
        .thenReturn(List.of("4.0.0", "3.5.11", "3.5.10", "3.5.9"));

    ToolResponse response =
        tools.compare_dependency_versions(
            "org.springframework.boot:spring-boot-starter-parent:3.5.9",
            StabilityFilter.STABLE_ONLY,
            false);

    VersionComparison comparison = getSuccessData(response);
    assertThat(comparison.dependencies()).hasSize(1);

    VersionComparison.DependencyComparisonResult dep = comparison.dependencies().getFirst();
    assertThat(dep.updateType()).isEqualTo("major");
    assertThat(dep.sameMajorStableFallback()).isPresent();
    assertThat(dep.sameMajorStableFallback().get().latestVersion()).isEqualTo("3.5.11");
    assertThat(dep.sameMajorStableFallback().get().updateType()).isEqualTo("patch");
  }

  @ParameterizedTest
  @EnumSource(StabilityFilter.class)
  void comparisonNeverChangesCurrentGuavaFlavor(StabilityFilter filter) {
    MavenCentralService maven = mock(MavenCentralService.class);
    when(maven.getAllVersions(any()))
        .thenReturn(List.of("35.0.0", "33.8.0-jre", "33.7.2-android", "33.7.1-android"));

    VersionComparison comparison =
        getSuccessData(
            buildTools(maven)
                .compare_dependency_versions(
                    "com.google.guava:guava:33.7.1-android", filter, false));

    assertThat(comparison.dependencies())
        .singleElement()
        .satisfies(
            dep -> {
              assertThat(dep.latestVersion()).isEqualTo("33.7.2-android");
              assertThat(dep.latestType()).isEqualTo("stable");
              assertThat(dep.updateType()).isEqualTo("patch");
              assertThat(dep.updateAvailable()).isTrue();
            });
  }

  @ParameterizedTest
  @EnumSource(StabilityFilter.class)
  void unsuffixedCurrentVersionDoesNotSuppressNewPlatformVariants(StabilityFilter filter) {
    MavenCentralService maven = mock(MavenCentralService.class);
    when(maven.getAllVersions(any())).thenReturn(List.of("33.7.1-jre", "33.7.1-android", "23.0"));

    VersionComparison comparison =
        getSuccessData(
            buildTools(maven)
                .compare_dependency_versions("com.google.guava:guava:23.0", filter, false));

    assertThat(comparison.dependencies())
        .singleElement()
        .satisfies(
            dep -> {
              assertThat(dep.latestVersion()).isEqualTo("33.7.1-jre");
              assertThat(dep.updateType()).isEqualTo("major");
              assertThat(dep.updateAvailable()).isTrue();
            });
  }

  @Test
  void stableComparisonAndFallbackPreserveJdbcJavaTarget() {
    MavenCentralService maven = mock(MavenCentralService.class);
    when(maven.getAllVersions(any()))
        .thenReturn(
            List.of(
                "13.0.0.jre17",
                "13.0.0.jre11",
                "12.9.0.jre17",
                "12.9.0.jre11-SNAPSHOT",
                "12.8.2.jre11",
                "12.8.1.jre11"));

    VersionComparison comparison =
        getSuccessData(
            buildTools(maven)
                .compare_dependency_versions(
                    "com.microsoft.sqlserver:mssql-jdbc:12.8.1.jre11",
                    StabilityFilter.STABLE_ONLY,
                    false));

    assertThat(comparison.dependencies())
        .singleElement()
        .satisfies(
            dep -> {
              assertThat(dep.latestVersion()).isEqualTo("13.0.0.jre11");
              assertThat(dep.updateType()).isEqualTo("major");
              assertThat(dep.sameMajorStableFallback())
                  .contains(new VersionComparison.SameMajorStableFallback("12.8.2.jre11", "patch"));
            });
  }

  @Test
  void comparisonHasNoUpgradeWhenOnlyAnotherFlavorIsNewer() {
    MavenCentralService maven = mock(MavenCentralService.class);
    when(maven.getAllVersions(any())).thenReturn(List.of("33.8.0-jre", "33.7.1-android"));

    VersionComparison comparison =
        getSuccessData(
            buildTools(maven)
                .compare_dependency_versions(
                    "com.google.guava:guava:33.7.1-android", StabilityFilter.STABLE_ONLY, false));

    assertThat(comparison.dependencies())
        .singleElement()
        .satisfies(
            dep -> {
              assertThat(dep.latestVersion()).isEqualTo("33.7.1-android");
              assertThat(dep.updateAvailable()).isFalse();
            });
  }

  @Test
  void lookupWithoutCurrentVersionStillReportsNewestStableAcrossVariants() {
    MavenCentralService maven = mock(MavenCentralService.class);
    when(maven.getAllVersions(any()))
        .thenReturn(List.of("33.8.0-RC1-jre", "33.7.1-jre", "33.7.1-android"));

    VersionsByType result =
        getSuccessData(buildTools(maven).get_latest_version("com.google.guava:guava", null));

    assertThat(result.latestStable()).isPresent();
    assertThat(result.latestStable().get().version()).isEqualTo("33.7.1-jre");
    assertThat(result.latestRc()).isPresent();
    assertThat(result.latestRc().get().version()).isEqualTo("33.8.0-RC1-jre");
    assertThat(result.totalVersions()).isEqualTo(3);
  }

  private static MavenDependencyTools buildTools(MavenCentralService maven) {
    return new MavenDependencyTools(
        maven,
        new VersionComparator(),
        new Context7Properties(false, null),
        mock(VulnerabilityService.class),
        mock(EffectivePomResolver.class));
  }
}
