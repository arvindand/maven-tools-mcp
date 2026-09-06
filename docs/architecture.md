# Architecture

This page collects the implementation notes that are useful for operators and contributors without overloading the main README.

## Design Principles

### Zero external state

- stateless Spring Boot application
- no database
- no persisted query/result store
- data comes from Maven Central and optional external services
- safe to scale horizontally

### Fail fast with graceful degradation

- if Context7 is unavailable, dependency analysis still works
- if OSV.dev is unavailable or returns incomplete data, the security assessment is `UNKNOWN`; this does not mean vulnerability-free
- network failures should surface clearly rather than silently degrading into wrong data

### Fast startup and low ceremony

- Spring AOT and GraalVM native image support
- desktop-friendly stdio transport
- HTTP transport for sidecar and remote-client use cases
- caching to reduce repeated Maven Central traffic

## Request Flow

```text
AI client -> MCP protocol -> Maven Tools MCP Server

1. Client extracts dependency coordinates from the prompt or project files
2. Client calls an MCP tool with Maven coordinates
3. Server fetches Maven metadata from Maven Central
4. Server classifies versions and optionally enriches with OSV.dev assessments and Context7 hints
5. Server returns structured JSON for the client to use in chat or automation
```

The POM-aware tools (`analyze_pom_dependencies`, `recommend_pom_upgrades`) take a different shape because they operate on a whole POM rather than a single coordinate:

```text
AI client -> analyze_pom_dependencies(pomXml, sideloadedPoms?) -> Maven Tools MCP Server

1. Client passes raw <project>...</project> XML (optionally a bundle of additional POMs)
2. Server checks XML size, nesting, DTD and model-bundle limits
3. Apache Maven Model Builder resolves parents and imported BOMs through an isolated
   ModelResolver backed by sideloaded POMs and the configured Maven repository
4. Maven builds the effective model: inheritance, interpolation, default profiles and
   dependency management. Imported BOM properties stay scoped to the BOM; direct
   management wins over imports, and the first applicable import wins between BOMs
5. Server classifies each declared dependency (EXPLICIT / MANAGED / EXPLICIT_OVERRIDE)
6. Server identifies directly editable root dependency-management and build/plugin declarations
7. Server surfaces multi-BOM conflicts and per-step warnings as raw data
8. Server returns structured JSON; reasoning about what to upgrade is the caller's job
```

`recommend_pom_upgrades` adds an opinion layer on top of the resolver output:

```text
AI client -> recommend_pom_upgrades(pomXml, mode?, sideloadedPoms?) -> Maven Tools MCP Server

1-8. Same resolver pass as above (warning-free, non-sideloaded results are cached for 1h)
9. For each user-controllable BOM (direct <parent> + root <dependencyManagement>
   imports), look up the latest stable on Maven Central; emit bom_bump or route a
   major to needsAttention. Transitively-imported BOMs are silently skipped —
   nothing for the caller to edit in their own POM.
10. For each direct root dependency-management declaration with a literal version or
   exact root-owned property, emit managed_decl_bump with edit metadata or route a
   major to needsAttention.
11. For each direct build or plugin-management dependency, emit plugin_dep_bump with
   its owner plugin and edit location, or route a major to needsAttention.
12. For each declared dep, classify: explicit_bump for available minor/patch
   upgrades, conflict / explicit_override / major_available to needsAttention
13. Return two lists — deterministicActions[] for mechanical agent application,
   needsAttention[] for human / LLM review (latestStable for major_available;
   latestOnCentral for conflicts and explicit overrides)
```

