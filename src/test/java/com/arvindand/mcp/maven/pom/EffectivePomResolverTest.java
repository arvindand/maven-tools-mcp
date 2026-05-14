package com.arvindand.mcp.maven.pom;

import static org.assertj.core.api.Assertions.assertThat;

import com.arvindand.mcp.maven.model.MavenCoordinate;
import java.io.StringReader;
import java.util.Map;
import java.util.Optional;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.junit.jupiter.api.Test;

class EffectivePomResolverTest {

  /** Stub PomFetcher backed by an in-memory map keyed by "groupId:artifactId:version". */
  static PomFetcher stub(Map<String, Model> models) {
    return coord -> Optional.ofNullable(models.get(coord.toCoordinateString()));
  }

  /** Helper that parses a POM XML literal into a {@link Model}. */
  static Model parse(String xml) {
    try {
      return new MavenXpp3Reader().read(new StringReader(xml));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void resolvesExplicitVersionWithNoParent() {
    String pom =
        """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <groupId>com.example</groupId>
          <artifactId>app</artifactId>
          <version>1.0.0</version>
          <dependencies>
            <dependency>
              <groupId>com.fasterxml.jackson.core</groupId>
              <artifactId>jackson-databind</artifactId>
              <version>2.19.2</version>
            </dependency>
          </dependencies>
        </project>
        """;

    EffectivePomResult result = new EffectivePomResolver(stub(Map.of())).resolve(pom);

    assertThat(result.parentChain()).isEmpty();
    assertThat(result.warnings()).isEmpty();
    assertThat(result.dependencies())
        .singleElement()
        .satisfies(
            d -> {
              assertThat(d.groupId()).isEqualTo("com.fasterxml.jackson.core");
              assertThat(d.artifactId()).isEqualTo("jackson-databind");
              assertThat(d.effectiveVersion()).isEqualTo("2.19.2");
              assertThat(d.source()).isEqualTo(Source.EXPLICIT);
              assertThat(d.managedBy()).isEmpty();
            });
  }

  @Test
  void resolvesVersionFromParentProperty() {
    Model parent =
        parse(
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId>
              <artifactId>parent</artifactId>
              <version>1.0.0</version>
              <properties>
                <jackson.version>2.19.2</jackson.version>
              </properties>
            </project>
            """);
    // Stub key format: "groupId:artifactId:version" — coordinate must have version set.
    PomFetcher fetcher = stub(Map.of("com.example:parent:1.0.0", parent));

    String childPom =
        """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <parent>
            <groupId>com.example</groupId>
            <artifactId>parent</artifactId>
            <version>1.0.0</version>
          </parent>
          <artifactId>child</artifactId>
          <dependencies>
            <dependency>
              <groupId>com.fasterxml.jackson.core</groupId>
              <artifactId>jackson-databind</artifactId>
              <version>${jackson.version}</version>
            </dependency>
          </dependencies>
        </project>
        """;

    EffectivePomResult result = new EffectivePomResolver(fetcher).resolve(childPom);

    assertThat(result.parentChain())
        .singleElement()
        .isEqualTo(MavenCoordinate.of("com.example", "parent", "1.0.0"));
    assertThat(result.dependencies())
        .singleElement()
        .satisfies(d -> assertThat(d.effectiveVersion()).isEqualTo("2.19.2"));
  }

  @Test
  void warnsWhenParentCannotBeFetched() {
    String childPom =
        """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <parent>
            <groupId>com.example</groupId>
            <artifactId>missing-parent</artifactId>
            <version>1.0.0</version>
          </parent>
          <artifactId>child</artifactId>
        </project>
        """;

    EffectivePomResult result = new EffectivePomResolver(stub(Map.of())).resolve(childPom);

    assertThat(result.warnings()).anySatisfy(w -> assertThat(w).contains("missing-parent"));
  }

  @Test
  void childPropertyOverridesParentProperty() {
    Model parent =
        parse(
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId>
              <artifactId>parent</artifactId>
              <version>1.0.0</version>
              <properties>
                <jackson.version>2.15.0</jackson.version>
              </properties>
            </project>
            """);
    PomFetcher fetcher = stub(Map.of("com.example:parent:1.0.0", parent));

    String childPom =
        """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <parent>
            <groupId>com.example</groupId>
            <artifactId>parent</artifactId>
            <version>1.0.0</version>
          </parent>
          <artifactId>child</artifactId>
          <properties>
            <jackson.version>2.19.2</jackson.version>
          </properties>
          <dependencies>
            <dependency>
              <groupId>com.fasterxml.jackson.core</groupId>
              <artifactId>jackson-databind</artifactId>
              <version>${jackson.version}</version>
            </dependency>
          </dependencies>
        </project>
        """;

    EffectivePomResult result = new EffectivePomResolver(fetcher).resolve(childPom);

    assertThat(result.dependencies())
        .singleElement()
        .satisfies(d -> assertThat(d.effectiveVersion()).isEqualTo("2.19.2"));
  }

  @Test
  void warnsWhenDependencyVersionIsUnresolvable() {
    String pom =
        """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <groupId>com.example</groupId>
          <artifactId>app</artifactId>
          <version>1.0.0</version>
          <dependencies>
            <dependency>
              <groupId>org.unknown</groupId>
              <artifactId>lib</artifactId>
              <version>${undefined.version}</version>
            </dependency>
          </dependencies>
        </project>
        """;

    EffectivePomResult result = new EffectivePomResolver(stub(Map.of())).resolve(pom);

    assertThat(result.dependencies()).isEmpty();
    assertThat(result.warnings()).anySatisfy(w -> assertThat(w).contains("org.unknown:lib"));
  }

  @Test
  void resolvesManagedVersionFromParentDependencyManagement() {
    Model parent =
        parse(
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId>
              <artifactId>parent</artifactId>
              <version>1.0.0</version>
              <dependencyManagement>
                <dependencies>
                  <dependency>
                    <groupId>com.fasterxml.jackson.core</groupId>
                    <artifactId>jackson-databind</artifactId>
                    <version>2.19.2</version>
                  </dependency>
                </dependencies>
              </dependencyManagement>
            </project>
            """);
    PomFetcher fetcher = stub(Map.of("com.example:parent:1.0.0", parent));

    String childPom =
        """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <parent>
            <groupId>com.example</groupId>
            <artifactId>parent</artifactId>
            <version>1.0.0</version>
          </parent>
          <artifactId>child</artifactId>
          <dependencies>
            <dependency>
              <groupId>com.fasterxml.jackson.core</groupId>
              <artifactId>jackson-databind</artifactId>
            </dependency>
          </dependencies>
        </project>
        """;

    EffectivePomResult result = new EffectivePomResolver(fetcher).resolve(childPom);

    assertThat(result.dependencies())
        .singleElement()
        .satisfies(
            d -> {
              assertThat(d.effectiveVersion()).isEqualTo("2.19.2");
              assertThat(d.source()).isEqualTo(Source.MANAGED);
              assertThat(d.managedBy())
                  .contains(MavenCoordinate.of("com.example", "parent", "1.0.0"));
            });
  }

  @Test
  void rootDependencyManagementWinsOverParentDependencyManagement() {
    Model parent =
        parse(
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId>
              <artifactId>parent</artifactId>
              <version>1.0.0</version>
              <dependencyManagement>
                <dependencies>
                  <dependency>
                    <groupId>com.fasterxml.jackson.core</groupId>
                    <artifactId>jackson-databind</artifactId>
                    <version>2.15.0</version>
                  </dependency>
                </dependencies>
              </dependencyManagement>
            </project>
            """);
    PomFetcher fetcher = stub(Map.of("com.example:parent:1.0.0", parent));

    // Child overrides the managed version in its own <dependencyManagement> — root wins.
    String childPom =
        """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <parent>
            <groupId>com.example</groupId>
            <artifactId>parent</artifactId>
            <version>1.0.0</version>
          </parent>
          <artifactId>child</artifactId>
          <dependencyManagement>
            <dependencies>
              <dependency>
                <groupId>com.fasterxml.jackson.core</groupId>
                <artifactId>jackson-databind</artifactId>
                <version>2.19.2</version>
              </dependency>
            </dependencies>
          </dependencyManagement>
          <dependencies>
            <dependency>
              <groupId>com.fasterxml.jackson.core</groupId>
              <artifactId>jackson-databind</artifactId>
            </dependency>
          </dependencies>
        </project>
        """;

    EffectivePomResult result = new EffectivePomResolver(fetcher).resolve(childPom);

    assertThat(result.dependencies())
        .singleElement()
        .satisfies(
            d -> {
              assertThat(d.effectiveVersion()).isEqualTo("2.19.2");
              assertThat(d.source()).isEqualTo(Source.MANAGED);
              // managedBy points at the child (the root POM) — not the inherited parent.
              assertThat(d.managedBy())
                  .contains(MavenCoordinate.of("com.example", "child", "1.0.0"));
            });
  }

  @Test
  void flagsExplicitOverrideWhenChildSpecifiesVersionForManagedDep() {
    Model parent =
        parse(
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId>
              <artifactId>parent</artifactId>
              <version>1.0.0</version>
              <dependencyManagement>
                <dependencies>
                  <dependency>
                    <groupId>com.fasterxml.jackson.core</groupId>
                    <artifactId>jackson-databind</artifactId>
                    <version>2.19.2</version>
                  </dependency>
                </dependencies>
              </dependencyManagement>
            </project>
            """);
    PomFetcher fetcher = stub(Map.of("com.example:parent:1.0.0", parent));

    String childPom =
        """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <parent>
            <groupId>com.example</groupId>
            <artifactId>parent</artifactId>
            <version>1.0.0</version>
          </parent>
          <artifactId>child</artifactId>
          <dependencies>
            <dependency>
              <groupId>com.fasterxml.jackson.core</groupId>
              <artifactId>jackson-databind</artifactId>
              <version>2.20.0</version>
            </dependency>
          </dependencies>
        </project>
        """;

    EffectivePomResult result = new EffectivePomResolver(fetcher).resolve(childPom);

    assertThat(result.dependencies())
        .singleElement()
        .satisfies(
            d -> {
              assertThat(d.effectiveVersion()).isEqualTo("2.20.0");
              assertThat(d.source()).isEqualTo(Source.EXPLICIT_OVERRIDE);
              assertThat(d.managedBy())
                  .contains(MavenCoordinate.of("com.example", "parent", "1.0.0"));
            });
  }

  @Test
  void resolvesProjectVersionPlaceholder() {
    String pom =
        """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <groupId>com.example</groupId>
          <artifactId>app</artifactId>
          <version>3.2.1</version>
          <dependencies>
            <dependency>
              <groupId>com.example</groupId>
              <artifactId>shared-lib</artifactId>
              <version>${project.version}</version>
            </dependency>
          </dependencies>
        </project>
        """;

    EffectivePomResult result = new EffectivePomResolver(stub(Map.of())).resolve(pom);

    assertThat(result.warnings()).isEmpty();
    assertThat(result.dependencies())
        .singleElement()
        .satisfies(
            d -> {
              assertThat(d.effectiveVersion()).isEqualTo("3.2.1");
              assertThat(d.source()).isEqualTo(Source.EXPLICIT);
            });
  }

  @Test
  void resolvesProjectParentVersionPlaceholder() {
    Model parent =
        parse(
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId>
              <artifactId>parent</artifactId>
              <version>2.0.0</version>
            </project>
            """);
    PomFetcher fetcher = stub(Map.of("com.example:parent:2.0.0", parent));

    String childPom =
        """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <parent>
            <groupId>com.example</groupId>
            <artifactId>parent</artifactId>
            <version>2.0.0</version>
          </parent>
          <artifactId>child</artifactId>
          <dependencies>
            <dependency>
              <groupId>com.example</groupId>
              <artifactId>other-module</artifactId>
              <version>${project.parent.version}</version>
            </dependency>
          </dependencies>
        </project>
        """;

    EffectivePomResult result = new EffectivePomResolver(fetcher).resolve(childPom);

    assertThat(result.warnings()).isEmpty();
    assertThat(result.dependencies())
        .singleElement()
        .satisfies(d -> assertThat(d.effectiveVersion()).isEqualTo("2.0.0"));
  }

  @Test
  void resolvesManagedVersionFromImportedBom() {
    Model bom =
        parse(
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example.bom</groupId>
              <artifactId>my-bom</artifactId>
              <version>1.0.0</version>
              <dependencyManagement>
                <dependencies>
                  <dependency>
                    <groupId>com.fasterxml.jackson.core</groupId>
                    <artifactId>jackson-databind</artifactId>
                    <version>2.19.2</version>
                  </dependency>
                </dependencies>
              </dependencyManagement>
            </project>
            """);
    PomFetcher fetcher = stub(Map.of("com.example.bom:my-bom:1.0.0", bom));

    String pom =
        """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <groupId>com.example</groupId>
          <artifactId>app</artifactId>
          <version>1.0.0</version>
          <dependencyManagement>
            <dependencies>
              <dependency>
                <groupId>com.example.bom</groupId>
                <artifactId>my-bom</artifactId>
                <version>1.0.0</version>
                <type>pom</type>
                <scope>import</scope>
              </dependency>
            </dependencies>
          </dependencyManagement>
          <dependencies>
            <dependency>
              <groupId>com.fasterxml.jackson.core</groupId>
              <artifactId>jackson-databind</artifactId>
            </dependency>
          </dependencies>
        </project>
        """;

    EffectivePomResult result = new EffectivePomResolver(fetcher).resolve(pom);

    assertThat(result.warnings()).isEmpty();
    assertThat(result.dependencies())
        .singleElement()
        .satisfies(
            d -> {
              assertThat(d.effectiveVersion()).isEqualTo("2.19.2");
              assertThat(d.source()).isEqualTo(Source.MANAGED);
              assertThat(d.managedBy())
                  .contains(MavenCoordinate.of("com.example.bom", "my-bom", "1.0.0"));
            });
    // rootImportedBoms must surface the BOM as a user-controllable knob — the user wrote this
    // import in their own POM and can therefore edit its version.
    assertThat(result.rootImportedBoms())
        .containsExactly(MavenCoordinate.of("com.example.bom", "my-bom", "1.0.0"));
  }

  @Test
  void rootImportedBomsExcludesTransitivelyImportedBoms() {
    // The user's POM has no direct DM imports — only a parent. That parent's DM imports another
    // BOM. The transitive BOM must NOT show up in rootImportedBoms (the user has no edit point
    // for it in their own POM file).
    Model jacksonBom =
        parse(
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.fasterxml.jackson</groupId>
              <artifactId>jackson-bom</artifactId>
              <version>2.19.0</version>
              <dependencyManagement>
                <dependencies>
                  <dependency>
                    <groupId>com.fasterxml.jackson.core</groupId>
                    <artifactId>jackson-databind</artifactId>
                    <version>2.19.0</version>
                  </dependency>
                </dependencies>
              </dependencyManagement>
            </project>
            """);
    Model parent =
        parse(
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId>
              <artifactId>parent</artifactId>
              <version>1.0.0</version>
              <dependencyManagement>
                <dependencies>
                  <dependency>
                    <groupId>com.fasterxml.jackson</groupId>
                    <artifactId>jackson-bom</artifactId>
                    <version>2.19.0</version>
                    <type>pom</type>
                    <scope>import</scope>
                  </dependency>
                </dependencies>
              </dependencyManagement>
            </project>
            """);
    PomFetcher fetcher =
        stub(
            Map.of(
                "com.example:parent:1.0.0",
                parent,
                "com.fasterxml.jackson:jackson-bom:2.19.0",
                jacksonBom));

    String childPom =
        """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <parent>
            <groupId>com.example</groupId>
            <artifactId>parent</artifactId>
            <version>1.0.0</version>
          </parent>
          <artifactId>app</artifactId>
          <dependencies>
            <dependency>
              <groupId>com.fasterxml.jackson.core</groupId>
              <artifactId>jackson-databind</artifactId>
            </dependency>
          </dependencies>
        </project>
        """;

    EffectivePomResult result = new EffectivePomResolver(fetcher).resolve(childPom);

    assertThat(result.rootImportedBoms()).isEmpty();
    // jackson-databind is still resolved through the transitive BOM.
    assertThat(result.dependencies())
        .singleElement()
        .satisfies(d -> assertThat(d.effectiveVersion()).isEqualTo("2.19.0"));
  }

  @Test
  void importedBomInheritsPropertiesAndManagedEntriesFromItsOwnParent() {
    // Grandparent BOM: defines the jackson property AND the managed entry
    Model grandparentBom =
        parse(
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example.platform</groupId>
              <artifactId>platform-parent</artifactId>
              <version>1.0.0</version>
              <properties>
                <jackson.version>2.19.2</jackson.version>
              </properties>
              <dependencyManagement>
                <dependencies>
                  <dependency>
                    <groupId>com.fasterxml.jackson.core</groupId>
                    <artifactId>jackson-databind</artifactId>
                    <version>${jackson.version}</version>
                  </dependency>
                </dependencies>
              </dependencyManagement>
            </project>
            """);

    // BOM itself: has the platform-parent as <parent>, no own depMgmt
    Model bom =
        parse(
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <parent>
                <groupId>com.example.platform</groupId>
                <artifactId>platform-parent</artifactId>
                <version>1.0.0</version>
              </parent>
              <artifactId>my-bom</artifactId>
            </project>
            """);

    PomFetcher fetcher =
        stub(
            Map.of(
                "com.example.platform:platform-parent:1.0.0", grandparentBom,
                "com.example.platform:my-bom:1.0.0", bom));

    String pom =
        """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <groupId>com.example</groupId>
          <artifactId>app</artifactId>
          <version>1.0.0</version>
          <dependencyManagement>
            <dependencies>
              <dependency>
                <groupId>com.example.platform</groupId>
                <artifactId>my-bom</artifactId>
                <version>1.0.0</version>
                <type>pom</type>
                <scope>import</scope>
              </dependency>
            </dependencies>
          </dependencyManagement>
          <dependencies>
            <dependency>
              <groupId>com.fasterxml.jackson.core</groupId>
              <artifactId>jackson-databind</artifactId>
            </dependency>
          </dependencies>
        </project>
        """;

    EffectivePomResult result = new EffectivePomResolver(fetcher).resolve(pom);

    assertThat(result.warnings()).isEmpty();
    assertThat(result.dependencies())
        .singleElement()
        .satisfies(
            d -> {
              assertThat(d.effectiveVersion()).isEqualTo("2.19.2");
              assertThat(d.source()).isEqualTo(Source.MANAGED);
              // managedBy points at the BOM that recorded it — in this case the grandparent
              // BOM since that's where the entry actually lives.
              assertThat(d.managedBy())
                  .contains(MavenCoordinate.of("com.example.platform", "platform-parent", "1.0.0"));
            });
  }

  @Test
  void surfacesConflictWhenTwoBomsImportedAtTheSameLevelManageTheSameDep() {
    // Two BOMs imported by the root POM, both manage jackson-databind at different versions.
    // Maven first-declared semantics: BOM A wins, BOM B's value is the losing candidate.
    Model bomA =
        parse(
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId>
              <artifactId>bom-a</artifactId>
              <version>1.0.0</version>
              <dependencyManagement>
                <dependencies>
                  <dependency>
                    <groupId>com.fasterxml.jackson.core</groupId>
                    <artifactId>jackson-databind</artifactId>
                    <version>2.18.0</version>
                  </dependency>
                </dependencies>
              </dependencyManagement>
            </project>
            """);
    Model bomB =
        parse(
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId>
              <artifactId>bom-b</artifactId>
              <version>2.0.0</version>
              <dependencyManagement>
                <dependencies>
                  <dependency>
                    <groupId>com.fasterxml.jackson.core</groupId>
                    <artifactId>jackson-databind</artifactId>
                    <version>2.19.2</version>
                  </dependency>
                </dependencies>
              </dependencyManagement>
            </project>
            """);
    PomFetcher fetcher =
        stub(Map.of("com.example:bom-a:1.0.0", bomA, "com.example:bom-b:2.0.0", bomB));

    String pom =
        """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <groupId>com.example</groupId>
          <artifactId>app</artifactId>
          <version>1.0.0</version>
          <dependencyManagement>
            <dependencies>
              <dependency>
                <groupId>com.example</groupId>
                <artifactId>bom-a</artifactId>
                <version>1.0.0</version>
                <type>pom</type>
                <scope>import</scope>
              </dependency>
              <dependency>
                <groupId>com.example</groupId>
                <artifactId>bom-b</artifactId>
                <version>2.0.0</version>
                <type>pom</type>
                <scope>import</scope>
              </dependency>
            </dependencies>
          </dependencyManagement>
          <dependencies>
            <dependency>
              <groupId>com.fasterxml.jackson.core</groupId>
              <artifactId>jackson-databind</artifactId>
            </dependency>
          </dependencies>
        </project>
        """;

    EffectivePomResult result = new EffectivePomResolver(fetcher).resolve(pom);

    assertThat(result.dependencies())
        .singleElement()
        .satisfies(
            d -> {
              // BOM A is first-declared in the importing POM → wins.
              assertThat(d.effectiveVersion()).isEqualTo("2.18.0");
              assertThat(d.source()).isEqualTo(Source.MANAGED);
              assertThat(d.managedBy())
                  .contains(MavenCoordinate.of("com.example", "bom-a", "1.0.0"));
              // BOM B's 2.19.2 surfaces as a losing candidate so the caller can detect the
              // conflict (and an LLM can reason about whether to pin the version explicitly).
              assertThat(d.conflicts())
                  .singleElement()
                  .satisfies(
                      c -> {
                        assertThat(c.version()).isEqualTo("2.19.2");
                        assertThat(c.managedBy())
                            .isEqualTo(MavenCoordinate.of("com.example", "bom-b", "2.0.0"));
                      });
            });
  }

  @Test
  void emitsEmptyConflictsForUncontentedManagedEntries() {
    Model parent =
        parse(
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId>
              <artifactId>parent</artifactId>
              <version>1.0.0</version>
              <dependencyManagement>
                <dependencies>
                  <dependency>
                    <groupId>com.fasterxml.jackson.core</groupId>
                    <artifactId>jackson-databind</artifactId>
                    <version>2.19.2</version>
                  </dependency>
                </dependencies>
              </dependencyManagement>
            </project>
            """);
    PomFetcher fetcher = stub(Map.of("com.example:parent:1.0.0", parent));

    String pom =
        """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <parent>
            <groupId>com.example</groupId>
            <artifactId>parent</artifactId>
            <version>1.0.0</version>
          </parent>
          <artifactId>child</artifactId>
          <dependencies>
            <dependency>
              <groupId>com.fasterxml.jackson.core</groupId>
              <artifactId>jackson-databind</artifactId>
            </dependency>
          </dependencies>
        </project>
        """;

    EffectivePomResult result = new EffectivePomResolver(fetcher).resolve(pom);

    assertThat(result.dependencies())
        .singleElement()
        .satisfies(d -> assertThat(d.conflicts()).isEmpty());
  }

  @Test
  void terminatesCleanlyOnCyclicBomImports() {
    // BOM A imports BOM B; BOM B imports BOM A. Pathological but valid POM XML.
    // Without cycle protection this would recurse until stack overflow.
    Model bomA =
        parse(
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId>
              <artifactId>bom-a</artifactId>
              <version>1.0.0</version>
              <dependencyManagement>
                <dependencies>
                  <dependency>
                    <groupId>com.example</groupId>
                    <artifactId>bom-b</artifactId>
                    <version>2.0.0</version>
                    <type>pom</type>
                    <scope>import</scope>
                  </dependency>
                  <dependency>
                    <groupId>com.fasterxml.jackson.core</groupId>
                    <artifactId>jackson-databind</artifactId>
                    <version>2.18.0</version>
                  </dependency>
                </dependencies>
              </dependencyManagement>
            </project>
            """);
    Model bomB =
        parse(
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId>
              <artifactId>bom-b</artifactId>
              <version>2.0.0</version>
              <dependencyManagement>
                <dependencies>
                  <dependency>
                    <groupId>com.example</groupId>
                    <artifactId>bom-a</artifactId>
                    <version>1.0.0</version>
                    <type>pom</type>
                    <scope>import</scope>
                  </dependency>
                </dependencies>
              </dependencyManagement>
            </project>
            """);
    PomFetcher fetcher =
        stub(Map.of("com.example:bom-a:1.0.0", bomA, "com.example:bom-b:2.0.0", bomB));

    String pom =
        """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <groupId>com.example</groupId>
          <artifactId>app</artifactId>
          <version>1.0.0</version>
          <dependencyManagement>
            <dependencies>
              <dependency>
                <groupId>com.example</groupId>
                <artifactId>bom-a</artifactId>
                <version>1.0.0</version>
                <type>pom</type>
                <scope>import</scope>
              </dependency>
            </dependencies>
          </dependencyManagement>
          <dependencies>
            <dependency>
              <groupId>com.fasterxml.jackson.core</groupId>
              <artifactId>jackson-databind</artifactId>
            </dependency>
          </dependencies>
        </project>
        """;

    // Should complete without StackOverflowError; bom-a's jackson-databind entry resolves.
    EffectivePomResult result = new EffectivePomResolver(fetcher).resolve(pom);

    assertThat(result.dependencies())
        .singleElement()
        .satisfies(
            d -> {
              assertThat(d.effectiveVersion()).isEqualTo("2.18.0");
              assertThat(d.source()).isEqualTo(Source.MANAGED);
              assertThat(d.managedBy())
                  .contains(MavenCoordinate.of("com.example", "bom-a", "1.0.0"));
            });
  }
}
