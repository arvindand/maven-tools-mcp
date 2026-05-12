# Phase 6a — Effective POM Resolver Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an internal `EffectivePomResolver` service that takes pasted Maven POM content and returns, for each declared dependency, its effective version and a classification of where that version came from (`EXPLICIT`, `MANAGED`, `EXPLICIT_OVERRIDE`) plus the managing BOM coordinate when applicable. Parent POMs and BOM imports are fetched from Maven Central. **No MCP tool is wired in this phase** — that ships in Phase 6b.

**Architecture:** Clean-room reimplementation of the resolution subset we need from `maxxq-org/maxxq-maven` (MIT, credited in `NOTICE` and the package-info). We deliberately do **not** use `org.apache.maven:maven-model-builder` or Aether/`maven-resolver` — they require filesystem + reflection plumbing and pull in 5–10MB of transitive dependencies. Instead we use `org.apache.maven:maven-model` (the data classes + Xpp3 reader, ~200KB) and hand-roll the resolution loop in ~250 lines. POM fetches go through the existing `MavenCentralService`, which already does direct repo access against `https://repo1.maven.org/maven2/...` (since v2.0.0); we add one `fetchPomXml(coord)` method alongside the existing `maven-metadata.xml` fetcher.

**Out of scope for Phase 6a (do not implement, do not add tests for):**
- Transitive dependency walking (we only resolve effective versions for *declared* deps)
- Version range syntax `[1.0,2.0)` (treat as opaque string and flag in warnings)
- Profile activation (analyze the default profile state only)
- CI-friendly `${revision}` and `<flatten-maven-plugin>` outputs (flag as warning)
- MCP tool wiring (`analyze_pom_dependencies` — Phase 6b)
- Recommendation generation ("upgrade Spring Boot to get Jackson 2.20" — Phase 6c)

