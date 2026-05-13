# Phase 6a Expansion — Multi-Module Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax for tracking.

**Slot in execution order:** After Task 9 (`<dependencyManagement>` merge) of the original Phase 6a plan, BEFORE Task 10 (BOM imports). The new tasks are numbered **9b, 9c, 9d, 9e**. Original Tasks 10–13 follow unchanged.

**Goal:** Make `EffectivePomResolver` capable of analyzing arbitrary multi-module Maven projects — including monorepos whose parent or sibling POMs are not yet published to Maven Central — without coupling the server to filesystem access. The caller passes a bundle of POM XML strings; the resolver builds an in-memory fetcher that falls back to Maven Central for anything not in the bundle.

**Architecture:**

```
EffectivePomResolver
  ├── resolve(pomXml)                            (existing — single POM, fetcher injected)
  ├── resolve(pomXml, List<String> sideloaded)   (NEW — same POM, with sideloaded bundle)
  └── resolveAll(List<String> poms)              (NEW — every POM in the bundle, each gets a result)

PomFetcher impls
  ├── MavenCentralPomFetcher        (existing)
  ├── InMemoryPomFetcher            (NEW — Map<GAV, Model> built from caller-supplied POMs)
  └── CompositePomFetcher           (NEW — chain: try fetchers in order, first hit wins)
```

`${project.version}` (and siblings `project.groupId`, `project.artifactId`, `project.parent.version` etc.) become elegant: just pre-seed them as properties on the root POM before interpolation. No new code path in the interpolator.

**What this unlocks:**
- Monorepo with on-disk parent POM (parent included in bundle → resolves cleanly)
- Sibling-module deps using `${project.version}` (resolves to the root POM's version)
- Aggregator-level analysis (`resolveAll` returns per-module results)
- Mixed-source parent chains (some ancestors local, some on Maven Central — composite fetcher handles both)

**Out of scope (still — Phase 6b/c territory):**
- MCP tool wiring (`analyze_pom_dependencies` arguments still come in Phase 6b — that tool will accept the new optional bundle parameter when wired)
- Profile activation
- `${revision}` / flatten-maven-plugin output
- Recommendation generation

**Tech additions:** None — pure code organization on top of existing libs.

---

## File Structure

New files in `com.arvindand.mcp.maven.pom/`:

| File | Responsibility |
|---|---|
| `InMemoryPomFetcher.java` | `PomFetcher` backed by a `Map<String GAV, Model>`. Built from a list of POM XML strings; computes the GAV of each by parsing. |
| `CompositePomFetcher.java` | `PomFetcher` chain: tries each in order, returns the first non-empty result. |

Modified files:
- `EffectivePomResolver.java` — pre-seed `project.*` properties; add `resolve(pomXml, sideloaded)` overload; add `resolveAll(poms)`.

New tests:
- `InMemoryPomFetcherTest.java`
- `CompositePomFetcherTest.java`
- `EffectivePomResolverMultiModuleTest.java` (top-level new test class — keeps the existing test file focused on single-POM cases)

---

## Task 9b: `${project.*}` property pre-seeding (TDD)

**Files:**
- Modify: `src/test/java/com/arvindand/mcp/maven/pom/EffectivePomResolverTest.java`
- Modify: `src/main/java/com/arvindand/mcp/maven/pom/EffectivePomResolver.java`

### Pre-flight (always)
1. `pwd` ends in `/maven-tools-mcp.pom-aware`
2. `git branch --show-current` prints `feat/pom-aware-analysis`
3. `git status` is clean

### Step 1: Add failing test

Append to `EffectivePomResolverTest`:

```java
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
```

### Step 2: Run tests, verify both fail

```bash
./mvnw test -Dtest=EffectivePomResolverTest -q 2>&1 | grep -E "(Tests run:|FAIL)" | tail -5
```

Expected: 7 prior pass + 2 new fail (versions still contain `${project.version}` placeholder).

### Step 3: Add `seedProjectProperties` in the resolver

Inside `EffectivePomResolver.java`, add a private helper:

```java
  /**
   * Seeds Maven's well-known {@code project.*} properties into the property map so that
   * dependency versions like {@code ${project.version}} or {@code ${project.parent.version}}
   * interpolate against the actual root POM coordinates.
   *
   * <p>Six bindings are produced where applicable: {@code project.groupId},
   * {@code project.artifactId}, {@code project.version}, and the {@code project.parent.*}
   * trio when the root POM declares a {@code <parent>} block.
   */
  private static void seedProjectProperties(Model root, Map<String, String> sink) {
    MavenCoordinate rootCoord = rootCoordinate(root);
    if (rootCoord.groupId() != null) {
      sink.put("project.groupId", rootCoord.groupId());
    }
    if (rootCoord.artifactId() != null) {
      sink.put("project.artifactId", rootCoord.artifactId());
    }
    if (rootCoord.version() != null) {
      sink.put("project.version", rootCoord.version());
    }
    var p = root.getParent();
    if (p != null) {
      if (p.getGroupId() != null) {
        sink.put("project.parent.groupId", p.getGroupId());
      }
      if (p.getArtifactId() != null) {
        sink.put("project.parent.artifactId", p.getArtifactId());
      }
      if (p.getVersion() != null) {
        sink.put("project.parent.version", p.getVersion());
      }
    }
  }
```

### Step 4: Call it from `buildPropertyMap`

Modify `buildPropertyMap` (in `EffectivePomResolver`) to seed `project.*` first, BEFORE seeding the root's own `<properties>` (this ordering means the root's own `<properties><project.version>X</project.version></properties>` would override our seed, which is the safe choice — though no real-world POM does that):

