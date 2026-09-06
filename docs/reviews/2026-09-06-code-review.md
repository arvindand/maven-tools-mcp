# Code and security review — 6 September 2026

This is the historical review of the pre-fix code. See [implemented adjustments](2026-09-06-adjustments.md) and [final release validation](2026-09-06-build-validation.md#release-follow-up) for the resolved state; findings below are retained as the audit record.

The highest priorities are isolating repository credentials, correcting vulnerability severity/remediation results, bounding untrusted POM processing, and fixing Maven model semantics. Apache Maven's own model builder is a strong replacement for the custom inheritance/interpolation/BOM engine. The existing tests pass, but targeted reproductions expose failures they do not cover.

At the time of this review, application code and dependency versions had not been changed. Priorities below describe remediation urgency for this project, not CVSS ratings.

**Validation and scope**

- Reviewed the Java tool surface, repository and OSV clients, POM engine, configuration, Python updater/client, build dependencies, and GitHub workflows.
- Java unit tests: **311 passed**. Used `./mvnw clean test` with an explicit Mockito Java agent. Initial sandbox runs failed on JVM attach and loopback socket permissions; the permitted rerun passed.
- Integration tests: **43 passed**, including HTTP/stdio protocol conformance and real repository/OSV requests, using `./mvnw verify -Pintegration` with the same agent. Formatting check passed: 90 Java files, zero violations.
- Python: **44 passed**, using `python3 -m pytest agents/copilot-maven-tools-agent/tests -q`.
- Queried OSV independently for **109 resolved compile/runtime artifacts**. Five advisories matched three artifacts. This was a coordinate/version inventory check, not an exploitability verdict or exhaustive advisory inventory.
- Compared synthetic POMs with the project's resolver and the official Maven model builder from the repository's Maven **3.9.9 wrapper distribution**, in separate JVM classpaths. Consulted the current **3.9.16** upstream documentation/source for the migration recommendation.
- Synthetic external-entity probes were rejected by both the POM and metadata parsers. No XXE file disclosure was demonstrated. The confirmed resource-exhaustion issue below is property substitution, independent of XML entities.
- No native-image build, container OS-package scan, production traffic test, or exhaustive Python/build-plugin dependency audit was performed.

**1. [P1] Private repository credentials are attached to OSV requests — reproduced**

Location: [HttpClientConfig.java:51](https://github.com/arvindand/maven-tools-mcp/blob/a817ca5b3324be71ee79c3960b920b4aec329978/src/main/java/com/arvindand/mcp/maven/config/HttpClientConfig.java#L51), [VulnerabilityService.java:58](https://github.com/arvindand/maven-tools-mcp/blob/a817ca5b3324be71ee79c3960b920b4aec329978/src/main/java/com/arvindand/mcp/maven/service/VulnerabilityService.java#L58).

The shared `RestClient.Builder` receives an unconditional repository authentication interceptor. `VulnerabilityService` uses that builder for `https://api.osv.dev/v1/query`. Consequently, enabling BASIC or BEARER repository authentication discloses that credential to OSV whenever an uncached scan runs. The default anonymous configuration is unaffected.

A `MockRestServiceServer` reproduction asserted that `Authorization: Bearer synthetic-review-token` was attached to the OSV URL. No real credential was used or transmitted.

Fix: create separate repository and OSV clients/builders. Scope repository authentication to the configured origin and explicitly define redirect handling, so a later cross-origin redirect cannot reintroduce disclosure. Test BASIC and BEARER requests against both destinations. If private repository credentials have been used with scanning enabled, rotate those credentials after fixing this path.

**2. [P1] CVSS vectors become zero, suppressing high/critical alerts — reproduced**

Location: [VulnerabilityService.java:210](https://github.com/arvindand/maven-tools-mcp/blob/a817ca5b3324be71ee79c3960b920b4aec329978/src/main/java/com/arvindand/mcp/maven/service/VulnerabilityService.java#L210).

OSV's severity score contains a CVSS vector. The implementation passes it to `Double.parseDouble`, catches the parse failure, and substitutes `0.0`. It also ignores CVSS v4. A standard CVSS v3.1 vector representing 9.8 produced `maxSeverity=UNKNOWN`, empty critical/high lists, and no urgent recommendation. The dependency remained `VULNERABLE`, but `requiresAction()` was false.

Fix: use a maintained CVSS implementation supporting the required vector versions; keep unavailable severity explicitly unknown. Add realistic OSV fixtures asserting severity, critical/high identifiers, and aggregate action flags. The existing historical Log4j integration test asserts vulnerable status only and therefore misses this bug. [OSV schema](https://ossf.github.io/osv-schema/).

**3. [P1] “Fixed in” can recommend a downgrade or an unrelated package's version — reproduced in part**

Location: [VulnerabilityService.java:261](https://github.com/arvindand/maven-tools-mcp/blob/a817ca5b3324be71ee79c3960b920b4aec329978/src/main/java/com/arvindand/mcp/maven/service/VulnerabilityService.java#L261), and the `OsvAffected` DTO near line 328.

The implementation selects the lexicographically greatest `fixed` event across all affected packages/ranges. A fixture with fixes `1.9.0` and `1.10.0`, queried at vulnerable `1.9.1`, returned **`1.9.0`**. The DTO discards affected-package identity, making correct package filtering impossible. Taking a maximum also does not prove that a candidate fixes every advisory: some advisories have no fix, or contain later reintroduced vulnerable intervals.

Fix: retain package identity and range events, compare Maven versions with the existing `ComparableVersion` wrapper, and restrict candidates to later versions of the queried package. Re-query candidate versions before describing one as fixing all findings; otherwise return an explicitly unverified candidate or no verified fix. Merely changing the comparator is insufficient.

**4. [P1] Small recursive properties can exhaust memory — bounded reproduction**

Location: [PropertyInterpolator.java:37](https://github.com/arvindand/maven-tools-mcp/blob/a817ca5b3324be71ee79c3960b920b4aec329978/src/main/java/com/arvindand/mcp/maven/pom/PropertyInterpolator.java#L37), [EffectivePomResolver.java:593](https://github.com/arvindand/maven-tools-mcp/blob/a817ca5b3324be71ee79c3960b920b4aec329978/src/main/java/com/arvindand/mcp/maven/pom/EffectivePomResolver.java#L593).

Ten substitution passes bound iteration count, not output size. Interpolating `${a}` with `a=${a}${a}${a}${a}` produced **4,194,304 characters** from a four-character input and sixteen-character property. Higher branching grows exponentially; no destructive out-of-memory test was needed. MCP callers control these values.

Related limits are also missing: overall POM/bundle bytes, bundle count, dependency count, and total fetched BOMs. The visited-BOM set prevents cycles but does not bound a long chain of distinct BOMs. Batch methods enqueue one virtual-thread task per input; their per-future timeouts do not establish a total deadline, and executor closing waits for unfinished work.

Fix: detect recursive property dependencies, enforce expansion/output budgets before appending, cap input bytes and graph depth/node count, and use a total request deadline with cancellation. Bound concurrent requests and queued work. These limits remain necessary after adopting an upstream model builder. HTTP exposure increases the impact; the current HTTP profile has no application authentication configuration and listens beyond loopback by default.

**5. [P1] Imported BOM properties incorrectly inherit overrides from the importer — reproduced**

Location: [EffectivePomResolver.java:538](https://github.com/arvindand/maven-tools-mcp/blob/a817ca5b3324be71ee79c3960b920b4aec329978/src/main/java/com/arvindand/mcp/maven/pom/EffectivePomResolver.java#L538).

Fixture: the root POM defines `lib.version=9`; an imported BOM defines `lib.version=1` and manages a dependency using `${lib.version}`. This tool reports **9**; Maven reports **1**. Maven builds the imported BOM's effective model before importing its management entries. A property in the importing POM is not equivalent to overriding a property inherited from a parent.

This behavior is explicitly described as intentional in `CLAUDE.md`, but contradicts Maven's effective model. It can make consumers believe they have upgraded a vulnerable dependency when their actual Maven build still uses the BOM's version. Correct the guidance and regression expectations together with the engine. [Maven model-building sequence](https://maven.apache.org/ref/3.9.16/maven-model-builder/).

**6. [P1] An earlier BOM import wins over a direct management declaration — reproduced**

Location: [EffectivePomResolver.java:457](https://github.com/arvindand/maven-tools-mcp/blob/a817ca5b3324be71ee79c3960b920b4aec329978/src/main/java/com/arvindand/mcp/maven/pom/EffectivePomResolver.java#L457), especially `processManagementEntry` and `mergeManagedEntry`.

Fixture: dependency management first imports a BOM managing `review:lib:1`, then explicitly manages `review:lib:2`. This tool reports **1**, while Maven reports **2**. Processing entries in document order with first-entry-wins conflates direct management precedence with ordering between imported BOMs.

Fix: use Maven's management import/merge behavior. First-declared wins applies between competing imports after stronger direct/inherited management has been assembled. Add order-independent direct-override tests and parent-versus-import fixtures. [Maven dependency management](https://maven.apache.org/guides/introduction/introduction-to-dependency-mechanism.html).

**7. [P2] Coordinate interpolation and active profiles are missing — reproduced**

Location: [EffectivePomResolver.java:354](https://github.com/arvindand/maven-tools-mcp/blob/a817ca5b3324be71ee79c3960b920b4aec329978/src/main/java/com/arvindand/mcp/maven/pom/EffectivePomResolver.java#L354).

Management keys and normal dependency output use raw group/artifact/type/classifier values. A dependency using `${lib.group}` failed to match a concrete managed coordinate and was omitted; Maven resolved it. Separately, a dependency declared in an `activeByDefault` profile disappeared with **no warning**, while Maven included it.

Fix: interpolate the complete relevant model before keying dependencies. Supply an explicit build/profile context, or warn that profiles are unsupported rather than returning apparently complete results. Keep “declared dependencies” distinct from a complete transitive vulnerability inventory; transitive traversal is already documented as out of scope.

**8. [P2] Configured resilience limits are replaced by defaults — reproduced**

Location: [HttpClientConfig.java:65](https://github.com/arvindand/maven-tools-mcp/blob/a817ca5b3324be71ee79c3960b920b4aec329978/src/main/java/com/arvindand/mcp/maven/config/HttpClientConfig.java#L65).

Explicit `ofDefaults()` registry beans bypass the configuration-backed registry creation. A booted application context reported `maven-central` rate limiting at **50 permits per 500 nanoseconds**, rather than YAML's 10 per second; the circuit window was **100**, rather than 10; the retry predicate accepted `IllegalArgumentException`, contrary to the configured exception list.

Fix: let Resilience4j's Boot auto-configuration construct the registries, or explicitly build them from bound configuration. Add a context test asserting actual named registry values.

**9. [P2] Failure handling bypasses resilience and caches transient failure as data — source-confirmed**

Location: [MavenCentralService.java:227](https://github.com/arvindand/maven-tools-mcp/blob/a817ca5b3324be71ee79c3960b920b4aec329978/src/main/java/com/arvindand/mcp/maven/service/MavenCentralService.java#L227), [VulnerabilityService.java:130](https://github.com/arvindand/maven-tools-mcp/blob/a817ca5b3324be71ee79c3960b920b4aec329978/src/main/java/com/arvindand/mcp/maven/service/VulnerabilityService.java#L130).

Metadata exceptions are converted to empty results inside the annotated method. The private `fetchRepositoryMetadata` annotations cannot intercept those internal calls. Bulk OSV scanning invokes `fetchFromOsv` directly instead of the annotated `scan` method, so its rate limiter/circuit breaker do not apply. That helper also catches failures before a circuit breaker could count them.

Empty metadata/false existence results are cached for 24 hours, and OSV `UNKNOWN` assessments for six hours. A temporary outage can therefore appear as missing versions, absent upgrades, or unavailable security checks long after recovery. POM-fetch fallback results are also cacheable.

Fix: put network operations behind an interceptable boundary, preserve transport failures until resilience processing completes, and distinguish not-found from unavailable. Avoid caching transient failures, or use a separate short-lived negative/error policy. Test fail-then-recover behavior with a real Spring proxy.

**10. [P2] A deterministic edit modifies unrelated POM sections — reproduced**

Location: [upgrade.py:68](https://github.com/arvindand/maven-tools-mcp/blob/a817ca5b3324be71ee79c3960b920b4aec329978/agents/copilot-maven-tools-agent/scripts/upgrade.py#L68), `apply_action` near line 190.

The updater's global regex ignores `declaredIn=dependency_management` for literal actions. A single root-management update from `1.0` to `1.1` also changed the same dependency's `0.9` version in a legacy profile to `1.1`. Property updates likewise replace every matching tag. Plugin matching ignores owner group ID, and action application never verifies the supplied `current` value.

Shared-property edits can also affect ignored dependencies or receive conflicting target values from separate actions. The last action then wins silently.

Fix: identify exact XML locations from server-provided declaration metadata, validate old values, and reject ambiguous edits. Preserve type/classifier/plugin-owner identity and reconcile shared-property consumers before emitting/applying actions. XML-aware editing can preserve formatting; regex fallback based on guessed property names should not count as a deterministic operation.

**11. [P1] Development branch pushes publish production `latest` images — source-confirmed**

Location: [docker.yml:4](https://github.com/arvindand/maven-tools-mcp/blob/a817ca5b3324be71ee79c3960b920b4aec329978/.github/workflows/docker.yml#L4), [docker.yml:136](https://github.com/arvindand/maven-tools-mcp/blob/a817ca5b3324be71ee79c3960b920b4aec329978/.github/workflows/docker.yml#L136).

The workflow triggers on `main`, `develop`, and version tags. Image publishing and manifest jobs only exclude pull requests, and always update `latest`, `latest-noc7`, and `latest-http`. Thus a development/SNAPSHOT push can become the version users and the weekly updater pull. Publishing is not gated on the separate test workflow, and the publishing jobs build with tests skipped.

Fix: restrict production publishing/latest promotion to validated release tags; publish branch builds under separate development tags if wanted. Gate promotion on verification and serialize competing release promotions. Align `CLAUDE.md`'s tag-only release description with the actual workflow.

**12. [P2] The resolved dependency inventory contains known affected versions**

These are real inventory matches, with deployment-dependent exploitability:

| Runtime artifact | Resolved | Minimum fixed version on the same line for the matched advisories | Applicability |
| --- | --- | --- | --- |
| `org.apache.tomcat.embed:tomcat-embed-core` | 11.0.22 | 11.0.25 | OSV matched CVE-2026-65905, CVE-2026-65182, CVE-2026-68525. These involve DIGEST/FORM authentication or servlet security constraints; the checked-in configuration does not enable those mechanisms. |
| `tools.jackson.core:jackson-databind` | 3.1.4 | 3.1.5 | CVE-2026-59889 requires the affected combination of active views and unwrapped container properties. No `JsonView`/`JsonUnwrapped` usage was found in project source. |
| `org.apache.logging.log4j:log4j-api` | 2.25.4 | 2.25.5 | CVE-2026-49844 requires affected `MapMessage` JSON formatting. This project uses Logback and a Log4j-to-SLF4J bridge; no direct `MapMessage` usage was found. |

The [Tomcat security page](https://tomcat.apache.org/security-11.html) lists additional fixes beyond the three returned by this OSV query, including an HTTP/2 allocation-leak denial of service; HTTP/2 is not enabled in the checked-in server configuration. This demonstrates why an OSV-only count is not exhaustive. Vendor severity also differs from generic database ratings for some Tomcat entries. See the [Jackson maintainer advisory](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-5gvw-p9qm-jgwh) and [Apache Logging advisory](https://logging.apache.org/security.html#CVE-2026-49844).

Fix: update the Spring Boot BOM to a verified compatible patch that supplies corrected versions, or use narrowly scoped temporary BOM/property overrides. Keep Jackson modules, Tomcat modules, and Log4j modules aligned. Re-resolve the tree and repeat the scan; the minimum versions above address the identified advisories and are not a promise of complete security. The Jackson 2 test dependency at 2.21.4 also falls in the Jackson advisory range; it was outside the runtime OSV query.

**Other actionable correctness and maintenance issues**

- **[P2] Truncation precedes semantic filtering.** [MavenCentralService.java:235](https://github.com/arvindand/maven-tools-mcp/blob/a817ca5b3324be71ee79c3960b920b4aec329978/src/main/java/com/arvindand/mcp/maven/service/MavenCentralService.java#L235) limits `getAllVersions` to 100 before callers choose stable/same-major versions. With more than 100 newer versions, a valid maintained older release line disappears. Cache complete metadata and limit the response after selecting candidates.
- **[P2] Unknown update types are considered deterministic.** The classifiers in [MavenDependencyTools.java:1655](https://github.com/arvindand/maven-tools-mcp/blob/a817ca5b3324be71ee79c3960b920b4aec329978/src/main/java/com/arvindand/mcp/maven/service/MavenDependencyTools.java#L1655) accept any update type other than `none` after excluding `major`. `unknown` therefore enters `MINOR_PATCH` actions for nonstandard versions. Explicitly allow `minor` and `patch`; route uncertainty to attention.
- **[P2] Security totals are fabricated.** [SecuritySummary.java:88](https://github.com/arvindand/maven-tools-mcp/blob/a817ca5b3324be71ee79c3960b920b4aec329978/src/main/java/com/arvindand/mcp/maven/model/security/SecuritySummary.java#L88) splits remaining vulnerabilities halfway between medium and low. A single medium finding becomes low; unknown findings become medium/low too. Carry exact counts or report unknown counts.
- **[P2] Tool-level errors become successful no-op updater runs.** [upgrade.py:573](https://github.com/arvindand/maven-tools-mcp/blob/a817ca5b3324be71ee79c3960b920b4aec329978/agents/copilot-maven-tools-agent/scripts/upgrade.py#L573) does not reject the application's `status=error` envelope; absent action arrays become empty lists and exit code 0. The direct client checks MCP `isError`, which does not cover a normally returned `ToolResponse.Error`. Validate both envelopes and return failure for incomplete analysis/unmatched required edits.
- **Coordinate validation:** the string parser checks only nonempty group/artifact fields, and POM coordinates bypass it. Reject path separators, traversal segments, query/fragment delimiters, control characters, and unresolved placeholders before URL construction. The configured base URL prevents a simple arbitrary-host substitution; no arbitrary-host SSRF was demonstrated.
- **License parsing:** replace the order-sensitive `<license>` regex with `MavenXpp3Reader` and `Model.getLicenses()`, already available. Reuse cached POM fetches; resolve inherited licenses where promised. Test reordered fields, comments, CDATA, and inherited licenses.
- **Release integrity:** pin privileged CI actions and publisher downloads to reviewed immutable revisions/checksums. The current publisher installation downloads and executes the latest release without verifying a checksum. Add a resolved dependency/SBOM audit to CI; the weekly upgrade recommender is not a transitive vulnerability scanner.
- **Guidance drift:** `CLAUDE.md` still says Boot 3.5 although the actual parent is Boot 4.1.0. The wrapper is Maven 3.9.9 while Maven libraries are 3.9.16. Update guidance and consider aligning the wrapper after verification.

**Where Apache Maven can replace custom code**

| Current responsibility | Apache-maintained replacement | Recommendation |
| --- | --- | --- |
| Parent inheritance, model interpolation, dependency-management import/merge, profile handling | `org.apache.maven:maven-model-builder:3.9.16` | Highest-value change. Use `DefaultModelBuilderFactory` / `ModelBuilder`, a request-scoped `ModelResolver`, and in-memory model sources backed by the existing POM fetchers. |
| Custom metadata XML DTO/parser | `org.apache.maven:maven-repository-metadata:3.9.16` and `MetadataXpp3Reader` | Good small change. Adapt Maven's metadata model to response records; Jackson XML and its dedicated DTO may then be unnecessary. |
| POM license regex | Existing `org.apache.maven:maven-model:3.9.16` | No additional library required for parsing declared licenses. |
| Version ordering | Existing `maven-artifact` / `ComparableVersion` | Already the right choice. Reuse it consistently in remediation selection. Stability classification and major/minor/patch policy still require product-specific decisions. |
| Repository transfer, snapshots, ranges, mirrors, checksums, local cache, future transitive graph | Apache Maven Resolver, with the Maven 3 supplier/provider combination | Consider when these capabilities are required. It adds substantially more components than model building alone. |

The [model builder documentation](https://maven.apache.org/ref/3.9.16/maven-model-builder/) confirms it handles the effective-model pipeline. Its [dependency list](https://maven.apache.org/ref/3.9.16/maven-model-builder/dependencies.html) adds builder support, interpolation, and injection support to Maven components already used here. The [repository metadata component](https://maven.apache.org/ref/3.9.16/maven-repository-metadata/) owns the repository metadata format. [Maven Resolver](https://maven.apache.org/resolver/) provides repository/dependency resolution; its [supplier integration](https://maven.apache.org/resolver/third-party-integrations.html) avoids writing bootstrap code, but requires aligned component versions. Current [Maven 3 supplier dependencies](https://maven.apache.org/resolver/maven-resolver-supplier-mvn3/dependencies.html) illustrate the larger dependency surface.

My recommendation is to adopt the **Maven 3 model builder first**, retaining the current transport initially. Apache's [release history](https://maven.apache.org/docs/history.html) identifies 3.9.16 as the current stable release and Maven 4 as a release-candidate line at review time. A Maven 4 migration is unnecessary to solve the confirmed issues. The [artifact-transfer shared component](https://maven.apache.org/shared/maven-artifact-transfer/) explicitly describes itself as not yet stable; it is not my first choice here.

The model builder is not a drop-in replacement for the whole product. Keep a separate layer for original declaration locations, user-editable properties, BOM provenance/conflict explanations, upgrade policy, MCP responses, and partial-result warnings. Effective models alone cannot safely reconstruct every original edit target.

Use a constrained embedding: in-memory sources, an explicit profile/property context, no ambient settings/environment secrets, controlled file-profile activation, and a `ModelResolver` that ignores/rejects repositories introduced by untrusted POMs. Preserve repository allowlists, authentication isolation, size/depth/time limits, and sideloaded lookup precedence. Do not invoke Maven builds/plugins on supplied POMs. Verify GraalVM reachability, reflection requirements, image size, startup time, and cold/warm resolution latency before replacing the native implementation.

**Suggested implementation order**

1. Isolate HTTP credentials, correct severity/remediation output, and add resource budgets with regression tests.
2. Correct release promotion and update affected dependency families with a fresh inventory scan.
3. Add Maven differential fixtures to the suite, then adopt the official model builder while preserving provenance and deterministic-edit metadata.
4. Replace metadata/license parsing, repair resilience/cache behavior, and make updater edits precise and transactional.
5. Add full Resolver only when repository features or transitive scanning justify its extra dependencies.

**Reproduction record**

| Fixture/check | Project result | Expected/reference result |
| --- | --- | --- |
| Import BOM managing `lib:1`, followed by direct management `lib:2` | 1 | Maven: 2 |
| Root `lib.version=9`, imported BOM owns `lib.version=1` | 9 | Maven: 1 |
| `${lib.group}` in dependency coordinates | Dependency omitted | Maven: resolves managed version 2 |
| Dependency in active-by-default profile | Empty list; no warning | Maven: includes dependency |
| CVSS v3.1 9.8 vector | UNKNOWN severity | CRITICAL |
| Current 1.9.1, fix events 1.9.0 and 1.10.0 | 1.9.0 | Later applicable fix: 1.10.0 |
| Repository auth + mock OSV scan | Repository Authorization attached | No repository credential on OSV request |
| Four-way self-referencing property, ten passes | 4,194,304 output characters | Cycle/budget rejection |
| Runtime rate limiter configuration | 50 / 500 ns | Configured 10 / second |
| Root-management literal update with matching legacy-profile dependency | Both declarations rewritten | Only root declaration rewritten |

Temporary local evidence: [Java reproductions](/tmp/MavenToolsReviewRepro.java), [Maven reference harness](/tmp/MavenBaselineReview.java), [reproduction output](/tmp/maven-tools-review-reproductions.txt), [runtime configuration output](/tmp/maven-tools-review-runtime.txt), [resolved dependency tree](/tmp/maven-tools-review-dependencies.txt), [OSV matches](/tmp/maven-tools-review-osv.json), [unit log](/tmp/maven-tools-review-tests-final.log), [integration log](/tmp/maven-tools-review-integration.log). These temporary files may be cleaned by the OS; the fixtures and observed outcomes are summarized above.
