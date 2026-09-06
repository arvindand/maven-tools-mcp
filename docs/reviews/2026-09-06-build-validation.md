# Build and runtime validation — 6 September 2026

Branch: `codex/security-and-maven-correctness`.

Follow-up to [the review adjustments](2026-09-06-adjustments.md), using the repository build scripts and independent Claude Sonnet QA agents. Image builds run on Docker Desktop, Linux ARM64, using Java 25 buildpacks for native images and Jib/Eclipse Temurin for the JVM image. The host JAR runs use Java 25.0.1.

## Defects found and corrected

- The global `BP_NATIVE_IMAGE=true` made the advertised JVM image commands attempt native compilation without the corresponding AOT profile. Native selection now follows `-Pnative`; JVM builds use a separate `-jvm` tag.
- Replaced the JVM buildpack path with Google's maintained Jib Maven plugin 3.5.2. Paketo's [known STDOUT logging issue](https://github.com/paketo-buildpacks/libpak/issues/447) prevents strict MCP framing before Java starts; `BP_LOG_LEVEL=ERROR` does not suppress these messages. Jib launches Java directly as an unprivileged user, without a custom filtering wrapper.
- Shell helpers depended on the caller's working directory despite documenting root invocation. They now resolve their own directory, including paths with spaces, and print a usable absolute JAR path.
- Windows helpers had stale variable expansion inside parenthesized blocks, unconditional error jumps, a missing HTTP option, and colliding JVM/native tags. The general helper now uses sequential labels and propagates failures; the Docker helper delegates builds to it.
- The first compiled native image failed during startup because Resilience4j exception names in YAML were unavailable to reflective class lookup. Explicit native hints cover those exception types. A regression test reads the YAML and checks all configured exception names, so future configuration additions cannot silently repeat the defect.
- Weighted Caffeine caches also required native factory metadata absent from the bundled metadata set. Hints cover the selected `SSMWW` cache and `PSWMW` node factories; a regression test inspects the actual cache configuration and checks those selected types. This follows Caffeine's upstream [cache factory](https://github.com/ben-manes/caffeine/blob/v3.2.4/caffeine/src/main/java/com/github/benmanes/caffeine/cache/LocalCacheFactory.java) and [node factory](https://github.com/ben-manes/caffeine/blob/v3.2.4/caffeine/src/main/java/com/github/benmanes/caffeine/cache/NodeFactory.java) selection.
- Registered the nested `McpError` record: native error responses previously failed JSON serialization. Invalid coordinate arguments now use the existing `INVALID_INPUT` classification. The native wire-protocol test exercises this negative path as well as successful calls.
- Moved the POM cache eligibility check into `EffectivePomResult.hasWarnings()`. The previous SpEL expression reflected on a private JDK immutable-list implementation and failed in native mode. Native conformance now calls upgrade recommendations twice to cover this cache boundary.
- Quoted the Mockito Java agent path for test forks whose Maven repository path contains spaces, identified by the Sonnet script review. The released-version fallback remains at `3.2.1`, as required by the repository guidance.
- Native conformance now exercises `${project.version}` inside an imported BOM as well as BOM property isolation. Documentation reflects the distinct image tags, runtime versus build-time profiles, and release-tag publication.

## Environment issue

Spring Boot's buildpack plugin failed while reading an empty Docker Hub authentication entry in this machine's Docker configuration (`DockerRegistryConfigAuthentication`, `'username' must not be null`). The stack trace showed builder-image authentication was responsible; changing publishing configuration was not the remedy and that experimental change was reverted.

The builds used a temporary `DOCKER_CONFIG` containing only `{"auths":{}}`, with `DOCKER_HOST` pointing at Docker Desktop's local Unix socket. This permits anonymous pulls of the public buildpack images and leaves the user's Docker configuration and credentials unchanged. Spring Boot documents its Docker configuration/authentication behavior in [Packaging OCI Images](https://docs.spring.io/spring-boot/maven-plugin/build-image.html).

## Verification

All four image variants built successfully through the helpers. All images run as unprivileged users.

| Image tag suffix | Build | Platform | Size | Runtime result |
| --- | --- | --- | --- | --- |
| `3.2.2-SNAPSHOT` | GraalVM buildpacks | Linux ARM64 | 157.8 MiB | PASS: STDIO, 11 tools |
| `3.2.2-SNAPSHOT-noc7` | GraalVM buildpacks | Linux ARM64 | 156.4 MiB | PASS: STDIO, 9 tools |
| `3.2.2-SNAPSHOT-http` | GraalVM buildpacks | Linux ARM64 | 168.2 MiB | PASS: HTTP health and MCP, 11 tools |
| `3.2.2-SNAPSHOT-jvm` | Jib 3.5.2 / Eclipse Temurin 25 | Linux ARM64 | 375.4 MiB | PASS: STDIO, 11 tools; 9 with Context7 disabled |

Commands used from the project root:

```bash
./build/build.sh 2
./build/build-docker.sh 2
./build/build.sh 8
./build/build-docker.sh 3
./mvnw clean package -Pci
./mvnw clean verify -Pfull
```

The final documented Maven builds used a repository path containing spaces via `-Dmaven.repo.local=...`, confirming the quoted Mockito agent path. The full suite passed **338 unit tests and 46 integration tests**. The unchanged Python updater had **52 passing tests** in the preceding security-review phase.

The six permanent STDIO conformance tests also passed against each of the final native, native-no-Context7, and JVM images using `-Dmaven.tools.test.image=...`. CI now builds and checks the JVM image through its documented helper. The HTTP readiness check retries startup connection resets as well as refused connections.

Strict JSON-RPC probes passed for all four images, both JVM ways of disabling Context7 (profile and environment flags), and the final JAR's default STDIO, no-Context7 STDIO, and HTTP profiles. Checks include:

- Tool inventories and Context7 exposure (11 versus 9 tools).
- Maven version lookup (`junit:junit` stable `4.13.2`).
- Imported BOM property isolation and reflective `${project.version}` interpolation.
- Rejection of traversal-style coordinates and DTD/external-entity POMs.
- HIGH vulnerability detection for `commons-io:commons-io:2.6`, with a verified newer fix at `2.14.0`.
- Minor/patch upgrade recommendations from JUnit `4.12` to `4.13.2` through cached POM resolution.

Independent Claude Sonnet agents (primary model `claude-sonnet-5`) exercised all eleven tools on the final native image, including the Context7 resolve/query chain. Separate Sonnet runs checked native HTTP, native no-Context7, and both final Jib JVM profiles. The JAR modes were also checked with Sonnet and rechecked after rebuilding with strict protocol probes. Final agent runs reported no unexpected tool failures. Strict framing probes additionally caught the Paketo JVM startup output that a tolerant client could ignore.

Shell syntax and **27 script routing/failure cases** passed, covering root/build-directory invocation and paths containing spaces. Sonnet reviewed Windows script control flow and parity; **Windows execution and AMD64 image builds were not performed on this ARM64 macOS host**. Workflow YAML and Git whitespace checks passed.

## Retained artifacts and cleanup

The four final image tags and packaged JAR in `target/` are retained. Task-specific test servers, containers, superseded image builds, temporary build caches and source-inspection fixtures are cleaned up. Existing user images and services are preserved. Nothing was published or pushed.

GraalVM reports some upstream metadata deprecation/experimental-option warnings, but native compilation and runtime checks pass. These runtime tests complement the dependency-advisory snapshot in the original adjustment report; they are not an exhaustive audit of container OS packages.