**Tech Stack:** Java 25, Spring Boot 3.5.14, `org.apache.maven:maven-model:3.9.12`, OkHttp (already used via Spring's `RestClient`), Caffeine (existing cache infra), JUnit 5, AssertJ, Mockito.

**Attribution policy:** Credit `maxxq-org/maxxq-maven` (MIT, Guy Chauliac) in three places: (a) one-line in the new `package-info.java` for `com.arvindand.mcp.maven.pom`, (b) a `NOTICE` file at repo root, (c) an "Acknowledgements" line in `README.md`. The Java sources are written from scratch — no copy-paste — but the algorithm shape (parent walk → properties → BOM import → depMgmt merge → classify) follows their approach.

---

## File Structure

New package `com.arvindand.mcp.maven.pom/`:

| File | Responsibility |
|---|---|
| `package-info.java` | Package Javadoc + maxxq-maven attribution |
| `Source.java` | enum: `EXPLICIT`, `MANAGED`, `EXPLICIT_OVERRIDE` |
| `EffectiveDependency.java` | record `(MavenCoordinate coord, String effectiveVersion, Source source, Optional<MavenCoordinate> managedBy)` |
| `EffectivePomResult.java` | record `(List<EffectiveDependency> deps, List<MavenCoordinate> parentChain, List<String> warnings)` |
| `PomFetcher.java` | interface `Optional<Model> fetch(MavenCoordinate coord)` |
| `MavenCentralPomFetcher.java` | impl wired to `MavenCentralService.fetchPomXml` + parses with `MavenXpp3Reader` |
| `PropertyInterpolator.java` | `${name}` substitution against a `Map<String,String>`; cap 10 passes for cycle safety |
| `EffectivePomResolver.java` | the orchestrator — public `EffectivePomResult resolve(String pomXml)` |

Modified files:
- `pom.xml` — add `maven-model` dep
- `src/main/java/com/arvindand/mcp/maven/service/MavenCentralService.java` — add `fetchPomXml(MavenCoordinate)`
- `src/main/java/com/arvindand/mcp/maven/config/NativeImageConfiguration.java` — reflection hints for maven-model classes (Task 13 only if native build fails)
- `README.md` — "Acknowledgements" section
- `NOTICE` (new file at repo root) — maxxq-maven MIT attribution
- `CHANGELOG.md` — Unreleased entry

---

## Task 1: Add `maven-model` dependency

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: Add the dep**

In `pom.xml`, locate the `<dependencies>` section (after the existing `org.apache.maven:maven-artifact` entry — they're related and should sit together). Add:

```xml
        <!-- Maven model XML reader and data classes for POM resolution (Phase 6a).
             We deliberately do not pull in maven-model-builder or maven-resolver -
             we hand-roll the resolution loop in com.arvindand.mcp.maven.pom. -->
        <dependency>
            <groupId>org.apache.maven</groupId>
            <artifactId>maven-model</artifactId>
            <version>3.9.12</version>
        </dependency>
```

- [ ] **Step 2: Verify the dep resolves and the project still compiles**

Run: `./mvnw clean compile -DskipTests`
Expected: BUILD SUCCESS. The `maven-model` jar (~180KB) plus its only transitive (`plexus-utils` for Xpp3) downloads.

- [ ] **Step 3: Commit**

```bash
git add pom.xml
git commit -m "build: add maven-model 3.9.12 for POM resolution

Data classes + Xpp3 reader only. Deliberately not pulling in
maven-model-builder or maven-resolver — the resolution loop is
hand-rolled in com.arvindand.mcp.maven.pom (Phase 6a)."
```

---

## Task 2: Domain types + package-info

**Files:**
- Create: `src/main/java/com/arvindand/mcp/maven/pom/package-info.java`
- Create: `src/main/java/com/arvindand/mcp/maven/pom/Source.java`
- Create: `src/main/java/com/arvindand/mcp/maven/pom/EffectiveDependency.java`
- Create: `src/main/java/com/arvindand/mcp/maven/pom/EffectivePomResult.java`

- [ ] **Step 1: Create package-info**

```java
/**
 * Effective POM resolution for the {@code analyze_pom_dependencies} family of tools.
 *
 * <p>Resolves the effective version of each declared dependency in a POM by walking the
 * parent chain, applying {@code <dependencyManagement>}, and resolving BOM imports
 * ({@code <scope>import</scope><type>pom</type>}). All parent and BOM fetches go through
 * {@link com.arvindand.mcp.maven.service.MavenCentralService} against Maven Central.
 *
 * <p>The resolution algorithm shape (parent walk → properties → BOM import → depMgmt merge)
 * is adapted from the MIT-licensed
 * <a href="https://github.com/maxxq-org/maxxq-maven">maxxq-org/maxxq-maven</a> by Guy Chauliac.
 * No source was copied; the implementation is written from scratch and scoped to declared-dep
 * resolution only (no transitive walking, no scope downgrade rules).
 */
package com.arvindand.mcp.maven.pom;
```

- [ ] **Step 2: Create Source enum**

```java
package com.arvindand.mcp.maven.pom;

/**
 * Classifies where the effective version of a declared dependency came from.
 *
 * <ul>
 *   <li>{@link #EXPLICIT} - the POM declared the version directly with no entry in any
 *       reachable {@code <dependencyManagement>}.
 *   <li>{@link #MANAGED} - the POM did not declare a version; the version was inherited
 *       from a parent POM or a BOM import.
 *   <li>{@link #EXPLICIT_OVERRIDE} - the POM declared an explicit version, but a reachable
 *       {@code <dependencyManagement>} entry also covers the same coordinate. The explicit
 *       value wins, but the override is worth surfacing to the user.
 * </ul>
 */
public enum Source {
  EXPLICIT,
  MANAGED,
  EXPLICIT_OVERRIDE
}
```

- [ ] **Step 3: Create EffectiveDependency record**

```java
package com.arvindand.mcp.maven.pom;

import com.arvindand.mcp.maven.model.MavenCoordinate;
import java.util.Optional;

/**
 * The resolved view of a single declared dependency in a POM.
 *
 * @param coordinate the dependency's {@code groupId:artifactId}
 * @param effectiveVersion the version that would be used at build time after parent + BOM
 *     resolution. Never null or blank; resolution failures are surfaced via warnings on
 *     {@link EffectivePomResult}, not silent nulls.
 * @param source where {@code effectiveVersion} came from — see {@link Source}
 * @param managedBy the BOM or parent coordinate that supplied {@code effectiveVersion} when
 *     {@code source == MANAGED} or {@code source == EXPLICIT_OVERRIDE}. Empty for
 *     {@code EXPLICIT}.
 */
public record EffectiveDependency(
    MavenCoordinate coordinate,
    String effectiveVersion,
    Source source,
    Optional<MavenCoordinate> managedBy) {

  public EffectiveDependency {
    if (coordinate == null) {
      throw new IllegalArgumentException("coordinate must not be null");
    }
    if (effectiveVersion == null || effectiveVersion.isBlank()) {
      throw new IllegalArgumentException("effectiveVersion must not be blank");
    }
    if (source == null) {
      throw new IllegalArgumentException("source must not be null");
    }
    if (managedBy == null) {
      throw new IllegalArgumentException("managedBy must not be null (use Optional.empty())");
    }
  }
}
```

- [ ] **Step 4: Create EffectivePomResult record**

```java
package com.arvindand.mcp.maven.pom;

import com.arvindand.mcp.maven.model.MavenCoordinate;
import java.util.List;

/**
 * The complete result of resolving a POM.
 *
 * @param dependencies one entry per declared dependency in the input POM (transitive
 *     dependencies are intentionally NOT included — Phase 6a scope)
 * @param parentChain the resolved parent coordinates walked during resolution, root-first
 *     (the input POM's immediate parent at index 0). Empty if the POM has no parent.
 * @param warnings non-fatal issues — unresolved properties, parents that couldn't be fetched,
 *     ranges left as opaque strings, etc. Resolution still produces a result; warnings let the
 *     caller decide whether to trust each entry.
 */
public record EffectivePomResult(
    List<EffectiveDependency> dependencies,
    List<MavenCoordinate> parentChain,
    List<String> warnings) {

  public EffectivePomResult {
    if (dependencies == null) {
      throw new IllegalArgumentException("dependencies must not be null");
    }
    if (parentChain == null) {
      throw new IllegalArgumentException("parentChain must not be null");
    }
    if (warnings == null) {
      throw new IllegalArgumentException("warnings must not be null");
    }
    dependencies = List.copyOf(dependencies);
    parentChain = List.copyOf(parentChain);
    warnings = List.copyOf(warnings);
  }
}
```

- [ ] **Step 5: Verify it compiles**

Run: `./mvnw clean compile -DskipTests`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/arvindand/mcp/maven/pom/
git commit -m "feat(pom): add domain types for effective POM resolution

Source enum, EffectiveDependency record, EffectivePomResult record,
plus package-info crediting maxxq-org/maxxq-maven (MIT). No behaviour
yet — pure types for the resolver service in subsequent tasks."
```

---

## Task 3: `PropertyInterpolator` (TDD)

**Files:**
- Create: `src/test/java/com/arvindand/mcp/maven/pom/PropertyInterpolatorTest.java`
- Create: `src/main/java/com/arvindand/mcp/maven/pom/PropertyInterpolator.java`

- [ ] **Step 1: Write failing tests**

```java
package com.arvindand.mcp.maven.pom;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class PropertyInterpolatorTest {

  @Test
  void returnsInputUnchangedWhenNoPlaceholder() {
    assertThat(PropertyInterpolator.interpolate("2.19.2", Map.of())).isEqualTo("2.19.2");
  }

  @Test
  void substitutesSinglePlaceholder() {
    assertThat(PropertyInterpolator.interpolate("${jackson.version}", Map.of("jackson.version", "2.19.2")))
        .isEqualTo("2.19.2");
  }

  @Test
  void resolvesChainedPlaceholders() {
    var props = Map.of("a", "${b}", "b", "${c}", "c", "final");
    assertThat(PropertyInterpolator.interpolate("${a}", props)).isEqualTo("final");
  }

  @Test
  void leavesUnknownPlaceholderUntouched() {
    assertThat(PropertyInterpolator.interpolate("${missing}", Map.of())).isEqualTo("${missing}");
  }

  @Test
  void substitutesMultiplePlaceholdersInOneString() {
    var props = Map.of("a", "1", "b", "2");
    assertThat(PropertyInterpolator.interpolate("${a}.${b}", props)).isEqualTo("1.2");
  }

  @Test
  void capsRecursionAtTenPasses() {
    // a cycle: a -> b -> a -> ...
    var props = Map.of("a", "${b}", "b", "${a}");
    // After 10 passes we stop. The string may still contain ${a} or ${b}.
    String result = PropertyInterpolator.interpolate("${a}", props);
    assertThat(result).matches("\\$\\{[ab]}");
  }

  @Test
  void returnsNullForNullInput() {
    assertThat(PropertyInterpolator.interpolate(null, Map.of())).isNull();
  }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw test -Dtest=PropertyInterpolatorTest -pl . -q 2>&1 | tail -20`
Expected: compilation error — `PropertyInterpolator` does not exist.

- [ ] **Step 3: Implement**

```java
package com.arvindand.mcp.maven.pom;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Substitutes Maven-style {@code ${name}} placeholders against a property map.
 *
 * <p>Supports chained placeholders ({@code ${a}} → {@code ${b}} → {@code final}) up to a fixed
 * depth (10 passes) to guarantee termination on cyclic property definitions. Unknown
 * placeholders are left unchanged — the resolver surfaces them as warnings, not errors.
 *
 * <p>Edge cases deliberately not supported in Phase 6a: {@code ${project.version}},
 * {@code ${project.parent.version}}, {@code ${revision}}, environment variables. These are
 * future work; for now they appear as literal unresolved placeholders in the output.
 */
final class PropertyInterpolator {

  private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");
  private static final int MAX_PASSES = 10;

  private PropertyInterpolator() {}

  static String interpolate(String input, Map<String, String> properties) {
    if (input == null) {
      return null;
    }
    String current = input;
    for (int pass = 0; pass < MAX_PASSES; pass++) {
      Matcher matcher = PLACEHOLDER.matcher(current);
      if (!matcher.find()) {
        return current;
      }
      StringBuilder replaced = new StringBuilder();
      matcher.reset();
      boolean anySubstitution = false;
      while (matcher.find()) {
        String key = matcher.group(1);
        String value = properties.get(key);
        if (value != null) {
          matcher.appendReplacement(replaced, Matcher.quoteReplacement(value));
          anySubstitution = true;
        } else {
          matcher.appendReplacement(replaced, Matcher.quoteReplacement(matcher.group(0)));
        }
      }
      matcher.appendTail(replaced);
      String next = replaced.toString();
      if (!anySubstitution || next.equals(current)) {
        return next;
      }
      current = next;
    }
    return current;
  }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw test -Dtest=PropertyInterpolatorTest -pl . -q 2>&1 | tail -10`
Expected: `Tests run: 7, Failures: 0, Errors: 0`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/arvindand/mcp/maven/pom/PropertyInterpolator.java \
        src/test/java/com/arvindand/mcp/maven/pom/PropertyInterpolatorTest.java
git commit -m "feat(pom): add PropertyInterpolator with cycle-safe 10-pass cap

\${name} substitution against a properties map. Unknown placeholders
are returned unchanged so the resolver can surface them as warnings.
project.version / revision / env-var resolution are out of scope for
Phase 6a."
```

---

## Task 4: `PomFetcher` interface

**Files:**
- Create: `src/main/java/com/arvindand/mcp/maven/pom/PomFetcher.java`

- [ ] **Step 1: Create interface**

```java
package com.arvindand.mcp.maven.pom;

import com.arvindand.mcp.maven.model.MavenCoordinate;
import java.util.Optional;
import org.apache.maven.model.Model;

/**
 * Resolves a Maven coordinate to a parsed {@link Model}. Implementations are responsible for
 * fetching the POM XML (typically from Maven Central) and parsing it. Returns an empty
 * {@link Optional} for any coordinate that cannot be fetched or parsed — callers (the
 * resolver) record this as a warning rather than an error.
 */
public interface PomFetcher {

  Optional<Model> fetch(MavenCoordinate coordinate);
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./mvnw clean compile -DskipTests`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/arvindand/mcp/maven/pom/PomFetcher.java
git commit -m "feat(pom): add PomFetcher interface

Single-method interface to keep the resolver pure and testable against
in-memory stubs. The Maven-Central-backed impl arrives in Task 5."
```

---

## Task 5: Extend `MavenCentralService` with `fetchPomXml`

**Files:**
- Modify: `src/main/java/com/arvindand/mcp/maven/service/MavenCentralService.java`

- [ ] **Step 1: Add the method**

`MavenCentralService` already does direct repo access against `https://repo1.maven.org/maven2/...` for `maven-metadata.xml`. We add one more endpoint alongside it for POM files. Locate any existing public method that uses `restClient` for direct-repo XML fetches (e.g., the maven-metadata fetcher) — the new method should sit near it for cohesion. Add:

```java
  /**
   * Fetches the raw POM XML for a Maven coordinate from the configured repository. Used by the
   * POM resolver (see {@link com.arvindand.mcp.maven.pom.MavenCentralPomFetcher}) to walk
   * parent chains and BOM imports.
   *
   * <p>Returns an empty {@link Optional} on 404 or any other client/server error — callers
   * surface this as a resolution warning rather than failing the whole analysis.
   *
   * @param coordinate must have a non-null version
   */
  @Cacheable(value = MAVEN_POM_XML, key = "#coordinate.toFullCoordinate()")
  public Optional<String> fetchPomXml(MavenCoordinate coordinate) {
    if (coordinate == null || coordinate.version() == null || coordinate.version().isBlank()) {
      throw new IllegalArgumentException("coordinate.version() must be set to fetch a POM");
    }
    String groupPath = coordinate.groupId().replace('.', '/');
    String path =
        "/" + groupPath + "/" + coordinate.artifactId() + "/" + coordinate.version() + "/"
            + coordinate.artifactId() + "-" + coordinate.version() + ".pom";
    try {
      String xml = restClient.get().uri(path).retrieve().body(String.class);
      return Optional.ofNullable(xml);
    } catch (RestClientException ex) {
      logger.debug("POM fetch failed for {}: {}", coordinate.toFullCoordinate(), ex.getMessage());
      return Optional.empty();
    }
  }
```

- [ ] **Step 2: Add the `MAVEN_POM_XML` cache name**

Locate `src/main/java/com/arvindand/mcp/maven/config/CacheConstants.java` and add:

```java
  public static final String MAVEN_POM_XML = "mavenPomXml";
```

- [ ] **Step 3: Register the cache**

Find where Caffeine caches are configured (search for `MAVEN_ALL_VERSIONS` to locate the config). Add a registration line for `MAVEN_POM_XML` with the same TTL/size policy as `MAVEN_ALL_VERSIONS` (POMs at fixed coords are immutable, so this is safe).

- [ ] **Step 4: Verify it compiles and existing tests still pass**

Run: `./mvnw clean test -q 2>&1 | tail -10`
Expected: BUILD SUCCESS, 221+ tests pass (same count as before plus zero new ones — this task adds no tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/arvindand/mcp/maven/service/MavenCentralService.java \
        src/main/java/com/arvindand/mcp/maven/config/CacheConstants.java \
        $(grep -rl 'MAVEN_ALL_VERSIONS' src/main/java/com/arvindand/mcp/maven/config/)
git commit -m "feat(maven-central): add fetchPomXml for POM resolution

Direct repo fetch of {groupPath}/{artifactId}/{version}/<a>-<v>.pom,
sitting alongside the existing maven-metadata.xml fetcher. Cached under
mavenPomXml (24h TTL, same policy as mavenAllVersions — fixed-coord
POMs are immutable). 404s return Optional.empty() so the resolver can
record them as warnings."
```

---

## Task 6: `MavenCentralPomFetcher` (parses XML to Model)

**Files:**
- Create: `src/test/java/com/arvindand/mcp/maven/pom/MavenCentralPomFetcherTest.java`
- Create: `src/main/java/com/arvindand/mcp/maven/pom/MavenCentralPomFetcher.java`

- [ ] **Step 1: Write failing tests**

```java
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
    var coord = new MavenCoordinate("com.example", "lib", "1.0.0");
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
    var coord = new MavenCoordinate("com.example", "missing", "1.0.0");
    when(service.fetchPomXml(coord)).thenReturn(Optional.empty());

    assertThat(new MavenCentralPomFetcher(service).fetch(coord)).isEmpty();
  }

  @Test
  void returnsEmptyOnMalformedXml() {
    var coord = new MavenCoordinate("com.example", "broken", "1.0.0");
    when(service.fetchPomXml(coord)).thenReturn(Optional.of("<not valid xml"));

    assertThat(new MavenCentralPomFetcher(service).fetch(coord)).isEmpty();
  }
}
```

- [ ] **Step 2: Run tests, verify they fail**

Run: `./mvnw test -Dtest=MavenCentralPomFetcherTest -q 2>&1 | tail -10`
Expected: compilation error — `MavenCentralPomFetcher` does not exist.

- [ ] **Step 3: Implement**

```java
package com.arvindand.mcp.maven.pom;

import com.arvindand.mcp.maven.model.MavenCoordinate;
import com.arvindand.mcp.maven.service.MavenCentralService;
import java.io.StringReader;
import java.util.Optional;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@link PomFetcher} that delegates raw XML retrieval to {@link MavenCentralService} and
 * parses the result with {@link MavenXpp3Reader}. Any fetch or parse failure becomes an empty
 * {@link Optional}; the resolver records it as a warning.
 */
@Component
public class MavenCentralPomFetcher implements PomFetcher {

  private static final Logger logger = LoggerFactory.getLogger(MavenCentralPomFetcher.class);
  private final MavenCentralService service;

  public MavenCentralPomFetcher(MavenCentralService service) {
    this.service = service;
  }

  @Override
  public Optional<Model> fetch(MavenCoordinate coordinate) {
    Optional<String> xml = service.fetchPomXml(coordinate);
    if (xml.isEmpty()) {
      return Optional.empty();
    }
    try {
      Model model = new MavenXpp3Reader().read(new StringReader(xml.get()));
      return Optional.of(model);
    } catch (XmlPullParserException | java.io.IOException ex) {
      logger.debug(
          "POM parse failed for {}: {}", coordinate.toFullCoordinate(), ex.getMessage());
      return Optional.empty();
    }
  }
}
```

- [ ] **Step 4: Run tests, verify they pass**

Run: `./mvnw test -Dtest=MavenCentralPomFetcherTest -q 2>&1 | tail -10`
Expected: `Tests run: 3, Failures: 0`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/arvindand/mcp/maven/pom/MavenCentralPomFetcher.java \
        src/test/java/com/arvindand/mcp/maven/pom/MavenCentralPomFetcherTest.java
git commit -m "feat(pom): add MavenCentralPomFetcher with Xpp3 parsing

PomFetcher impl wired to MavenCentralService.fetchPomXml. Parse errors
become Optional.empty so the resolver can record them as warnings."
```

---

## Task 7: `EffectivePomResolver` — single POM, no parent (TDD)

We grow the resolver incrementally across Tasks 7–10. Each task adds one behaviour with its own failing test, implementation, and commit.

**Files:**
- Create: `src/test/java/com/arvindand/mcp/maven/pom/EffectivePomResolverTest.java`
- Create: `src/main/java/com/arvindand/mcp/maven/pom/EffectivePomResolver.java`

- [ ] **Step 1: Write failing test**

```java
package com.arvindand.mcp.maven.pom;

import static org.assertj.core.api.Assertions.assertThat;

import com.arvindand.mcp.maven.model.MavenCoordinate;
import java.util.Map;
import java.util.Optional;
import org.apache.maven.model.Model;
import org.junit.jupiter.api.Test;

class EffectivePomResolverTest {

  /** Stub PomFetcher backed by an in-memory map keyed by groupId:artifactId:version. */
  static PomFetcher stub(Map<String, Model> models) {
    return coord -> Optional.ofNullable(models.get(coord.toFullCoordinate()));
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
              assertThat(d.coordinate())
                  .isEqualTo(
                      new MavenCoordinate("com.fasterxml.jackson.core", "jackson-databind", null));
              assertThat(d.effectiveVersion()).isEqualTo("2.19.2");
              assertThat(d.source()).isEqualTo(Source.EXPLICIT);
              assertThat(d.managedBy()).isEmpty();
            });
  }
}
```

- [ ] **Step 2: Run test, verify it fails**

Run: `./mvnw test -Dtest=EffectivePomResolverTest -q 2>&1 | tail -10`
Expected: compilation error — `EffectivePomResolver` does not exist.

- [ ] **Step 3: Implement minimum viable resolver**

```java
package com.arvindand.mcp.maven.pom;

import com.arvindand.mcp.maven.model.MavenCoordinate;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.springframework.stereotype.Service;

/**
 * Resolves the effective version of each declared dependency in a POM. See
 * {@code package-info.java} for design notes and attribution.
 */
@Service
public class EffectivePomResolver {

  private final PomFetcher fetcher;

  public EffectivePomResolver(PomFetcher fetcher) {
    this.fetcher = fetcher;
  }

  public EffectivePomResult resolve(String pomXml) {
    Model root;
    try {
      root = new MavenXpp3Reader().read(new StringReader(pomXml));
    } catch (XmlPullParserException | IOException ex) {
      throw new IllegalArgumentException("Input is not a valid POM: " + ex.getMessage(), ex);
    }
    List<EffectiveDependency> deps = new ArrayList<>();
    for (Dependency d : root.getDependencies()) {
      if (d.getVersion() != null && !d.getVersion().isBlank()) {
        deps.add(
            new EffectiveDependency(
                new MavenCoordinate(d.getGroupId(), d.getArtifactId(), null),
                d.getVersion(),
                Source.EXPLICIT,
                Optional.empty()));
      }
    }
    return new EffectivePomResult(deps, List.of(), List.of());
  }
}
```

- [ ] **Step 4: Run test, verify it passes**

Run: `./mvnw test -Dtest=EffectivePomResolverTest -q 2>&1 | tail -10`
Expected: `Tests run: 1, Failures: 0`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/arvindand/mcp/maven/pom/EffectivePomResolver.java \
        src/test/java/com/arvindand/mcp/maven/pom/EffectivePomResolverTest.java
git commit -m "feat(pom): resolver MVP - explicit version, no parent

First slice. Resolves a single POM with literal version declarations.
Parent chain, property interpolation, depMgmt, BOM imports arrive in
subsequent commits."
```

---

## Task 8: Parent chain walking + property merge

**Files:**
- Modify: `src/test/java/com/arvindand/mcp/maven/pom/EffectivePomResolverTest.java`
- Modify: `src/main/java/com/arvindand/mcp/maven/pom/EffectivePomResolver.java`

- [ ] **Step 1: Add failing tests**

Add to `EffectivePomResolverTest`:

```java
  /** Helper that parses a POM XML literal into a {@link Model}. */
  static Model parse(String xml) {
    try {
      return new org.apache.maven.model.io.xpp3.MavenXpp3Reader()
          .read(new java.io.StringReader(xml));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
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
        .isEqualTo(new MavenCoordinate("com.example", "parent", "1.0.0"));
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
```

- [ ] **Step 2: Run tests, verify two new tests fail**

Run: `./mvnw test -Dtest=EffectivePomResolverTest -q 2>&1 | tail -15`
Expected: 1 pass, 2 failures or errors.

- [ ] **Step 3: Extend the resolver**

Replace the `resolve` method body with:

```java
  public EffectivePomResult resolve(String pomXml) {
    Model root;
    try {
      root = new MavenXpp3Reader().read(new StringReader(pomXml));
    } catch (XmlPullParserException | IOException ex) {
      throw new IllegalArgumentException("Input is not a valid POM: " + ex.getMessage(), ex);
    }

    List<MavenCoordinate> parentChain = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    Map<String, String> properties = new HashMap<>();
    if (root.getProperties() != null) {
      root.getProperties().forEach((k, v) -> properties.put(k.toString(), v.toString()));
    }

    Model cursor = root;
    int safety = 0;
    while (cursor.getParent() != null && safety++ < 10) {
      var p = cursor.getParent();
      var parentCoord = new MavenCoordinate(p.getGroupId(), p.getArtifactId(), p.getVersion());
      Optional<Model> fetched = fetcher.fetch(parentCoord);
      if (fetched.isEmpty()) {
        warnings.add("Parent " + parentCoord.toFullCoordinate() + " could not be fetched");
        break;
      }
      parentChain.add(parentCoord);
      Model parent = fetched.get();
      if (parent.getProperties() != null) {
        parent
            .getProperties()
            .forEach(
                (k, v) -> properties.putIfAbsent(k.toString(), v.toString()));
      }
      cursor = parent;
    }

    List<EffectiveDependency> deps = new ArrayList<>();
    for (Dependency d : root.getDependencies()) {
      if (d.getVersion() == null || d.getVersion().isBlank()) {
        continue;
      }
      String resolved = PropertyInterpolator.interpolate(d.getVersion(), properties);
      if (resolved == null || resolved.isBlank() || resolved.contains("${")) {
        warnings.add(
            "Could not resolve version for "
                + d.getGroupId()
                + ":"
                + d.getArtifactId()
                + " (raw: "
                + d.getVersion()
                + ")");
        continue;
      }
      deps.add(
          new EffectiveDependency(
              new MavenCoordinate(d.getGroupId(), d.getArtifactId(), null),
              resolved,
              Source.EXPLICIT,
              Optional.empty()));
    }
    return new EffectivePomResult(deps, parentChain, warnings);
  }
```

Add the imports:

```java
import java.util.HashMap;
import java.util.Map;
```

- [ ] **Step 4: Run tests, verify all pass**

Run: `./mvnw test -Dtest=EffectivePomResolverTest -q 2>&1 | tail -10`
Expected: `Tests run: 3, Failures: 0`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/arvindand/mcp/maven/pom/EffectivePomResolver.java \
        src/test/java/com/arvindand/mcp/maven/pom/EffectivePomResolverTest.java
git commit -m "feat(pom): resolver walks parent chain and merges properties

Parent fetches via PomFetcher; properties walk top-down with child
values winning. Property placeholders in dependency versions are
interpolated; unresolved placeholders surface as warnings. Parent chain
recursion is capped at 10 levels for cycle safety. Failed parent fetch
becomes a warning, not a fatal error."
```

---

## Task 9: `<dependencyManagement>` merge (`MANAGED` + `EXPLICIT_OVERRIDE`)

**Files:**
- Modify: `src/test/java/com/arvindand/mcp/maven/pom/EffectivePomResolverTest.java`
- Modify: `src/main/java/com/arvindand/mcp/maven/pom/EffectivePomResolver.java`

- [ ] **Step 1: Add failing tests**

```java
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
                  .contains(new MavenCoordinate("com.example", "parent", "1.0.0"));
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
                  .contains(new MavenCoordinate("com.example", "parent", "1.0.0"));
            });
  }
```

- [ ] **Step 2: Run tests, verify the two new ones fail**

Run: `./mvnw test -Dtest=EffectivePomResolverTest -q 2>&1 | tail -10`
Expected: 3 pass, 2 fail (managed dep returns nothing; override dep is classified as EXPLICIT).

- [ ] **Step 3: Extend the resolver**

We introduce a `ManagedEntry` record to track which BOM/parent supplied a managed version, then thread it through the parent walk and the per-dep classification. Replace the entire `resolve()` method:

```java
  public EffectivePomResult resolve(String pomXml) {
    Model root;
    try {
      root = new MavenXpp3Reader().read(new StringReader(pomXml));
    } catch (XmlPullParserException | IOException ex) {
      throw new IllegalArgumentException("Input is not a valid POM: " + ex.getMessage(), ex);
    }

    List<MavenCoordinate> parentChain = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    Map<String, String> properties = new HashMap<>();
    Map<String, ManagedEntry> managed = new HashMap<>();

    if (root.getProperties() != null) {
      root.getProperties().forEach((k, v) -> properties.put(k.toString(), v.toString()));
    }
    // Note: the *root* POM is not added to `managed` — entries in its own
    // dependencyManagement are recorded under root.coord() so EXPLICIT_OVERRIDE works
    // when a sibling module manages a dep that the same POM also declares.
    var rootCoord =
        new MavenCoordinate(
            firstNonNull(root.getGroupId(), root.getParent() != null ? root.getParent().getGroupId() : null),
            root.getArtifactId(),
            firstNonNull(root.getVersion(), root.getParent() != null ? root.getParent().getVersion() : null));
    recordManagedFrom(root, rootCoord, properties, managed);

    Model cursor = root;
    int safety = 0;
    while (cursor.getParent() != null && safety++ < 10) {
      var p = cursor.getParent();
      var parentCoord = new MavenCoordinate(p.getGroupId(), p.getArtifactId(), p.getVersion());
      Optional<Model> fetched = fetcher.fetch(parentCoord);
      if (fetched.isEmpty()) {
        warnings.add("Parent " + parentCoord.toFullCoordinate() + " could not be fetched");
        break;
      }
      parentChain.add(parentCoord);
      Model parent = fetched.get();
      if (parent.getProperties() != null) {
        parent
            .getProperties()
            .forEach((k, v) -> properties.putIfAbsent(k.toString(), v.toString()));
      }
      recordManagedFrom(parent, parentCoord, properties, managed);
      cursor = parent;
    }

    List<EffectiveDependency> deps = new ArrayList<>();
    for (Dependency d : root.getDependencies()) {
      String key = d.getGroupId() + ":" + d.getArtifactId();
      ManagedEntry mgmt = managed.get(key);
      String declared = d.getVersion();
      if (declared == null || declared.isBlank()) {
        if (mgmt == null) {
          warnings.add(
              "No version for " + key + " and no managed entry found — skipped");
          continue;
        }
        deps.add(
            new EffectiveDependency(
                new MavenCoordinate(d.getGroupId(), d.getArtifactId(), null),
                mgmt.version(),
                Source.MANAGED,
                Optional.of(mgmt.managedBy())));
      } else {
        String resolved = PropertyInterpolator.interpolate(declared, properties);
        if (resolved == null || resolved.isBlank() || resolved.contains("${")) {
          warnings.add(
              "Could not resolve version for " + key + " (raw: " + declared + ")");
          continue;
        }
        Source source = mgmt == null ? Source.EXPLICIT : Source.EXPLICIT_OVERRIDE;
        Optional<MavenCoordinate> mgr =
            mgmt == null ? Optional.empty() : Optional.of(mgmt.managedBy());
        deps.add(
            new EffectiveDependency(
                new MavenCoordinate(d.getGroupId(), d.getArtifactId(), null),
                resolved,
                source,
                mgr));
      }
    }
    return new EffectivePomResult(deps, parentChain, warnings);
  }

  private void recordManagedFrom(
      Model model,
      MavenCoordinate source,
      Map<String, String> properties,
      Map<String, ManagedEntry> sink) {
    if (model.getDependencyManagement() == null) {
      return;
    }
    for (Dependency d : model.getDependencyManagement().getDependencies()) {
      // BOM imports are handled in Task 10; skip them here.
      if ("import".equals(d.getScope()) && "pom".equals(d.getType())) {
        continue;
      }
      String key = d.getGroupId() + ":" + d.getArtifactId();
      if (sink.containsKey(key)) {
        // Closer ancestors win.
        continue;
      }
      String version = PropertyInterpolator.interpolate(d.getVersion(), properties);
      if (version == null || version.isBlank() || version.contains("${")) {
        continue;
      }
      sink.put(key, new ManagedEntry(version, source));
    }
  }

  private static String firstNonNull(String a, String b) {
    return a != null ? a : b;
  }

  private record ManagedEntry(String version, MavenCoordinate managedBy) {}
```

- [ ] **Step 4: Run tests, verify all 5 pass**

Run: `./mvnw test -Dtest=EffectivePomResolverTest -q 2>&1 | tail -10`
Expected: `Tests run: 5, Failures: 0`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/arvindand/mcp/maven/pom/EffectivePomResolver.java \
        src/test/java/com/arvindand/mcp/maven/pom/EffectivePomResolverTest.java
git commit -m "feat(pom): merge dependencyManagement, classify MANAGED + EXPLICIT_OVERRIDE

Walks parent chain accumulating managed entries (closer-ancestor wins).
For each declared dep: no version + managed → MANAGED; explicit version
+ managed → EXPLICIT_OVERRIDE; explicit + no managed → EXPLICIT.
managedBy is set whenever a managed entry was found, regardless of
whether the child overrode the value. BOM imports are deferred to the
next task."
```

---

## Task 10: BOM imports (`<scope>import</scope><type>pom</type>`)

**Files:**
- Modify: `src/test/java/com/arvindand/mcp/maven/pom/EffectivePomResolverTest.java`
- Modify: `src/main/java/com/arvindand/mcp/maven/pom/EffectivePomResolver.java`

- [ ] **Step 1: Add failing test**

```java
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

    assertThat(result.dependencies())
        .singleElement()
        .satisfies(
            d -> {
              assertThat(d.effectiveVersion()).isEqualTo("2.19.2");
              assertThat(d.source()).isEqualTo(Source.MANAGED);
              assertThat(d.managedBy())
                  .contains(new MavenCoordinate("com.example.bom", "my-bom", "1.0.0"));
            });
  }
```

- [ ] **Step 2: Run test, verify it fails**

Run: `./mvnw test -Dtest=EffectivePomResolverTest -q 2>&1 | tail -10`
Expected: the new test fails — the resolver currently skips imports.

- [ ] **Step 3: Add BOM-import resolution**

Replace `recordManagedFrom`:

```java
  private void recordManagedFrom(
      Model model,
      MavenCoordinate source,
      Map<String, String> properties,
      Map<String, ManagedEntry> sink) {
    if (model.getDependencyManagement() == null) {
      return;
    }
    for (Dependency d : model.getDependencyManagement().getDependencies()) {
      if ("import".equals(d.getScope()) && "pom".equals(d.getType())) {
        importBom(d, properties, sink);
        continue;
      }
      String key = d.getGroupId() + ":" + d.getArtifactId();
      if (sink.containsKey(key)) {
        continue;
      }
      String version = PropertyInterpolator.interpolate(d.getVersion(), properties);
      if (version == null || version.isBlank() || version.contains("${")) {
        continue;
      }
      sink.put(key, new ManagedEntry(version, source));
    }
  }

  private void importBom(
      Dependency bomDep, Map<String, String> properties, Map<String, ManagedEntry> sink) {
    String groupId = PropertyInterpolator.interpolate(bomDep.getGroupId(), properties);
    String artifactId = PropertyInterpolator.interpolate(bomDep.getArtifactId(), properties);
    String version = PropertyInterpolator.interpolate(bomDep.getVersion(), properties);
    if (groupId == null || artifactId == null || version == null) {
      return;
    }
    var bomCoord = new MavenCoordinate(groupId, artifactId, version);
    Optional<Model> bom = fetcher.fetch(bomCoord);
    if (bom.isEmpty()) {
      return;
    }
    Model bomModel = bom.get();
    Map<String, String> mergedProps = new HashMap<>(properties);
    if (bomModel.getProperties() != null) {
      bomModel
          .getProperties()
          .forEach((k, v) -> mergedProps.putIfAbsent(k.toString(), v.toString()));
    }
    // Recurse: a BOM may itself import other BOMs.
    recordManagedFrom(bomModel, bomCoord, mergedProps, sink);
  }
```

- [ ] **Step 4: Run tests, verify all 6 pass**

Run: `./mvnw test -Dtest=EffectivePomResolverTest -q 2>&1 | tail -10`
Expected: `Tests run: 6, Failures: 0`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/arvindand/mcp/maven/pom/EffectivePomResolver.java \
        src/test/java/com/arvindand/mcp/maven/pom/EffectivePomResolverTest.java
git commit -m "feat(pom): resolve BOM imports (<scope>import</scope>)

Imported BOMs are fetched and their dependencyManagement is merged in,
with the BOM's own properties layered in for interpolation. Recurses
for BOMs that themselves import other BOMs (Spring Cloud → Spring Boot
patterns). managedBy points at the BOM coordinate, not the importing POM."
```

---

## Task 11: Integration test against this repo's own `pom.xml`

This is the real-world smoke test — it actually hits Maven Central. Marked `@Tag("integration")` so it runs only under the `-Pintegration` or `-Pfull` profile, never on the default `./mvnw test`.

**Files:**
- Create: `src/test/java/com/arvindand/mcp/maven/pom/EffectivePomResolverIT.java`

- [ ] **Step 1: Write the integration test**

```java
package com.arvindand.mcp.maven.pom;

import static org.assertj.core.api.Assertions.assertThat;

import com.arvindand.mcp.maven.config.MavenCentralProperties;
import com.arvindand.mcp.maven.service.MavenCentralService;
import com.arvindand.mcp.maven.util.VersionComparator;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.client.RestClient;

/**
 * Integration test: resolve this repo's own pom.xml against real Maven Central. Verifies the
 * spring-boot-starter-parent chain is followed and that Jackson + similar deps come back as
 * MANAGED rather than EXPLICIT.
 */
@Tag("integration")
class EffectivePomResolverIT {

  @Test
  void resolvesOurOwnPomAgainstRealMavenCentral() throws Exception {
    Path pomPath = Path.of(System.getProperty("user.dir"), "pom.xml");
    String pomXml = Files.readString(pomPath);

    MavenCentralProperties properties = new MavenCentralProperties();
    properties.setBaseUrl("https://repo1.maven.org/maven2");
    RestClient client = RestClient.builder().baseUrl(properties.getBaseUrl()).build();
    MavenCentralService service =
        new MavenCentralService(properties, client, dummyObjectProvider());
    MavenCentralPomFetcher fetcher = new MavenCentralPomFetcher(service);
    EffectivePomResolver resolver = new EffectivePomResolver(fetcher);

    EffectivePomResult result = resolver.resolve(pomXml);

    // Parent chain: spring-boot-starter-parent should resolve.
    assertThat(result.parentChain())
        .anyMatch(c -> c.artifactId().equals("spring-boot-starter-parent"));

    // Maven-model 3.9.12 is an explicit dep we just added, with a literal version.
    assertThat(result.dependencies())
        .anySatisfy(
            d -> {
              if (d.coordinate().artifactId().equals("maven-model")) {
                assertThat(d.effectiveVersion()).isEqualTo("3.9.12");
                assertThat(d.source()).isEqualTo(Source.EXPLICIT);
              }
            });
  }

  // Minimal ObjectProvider for the integration test where Spring is not running.
  @SuppressWarnings("unchecked")
  private static <T> ObjectProvider<T> dummyObjectProvider() {
    return (ObjectProvider<T>) new ObjectProvider<Object>() {
      @Override public Object getObject(Object... args) { return null; }
      @Override public Object getObject() { return null; }
      @Override public Object getIfAvailable() { return null; }
      @Override public Object getIfUnique() { return null; }
    };
  }
}
```

- [ ] **Step 2: Run the integration test**

Run: `./mvnw failsafe:integration-test -Dit.test=EffectivePomResolverIT 2>&1 | tail -20`
Expected: BUILD SUCCESS, 1 IT pass. May take 10–20 seconds depending on cache state and network.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/arvindand/mcp/maven/pom/EffectivePomResolverIT.java
git commit -m "test(pom): integration test resolving this repo's own pom.xml

Hits real Maven Central to verify the spring-boot-starter-parent chain
is fetched + walked, and that an explicitly-declared dep (maven-model)
comes back as EXPLICIT with the literal version. Tagged @integration so
it only runs under -Pintegration or -Pfull."
```

---

## Task 12: Attribution updates

**Files:**
- Create: `NOTICE`
- Modify: `README.md`
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Create `NOTICE` file**

At repo root:

```
maven-tools-mcp
Copyright 2024-2026 Arvind Menon

Licensed under the MIT License (see LICENSE).

------------------------------------------------------------------------
Third-party attributions
------------------------------------------------------------------------

The effective POM resolver (com.arvindand.mcp.maven.pom) is a clean-room
implementation. Its algorithm shape — parent chain walking, property
interpolation, dependencyManagement merge, and <scope>import</scope> BOM
resolution — is adapted from the MIT-licensed maxxq-org/maxxq-maven
library by Guy Chauliac (https://github.com/maxxq-org/maxxq-maven).
No source was copied; the implementation was rewritten from scratch and
scoped to declared-dep resolution only (no transitive dependency walking).
```

- [ ] **Step 2: Add Acknowledgements section to README**

Locate the bottom of `README.md` (after FAQ, before any final links). Add:

```markdown
## Acknowledgements

The effective POM resolver under `com.arvindand.mcp.maven.pom` adapts the resolution
algorithm shape from [maxxq-org/maxxq-maven](https://github.com/maxxq-org/maxxq-maven)
(MIT, Guy Chauliac) — no code was copied; the implementation was written from scratch
and scoped to declared-dep resolution. See [`NOTICE`](NOTICE) for full attribution.
```

- [ ] **Step 3: Update CHANGELOG**

In the `## [Unreleased]` section under `### Added (Unreleased)`:

```markdown
- **POM resolver service** (`com.arvindand.mcp.maven.pom`): internal service that takes
  pasted POM content and returns per-dependency effective versions classified as
  `EXPLICIT`, `MANAGED`, or `EXPLICIT_OVERRIDE`, walking parent POMs and BOM imports
  via Maven Central. No MCP tool exposed yet — that arrives in Phase 6b.
```

- [ ] **Step 4: Commit**

```bash
git add NOTICE README.md CHANGELOG.md
git commit -m "docs(pom): credit maxxq-maven, document Phase 6a internal service

NOTICE file with MIT attribution at repo root; README Acknowledgements
line; CHANGELOG unreleased entry. The resolver is internal in Phase 6a
(no MCP tool yet) — that's reflected in the wording."
```

---

## Task 13: Native image validation + reflection hint sweep

GraalVM Native Image with `maven-model` requires reflection metadata for the Xpp3 parser path. The reachability metadata repository covers most common Maven libraries, but `maven-model` specifically can require manual hints depending on which classes get reached. We discover the gaps by running the native build and fixing what breaks.

**Files:**
- Modify (possibly): `src/main/java/com/arvindand/mcp/maven/config/NativeImageConfiguration.java`

- [ ] **Step 1: Attempt a native image build**

Run from the worktree root:

```bash
./mvnw clean package -DskipTests
SPRING_PROFILES_ACTIVE=docker ./mvnw -Pnative spring-boot:build-image \
  -Dspring-boot.build-image.imageName=maven-tools-mcp:phase6a-test
```

Expected: either BUILD SUCCESS, or a native-image error mentioning a class under `org.apache.maven.model.*` or `org.codehaus.plexus.util.xml.*`.

- [ ] **Step 2: If the build succeeds, smoke-test the image**

```bash
docker run --rm -i maven-tools-mcp:phase6a-test < /dev/null
```

Expected: clean startup log (the server starts and idles waiting for stdio input).

- [ ] **Step 3: If the build fails on reflection**

Add the failing classes to `NativeImageConfiguration.java` as `RuntimeHints` entries. The most likely candidates (add only as needed — do NOT speculatively add all of these):

```java
hints.reflection()
    .registerType(
        org.apache.maven.model.io.xpp3.MavenXpp3Reader.class,
        MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
        MemberCategory.INVOKE_PUBLIC_METHODS)
    .registerType(
        org.apache.maven.model.Model.class,
        MemberCategory.DECLARED_FIELDS,
        MemberCategory.INVOKE_DECLARED_METHODS);
```

Re-run Step 1. Iterate until BUILD SUCCESS.

- [ ] **Step 4: Commit if hints were added**

```bash
git add src/main/java/com/arvindand/mcp/maven/config/NativeImageConfiguration.java
git commit -m "build(native): reflection hints for maven-model in native image

Added only the hints surfaced by failed native builds, not speculatively."
```

If no hints were needed, this task is just the validation — no commit.

---

## Done conditions for Phase 6a

- [ ] Maven build is green: `./mvnw clean verify -Pfull` (all unit + integration tests pass)
- [ ] `EffectivePomResolverTest`: 7 tests (1 PropertyInterpolator infra reuse, 6 resolver behaviours) all green
- [ ] `EffectivePomResolverIT`: green against real Maven Central
- [ ] Native image build with the new resolver in the binary succeeds
- [ ] Native image smoke-starts and exits cleanly on empty stdin
- [ ] No public MCP tool added — `analyze_pom_dependencies` is deferred to Phase 6b (the next plan)
- [ ] `NOTICE` exists at repo root; README has Acknowledgements; CHANGELOG Unreleased entry mentions the service

## Self-review notes

- All 13 tasks have concrete code, exact file paths, and exact commands.
- No `TBD`, no "handle edge cases", no "similar to Task N".
- Type names referenced in later tasks (`Source`, `EffectiveDependency`, `EffectivePomResult`, `PomFetcher`, `ManagedEntry`) are all defined in Task 2 or introduced inline.
- The resolver grows in five TDD slices (Tasks 7→10) with each commit isolating one behaviour.
- Native image validation is its own task at the end so any reflection-hint sweep doesn't pollute the resolver commits.
