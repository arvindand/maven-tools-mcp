package com.arvindand.mcp.maven.service;

import static com.arvindand.mcp.maven.TestHelpers.getSuccessData;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.arvindand.mcp.maven.config.Context7Properties;
import com.arvindand.mcp.maven.model.MavenCoordinate;
import com.arvindand.mcp.maven.model.NeedsAttention;
import com.arvindand.mcp.maven.model.PomUpgradeRecommendation;
import com.arvindand.mcp.maven.model.ToolResponse;
import com.arvindand.mcp.maven.model.UpgradeAction;
import com.arvindand.mcp.maven.model.UpgradeMode;
import com.arvindand.mcp.maven.pom.EffectiveDependency;
import com.arvindand.mcp.maven.pom.EffectivePomResolver;
import com.arvindand.mcp.maven.pom.EffectivePomResult;
import com.arvindand.mcp.maven.pom.ManagedAlternative;
import com.arvindand.mcp.maven.pom.ManagedDeclaration;
import com.arvindand.mcp.maven.pom.PluginDependencyDeclaration;
import com.arvindand.mcp.maven.pom.Source;
import com.arvindand.mcp.maven.util.VersionComparator;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unit tests for the {@code recommend_pom_upgrades} MCP tool. Mocks the POM resolver and Maven
 * Central lookups so the test exercises the classification logic in isolation.
 *
 * @author Arvind Menon
 */
class RecommendPomUpgradesToolTest {

  @Test
  void emitsExplicitBumpForMinorPatchAvailableExplicitDep() {
    EffectivePomResolver resolver = mock(EffectivePomResolver.class);
    MavenCentralService maven = mock(MavenCentralService.class);
    when(resolver.resolve("<pom/>"))
        .thenReturn(
            new EffectivePomResult(
                List.of(explicit("com.example", "lib", "1.0.0")), List.of(), List.of(), List.of()));
    // Newest first per Maven Central semantics.
    when(maven.getAllVersions(any())).thenReturn(List.of("1.0.1", "1.0.0"));

    PomUpgradeRecommendation rec =
        getSuccessData(
            buildTools(resolver, maven)
                .recommend_pom_upgrades("<pom/>", UpgradeMode.MINOR_PATCH, null));

    assertThat(rec.deterministicActions())
        .singleElement()
        .satisfies(
            a -> {
              assertThat(a.kind()).isEqualTo(UpgradeAction.KIND_EXPLICIT_BUMP);
              assertThat(a.groupId()).isEqualTo("com.example");
              assertThat(a.artifactId()).isEqualTo("lib");
              assertThat(a.current()).isEqualTo("1.0.0");
              assertThat(a.target()).isEqualTo("1.0.1");
              assertThat(a.updateType()).isEqualTo("patch");
            });
    assertThat(rec.needsAttention()).isEmpty();
  }

  @Test
  void ignoresDotSeparatedPreReleaseWhenChoosingStableExplicitBumpTarget() {
    EffectivePomResolver resolver = mock(EffectivePomResolver.class);
    MavenCentralService maven = mock(MavenCentralService.class);
    when(resolver.resolve("<pom/>"))
        .thenReturn(
            new EffectivePomResult(
                List.of(explicit("org.mapstruct", "mapstruct", "1.6.3")),
                List.of(),
                List.of(),
                List.of()));
    when(maven.getAllVersions(any())).thenReturn(List.of("1.7.0.Beta1", "1.6.4", "1.6.3"));

    PomUpgradeRecommendation rec =
        getSuccessData(
            buildTools(resolver, maven)
                .recommend_pom_upgrades("<pom/>", UpgradeMode.MINOR_PATCH, null));

    assertThat(rec.deterministicActions())
        .singleElement()
        .satisfies(
            a -> {
              assertThat(a.kind()).isEqualTo(UpgradeAction.KIND_EXPLICIT_BUMP);
              assertThat(a.groupId()).isEqualTo("org.mapstruct");
              assertThat(a.artifactId()).isEqualTo("mapstruct");
              assertThat(a.current()).isEqualTo("1.6.3");
              assertThat(a.target()).isEqualTo("1.6.4");
              assertThat(a.updateType()).isEqualTo("patch");
            });
    assertThat(rec.needsAttention()).isEmpty();
  }