The POM resolver lives in `com.arvindand.mcp.maven.pom`. `MavenModelSession` adapts Apache Maven Model Builder to bounded, in-memory model sources; `EffectivePomResolver` classifies declarations and retains management provenance and editable locations. The original resolver design was inspired by [maxxq-org/maxxq-maven](https://github.com/maxxq-org/maxxq-maven); see `NOTICE` for attribution.

The tool analyzes declarations in the supplied POM, including dependencies from active-by-default profiles. It does not resolve a transitive dependency graph or run plugins. Maven settings, mirrors, POM-declared repositories, local parent paths and server environment/system properties are not imported. JDK, OS, property and file profile activations are not evaluated; warnings may therefore name a profile in a fetched parent or imported BOM, even when the input POM has no profiles.

### Caches and input limits

- Warning-free `resolve(pomXml)` results use a one-hour effective-POM cache with a 16 MiB weight budget. Sideloaded bundles bypass this result cache.
- Maven metadata and POM XML caches use 24-hour expiry and a 32 MiB weight budget per region. Complete OSV responses use six-hour expiry and a 32 MiB budget. Failed upstream calls and incomplete OSV responses are not cached.
- Cache weights estimate retained character data and impose a minimum entry weight; these are allocation controls, not measurements of JVM heap usage. Expiry is independent between result and upstream caches, so recomputation may reuse cached parent/BOM XML.
- Each POM is limited to 1,048,576 Java characters, 64 levels of XML nesting and 20,000 elements. Sideloaded bundles permit up to 64 documents and 4,194,304 characters; fetched models also have per-resolution count and cumulative-character budgets. DTDs are rejected, and property expansion is bounded.
- Repository and OSV responses are limited to 4 MiB of bytes, including streamed bodies. Redirects are rejected; configure the final endpoint.
- Coordinate batches permit up to 500 items, share ten concurrent operations, and have a 30-second total deadline including permit waits. Model resolution checks a 60-second budget between stages; an in-flight fetch still follows its network timeout and retry policy.

## Deployment Options

### Docker STDIO

Best for desktop MCP clients:

```bash
docker run -i --rm arvindand/maven-tools-mcp:latest
```

Optional with Context7 API key:

```bash
docker run -i --rm -e CONTEXT7_API_KEY arvindand/maven-tools-mcp:latest
```

### Docker HTTP

Best for sidecar workflows and streamable HTTP clients:

```bash
docker run --rm -p 127.0.0.1:8080:8080 arvindand/maven-tools-mcp:latest-http
```

### Native image

The published Docker variants contain native executables. They provide:

- lower memory footprint than a standard JVM process
- built from the same Spring Boot application
- good fit for tightly controlled environments

### JVM JAR

Useful when portability matters more than startup speed:

```bash
java -jar target/maven-tools-mcp-<version>.jar
```

## Network Requirements

The server may need outbound HTTPS to:

- `repo1.maven.org` for Maven metadata
- `api.osv.dev` for vulnerability data
- `mcp.context7.com` for optional documentation tools

For corporate networks:

- network routing and proxy requirements depend on the deployed Java/Docker environment; there is no dedicated MCP proxy setting
- custom CA certificates are supported via a custom image
- `:latest-noc7` is available when Context7 is not usable

## Operational Security Notes

- no credentials are required for the default Maven Central-only path
- default outbound endpoints use HTTPS; private-repository deployments choose their configured endpoint
- repository credentials are restricted to the configured origin and are not attached to OSV requests
- HTTP transport has no built-in client authentication; use localhost for local clients, or an authenticated TLS gateway for remote access
- the application is self-hostable and does not depend on a SaaS control plane
- vulnerability and license checks are enrichments, not hidden external state

## Configuration Notes

The main runtime configuration is under `src/main/resources/`.

Relevant configuration files include:

- `application.yaml`
- `application-docker.yaml`
- `application-http.yaml`
- `application-no-context7.yaml`

Key points:

- stdio and HTTP transports are intentionally separated by profile
- logging is kept minimal for stdio safety
- caches reduce repeated remote lookups

## Technical Snapshot

- Framework: Spring Boot 4.1.1; Spring AI 2.0.1
- Java: 25 (LTS)
- Protocol: MCP 2025-11-25
- Maven components: `maven-model-builder`, `maven-model`, `maven-artifact` and `maven-repository-metadata` 3.9.16; the full Maven Resolver dependency-graph stack is not used
- CVSS scoring: `us.springett:cvss-calculator` 1.5.1 for v2/v3/v4 vectors
- Local JVM container builds: Jib 3.5.2 with Eclipse Temurin 25; native containers use Spring Boot buildpacks
- Default transport for desktop images: stdio
- HTTP transport available as `:latest-http`
- Data source: Maven Central metadata
- Optional enrichments: OSV.dev and Context7

## Reference Links

- [Model Context Protocol](https://modelcontextprotocol.io/)
- [MCP specification](https://github.com/modelcontextprotocol/specification)
- [Spring AI MCP reference](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-overview.html)
- [Maven Central metadata reference](https://maven.apache.org/ref/3.9.16/maven-repository-metadata/)
- [Context7 MCP server](https://github.com/upstash/context7)

## Related Docs

- [`setup.md`](setup.md)
- [`tools.md`](tools.md)
- [`troubleshooting.md`](troubleshooting.md)
