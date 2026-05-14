package com.arvindand.mcp.maven.pom;

import static com.arvindand.mcp.maven.config.CacheConstants.MAVEN_EFFECTIVE_POM;
import static org.assertj.core.api.Assertions.assertThat;

import com.arvindand.mcp.maven.model.MavenCoordinate;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.maven.model.Model;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

/**
 * Verifies that the Spring {@code @Cacheable} annotation on {@link EffectivePomResolver#resolve} is
 * wired correctly. Two calls with the same {@code pomXml} input must invoke the underlying {@link
 * PomFetcher} only once (for the parent walk), and the cache region must hold the result.
 *
 * @author Arvind Menon
 * @since 3.0.0
 */
@SpringBootTest
@ActiveProfiles("test")
class EffectivePomResolverCacheTest {

  @Autowired private EffectivePomResolver resolver;
  @Autowired private CacheManager cacheManager;
  @Autowired private CountingPomFetcher countingFetcher;

  @BeforeEach
  void clearCacheAndCounters() {
    Cache cache = cacheManager.getCache(MAVEN_EFFECTIVE_POM);
    if (cache != null) {
      cache.clear();
    }
    countingFetcher.reset();
  }

  @Test
  void secondCallWithSameInputServesFromCacheAndSkipsParentFetch() {
    String pomXml =
        """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <parent>
            <groupId>com.example.cache-it</groupId>
            <artifactId>parent</artifactId>
            <version>1.0.0</version>
          </parent>
          <artifactId>app</artifactId>
        </project>
        """;

    EffectivePomResult first = resolver.resolve(pomXml);
    int callsAfterFirst = countingFetcher.calls();

    EffectivePomResult second = resolver.resolve(pomXml);
    int callsAfterSecond = countingFetcher.calls();

    assertThat(second).isSameAs(first);
    assertThat(callsAfterFirst).as("first resolve fetches the parent").isEqualTo(1);
    assertThat(callsAfterSecond)
        .as("second resolve must hit the cache and not re-fetch")
        .isEqualTo(callsAfterFirst);

    Cache cache = cacheManager.getCache(MAVEN_EFFECTIVE_POM);
    assertThat(cache).isNotNull();
    assertThat(cache.get(pomXml, EffectivePomResult.class)).isSameAs(first);
  }

  @TestConfiguration
  static class CountingFetcherConfig {

    @Bean
    @Primary
    CountingPomFetcher countingPomFetcher() {
      return new CountingPomFetcher();
    }
  }

  static final class CountingPomFetcher implements PomFetcher {

    private static final String EXPECTED_PARENT_KEY = "com.example.cache-it:parent:1.0.0";

    private final AtomicInteger calls = new AtomicInteger();
    private final Model parentModel;

    CountingPomFetcher() {
      try {
        this.parentModel =
            new org.apache.maven.model.io.xpp3.MavenXpp3Reader()
                .read(
                    new java.io.StringReader(
                        """
                        <project xmlns="http://maven.apache.org/POM/4.0.0">
                          <modelVersion>4.0.0</modelVersion>
                          <groupId>com.example.cache-it</groupId>
                          <artifactId>parent</artifactId>
                          <version>1.0.0</version>
                        </project>
                        """));
      } catch (Exception ex) {
        throw new IllegalStateException("test parent POM is invalid", ex);
      }
    }

    @Override
    public Optional<Model> fetch(MavenCoordinate coord) {
      calls.incrementAndGet();
      if (EXPECTED_PARENT_KEY.equals(coord.toCoordinateString())) {
        return Optional.of(parentModel);
      }
      return Optional.empty();
    }

    int calls() {
      return calls.get();
    }

    void reset() {
      calls.set(0);
    }
  }
}