  @Test
  void emitsBomBumpForMinorPatchAvailableManagedBom() {
    EffectivePomResolver resolver = mock(EffectivePomResolver.class);
    MavenCentralService maven = mock(MavenCentralService.class);

    MavenCoordinate bom = MavenCoordinate.of("com.example", "platform-bom", "1.0.0");
    when(resolver.resolve("<pom/>"))
        .thenReturn(
            new EffectivePomResult(
                List.of(managed("com.example", "lib", "5.0.0", bom)),
                List.of(),
                List.of(bom),
                List.of()));
    when(maven.getAllVersions(any())).thenReturn(List.of("1.1.0", "1.0.0"));

    PomUpgradeRecommendation rec =
        getSuccessData(
            buildTools(resolver, maven)
                .recommend_pom_upgrades("<pom/>", UpgradeMode.MINOR_PATCH, null));

    assertThat(rec.deterministicActions())
        .singleElement()
        .satisfies(
            a -> {
              assertThat(a.kind()).isEqualTo(UpgradeAction.KIND_BOM_BUMP);
              assertThat(a.groupId()).isEqualTo("com.example");
              assertThat(a.artifactId()).isEqualTo("platform-bom");
              assertThat(a.current()).isEqualTo("1.0.0");
              assertThat(a.target()).isEqualTo("1.1.0");
              assertThat(a.updateType()).isEqualTo("minor");
            });
  }

  @Test
  void emitsManagedDeclarationBumpsForPlatformPomWithoutDeclaredDependencies() {
    EffectivePomResolver resolver = mock(EffectivePomResolver.class);
    MavenCentralService maven = mock(MavenCentralService.class);
    List<ManagedDeclaration> declarations =
        List.of(
            ManagedDeclaration.property(
                "io.cloudevents", "cloudevents-core", "1.0.0", "cloudevents.version"),
            ManagedDeclaration.property(
                "io.fabric8", "kubernetes-client", "1.0.0", "fabric8.version"),
            ManagedDeclaration.property(
                "io.github.springwolf", "springwolf-core", "1.0.0", "springwolf.version"),
            ManagedDeclaration.property("io.nats", "jnats", "1.0.0", "jnats.version"),
            ManagedDeclaration.property(
                "org.apache.camel.springboot", "camel-bean-starter", "1.0.0", "camel.version"),
            ManagedDeclaration.literal("org.apache.tomcat.embed", "tomcat-embed-core", "1.0.0"));
    when(resolver.resolve("<platform/>"))
        .thenReturn(
            new EffectivePomResult(List.of(), List.of(), List.of(), declarations, List.of()));
    when(maven.getAllVersions(any())).thenReturn(List.of("1.1.0", "1.0.0"));

    PomUpgradeRecommendation rec =
        getSuccessData(
            buildTools(resolver, maven)
                .recommend_pom_upgrades("<platform/>", UpgradeMode.MINOR_PATCH, null));

    assertThat(rec.deterministicActions())
        .hasSize(6)
        .allSatisfy(
            action -> {
              assertThat(action.kind()).isEqualTo(UpgradeAction.KIND_MANAGED_DECL_BUMP);
              assertThat(action.target()).isEqualTo("1.1.0");
              assertThat(action.declaredIn()).isEqualTo("dependency_management");
            })
        .extracting(UpgradeAction::artifactId)
        .containsExactly(
            "cloudevents-core",
            "kubernetes-client",
            "springwolf-core",
            "jnats",
            "camel-bean-starter",
            "tomcat-embed-core");
    assertThat(rec.deterministicActions().getFirst())
        .satisfies(
            action -> {
              assertThat(action.editTarget()).isEqualTo(ManagedDeclaration.EDIT_TARGET_PROPERTY);
              assertThat(action.propertyName()).isEqualTo("cloudevents.version");
            });
    assertThat(rec.deterministicActions().getLast())
        .satisfies(
            action -> {
              assertThat(action.editTarget())
                  .isEqualTo(ManagedDeclaration.EDIT_TARGET_LITERAL_VERSION);
              assertThat(action.propertyName()).isNull();
            });
    assertThat(rec.needsAttention()).isEmpty();
  }

