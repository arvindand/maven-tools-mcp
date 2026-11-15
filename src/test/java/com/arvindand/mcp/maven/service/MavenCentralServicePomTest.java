package com.arvindand.mcp.maven.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.arvindand.mcp.maven.config.MavenCentralProperties;
import com.arvindand.mcp.maven.model.MavenCoordinate;
import com.arvindand.mcp.maven.model.Relocation;
import com.arvindand.mcp.maven.model.license.LicenseInfo;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/** Parent-chain license lookup and relocation lookup, with {@code fetchPomXml} stubbed. */
class MavenCentralServicePomTest {

  private MavenCentralService service;

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setUp() {
    ObjectProvider<MavenCentralService> provider = mock(ObjectProvider.class);
    service =
        spy(
            new MavenCentralService(
                new MavenCentralProperties(
                    "https://repo.invalid/maven2", Duration.ofSeconds(1), 100, null),
                RestClient.create(),
                provider));
    doReturn(service).when(provider).getObject();
  }

  private static String pom(String groupId, String artifactId, String version, String body) {
    return "<project><modelVersion>4.0.0</modelVersion><groupId>"
        + groupId
        + "</groupId><artifactId>"
        + artifactId
        + "</artifactId><version>"
        + version
        + "</version>"
        + body
        + "</project>";
  }

  private static String parent(String g, String a, String v) {
    return "<parent><groupId>"
        + g
        + "</groupId><artifactId>"
        + a
        + "</artifactId><version>"
        + v
        + "</version></parent>";
  }

  private static final String APACHE =
      "<licenses><license><name>Apache-2.0</name>"
          + "<url>https://www.apache.org/licenses/LICENSE-2.0.txt</url></license></licenses>";

  private void stubPom(String g, String a, String v, String xml) {
    doReturn(Optional.ofNullable(xml)).when(service).fetchPomXml(MavenCoordinate.of(g, a, v));
  }

  // ---- licenses ----

  @Test
  void ownLicenseDoesNotFetchParents() {
    stubPom("org.x", "lib", "1", pom("org.x", "lib", "1", APACHE + parent("org.p", "par", "9")));
    List<LicenseInfo> licenses = service.getLicenses(MavenCoordinate.of("org.x", "lib", "1"));
    assertThat(licenses).hasSize(1);
    assertThat(licenses.get(0).name()).isEqualTo("Apache-2.0");
    verify(service, times(1)).fetchPomXml(any());
    verify(service, never()).fetchPomXml(MavenCoordinate.of("org.p", "par", "9"));
  }

  @Test
  void licenseInheritedTwoLevelsUp() {
    stubPom("org.x", "lib", "1", pom("org.x", "lib", "1", parent("org.p", "par", "2")));
    stubPom("org.p", "par", "2", pom("org.p", "par", "2", parent("org.g", "root", "3")));
    stubPom("org.g", "root", "3", pom("org.g", "root", "3", APACHE));
    List<LicenseInfo> licenses = service.getLicenses(MavenCoordinate.of("org.x", "lib", "1"));
    assertThat(licenses).extracting(LicenseInfo::name).containsExactly("Apache-2.0");
  }

  @Test
  void noLicenseAnywhereIsEmpty() {
    stubPom("org.x", "lib", "1", pom("org.x", "lib", "1", parent("org.p", "par", "2")));
    stubPom("org.p", "par", "2", pom("org.p", "par", "2", ""));
    assertThat(service.getLicenses(MavenCoordinate.of("org.x", "lib", "1"))).isEmpty();
  }

  @Test
  void cyclicParentsTerminate() {
    stubPom("org.a", "a", "1", pom("org.a", "a", "1", parent("org.b", "b", "1")));
    stubPom("org.b", "b", "1", pom("org.b", "b", "1", parent("org.a", "a", "1")));
    assertThat(service.getLicenses(MavenCoordinate.of("org.a", "a", "1"))).isEmpty();
    verify(service, times(2)).fetchPomXml(any());
  }

  @Test
  void depthLimitStopsTheWalk() {
    // chain c0 -> c1 -> ... -> c9, license only on c9; limit is 5 parents (c0..c5 fetched)
    for (int i = 0; i < 10; i++) {
      String body = i == 9 ? APACHE : parent("org.c", "c" + (i + 1), "1");
      stubPom("org.c", "c" + i, "1", pom("org.c", "c" + i, "1", body));
    }
    assertThat(service.getLicenses(MavenCoordinate.of("org.c", "c0", "1"))).isEmpty();
    verify(service, times(MavenCentralService.MAX_PARENT_DEPTH + 1)).fetchPomXml(any());
  }

  @Test
  void licenseAtMaximumDepthIsFound() {
    for (int i = 0; i <= MavenCentralService.MAX_PARENT_DEPTH; i++) {
      String body =
          i == MavenCentralService.MAX_PARENT_DEPTH ? APACHE : parent("org.c", "c" + (i + 1), "1");
      stubPom("org.c", "c" + i, "1", pom("org.c", "c" + i, "1", body));
    }
    assertThat(service.getLicenses(MavenCoordinate.of("org.c", "c0", "1"))).hasSize(1);
  }