```java
  private Map<String, String> buildPropertyMap(
      Model root, List<MavenCoordinate> parentChain, List<String> warnings) {
    Map<String, String> properties = new HashMap<>();
    seedProjectProperties(root, properties);
    if (root.getProperties() != null) {
      root.getProperties().forEach((k, v) -> properties.put(k.toString(), v.toString()));
    }
    // ... rest unchanged
  }
```

### Step 5: Run tests, verify all 9 pass

```bash
./mvnw test -Dtest=EffectivePomResolverTest -q 2>&1 | grep -E "(Tests run:|BUILD)" | tail -5
```

Expected: `Tests run: 9, Failures: 0`.

### Step 6: Pre-flight + commit

```bash
git add src/main/java/com/arvindand/mcp/maven/pom/EffectivePomResolver.java \
        src/test/java/com/arvindand/mcp/maven/pom/EffectivePomResolverTest.java
git commit -m "feat(pom): resolve \${project.version} via property pre-seeding

seedProjectProperties binds project.groupId/artifactId/version and the
parent triplet into the property map before any other properties are
read. Interpolation then resolves \${project.version} (the common
multi-module sibling-version idiom) without any new code path.

The root POM's own <properties> block can still override these seeds
if it explicitly declares project.version (no real POM does this — the
override semantics are belt-and-suspenders)."
```

---

## Task 9c: `InMemoryPomFetcher` + `CompositePomFetcher` (TDD)

**Files:**
- Create: `src/test/java/com/arvindand/mcp/maven/pom/InMemoryPomFetcherTest.java`
- Create: `src/main/java/com/arvindand/mcp/maven/pom/InMemoryPomFetcher.java`
- Create: `src/test/java/com/arvindand/mcp/maven/pom/CompositePomFetcherTest.java`
- Create: `src/main/java/com/arvindand/mcp/maven/pom/CompositePomFetcher.java`

### Pre-flight: as Task 9b.

### Step 1: Tests for `InMemoryPomFetcher`