  @Test
  void emitsPluginDependencyBumpWithOwnerMetadata() {
    EffectivePomResolver resolver = mock(EffectivePomResolver.class);
    MavenCentralService maven = mock(MavenCentralService.class);
    PluginDependencyDeclaration checkstyle =
        PluginDependencyDeclaration.property(
            "com.puppycrawl.tools",
            "checkstyle",
            "10.26.1",
            "checkstyle.version",
            "org.apache.maven.plugins",
            "maven-checkstyle-plugin",
            PluginDependencyDeclaration.BUILD_PLUGINS);
    when(resolver.resolve("<build-parent/>"))
        .thenReturn(
            new EffectivePomResult(
                List.of(), List.of(), List.of(), List.of(), List.of(checkstyle), List.of()));
    when(maven.getAllVersions(any())).thenReturn(List.of("10.27.0", "10.26.1"));

    PomUpgradeRecommendation rec =
        getSuccessData(
            buildTools(resolver, maven)
                .recommend_pom_upgrades("<build-parent/>", UpgradeMode.MINOR_PATCH, null));

    assertThat(rec.deterministicActions())
        .singleElement()
        .satisfies(
            action -> {
              assertThat(action.kind()).isEqualTo(UpgradeAction.KIND_PLUGIN_DEP_BUMP);
              assertThat(action.groupId()).isEqualTo("com.puppycrawl.tools");
              assertThat(action.artifactId()).isEqualTo("checkstyle");
              assertThat(action.target()).isEqualTo("10.27.0");
              assertThat(action.editTarget()).isEqualTo(ManagedDeclaration.EDIT_TARGET_PROPERTY);
              assertThat(action.propertyName()).isEqualTo("checkstyle.version");
              assertThat(action.declaredIn()).isEqualTo(PluginDependencyDeclaration.BUILD_PLUGINS);
              assertThat(action.ownerGroupId()).isEqualTo("org.apache.maven.plugins");
              assertThat(action.ownerArtifactId()).isEqualTo("maven-checkstyle-plugin");
            });
  }

  @Test
  void routesMajorUpgradeToNeedsAttentionInMinorPatchMode() {
    EffectivePomResolver resolver = mock(EffectivePomResolver.class);
    MavenCentralService maven = mock(MavenCentralService.class);
    when(resolver.resolve("<pom/>"))
        .thenReturn(
            new EffectivePomResult(
                List.of(explicit("com.example", "lib", "1.5.0")), List.of(), List.of(), List.of()));
    // Newest first: major 2.x available, 1.5.3 is the latest same-major stable.
    when(maven.getAllVersions(any())).thenReturn(List.of("2.0.0", "1.5.3", "1.5.0"));

    PomUpgradeRecommendation rec =
        getSuccessData(
            buildTools(resolver, maven)
                .recommend_pom_upgrades("<pom/>", UpgradeMode.MINOR_PATCH, null));

    assertThat(rec.deterministicActions()).isEmpty();
    assertThat(rec.needsAttention())
        .singleElement()
        .isInstanceOfSatisfying(
            NeedsAttention.MajorAvailable.class,
            entry -> {
              assertThat(entry.kind()).isEqualTo("major_available");
              assertThat(entry.current()).isEqualTo("1.5.0");
              assertThat(entry.currentMajorLatest()).isEqualTo("1.5.3");
              assertThat(entry.latestStable()).isEqualTo("2.0.0");
              assertThat(entry.source()).isEqualTo("EXPLICIT");
            });
  }