  @Test
  void parentFetchFailureDegradesToEmpty() {
    stubPom("org.x", "lib", "1", pom("org.x", "lib", "1", parent("org.p", "par", "2")));
    doThrow(new IllegalStateException("boom"))
        .when(service)
        .fetchPomXml(MavenCoordinate.of("org.p", "par", "2"));
    assertThat(service.getLicenses(MavenCoordinate.of("org.x", "lib", "1"))).isEmpty();
  }

  @Test
  void missingOrBlankVersionYieldsEmpty() {
    assertThat(service.getLicenses(MavenCoordinate.of("org.x", "lib"))).isEmpty();
    verify(service, never()).fetchPomXml(any());
  }

  // ---- relocation ----

  private static final String MYSQL_POM =
      pom(
          "mysql",
          "mysql-connector-java",
          "8.0.33",
          "<distributionManagement><relocation><groupId>com.mysql</groupId>"
              + "<artifactId>mysql-connector-j</artifactId><message>MySQL Connector/J artifacts"
              + " moved to reverse-DNS compliant Maven 2+ coordinates.</message></relocation>"
              + "</distributionManagement>");

  @Test
  void mysqlRelocationIsFound() {
    MavenCoordinate coord = MavenCoordinate.of("mysql", "mysql-connector-java", "8.0.33");
    stubPom("mysql", "mysql-connector-java", "8.0.33", MYSQL_POM);
    assertThat(service.findRelocation(coord))
        .contains(
            new Relocation(
                "com.mysql",
                "mysql-connector-j",
                null,
                "MySQL Connector/J artifacts moved to reverse-DNS compliant Maven 2+"
                    + " coordinates."));
  }

  @Test
  void groupIdOnlyRelocationDefaultsArtifactId() {
    stubPom(
        "org.old",
        "thing",
        "1.0",
        pom(
            "org.old",
            "thing",
            "1.0",
            "<distributionManagement><relocation><groupId>org.new</groupId>"
                + "</relocation></distributionManagement>"));
    assertThat(service.findRelocation(MavenCoordinate.of("org.old", "thing", "1.0")))
        .contains(new Relocation("org.new", "thing", null, null));
  }

  @Test
  void artifactIdOnlyRelocationDefaultsGroupIdAndKeepsVersion() {
    stubPom(
        "org.old",
        "thing",
        "1.0",
        pom(
            "org.old",
            "thing",
            "1.0",
            "<distributionManagement><relocation><artifactId>thing-core</artifactId>"
                + "<version>2.0</version></relocation></distributionManagement>"));
    assertThat(service.findRelocation(MavenCoordinate.of("org.old", "thing", "1.0")))
        .contains(new Relocation("org.old", "thing-core", "2.0", null));
  }

  @Test
  void noRelocationAndMissingPomAreEmpty() {
    stubPom("org.x", "lib", "1", pom("org.x", "lib", "1", ""));
    assertThat(service.findRelocation(MavenCoordinate.of("org.x", "lib", "1"))).isEmpty();
    stubPom(
        "org.x",
        "dm",
        "1",
        pom(
            "org.x",
            "dm",
            "1",
            "<distributionManagement><status>deployed</status>" + "</distributionManagement>"));
    assertThat(service.findRelocation(MavenCoordinate.of("org.x", "dm", "1"))).isEmpty();
    stubPom("org.x", "gone", "1", null);
    assertThat(service.findRelocation(MavenCoordinate.of("org.x", "gone", "1"))).isEmpty();
  }

  @Test
  void selfRelocationIsEmpty() {
    stubPom(
        "org.x",
        "lib",
        "1",
        pom(
            "org.x",
            "lib",
            "1",
            "<distributionManagement><relocation><groupId>org.x</groupId>"
                + "<artifactId>lib</artifactId></relocation></distributionManagement>"));
    assertThat(service.findRelocation(MavenCoordinate.of("org.x", "lib", "1"))).isEmpty();
  }

  @Test
  void upstreamFailuresPropagate() {
    HttpClientErrorException tooMany =
        HttpClientErrorException.create(
            HttpStatus.TOO_MANY_REQUESTS,
            "Too Many Requests",
            new HttpHeaders(),
            new byte[0],
            null);
    doThrow(tooMany).when(service).fetchPomXml(MavenCoordinate.of("org.x", "lib", "1"));
    assertThatThrownBy(() -> service.findRelocation(MavenCoordinate.of("org.x", "lib", "1")))
        .isSameAs(tooMany);
  }

  @Test
  void relocationOfLatestChecksNewestVersion() {
    MavenCoordinate unversioned = MavenCoordinate.of("mysql", "mysql-connector-java");
    doReturn(List.of("8.0.33", "8.0.32")).when(service).getAllVersions(unversioned);
    stubPom("mysql", "mysql-connector-java", "8.0.33", MYSQL_POM);
    assertThat(service.findRelocationOfLatest(unversioned))
        .get()
        .extracting(Relocation::groupId, Relocation::artifactId)
        .containsExactly("com.mysql", "mysql-connector-j");

    doReturn(List.of()).when(service).getAllVersions(MavenCoordinate.of("org.x", "none"));
    assertThat(service.findRelocationOfLatest(MavenCoordinate.of("org.x", "none"))).isEmpty();
  }
}
