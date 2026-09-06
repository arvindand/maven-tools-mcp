package com.arvindand.mcp.maven.service;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.arvindand.mcp.maven.config.CacheConfig;
import com.arvindand.mcp.maven.config.MavenCentralProperties;
import com.arvindand.mcp.maven.model.MavenCoordinate;
import com.arvindand.mcp.maven.model.security.SecurityAssessment;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Exercises Spring cache proxies across successful responses and transient upstream failures.
 *
 * @author Arvind Menon
 * @since 3.2.2
 */
class NetworkCacheTest {
  private AnnotationConfigApplicationContext context;
  private MockRestServiceServer server;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder();
    server = MockRestServiceServer.bindTo(builder).build();
    context = new AnnotationConfigApplicationContext();
    context.register(CacheConfig.class, MavenCentralService.class, OsvClient.class);
    context.registerBean(RestClient.Builder.class, () -> builder);
    context.registerBean("mavenCentralRestClient", RestClient.class, builder::build);
    context.registerBean(
        MavenCentralProperties.class,
        () -> new MavenCentralProperties("https://repo.example", Duration.ofSeconds(1), 100, null));
    context.refresh();
  }

  @AfterEach
  void tearDown() {
    context.close();
    server.verify();
  }

  @Test
  void metadataFailureDoesNotBecomeCachedMissingVersion() {
    String url = "https://repo.example/g/a/maven-metadata.xml";
    server.expect(requestTo(url)).andRespond(withServerError());
    server
        .expect(requestTo(url))
        .andRespond(
            withSuccess(
                "<metadata><versioning><versions><version>1</version></versions></versioning></metadata>",
                MediaType.APPLICATION_XML));
    MavenCentralService service = context.getBean(MavenCentralService.class);
    MavenCoordinate coordinate = MavenCoordinate.of("g", "a", null);
    assertThatThrownBy(() -> service.checkVersionExists(coordinate, "1"))
        .isInstanceOf(RestClientException.class);
    assertThat(service.checkVersionExists(coordinate, "1")).isTrue();
    assertThat(service.checkVersionExists(coordinate, "1")).isTrue();
  }

  @Test
  void allVersionsRetainsOlderReleaseLinesBeyondDisplayLimit() {
    String versions =
        java.util.stream.IntStream.rangeClosed(1, 150)
            .mapToObj(version -> "<version>" + version + ".0</version>")
            .collect(java.util.stream.Collectors.joining());
    server
        .expect(requestTo("https://repo.example/g/a/maven-metadata.xml"))
        .andRespond(
            withSuccess(
                "<metadata><versioning><versions>"
                    + versions
                    + "</versions></versioning></metadata>",
                MediaType.APPLICATION_XML));
    assertThat(
            context
                .getBean(MavenCentralService.class)
                .getAllVersions(MavenCoordinate.of("g", "a", null)))
        .hasSize(150)
        .contains("1.0")
        .startsWith("150.0");
  }

  @Test
  void pomFailureIsRetriedAndLicenseReaderHandlesDifferentElementOrder() {
    String url = "https://repo.example/g/a/1/a-1.pom";
    server.expect(requestTo(url)).andRespond(withServerError());
    server
        .expect(requestTo(url))
        .andRespond(
            withSuccess(
                """
        <project><modelVersion>4.0.0</modelVersion><groupId>g</groupId>
        <artifactId>a</artifactId><version>1</version><licenses><license>
        <url>https://www.apache.org/licenses/LICENSE-2.0</url><name>Apache License, Version 2.0</name>
        </license></licenses></project>
        """,
                MediaType.APPLICATION_XML));
    MavenCentralService service = context.getBean(MavenCentralService.class);
    MavenCoordinate coordinate = MavenCoordinate.of("g", "a", "1");
    assertThatThrownBy(() -> service.fetchPomXml(coordinate))
        .isInstanceOf(RestClientException.class);
    assertThat(service.getLicenses(coordinate)).hasSize(1);
    assertThat(service.fetchPomXml(coordinate)).isPresent();
  }

  @Test
  void osvFailuresAndIncompletePagesAreNotCached() {
    String url = "https://api.osv.dev/v1/query";
    server.expect(requestTo(url)).andRespond(withServerError());
    server
        .expect(requestTo(url))
        .andRespond(withSuccess("{\"next_page_token\":\"more\"}", MediaType.APPLICATION_JSON));
    server.expect(requestTo(url)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
    VulnerabilityService service = new VulnerabilityService(context.getBean(OsvClient.class));
    MavenCoordinate coordinate = MavenCoordinate.of("g", "a", "1");
    assertThat(service.scan(coordinate).status()).isEqualTo(SecurityAssessment.Status.UNKNOWN);
    assertThat(service.scan(coordinate).status()).isEqualTo(SecurityAssessment.Status.UNKNOWN);
    assertThat(service.scan(coordinate).status()).isEqualTo(SecurityAssessment.Status.OK);
    assertThat(service.scan(coordinate).status()).isEqualTo(SecurityAssessment.Status.OK);
  }
}