  @Test
  void modeAllPromotesMajorToDeterministicAction() {
    EffectivePomResolver resolver = mock(EffectivePomResolver.class);
    MavenCentralService maven = mock(MavenCentralService.class);
    when(resolver.resolve("<pom/>"))
        .thenReturn(
            new EffectivePomResult(
                List.of(explicit("com.example", "lib", "1.5.0")), List.of(), List.of(), List.of()));
    when(maven.getAllVersions(any())).thenReturn(List.of("2.0.0", "1.5.0"));

    PomUpgradeRecommendation rec =
        getSuccessData(
            buildTools(resolver, maven).recommend_pom_upgrades("<pom/>", UpgradeMode.ALL, null));

    assertThat(rec.deterministicActions())
        .singleElement()
        .satisfies(
            a -> {
              assertThat(a.kind()).isEqualTo(UpgradeAction.KIND_EXPLICIT_BUMP);
              assertThat(a.target()).isEqualTo("2.0.0");
              assertThat(a.updateType()).isEqualTo("major");
            });
    assertThat(rec.needsAttention()).isEmpty();
  }

  @Test
  void surfacesExplicitOverrideToNeedsAttention() {
    EffectivePomResolver resolver = mock(EffectivePomResolver.class);
    MavenCentralService maven = mock(MavenCentralService.class);

    MavenCoordinate bom = MavenCoordinate.of("com.example", "platform-bom", "1.0.0");
    EffectiveDependency override =
        new EffectiveDependency(
            "com.fasterxml.jackson.core",
            "jackson-databind",
            "2.20.0",
            Source.EXPLICIT_OVERRIDE,
            Optional.of(bom),
            List.of(new ManagedAlternative("2.19.0", bom)));
    when(resolver.resolve("<pom/>"))
        .thenReturn(new EffectivePomResult(List.of(override), List.of(), List.of(), List.of()));
    when(maven.getAllVersions(any())).thenReturn(List.of("2.20.5", "2.20.0"));

    PomUpgradeRecommendation rec =
        getSuccessData(
            buildTools(resolver, maven)
                .recommend_pom_upgrades("<pom/>", UpgradeMode.MINOR_PATCH, null));

    assertThat(rec.deterministicActions()).isEmpty();
    assertThat(rec.needsAttention())
        .singleElement()
        .isInstanceOfSatisfying(
            NeedsAttention.ExplicitOverride.class,
            entry -> {
              assertThat(entry.kind()).isEqualTo("explicit_override");
              assertThat(entry.currentExplicit()).isEqualTo("2.20.0");
              assertThat(entry.latestOnCentral()).isEqualTo("2.20.5");
              assertThat(entry.managingCandidates())
                  .singleElement()
                  .satisfies(
                      c -> {
                        assertThat(c.version()).isEqualTo("2.19.0");
                        assertThat(c.managedByCoordinate())
                            .isEqualTo("com.example:platform-bom:1.0.0");
                      });
            });
  }

  @Test
  void surfacesMultiBomConflictToNeedsAttention() {
    EffectivePomResolver resolver = mock(EffectivePomResolver.class);
    MavenCentralService maven = mock(MavenCentralService.class);

    MavenCoordinate bomA = MavenCoordinate.of("com.example", "bom-a", "1.0.0");
    MavenCoordinate bomB = MavenCoordinate.of("com.example", "bom-b", "2.0.0");
    EffectiveDependency contestedManaged =
        new EffectiveDependency(
            "com.fasterxml.jackson.core",
            "jackson-databind",
            "2.18.0",
            Source.MANAGED,
            Optional.of(bomA),
            List.of(new ManagedAlternative("2.19.2", bomB)));
    when(resolver.resolve("<pom/>"))
        .thenReturn(
            new EffectivePomResult(List.of(contestedManaged), List.of(), List.of(), List.of()));
    // The BOMs themselves have no newer versions in this fixture, so no bom_bump fires —
    // and the contested dep itself goes to needs_attention via the conflict branch.
    when(maven.getAllVersions(any())).thenReturn(List.of("2.20.0", "2.19.2", "2.18.0"));

    PomUpgradeRecommendation rec =
        getSuccessData(
            buildTools(resolver, maven)
                .recommend_pom_upgrades("<pom/>", UpgradeMode.MINOR_PATCH, null));

    // Conflict surfaces regardless of BOM bump path.
    assertThat(rec.needsAttention())
        .filteredOn(NeedsAttention.Conflict.class::isInstance)
        .singleElement()
        .isInstanceOfSatisfying(
            NeedsAttention.Conflict.class,
            entry -> {
              assertThat(entry.currentlyResolvesTo()).isEqualTo("2.18.0");
              assertThat(entry.currentlyManagedBy()).isEqualTo("com.example:bom-a:1.0.0");
              assertThat(entry.candidates())
                  .hasSize(2)
                  .satisfies(
                      list -> {
                        assertThat(list.get(0).version()).isEqualTo("2.18.0");
                        assertThat(list.get(1).version()).isEqualTo("2.19.2");
                      });
              assertThat(entry.latestOnCentral()).isEqualTo("2.20.0");
            });
  }