```java
package com.arvindand.mcp.maven.pom;

import static org.assertj.core.api.Assertions.assertThat;

import com.arvindand.mcp.maven.model.MavenCoordinate;
import java.util.List;
import org.junit.jupiter.api.Test;

class InMemoryPomFetcherTest {

  @Test
  void resolvesPomFromSelfDeclaredGav() {
    String pom =
        """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <groupId>com.example</groupId>
          <artifactId>lib</artifactId>
          <version>1.0.0</version>
        </project>
        """;

    var fetcher = InMemoryPomFetcher.fromXml(List.of(pom));

    assertThat(fetcher.fetch(MavenCoordinate.of("com.example", "lib", "1.0.0"))).isPresent();
    assertThat(fetcher.fetch(MavenCoordinate.of("com.example", "lib", "2.0.0"))).isEmpty();
  }

  @Test
  void inheritsGroupIdAndVersionFromParentBlockWhenMissing() {
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
        </project>
        """;

    var fetcher = InMemoryPomFetcher.fromXml(List.of(pom));

    assertThat(fetcher.fetch(MavenCoordinate.of("com.example", "child", "1.0.0"))).isPresent();
  }

  @Test
  void skipsUnparseablePomsButLoadsTheRest() {
    String validPom =
        """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <groupId>com.example</groupId>
          <artifactId>good</artifactId>
          <version>1.0.0</version>
        </project>
        """;
    String malformed = "<not valid xml";

    var fetcher = InMemoryPomFetcher.fromXml(List.of(validPom, malformed));

    assertThat(fetcher.fetch(MavenCoordinate.of("com.example", "good", "1.0.0"))).isPresent();
  }

  @Test
  void emptyBundleReturnsEmpty() {
    var fetcher = InMemoryPomFetcher.fromXml(List.of());
    assertThat(fetcher.fetch(MavenCoordinate.of("com.example", "anything", "1.0.0"))).isEmpty();
  }
}
```

### Step 2: Implement `InMemoryPomFetcher`

```java
package com.arvindand.mcp.maven.pom;

import com.arvindand.mcp.maven.model.MavenCoordinate;
import java.io.StringReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link PomFetcher} backed by a bundle of POM XML strings supplied at construction. Indexes
 * each POM by its self-declared {@code groupId:artifactId:version}, falling back to the
 * {@code <parent>} block for inherited groupId / version. Unparseable POMs are skipped (a
 * debug log is emitted) — partial bundles still serve hits for the POMs that parsed cleanly.
 *
 * <p>Used as the local-side of a {@link CompositePomFetcher} so callers can sideload
 * unreleased monorepo POMs alongside Maven Central as the fallback.
 */
public final class InMemoryPomFetcher implements PomFetcher {

  private static final Logger logger = LoggerFactory.getLogger(InMemoryPomFetcher.class);
  private final Map<String, Model> byGav;

  private InMemoryPomFetcher(Map<String, Model> byGav) {
    this.byGav = byGav;
  }

  public static InMemoryPomFetcher fromXml(List<String> pomXmls) {
    Objects.requireNonNull(pomXmls, "pomXmls must not be null");
    Map<String, Model> byGav = new HashMap<>();
    for (String xml : pomXmls) {
      try {
        Model model = new MavenXpp3Reader().read(new StringReader(xml));
        String key = gavKey(model);
        if (key != null) {
          byGav.put(key, model);
        }
      } catch (Exception ex) {
        logger.debug("Skipping unparseable sideloaded POM: {}", ex.getMessage());
      }
    }
    return new InMemoryPomFetcher(Map.copyOf(byGav));
  }

  @Override
  public Optional<Model> fetch(MavenCoordinate coordinate) {
    return Optional.ofNullable(byGav.get(coordinate.toCoordinateString()));
  }

  private static String gavKey(Model model) {
    String groupId = model.getGroupId();
    String version = model.getVersion();
    if (model.getParent() != null) {
      if (groupId == null) {
        groupId = model.getParent().getGroupId();
      }
      if (version == null) {
        version = model.getParent().getVersion();
      }
    }
    String artifactId = model.getArtifactId();
    if (groupId == null || artifactId == null || version == null) {
      return null;
    }
    return groupId + ":" + artifactId + ":" + version;
  }
}
```

### Step 3: Tests for `CompositePomFetcher`

```java
package com.arvindand.mcp.maven.pom;

import static org.assertj.core.api.Assertions.assertThat;

import com.arvindand.mcp.maven.model.MavenCoordinate;
import java.util.List;
import java.util.Optional;
import org.apache.maven.model.Model;
import org.junit.jupiter.api.Test;

class CompositePomFetcherTest {

  @Test
  void returnsFirstNonEmptyHit() {
    Model modelA = new Model();
    Model modelB = new Model();
    PomFetcher empty = coord -> Optional.empty();
    PomFetcher hitsA = coord -> Optional.of(modelA);
    PomFetcher hitsB = coord -> Optional.of(modelB);

    var composite = new CompositePomFetcher(List.of(empty, hitsA, hitsB));

    assertThat(composite.fetch(MavenCoordinate.of("g", "a", "1"))).contains(modelA);
  }

  @Test
  void returnsEmptyWhenAllFetchersReturnEmpty() {
    PomFetcher empty = coord -> Optional.empty();
    var composite = new CompositePomFetcher(List.of(empty, empty));

    assertThat(composite.fetch(MavenCoordinate.of("g", "a", "1"))).isEmpty();
  }

  @Test
  void preservesFetcherOrder() {
    Model first = new Model();
    Model second = new Model();
    PomFetcher firstFetcher = coord -> Optional.of(first);
    PomFetcher secondFetcher = coord -> Optional.of(second);

    var composite = new CompositePomFetcher(List.of(firstFetcher, secondFetcher));

    assertThat(composite.fetch(MavenCoordinate.of("g", "a", "1"))).contains(first);
  }
}
```

