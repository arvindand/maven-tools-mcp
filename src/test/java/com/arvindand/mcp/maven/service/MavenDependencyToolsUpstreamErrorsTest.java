package com.arvindand.mcp.maven.service;

import static com.arvindand.mcp.maven.TestHelpers.getSuccessData;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.arvindand.mcp.maven.config.Context7Properties;
import com.arvindand.mcp.maven.model.BulkCheckResult;
import com.arvindand.mcp.maven.model.MavenCoordinate;
import com.arvindand.mcp.maven.model.McpError;
import com.arvindand.mcp.maven.model.PomUpgradeRecommendation;
import com.arvindand.mcp.maven.model.ProjectHealthAnalysis;
import com.arvindand.mcp.maven.model.StabilityFilter;
import com.arvindand.mcp.maven.model.ToolResponse;
import com.arvindand.mcp.maven.model.UpgradeMode;
import com.arvindand.mcp.maven.model.VersionComparison;
import com.arvindand.mcp.maven.pom.EffectiveDependency;
import com.arvindand.mcp.maven.pom.EffectivePomResolver;
import com.arvindand.mcp.maven.pom.EffectivePomResult;
import com.arvindand.mcp.maven.pom.Source;
import com.arvindand.mcp.maven.util.BoundedBatch;
import com.arvindand.mcp.maven.util.VersionComparator;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tool-level reporting of upstream failures: precise {@link McpError}s instead of raw upstream text
 * or "Unexpected error". Everything is mocked; no network.
 */
class MavenDependencyToolsUpstreamErrorsTest {

  private static final String RAW_BODY =
      "Your ip has exceeded rate limits. Find out more here"
          + " https://central.sonatype.org/faq/429-error/";

  private final MavenCentralService maven = mock(MavenCentralService.class);
  private final EffectivePomResolver resolver = mock(EffectivePomResolver.class);
  private final MavenDependencyTools tools =
      new MavenDependencyTools(
          maven,
          new VersionComparator(),
          new Context7Properties(false, null),
          mock(VulnerabilityService.class),
          resolver);

  private static HttpClientErrorException tooMany(String retryAfter) {
    HttpHeaders headers = new HttpHeaders();
    if (retryAfter != null) {
      headers.add(HttpHeaders.RETRY_AFTER, retryAfter);
    }
    return HttpClientErrorException.create(
        HttpStatus.TOO_MANY_REQUESTS,
        "Too Many Requests",
        headers,
        RAW_BODY.getBytes(StandardCharsets.UTF_8),
        null);
  }

  private static McpError errorOf(ToolResponse response) {
    assertThat(response).isInstanceOf(ToolResponse.Error.class);
    return ((ToolResponse.Error) response).error();
  }

  // ------------------------------------------------------------ whole-tool errors

  @Test
  void batchDeadlineBecomesARetryableTimeoutNotAnInternalError() {
    when(maven.getAllVersions(any()))
        .thenThrow(new BoundedBatch.DeadlineExceededException(new TimeoutException()));

    McpError error = errorOf(tools.get_latest_version("g:a", null));

    assertThat(error.code()).isEqualTo(McpError.EXTERNAL_SERVICE_UNAVAILABLE);
    assertThat(error.data()).containsEntry("reason", "BATCH_TIMEOUT");
    assertThat(error.retryAfter()).isEqualTo(2);
    assertThat(error.message()).contains("retry").doesNotContain("Unexpected error");
  }

  @Test
  void rateLimitedFailureBecomesRateLimitedErrorWithHonestRetryHint() {
    when(maven.getAllVersions(any())).thenThrow(tooMany("54161"));

    McpError error = errorOf(tools.get_latest_version("g:a", null));

    assertThat(error.code()).isEqualTo(McpError.RATE_LIMITED);
    assertThat(error.retryAfter()).isEqualTo(54161);
    assertThat(error.message()).contains("~15h").doesNotContain("exceeded rate limits");
  }

  @Test
  void throttledNotFoundFromServiceIsRateLimitedToo() {
    when(maven.getAllVersions(any()))
        .thenThrow(
            new MavenCentralException(
                MavenCentralException.Reason.RATE_LIMITED, "throttled", 54161, null));

    assertThat(errorOf(tools.get_latest_version("g:a", null)).code())
        .isEqualTo(McpError.RATE_LIMITED);
  }

  @Test
  void openCircuitBecomesUnavailableNotInternalError() {
    when(maven.getAllVersions(any()))
        .thenThrow(
            CallNotPermittedException.createCallNotPermittedException(
                CircuitBreaker.ofDefaults("maven-central")));

    McpError error = errorOf(tools.get_latest_version("g:a", null));

    assertThat(error.code()).isEqualTo(McpError.EXTERNAL_SERVICE_UNAVAILABLE);
    assertThat(error.data()).containsEntry("reason", "CIRCUIT_OPEN");
    assertThat(error.retryAfter())
        .isEqualTo(MavenCentralException.DEFAULT_CIRCUIT_OPEN_RETRY_SECONDS);
    assertThat(error.message()).doesNotContain("Unexpected error");
  }