  @Test
  void noActionForUpToDateExplicitDep() {
    EffectivePomResolver resolver = mock(EffectivePomResolver.class);
    MavenCentralService maven = mock(MavenCentralService.class);
    when(resolver.resolve("<pom/>"))
        .thenReturn(
            new EffectivePomResult(
                List.of(explicit("com.example", "lib", "1.0.0")), List.of(), List.of(), List.of()));
    when(maven.getAllVersions(any())).thenReturn(List.of("1.0.0", "0.9.0"));

    PomUpgradeRecommendation rec =
        getSuccessData(
            buildTools(resolver, maven)
                .recommend_pom_upgrades("<pom/>", UpgradeMode.MINOR_PATCH, null));

    assertThat(rec.deterministicActions()).isEmpty();
    assertThat(rec.needsAttention()).isEmpty();
  }

  @Test
  void passesThroughResolverWarnings() {
    EffectivePomResolver resolver = mock(EffectivePomResolver.class);
    MavenCentralService maven = mock(MavenCentralService.class);
    when(resolver.resolve("<pom/>"))
        .thenReturn(
            new EffectivePomResult(
                List.of(),
                List.of(),
                List.of(),
                List.of("Parent com.example:parent:1.0.0 could not be fetched")));

    PomUpgradeRecommendation rec =
        getSuccessData(buildTools(resolver, maven).recommend_pom_upgrades("<pom/>", null, null));

    assertThat(rec.warnings())
        .containsExactly("Parent com.example:parent:1.0.0 could not be fetched");
  }

  @Test
  void translatesInvalidPomToErrorResponse() {
    EffectivePomResolver resolver = mock(EffectivePomResolver.class);
    when(resolver.resolve("<bad-pom"))
        .thenThrow(new IllegalArgumentException("Input is not a valid POM: malformed"));

    ToolResponse response =
        buildTools(resolver, mock(MavenCentralService.class))
            .recommend_pom_upgrades("<bad-pom", null, null);

    assertThat(response).isInstanceOf(ToolResponse.Error.class);
    assertThat(((ToolResponse.Error) response).error().message())
        .startsWith("Invalid POM input:")
        .contains("malformed");
  }