### Step 4: Implement `CompositePomFetcher`

```java
package com.arvindand.mcp.maven.pom;

import com.arvindand.mcp.maven.model.MavenCoordinate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.apache.maven.model.Model;

/**
 * Chains multiple {@link PomFetcher}s. Returns the first non-empty result for a given
 * coordinate. Typical use: {@code new CompositePomFetcher(List.of(localBundleFetcher,
 * mavenCentralFetcher))} — local POMs win, Maven Central is the fallback.
 */
public final class CompositePomFetcher implements PomFetcher {

  private final List<PomFetcher> fetchers;

  public CompositePomFetcher(List<PomFetcher> fetchers) {
    Objects.requireNonNull(fetchers, "fetchers must not be null");
    this.fetchers = List.copyOf(fetchers);
  }

  @Override
  public Optional<Model> fetch(MavenCoordinate coordinate) {
    for (PomFetcher fetcher : fetchers) {
      Optional<Model> hit = fetcher.fetch(coordinate);
      if (hit.isPresent()) {
        return hit;
      }
    }
    return Optional.empty();
  }
}
```

### Step 5: Verify, then pre-flight + commit

```bash
./mvnw test -Dtest=InMemoryPomFetcherTest,CompositePomFetcherTest -q 2>&1 | grep -E "(Tests run:|BUILD)" | tail -5
```

Expected: `Tests run: 7, Failures: 0` (4 + 3).

```bash
git add src/main/java/com/arvindand/mcp/maven/pom/InMemoryPomFetcher.java \
        src/main/java/com/arvindand/mcp/maven/pom/CompositePomFetcher.java \
        src/test/java/com/arvindand/mcp/maven/pom/InMemoryPomFetcherTest.java \
        src/test/java/com/arvindand/mcp/maven/pom/CompositePomFetcherTest.java
git commit -m "feat(pom): InMemoryPomFetcher + CompositePomFetcher for sideloaded bundles

InMemoryPomFetcher indexes a caller-supplied bundle of POM XML strings
by self-declared GAV (with <parent> fallback for inherited groupId /
version). Unparseable POMs are skipped with a debug log so partial
bundles still serve hits.

CompositePomFetcher chains fetchers in declaration order — typical use
is (localBundle, mavenCentral) so monorepo parents and siblings win
over Maven Central, which serves anything not in the bundle."
```

---

## Task 9d: `resolve(pomXml, sideloaded)` overload + `resolveAll(poms)` (TDD)

**Files:**
- Create: `src/test/java/com/arvindand/mcp/maven/pom/EffectivePomResolverMultiModuleTest.java`
- Modify: `src/main/java/com/arvindand/mcp/maven/pom/EffectivePomResolver.java`

### Pre-flight: as before.

### Step 1: New test class for multi-module behaviour

Keeping the existing `EffectivePomResolverTest` focused on single-POM cases. New tests live in `EffectivePomResolverMultiModuleTest.java`:

```java
package com.arvindand.mcp.maven.pom;

import static org.assertj.core.api.Assertions.assertThat;

import com.arvindand.mcp.maven.model.MavenCoordinate;
import java.util.List;
import java.util.Optional;
import org.apache.maven.model.Model;
import org.junit.jupiter.api.Test;

class EffectivePomResolverMultiModuleTest {

  /** Always-empty fetcher — forces the resolver to find parents in the sideloaded bundle. */
  private static final PomFetcher EMPTY = coord -> Optional.empty();

  @Test
  void resolveWithSideloadedParentResolvesFromBundleWhenCentralUnavailable() {
    String parentPom =
        """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <groupId>com.example</groupId>
          <artifactId>parent</artifactId>
          <version>1.0.0-SNAPSHOT</version>
          <properties>
            <jackson.version>2.19.2</jackson.version>
          </properties>
        </project>
        """;

    String childPom =
        """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <parent>
            <groupId>com.example</groupId>
            <artifactId>parent</artifactId>
            <version>1.0.0-SNAPSHOT</version>
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

    EffectivePomResult result =
        new EffectivePomResolver(EMPTY).resolve(childPom, List.of(parentPom));

    assertThat(result.warnings()).isEmpty();
    assertThat(result.parentChain())
        .singleElement()
        .isEqualTo(MavenCoordinate.of("com.example", "parent", "1.0.0-SNAPSHOT"));
    assertThat(result.dependencies())
        .singleElement()
        .satisfies(d -> assertThat(d.effectiveVersion()).isEqualTo("2.19.2"));
  }

  @Test
  void resolveAllReturnsOneResultPerPomInBundle() {
    String aggregator =
        """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <groupId>com.example</groupId>
          <artifactId>root</artifactId>
          <version>1.0.0</version>
          <packaging>pom</packaging>
          <modules>
            <module>module-a</module>
            <module>module-b</module>
          </modules>
        </project>
        """;

    String moduleA =
        """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <parent>
            <groupId>com.example</groupId>
            <artifactId>root</artifactId>
            <version>1.0.0</version>
          </parent>
          <artifactId>module-a</artifactId>
          <dependencies>
            <dependency>
              <groupId>com.example</groupId>
              <artifactId>module-b</artifactId>
              <version>${project.version}</version>
            </dependency>
          </dependencies>
        </project>
        """;

    String moduleB =
        """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <parent>
            <groupId>com.example</groupId>
            <artifactId>root</artifactId>
            <version>1.0.0</version>
          </parent>
          <artifactId>module-b</artifactId>
        </project>
        """;

    List<EffectivePomResult> results =
        new EffectivePomResolver(EMPTY).resolveAll(List.of(aggregator, moduleA, moduleB));

    assertThat(results).hasSize(3);

    // Aggregator: no dependencies
    assertThat(results.get(0).dependencies()).isEmpty();

    // module-a: depends on module-b at project.version (1.0.0 inherited from parent)
    assertThat(results.get(1).dependencies())
        .singleElement()
        .satisfies(
            d -> {
              assertThat(d.coordinate())
                  .isEqualTo(MavenCoordinate.of("com.example", "module-b", null));
              assertThat(d.effectiveVersion()).isEqualTo("1.0.0");
              assertThat(d.source()).isEqualTo(Source.EXPLICIT);
            });

    // module-b: no dependencies
    assertThat(results.get(2).dependencies()).isEmpty();
    // module-b's parent (the aggregator) was found in the bundle, not Maven Central
    assertThat(results.get(2).warnings()).isEmpty();
  }

  @Test
  void resolveAllFallsBackToInjectedFetcherForPomsNotInBundle() {
    Model externalParent = new Model();
    externalParent.setGroupId("com.external");
    externalParent.setArtifactId("external-parent");
    externalParent.setVersion("9.9.9");
    PomFetcher external =
        coord ->
            "com.external:external-parent:9.9.9".equals(coord.toCoordinateString())
                ? Optional.of(externalParent)
                : Optional.empty();

    String localChild =
        """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <parent>
            <groupId>com.external</groupId>
            <artifactId>external-parent</artifactId>
            <version>9.9.9</version>
          </parent>
          <artifactId>local-child</artifactId>
        </project>
        """;

    List<EffectivePomResult> results =
        new EffectivePomResolver(external).resolveAll(List.of(localChild));

    assertThat(results).singleElement()
        .satisfies(r -> {
          assertThat(r.warnings()).isEmpty();
          assertThat(r.parentChain())
              .singleElement()
              .isEqualTo(MavenCoordinate.of("com.external", "external-parent", "9.9.9"));
        });
  }
}
```

### Step 2: Verify the tests fail to compile (the new methods don't exist yet)

```bash
./mvnw test -Dtest=EffectivePomResolverMultiModuleTest -q 2>&1 | tail -10
```