  @Test
  void networkFailureBecomesUnavailableWithClassifiedMessage() {
    when(maven.getAllVersions(any()))
        .thenThrow(new ResourceAccessException("timeout", new SocketTimeoutException("slow")));

    McpError error = errorOf(tools.get_latest_version("g:a", null));

    assertThat(error.code()).isEqualTo(McpError.EXTERNAL_SERVICE_UNAVAILABLE);
    assertThat(error.message()).contains("unreachable or timed out");
    assertThat(error.retryAfter()).isNotNull();
  }

  @Test
  void unknownRuntimeFailureStaysInternalError() {
    when(maven.getAllVersions(any())).thenThrow(new IllegalStateException("boom"));

    McpError error = errorOf(tools.get_latest_version("g:a", null));

    assertThat(error.code()).isEqualTo(McpError.INTERNAL_ERROR);
    assertThat(error.message()).startsWith("Unexpected error").contains("boom");
  }

  @Test
  void invalidCoordinateStaysInvalidInput() {
    assertThat(errorOf(tools.get_latest_version("not-a-coordinate", null)).code())
        .isEqualTo(McpError.INVALID_INPUT);
  }

  @Test
  void releasePatternsOfUnknownArtifactIsNotFoundNotAnUpstreamError() {
    when(maven.getRecentVersionsWithAccurateTimestamps(
            any(), org.mockito.ArgumentMatchers.anyInt()))
        .thenReturn(List.of());

    ToolResponse response = tools.analyze_release_patterns("g:a", null);

    assertThat(response).isInstanceOf(ToolResponse.Error.class);
    assertThat(((ToolResponse.Error) response).status()).isEqualTo("not_found");
  }

  // ------------------------------------------------------------ bulk tools

  @Test
  void bulkCheckWhereEveryDependencyIsRateLimitedReturnsToolLevelError() {
    when(maven.getAllVersions(any())).thenThrow(tooMany("120"));

    McpError error = errorOf(tools.check_multiple_dependencies("g:a,g:b,g:c", null));

    assertThat(error.code()).isEqualTo(McpError.RATE_LIMITED);
    assertThat(error.retryAfter()).isEqualTo(120);
  }

  @Test
  void compareWhereEveryDependencyIsRateLimitedReturnsToolLevelError() {
    when(maven.getAllVersions(any())).thenThrow(tooMany("54161"));

    McpError error = errorOf(tools.compare_dependency_versions("g:a:1.0,g:b:1.0", null, false));

    assertThat(error.code()).isEqualTo(McpError.RATE_LIMITED);
    assertThat(error.retryAfter()).isEqualTo(54161);
  }

  @Test
  void healthWhereEveryDependencyFailedUpstreamReturnsToolLevelError() {
    when(maven.getAllVersions(any())).thenThrow(tooMany(null));

    McpError error =
        errorOf(tools.analyze_project_health("g:a:1.0,g:b:1.0", null, null, false, false));

    assertThat(error.code()).isEqualTo(McpError.RATE_LIMITED);
  }

  @Test
  void mixedBulkResultsStayPerDependencyWithClassifiedMessagesAndRetryHint() {
    when(maven.getAllVersions(any()))
        .thenAnswer(
            invocation -> {
              MavenCoordinate c = invocation.getArgument(0);
              if ("limited".equals(c.artifactId())) {
                throw tooMany("54161");
              }
              return List.of("2.0.0", "1.0.0");
            });

    BulkCheckResultList bulk =
        new BulkCheckResultList(
            getSuccessData(
                tools.check_multiple_dependencies("g:ok,g:limited", StabilityFilter.ALL)));
    BulkCheckResult limited = bulk.byDependency("g:limited");
    assertThat(limited.status()).isEqualTo("error");
    assertThat(limited.error()).contains("rate limiting").contains("~15h");
    assertThat(limited.error()).doesNotContain("exceeded rate limits");
    assertThat(limited.retryAfterSeconds()).isEqualTo(54161);
    assertThat(bulk.byDependency("g:ok").status()).isEqualTo("found");
    assertThat(bulk.byDependency("g:ok").retryAfterSeconds()).isNull();

    VersionComparison comparison =
        getSuccessData(
            tools.compare_dependency_versions("g:ok:1.0.0,g:limited:1.0.0", null, false));
    var failed =
        comparison.dependencies().stream()
            .filter(d -> "error".equals(d.status()))
            .findFirst()
            .orElseThrow();
    assertThat(failed.error()).contains("rate limiting").doesNotContain("exceeded rate limits");
    assertThat(failed.retryAfterSeconds()).isEqualTo(54161);

    ProjectHealthAnalysis health =
        getSuccessData(
            tools.analyze_project_health("g:ok:1.0.0,g:limited:1.0.0", null, null, false, false));
    var healthFailed =
        health.dependencies().stream()
            .filter(d -> "error".equals(d.status()))
            .findFirst()
            .orElseThrow();
    assertThat(healthFailed.error())
        .hasValueSatisfying(e -> assertThat(e).contains("rate limiting"));
    assertThat(healthFailed.retryAfterSeconds()).isEqualTo(54161);
  }

