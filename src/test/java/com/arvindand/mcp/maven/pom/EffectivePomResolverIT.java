package com.arvindand.mcp.maven.pom;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration test: resolve this repo's own pom.xml against real Maven Central. Verifies the
 * spring-boot-starter-parent chain is followed and that an explicitly-declared dep (maven-model)
 * comes back as EXPLICIT with the literal version.
 *
 * <p>Runs only under the {@code -Pintegration} or {@code -Pfull} profiles (failsafe picks up {@code
 * *IT.java}; surefire excludes them). May take 10–30 seconds on first run due to Maven Central
 * network calls.
 *
 * @author Arvind Menon
 * @since 2.1.1
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

    // maven-model 3.9.12 is an explicit dep with a literal version — must come back as EXPLICIT.
    assertThat(result.dependencies())
        .filteredOn(d -> d.coordinate().artifactId().equals("maven-model"))
        .singleElement()
        .satisfies(
            d -> {
              assertThat(d.effectiveVersion())
                  .as("maven-model should have explicit version 3.9.12")
                  .isEqualTo("3.9.12");
              assertThat(d.source())
                  .as("maven-model should be classified as EXPLICIT")
                  .isEqualTo(Source.EXPLICIT);
            });
  }
}