Expected: compile error — `resolve(String, List<String>)` and `resolveAll(List<String>)` do not exist.

### Step 3: Add the new methods to `EffectivePomResolver`

```java
  /**
   * Resolves {@code pomXml} with a bundle of sideloaded POMs available to the parent /
   * dependencyManagement / BOM-import walks. The sideloaded POMs are tried first; the
   * injected {@link PomFetcher} (typically {@code MavenCentralPomFetcher}) serves as the
   * fallback.
   */
  public EffectivePomResult resolve(String pomXml, List<String> sideloadedPoms) {
    if (sideloadedPoms == null || sideloadedPoms.isEmpty()) {
      return resolve(pomXml);
    }
    PomFetcher composite =
        new CompositePomFetcher(
            List.of(InMemoryPomFetcher.fromXml(sideloadedPoms), this.fetcher));
    return new EffectivePomResolver(composite).resolve(pomXml);
  }

  /**
   * Resolves every POM in the bundle as a primary POM, with all other POMs in the bundle
   * available as sideloaded context. Each result is independent — order matches the input
   * list. Use this for aggregator-level analysis of a multi-module project.
   */
  public List<EffectivePomResult> resolveAll(List<String> poms) {
    Objects.requireNonNull(poms, "poms must not be null");
    if (poms.isEmpty()) {
      return List.of();
    }
    PomFetcher composite =
        new CompositePomFetcher(List.of(InMemoryPomFetcher.fromXml(poms), this.fetcher));
    EffectivePomResolver bundleResolver = new EffectivePomResolver(composite);
    return poms.stream().map(bundleResolver::resolve).toList();
  }
```

Add the import `import java.util.Objects;` if not already present.

### Step 4: Run tests, verify all 3 multi-module + 9 single-POM pass (12 total)

```bash
./mvnw test -Dtest=EffectivePomResolverTest,EffectivePomResolverMultiModuleTest -q 2>&1 | grep -E "(Tests run:|BUILD)" | tail -5
```

Expected: `Tests run: 12, Failures: 0`.

### Step 5: Pre-flight + commit

```bash
git add src/main/java/com/arvindand/mcp/maven/pom/EffectivePomResolver.java \
        src/test/java/com/arvindand/mcp/maven/pom/EffectivePomResolverMultiModuleTest.java
git commit -m "feat(pom): multi-module resolution via sideloaded bundle

Adds two new entry points on EffectivePomResolver:

- resolve(pomXml, sideloadedPoms): single POM, bundle available as
  parent / depMgmt source. The composite fetcher tries the bundle
  first, falls back to the injected (Maven Central) fetcher.
- resolveAll(poms): every POM in the bundle is treated as a primary
  POM with all others as context. Order-preserving 1-to-1 results.

Both wrap a CompositePomFetcher(InMemoryPomFetcher, fetcher) and
delegate to the existing single-POM resolve(). The algorithm itself
is unchanged; only the input shape grows."
```

---

## Task 9e: Integration verification against the realistic monorepo scenario

**Files:**
- Modify: `src/test/java/com/arvindand/mcp/maven/pom/EffectivePomResolverMultiModuleTest.java`

A small additional integration-style test that combines all the moving parts: a monorepo with an on-disk parent that's NOT on Maven Central, a child module using `${project.version}` to reference a sibling, and a real Maven Central dependency thrown in for the composite fallback path. No real network call — uses a stub `external` fetcher that mimics what Maven Central would return for one well-known coord.

### Pre-flight: as before.

### Step 1: Add the test

