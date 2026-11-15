package com.arvindand.mcp.maven.pom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.arvindand.mcp.maven.service.MavenCentralException;
import com.arvindand.mcp.maven.service.MavenCentralException.Reason;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

/**
 * A rate limited or circuit-open parent / BOM fetch must surface as that precise failure; other
 * fetch failures stay warnings so a partial result is still produced.
 */
class MavenModelSessionUpstreamFailureTest {

  private static final String CHILD =
      "<project><modelVersion>4.0.0</modelVersion>"
          + "<parent><groupId>g</groupId><artifactId>parent</artifactId><version>1</version></parent>"
          + "<artifactId>app</artifactId></project>";

  @Test
  void rateLimitedParentFetchFailsResolutionWithRateLimitedReason() {
    PomFetcher fetcher =
        coordinate -> {
          throw new MavenCentralException(Reason.RATE_LIMITED, "limited", 54161, null);
        };

    assertThatThrownBy(() -> new EffectivePomResolver(fetcher).resolve(CHILD))
        .isInstanceOfSatisfying(
            MavenCentralException.class,
            e -> {
              assertThat(e.getReason()).isEqualTo(Reason.RATE_LIMITED);
              assertThat(e.getRetryAfterSeconds()).isEqualTo(54161);
            });
  }

  @Test
  void circuitOpenParentFetchFailsResolutionWithCircuitOpenReason() {
    PomFetcher fetcher =
        coordinate -> {
          throw new MavenCentralException(Reason.CIRCUIT_OPEN, "open", 30, null);
        };

    assertThatThrownBy(() -> new EffectivePomResolver(fetcher).resolve(CHILD))
        .isInstanceOfSatisfying(
            MavenCentralException.class,
            e -> assertThat(e.getReason()).isEqualTo(Reason.CIRCUIT_OPEN));
  }

  @Test
  void otherFetchFailuresRemainWarningsAndResolutionContinues() {
    PomFetcher fetcher =
        coordinate -> {
          throw new ResourceAccessException("timeout");
        };

    EffectivePomResult result = new EffectivePomResolver(fetcher).resolve(CHILD);

    assertThat(result.warnings()).anyMatch(w -> w.contains("g:parent:1"));
  }

  @Test
  void missingParentRemainsAWarning() {
    PomFetcher fetcher = coordinate -> Optional.empty();

    EffectivePomResult result = new EffectivePomResolver(fetcher).resolve(CHILD);

    assertThat(result.warnings()).anyMatch(w -> w.contains("could not be fetched"));
  }
}