  @Test
  void skipsTransitivelyManagedBomNotInUserPom() {
    // Simulates jackson-bom being transitively imported via the parent chain. The user's POM
    // has no jackson-bom entry, so even though jackson-bom 1.0.0 -> 1.0.1 is available we must
    // NOT emit a bom_bump action (the agent would have nowhere to apply it).
    EffectivePomResolver resolver = mock(EffectivePomResolver.class);
    MavenCentralService maven = mock(MavenCentralService.class);

    MavenCoordinate transitiveBom =
        MavenCoordinate.of("com.fasterxml.jackson", "jackson-bom", "1.0.0");
    MavenCoordinate directParent =
        MavenCoordinate.of("org.springframework.boot", "spring-boot-starter-parent", "3.5.14");
    when(resolver.resolve("<pom/>"))
        .thenReturn(
            new EffectivePomResult(
                List.of(
                    managed(
                        "com.fasterxml.jackson.core", "jackson-databind", "2.18.0", transitiveBom)),
                List.of(directParent),
                List.of(), // no root imports
                List.of()));
    // jackson-bom HAS a newer version on Central, but it's not user-controllable here.
    // Parent has no newer version in this fixture.
    when(maven.getAllVersions(MavenCoordinate.of("com.fasterxml.jackson", "jackson-bom", null)))
        .thenReturn(List.of("1.0.1", "1.0.0"));
    when(maven.getAllVersions(
            MavenCoordinate.of("org.springframework.boot", "spring-boot-starter-parent", null)))
        .thenReturn(List.of("3.5.14"));

    PomUpgradeRecommendation rec =
        getSuccessData(
            buildTools(resolver, maven)
                .recommend_pom_upgrades("<pom/>", UpgradeMode.MINOR_PATCH, null));

    assertThat(rec.deterministicActions())
        .as("transitive jackson-bom must not be recommended as a bom_bump")
        .noneMatch(a -> "jackson-bom".equals(a.artifactId()));
    assertThat(rec.needsAttention())
        .as("transitive jackson-bom must not surface as needs_attention either")
        .noneMatch(n -> "jackson-bom".equals(n.artifactId()));
  }

  @Test
  void classifiesDirectParentEvenWithoutManagedDeps() {
    // The direct parent is always a user-controllable knob, even if no managed dep cites it as
    // its immediate managedBy (most management cascades through deeper BOM imports).
    EffectivePomResolver resolver = mock(EffectivePomResolver.class);
    MavenCentralService maven = mock(MavenCentralService.class);

    MavenCoordinate parent =
        MavenCoordinate.of("org.springframework.boot", "spring-boot-starter-parent", "3.5.14");
    when(resolver.resolve("<pom/>"))
        .thenReturn(new EffectivePomResult(List.of(), List.of(parent), List.of(), List.of()));
    when(maven.getAllVersions(any())).thenReturn(List.of("3.5.16", "3.5.14"));

    PomUpgradeRecommendation rec =
        getSuccessData(
            buildTools(resolver, maven)
                .recommend_pom_upgrades("<pom/>", UpgradeMode.MINOR_PATCH, null));

    assertThat(rec.deterministicActions())
        .singleElement()
        .satisfies(
            a -> {
              assertThat(a.kind()).isEqualTo(UpgradeAction.KIND_BOM_BUMP);
              assertThat(a.artifactId()).isEqualTo("spring-boot-starter-parent");
              assertThat(a.current()).isEqualTo("3.5.14");
              assertThat(a.target()).isEqualTo("3.5.16");
            });
  }

  @Test
  void serializesNeedsAttentionKindDiscriminator() {
    JsonMapper mapper = new JsonMapper();
    NeedsAttention major =
        new NeedsAttention.MajorAvailable("g", "a", "1.0", "1.5", "2.0", "EXPLICIT", null);
    NeedsAttention conflict =
        new NeedsAttention.Conflict("g", "a", "1.0", "bom:1", List.of(), "2.0");
    NeedsAttention override =
        new NeedsAttention.ExplicitOverride("g", "a", "1.0", List.of(), "2.0");

    assertThat(mapper.writeValueAsString(major)).contains("\"kind\":\"major_available\"");
    assertThat(mapper.writeValueAsString(conflict)).contains("\"kind\":\"conflict\"");
    assertThat(mapper.writeValueAsString(override)).contains("\"kind\":\"explicit_override\"");
  }

  private static EffectiveDependency explicit(String groupId, String artifactId, String version) {
    return new EffectiveDependency(
        groupId, artifactId, version, Source.EXPLICIT, Optional.empty(), List.of());
  }

  private static EffectiveDependency managed(
      String groupId, String artifactId, String version, MavenCoordinate managedBy) {
    return new EffectiveDependency(
        groupId, artifactId, version, Source.MANAGED, Optional.of(managedBy), List.of());
  }

  private static MavenDependencyTools buildTools(
      EffectivePomResolver resolver, MavenCentralService maven) {
    return new MavenDependencyTools(
        maven,
        new VersionComparator(),
        new Context7Properties(false, null),
        mock(VulnerabilityService.class),
        resolver);
  }
}
