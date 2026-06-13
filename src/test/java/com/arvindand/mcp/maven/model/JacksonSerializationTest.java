package com.arvindand.mcp.maven.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.arvindand.mcp.maven.pom.EffectiveDependency;
import com.arvindand.mcp.maven.pom.Source;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.xml.XmlMapper;

/**
 * Jackson 3 serialization regression tests. After the Jackson 2 → 3 migration these lock down the
 * parts of the MCP wire contract that Jackson controls: snake_case naming, {@link ToolResponse}
 * envelope shapes, native {@link Optional} handling (the dropped jackson-datatype-jdk8 module), and
 * unwrapped {@code <version>} XML parsing. Uses plain Jackson 3 mappers — {@code @JsonNaming},
 * record handling, and Optional support are all core databind behavior, independent of Boot's
 * auto-configuration.
 */
class JacksonSerializationTest {

  private final JsonMapper json = new JsonMapper();
  private final XmlMapper xml = new XmlMapper();

  @Test
  void snakeCaseNamingForResponseRecords() {
    DependencyInfo info =
        new DependencyInfo("success", "com.example", "lib", "1.2.3", true, "stable", true, 123L);

    String out = json.writeValueAsString(info);

    assertThat(out)
        .contains("\"group_id\":\"com.example\"")
        .contains("\"artifact_id\":\"lib\"")
        .contains("\"is_stable\":true")
        .doesNotContain("groupId")
        .doesNotContain("isStable");
  }

  @Test
  void toolResponseSuccessShape() {
    DependencyInfo info =
        new DependencyInfo("success", "com.example", "lib", "1.2.3", true, "stable", true, null);

    String out = json.writeValueAsString(ToolResponse.Success.of(info));

    assertThat(out).contains("\"status\":\"success\"").contains("\"data\":{");
  }

  @Test
  void toolResponseErrorShape() {
    String out = json.writeValueAsString(ToolResponse.Error.of("boom"));

    assertThat(out).contains("\"status\":\"error\"").contains("\"error\":{");
  }

  @Test
  void optionalPresentSerializesUnwrapped() {
    EffectiveDependency managed =
        new EffectiveDependency(
            "com.example",
            "lib",
            "1.2.3",
            Source.MANAGED,
            Optional.of(MavenCoordinate.of("org.springframework.boot", "spring-boot-dependencies")),
            List.of());

    String out = json.writeValueAsString(managed);

    // Jackson 3 unwraps the Optional to the contained value (no jackson-datatype-jdk8 needed).
    assertThat(out)
        .contains("\"spring-boot-dependencies\"")
        .doesNotContain("\"present\"")
        .doesNotContain("\"empty\"");
  }

  @Test
  void optionalEmptySerializesAsNull() {
    EffectiveDependency explicit =
        new EffectiveDependency(
            "com.example", "lib", "1.2.3", Source.EXPLICIT, Optional.empty(), List.of());

    String out = json.writeValueAsString(explicit);

    assertThat(out).contains("\"managedBy\":null");
  }

  @Test
  void mavenMetadataXmlParsesUnwrappedVersionList() {
    String metadataXml =
        """
        <metadata>
          <groupId>org.example</groupId>
          <artifactId>demo</artifactId>
          <versioning>
            <latest>2.0.0</latest>
            <release>2.0.0</release>
            <versions>
              <version>1.0.0</version>
              <version>1.5.0</version>
              <version>2.0.0</version>
            </versions>
            <lastUpdated>20260101000000</lastUpdated>
          </versioning>
        </metadata>
        """;

    MavenMetadata metadata = xml.readValue(metadataXml, MavenMetadata.class);

    assertThat(metadata.hasValidVersioning()).isTrue();
    assertThat(metadata.versioning().latest()).isEqualTo("2.0.0");
    assertThat(metadata.versioning().release()).isEqualTo("2.0.0");
    assertThat(metadata.versioning().getVersionStrings())
        .containsExactly("1.0.0", "1.5.0", "2.0.0");
  }
}
