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
import com.arvindand.mcp.maven.util.VersionComparator;
import java.util.List;
import org.junit.jupiter.api.Test;

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
    MavenDependencyTools tools =
        new MavenDependencyTools(
            mavenCentralService, versionComparator, context7Properties, vulnerabilityService);

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
}