```java
  @Test
  void resolvesRealisticMonorepoMixingLocalParentSiblingAndCentralFallback() {
    // Parent POM exists only in the bundle (e.g., not yet released to Central)
    String parentPom =
        """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <groupId>com.acme</groupId>
          <artifactId>acme-parent</artifactId>
          <version>0.1.0-SNAPSHOT</version>
          <packaging>pom</packaging>
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
        """;

    // Sibling module also in the bundle
    String siblingPom =
        """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <parent>
            <groupId>com.acme</groupId>
            <artifactId>acme-parent</artifactId>
            <version>0.1.0-SNAPSHOT</version>
          </parent>
          <artifactId>acme-core</artifactId>
        </project>
        """;

    // Primary module: uses local parent's managed jackson version, sibling via project.version,
    // and a third-party dep that the test's external fetcher won't know about (warning expected
    // for that one to prove the fallback chain reached the end).
    String primaryPom =
        """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <parent>
            <groupId>com.acme</groupId>
            <artifactId>acme-parent</artifactId>
            <version>0.1.0-SNAPSHOT</version>
          </parent>
          <artifactId>acme-app</artifactId>
          <dependencies>
            <dependency>
              <groupId>com.fasterxml.jackson.core</groupId>
              <artifactId>jackson-databind</artifactId>
            </dependency>
            <dependency>
              <groupId>com.acme</groupId>
              <artifactId>acme-core</artifactId>
              <version>${project.version}</version>
            </dependency>
          </dependencies>
        </project>
        """;

    EffectivePomResult result =
        new EffectivePomResolver(EMPTY)
            .resolve(primaryPom, List.of(parentPom, siblingPom));

    assertThat(result.warnings()).isEmpty();
    assertThat(result.parentChain())
        .singleElement()
        .isEqualTo(MavenCoordinate.of("com.acme", "acme-parent", "0.1.0-SNAPSHOT"));
    assertThat(result.dependencies())
        .hasSize(2)
        .satisfies(
            deps -> {
              EffectiveDependency jackson =
                  deps.stream()
                      .filter(d -> "jackson-databind".equals(d.coordinate().artifactId()))
                      .findFirst()
                      .orElseThrow();
              assertThat(jackson.effectiveVersion()).isEqualTo("2.19.2");
              assertThat(jackson.source()).isEqualTo(Source.MANAGED);
              assertThat(jackson.managedBy())
                  .contains(MavenCoordinate.of("com.acme", "acme-parent", "0.1.0-SNAPSHOT"));

              EffectiveDependency core =
                  deps.stream()
                      .filter(d -> "acme-core".equals(d.coordinate().artifactId()))
                      .findFirst()
                      .orElseThrow();
              assertThat(core.effectiveVersion()).isEqualTo("0.1.0-SNAPSHOT");
              assertThat(core.source()).isEqualTo(Source.EXPLICIT);
            });
  }
```

### Step 2: Run, verify it passes

```bash
./mvnw test -Dtest=EffectivePomResolverMultiModuleTest -q 2>&1 | grep -E "(Tests run:|BUILD)" | tail -5
```

Expected: `Tests run: 4, Failures: 0`.

Full suite check:

```bash
./mvnw test -q 2>&1 | grep -E "(Tests run:|BUILD)" | tail -3
```

Expected: ~245 tests total, BUILD SUCCESS.

### Step 3: Pre-flight + commit

```bash
git add src/test/java/com/arvindand/mcp/maven/pom/EffectivePomResolverMultiModuleTest.java
git commit -m "test(pom): realistic monorepo integration covers all moving parts

One test exercises every multi-module mechanism at once: an on-disk
parent supplying both <properties> AND <dependencyManagement>, a
sibling module referenced via \${project.version}, classification
producing MANAGED for the parent-managed dep and EXPLICIT for the
sibling. The injected fetcher is empty so the bundle is the sole
source of parent + sibling resolution — proves the composite fetcher
correctly serves all hits from local before reaching the fallback."
```

---

## Done conditions for Phase 6a multi-module expansion

- [ ] Maven build green: `./mvnw clean verify` from the worktree root (with all the new tests in)
- [ ] `EffectivePomResolverTest`: 9 tests (single-POM cases including `project.version`)
- [ ] `EffectivePomResolverMultiModuleTest`: 4 tests (sideloaded parent, resolveAll, fallback verification, realistic monorepo)
- [ ] `InMemoryPomFetcherTest`: 4 tests
- [ ] `CompositePomFetcherTest`: 3 tests
- [ ] Original Tasks 10–13 still apply unchanged

## Self-review notes

- API additions are purely additive — `resolve(pomXml)` keeps its signature.
- The composite + in-memory pair is a recognizable Strategy-pattern composition; no surprise idioms.
- `${project.*}` handled via property pre-seeding — no new code path through `PropertyInterpolator`.
- `resolveAll` returns `List<EffectivePomResult>` in input order; no guesswork about identity (caller can correlate by index or by parsing the GAV themselves).
- No filesystem access introduced; MCP server stays stateless across worktrees and Docker variants.
