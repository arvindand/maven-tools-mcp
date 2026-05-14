package com.arvindand.mcp.maven.service;

import static com.arvindand.mcp.maven.TestHelpers.getSuccessData;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.arvindand.mcp.maven.config.Context7Properties;
import com.arvindand.mcp.maven.model.MavenCoordinate;
import com.arvindand.mcp.maven.model.ToolResponse;
import com.arvindand.mcp.maven.pom.EffectiveDependency;
import com.arvindand.mcp.maven.pom.EffectivePomResolver;
import com.arvindand.mcp.maven.pom.EffectivePomResult;
import com.arvindand.mcp.maven.pom.Source;
import com.arvindand.mcp.maven.util.VersionComparator;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@code analyze_pom_dependencies} MCP tool method on {@link
 * MavenDependencyTools}. Exercises the adapter layer (delegation + error translation); the
 * underlying resolver has its own test suite.
 *
 * @author Arvind Menon
 */
class PomAnalysisToolTest {

  @Test
  void delegatesToSinglePomResolveWhenNoSideloadedBundle() {
    EffectivePomResolver resolver = mock(EffectivePomResolver.class);
    EffectivePomResult expected =
        new EffectivePomResult(
            List.of(
                new EffectiveDependency(
                    "com.fasterxml.jackson.core",
                    "jackson-databind",
                    "2.19.2",
                    Source.EXPLICIT,
                    Optional.empty(),
                    List.of())),
            List.of(),
            List.of());
    when(resolver.resolve("<project/>")).thenReturn(expected);

    MavenDependencyTools tools = buildTools(resolver);

    ToolResponse response = tools.analyze_pom_dependencies("<project/>", null);

    EffectivePomResult actual = getSuccessData(response);
    assertThat(actual).isEqualTo(expected);
    verify(resolver).resolve("<project/>");
    verify(resolver, never()).resolve(eq("<project/>"), anyList());
  }

  @Test
  void delegatesToBundleResolveWhenSideloadedPomsProvided() {
    EffectivePomResolver resolver = mock(EffectivePomResolver.class);
    EffectivePomResult expected =
        new EffectivePomResult(
            List.of(), List.of(MavenCoordinate.of("com.example", "parent", "1.0.0")), List.of());
    when(resolver.resolve(eq("<child/>"), anyList())).thenReturn(expected);

    MavenDependencyTools tools = buildTools(resolver);

    ToolResponse response = tools.analyze_pom_dependencies("<child/>", List.of("<parent/>"));

    EffectivePomResult actual = getSuccessData(response);
    assertThat(actual).isEqualTo(expected);
    verify(resolver).resolve("<child/>", List.of("<parent/>"));
    verify(resolver, never()).resolve("<child/>");
  }

  @Test
  void emptySideloadedListTakesSinglePomPath() {
    EffectivePomResolver resolver = mock(EffectivePomResolver.class);
    when(resolver.resolve("<project/>"))
        .thenReturn(new EffectivePomResult(List.of(), List.of(), List.of()));

    MavenDependencyTools tools = buildTools(resolver);

    tools.analyze_pom_dependencies("<project/>", List.of());

    verify(resolver).resolve("<project/>");
    verify(resolver, never()).resolve(eq("<project/>"), anyList());
  }

  @Test
  void translatesParseFailureToInvalidPomInputError() {
    EffectivePomResolver resolver = mock(EffectivePomResolver.class);
    when(resolver.resolve("<not-a-pom"))
        .thenThrow(new IllegalArgumentException("Input is not a valid POM: malformed XML"));

    MavenDependencyTools tools = buildTools(resolver);

    ToolResponse response = tools.analyze_pom_dependencies("<not-a-pom", null);

    assertThat(response).isInstanceOf(ToolResponse.Error.class);
    assertThat(((ToolResponse.Error) response).error().message())
        .startsWith("Invalid POM input:")
        .contains("malformed XML");
  }

  @Test
  void unexpectedExceptionsBecomeGenericErrorResponses() {
    EffectivePomResolver resolver = mock(EffectivePomResolver.class);
    when(resolver.resolve("<project/>")).thenThrow(new RuntimeException("network exploded"));

    MavenDependencyTools tools = buildTools(resolver);

    ToolResponse response = tools.analyze_pom_dependencies("<project/>", null);

    assertThat(response).isInstanceOf(ToolResponse.Error.class);
    assertThat(((ToolResponse.Error) response).error().message())
        .startsWith("Unexpected error")
        .contains("network exploded");
  }

  private static MavenDependencyTools buildTools(EffectivePomResolver resolver) {
    return new MavenDependencyTools(
        mock(MavenCentralService.class),
        new VersionComparator(),
        new Context7Properties(false, null),
        mock(VulnerabilityService.class),
        resolver);
  }
}
