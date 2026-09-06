# Review adjustments — 6 September 2026

Branch: `codex/security-and-maven-correctness`.

This implements the confirmed findings in [the original review](2026-09-06-code-review.md).
The review describes the code before these changes.

## Changes

- **Credential isolation:** only the repository-specific client receives authentication. Shared builders and OSV clients remain anonymous. Authenticated cross-origin requests and HTTP redirects are rejected. Operators using a redirecting repository must configure its final URL.
- **Security assessments:** use `us.springett:cvss-calculator:1.5.1` for CVSS vectors, including v4. Select only newer Maven-ordered fixed-version candidates for the queried package, and verify each candidate against OSV before suggesting it. At most three candidates are checked; a missing recommendation means no candidate was verified, not that no fix exists. Failed, malformed or paginated/incomplete OSV responses do not become cached clean results.
- **Maven semantics:** use `maven-model-builder:3.9.16` for inheritance, interpolation, default profiles and management precedence. Imported BOM properties remain scoped to their own effective model. Direct management wins regardless of import ordering. Apache Maven's metadata and POM readers replace the custom XML DTO and regex license parser. Retain `ComparableVersion` for version comparisons.
- **Isolated model resolution:** use in-memory model sources and the configured fetcher only. Ignore POM-provided repository endpoints and filesystem-relative parents. Do not import server system/user properties or evaluate environment/file/JDK profile activators. Report conditional profiles as warnings. Default-profile dependencies remain visible in analysis; profile-only or ambiguous declarations are not emitted as automatic dependency edits.
- **Resource bounds:** one MiB of characters per POM, four MiB per input bundle or model-resolution session, 64 supplied/fetched models, XML depth 64, and 20,000 XML elements. Bound individual interpolation values to 16,384 characters and preflight aggregate expansion. Check the 60-second model budget between operations; an in-flight request is still subject to its HTTP timeout. HTTP bodies are limited to four MiB. Batch operations accept at most 500 items, include permit wait time in their common deadline, and cancel without an unbounded executor-close wait. Cache weights bound retained document/response data.
- **Resilience and caching:** use Spring-managed configured registries and cache successful network results outside the resilience aspects. Transient failures do not become cached empty versions, false existence checks, missing POMs or UNKNOWN assessments. Local rate-limit rejections do not trip upstream circuit breakers. Timestamp requests now use the same cache/resilience boundary.
- **Precise updater edits:** locate XML byte spans without reserializing the POM or calculating Maven versions in Python. Match root/plugin location, plugin owner group/artifact, and the expected current version. Reject ambiguous declarations, guessed properties and inconsistent shared-property updates. Every reference to a changed shared property must have a matching action, so ignored dependencies and profiles cannot be changed indirectly. A failed action prevents saving the batch. MCP application errors return failure rather than “no updates.”
- **Release gating:** image publication requires a release tag matching the POM version and successful Java/Python validation. Branch builds cannot overwrite published tags. Native PR smoke checks invoke MCP conformance, including a sideloaded BOM resolution; HTTP checks fail on an unhealthy image.
- **Dependency fixes:** Spring Boot 4.1.1 aligns Jackson 3.1.5, Jackson 2.21.5 and Log4j 2.25.5. A temporary Tomcat 11.0.25 override covers fixes beyond Boot's managed 11.0.24. Remove the override once the Boot BOM catches up.

New Java classes and regression tests use the repository's `@author Arvind Menon`, Javadoc and formatting conventions. Explicit types remain the default; records, pattern matching, virtual threads and collection conveniences are used where useful.

## Validation

- `./mvnw fmt:format clean verify -Pfull`: **338 unit tests and 46 integration tests passed**, including real Maven Central resolution and MCP wire-protocol checks.
- Python `pytest`: **52 tests passed**.
- Native STDIO, native no-Context7, native HTTP, and JVM images built and passed runtime checks on Linux ARM64. Claude Sonnet agents exercised the actual tools. See [the build validation report](2026-09-06-build-validation.md) for the matrix and additional fixes.
- Refreshed OSV query: **114 runtime coordinates, no matching advisories** on 6 September 2026. This is a dependency-database snapshot, not proof that all possible vulnerabilities are absent.
- Java formatting and whitespace checks passed. New Python editor/regression files pass Ruff.

## Limits and follow-ups

GraalVM executables were compiled through Docker buildpacks and tested locally. The later release validation also built and tested AMD64 variants in CI and checked all six published images locally (AMD64 through emulation). Windows script execution remains unverified. See the [release follow-up](2026-09-06-build-validation.md#release-follow-up).

Maven 4 migration and the full Maven Resolver stack are deferred: Maven 3's model builder and repository metadata components cover the current parent/BOM use case with a smaller dependency surface. Transitive graph resolution, Maven settings/mirrors and explicit activation contexts require separate product work.

The Maven wrapper remains at 3.9.9. Pinning every third-party workflow action to immutable commits and adding a recurring SBOM/advisory audit remain separate supply-chain follow-ups; this branch fixes the confirmed publication gate.

The ignored local `CLAUDE.md` still describes the old custom BOM merge behavior. The implementation and regression tests are authoritative: imported BOM properties do not inherit importer overrides. Python parses XML only to locate safe edit spans; effective-version resolution stays in Maven.
