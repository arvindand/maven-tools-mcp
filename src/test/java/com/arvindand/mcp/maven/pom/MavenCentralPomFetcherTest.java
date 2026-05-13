package com.arvindand.mcp.maven.pom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.arvindand.mcp.maven.model.MavenCoordinate;
import com.arvindand.mcp.maven.service.MavenCentralService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MavenCentralPomFetcherTest {

  @Mock MavenCentralService service;

  @Test
  void parsesMinimalPomXml() {
    var coord = MavenCoordinate.of("com.example", "lib", "1.0.0");
    when(service.fetchPomXml(coord))
        .thenReturn(
            Optional.of(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>lib</artifactId>
                  <version>1.0.0</version>
                </project>
                """));

    var model = new MavenCentralPomFetcher(service).fetch(coord);

    assertThat(model).isPresent();
    assertThat(model.get().getGroupId()).isEqualTo("com.example");
    assertThat(model.get().getArtifactId()).isEqualTo("lib");
    assertThat(model.get().getVersion()).isEqualTo("1.0.0");
  }

  @Test
  void returnsEmptyWhenServiceReturnsEmpty() {
    var coord = MavenCoordinate.of("com.example", "missing", "1.0.0");
    when(service.fetchPomXml(coord)).thenReturn(Optional.empty());

    assertThat(new MavenCentralPomFetcher(service).fetch(coord)).isEmpty();
  }

  @Test
  void returnsEmptyOnMalformedXml() {
    var coord = MavenCoordinate.of("com.example", "broken", "1.0.0");
    when(service.fetchPomXml(coord)).thenReturn(Optional.of("<not valid xml"));

    assertThat(new MavenCentralPomFetcher(service).fetch(coord)).isEmpty();
  }
}