  @Test
  void invalidAndFailingCoordinatesTogetherAreNotAllUpstreamFailures() {
    when(maven.getAllVersions(any())).thenThrow(tooMany("30"));

    ToolResponse response = tools.check_multiple_dependencies("g:a,not-a-coordinate", null);

    assertThat(response).isInstanceOf(ToolResponse.Success.class);
  }

  // ------------------------------------------------------------ recommend_pom_upgrades

  @Test
  void rateLimitedLookupsAreNamedInTheAggregatedWarningAndMarkIncomplete() {
    when(resolver.resolve("<pom/>"))
        .thenReturn(
            result(
                explicit("g", "a", "1.0.0"),
                explicit("g", "b", "1.0.0"),
                explicit("g", "c", "1.0.0")));
    when(maven.getAllVersions(any())).thenThrow(tooMany("54161"));

    PomUpgradeRecommendation rec =
        getSuccessData(
            tools.recommend_pom_upgrades("<pom/>", UpgradeMode.MINOR_PATCH, null, false, null));

    assertThat(rec.incomplete()).isTrue();
    assertThat(rec.warnings())
        .anySatisfy(
            w ->
                assertThat(w)
                    .startsWith(
                        "Version lookup failed for 3 coordinates (repository rate limited; retry in"
                            + " ~15h): g:a, g:b, g:c"));
  }

  @Test
  void circuitOpenLookupsAreNamedInTheWarning() {
    when(resolver.resolve("<pom/>")).thenReturn(result(explicit("g", "a", "1.0.0")));
    when(maven.getAllVersions(any()))
        .thenThrow(
            CallNotPermittedException.createCallNotPermittedException(
                CircuitBreaker.ofDefaults("maven-central")));

    PomUpgradeRecommendation rec =
        getSuccessData(
            tools.recommend_pom_upgrades("<pom/>", UpgradeMode.MINOR_PATCH, null, false, null));

    assertThat(rec.incomplete()).isTrue();
    assertThat(rec.warnings())
        .anySatisfy(
            w ->
                assertThat(w)
                    .contains(
                        "1 coordinate (repository circuit breaker open; retry in 30 seconds)"));
  }

  @Test
  void rateLimitedParentChainReturnsRateLimitErrorInsteadOfSuccessWithWarnings() {
    when(resolver.resolve("<pom/>"))
        .thenThrow(
            new MavenCentralException(
                MavenCentralException.Reason.RATE_LIMITED, "limited", 54161, null));

    McpError error =
        errorOf(tools.recommend_pom_upgrades("<pom/>", UpgradeMode.MINOR_PATCH, null, false, null));
    assertThat(error.code()).isEqualTo(McpError.RATE_LIMITED);
    assertThat(error.retryAfter()).isEqualTo(54161);

    McpError analysis = errorOf(tools.analyze_pom_dependencies("<pom/>", null));
    assertThat(analysis.code()).isEqualTo(McpError.RATE_LIMITED);
  }

  @Test
  void unexpectedResolverFailureStaysInternalError() {
    when(resolver.resolve("<pom/>")).thenThrow(new IllegalStateException("boom"));

    assertThat(
            errorOf(
                    tools.recommend_pom_upgrades(
                        "<pom/>", UpgradeMode.MINOR_PATCH, null, false, null))
                .code())
        .isEqualTo(McpError.INTERNAL_ERROR);
  }

  @Test
  void retryHintFormatting() {
    assertThat(McpError.describeRetry(12)).isEqualTo("12 seconds");
    assertThat(McpError.describeRetry(300)).isEqualTo("~5 min");
    assertThat(McpError.describeRetry(54161)).isEqualTo("~15h");
    assertThat(McpError.mavenCentralRateLimited(54161).message())
        .isEqualTo(
            "Maven repository is rate limiting requests (HTTP 429). Retry in ~15h (54161 seconds).");
  }

  @Test
  void errorShapeSerializesWithRetryAfter() {
    String json = new JsonMapper().writeValueAsString(rateLimited());
    assertThat(json).contains("\"code\":\"RATE_LIMITED\"").contains("\"retryAfter\":54161");
  }

  // ------------------------------------------------------------ helpers

  private ToolResponse rateLimited() {
    when(maven.getAllVersions(any())).thenThrow(tooMany("54161"));
    return tools.get_latest_version("g:a", null);
  }

  private static EffectivePomResult result(EffectiveDependency... deps) {
    return new EffectivePomResult(List.of(deps), List.of(), List.of(), List.of());
  }

  private static EffectiveDependency explicit(String g, String a, String v) {
    return new EffectiveDependency(g, a, v, Source.EXPLICIT, Optional.empty(), List.of());
  }

  private record BulkCheckResultList(List<BulkCheckResult> items) {
    BulkCheckResultList(Object data) {
      this(castList(data));
    }

    @SuppressWarnings("unchecked")
    private static List<BulkCheckResult> castList(Object data) {
      return (List<BulkCheckResult>) data;
    }

    BulkCheckResult byDependency(String dependency) {
      return items.stream()
          .filter(r -> dependency.equals(r.dependency()))
          .findFirst()
          .orElseThrow();
    }
  }
}
