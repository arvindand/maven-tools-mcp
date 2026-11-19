package com.arvindand.mcp.maven.pom;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration test: resolve this repo's own pom.xml against real Maven Central. Verifies the
 * spring-boot-starter-parent chain is followed and that an explicitly-declared dep
 * (maven-model-builder) comes back as EXPLICIT with its property-backed version.
 *
 * <p>Runs only under the {@code -Pintegration} or {@code -Pfull} profiles (failsafe picks up {@code
 * *IT.java}; surefire excludes them). May take 10–30 seconds on first run due to Maven Central
 * network calls.
 *
 * @author Arvind Menon
 * @since 3.0.0
 */
@SpringBootTest
@ActiveProfiles("test")
class EffectivePomResolverIT {

  @Autowired private EffectivePomResolver resolver;

  @Test
  void resolvesOurOwnPomAgainstRealMavenCentral() throws Exception {
    Path pomPath = Path.of(System.getProperty("user.dir"), "pom.xml");
    String pomXml = Files.readString(pomPath);

    EffectivePomResult result = resolver.resolve(pomXml);

    // Parent chain: spring-boot-starter-parent must be fetched and walked.
    assertThat(result.parentChain())
        .isNotEmpty()
        .anyMatch(c -> c.artifactId().equals("spring-boot-starter-parent"));

    // maven-model-builder is an explicit dep versioned by a root property — it must come back as
    // EXPLICIT with that property's value. Read it from the POM so dependency bumps don't break
    // this test.
    String expected =
        new MavenXpp3Reader()
            .read(new StringReader(pomXml))
            .getProperties()
            .getProperty("maven-artifact.version");
    assertThat(result.dependencies())
        .filteredOn(d -> d.artifactId().equals("maven-model-builder"))
        .singleElement()
        .satisfies(
            d -> {
              assertThat(d.effectiveVersion())
                  .as("maven-model-builder should resolve to maven-artifact.version")
                  .isEqualTo(expected);
              assertThat(d.source())
                  .as("maven-model-builder should be classified as EXPLICIT")
                  .isEqualTo(Source.EXPLICIT);
            });
  }
}
