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
- if OSV.dev is slow or unavailable, results can return without CVE enrichment
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
4. Server classifies versions and optionally enriches with OSV.dev and Context7 guidance
5. Server returns structured JSON for the client to use in chat or automation
```

The POM-aware tools (`analyze_pom_dependencies`, `recommend_pom_upgrades`) take a different shape because they operate on a whole POM rather than a single coordinate:

```text
AI client -> analyze_pom_dependencies(pomXml, sideloadedPoms?) -> Maven Tools MCP Server

1. Client passes raw <project>...</project> XML (optionally a bundle of additional POMs)
2. Server parses the POM via maven-model (data classes only, no maven-model-builder)
3. Server walks the parent chain via MavenCentralPomFetcher, fetching each parent POM
4. Server merges <properties>, <dependencyManagement>, and <scope>import</scope> BOMs
   (project.* placeholders scoped per-POM so an imported BOM's ${project.version}
   resolves to that BOM's version, not the importer's)
5. Server classifies each declared dependency (EXPLICIT / MANAGED / EXPLICIT_OVERRIDE)
6. Server identifies directly editable root dependency-management and build/plugin declarations
7. Server surfaces multi-BOM conflicts and per-step warnings as raw data
8. Server returns structured JSON; reasoning about what to upgrade is the caller's job
```

`recommend_pom_upgrades` adds an opinion layer on top of the resolver output:

```text
AI client -> recommend_pom_upgrades(pomXml, mode?, sideloadedPoms?) -> Maven Tools MCP Server

1-8. Same resolver pass as above (result is cached by raw pomXml for 1h)
9. For each user-controllable BOM (direct <parent> + root <dependencyManagement>
   imports), look up the latest stable on Maven Central; emit bom_bump or route a
   major to needs_attention. Transitively-imported BOMs are silently skipped —
   nothing for the caller to edit in their own POM.
10. For each direct root dependency-management declaration with a literal version or
   exact root-owned property, emit managed_decl_bump with edit metadata or route a
   major to needs_attention.
11. For each direct build or plugin-management dependency, emit plugin_dep_bump with
   its owner plugin and edit location, or route a major to needs_attention.
12. For each declared dep, classify: explicit_bump for available minor/patch
   upgrades, conflict / explicit_override / major_available to needs_attention
13. Return two lists — deterministic_actions[] for mechanical agent application,
   needs_attention[] (each entry carries latestOnCentral) for human / LLM review
```

The POM resolver lives in `com.arvindand.mcp.maven.pom`. Its design notes are in the package-info; the algorithm shape (parent walk → properties → BOM import → depMgmt merge) follows the MIT-licensed [maxxq-org/maxxq-maven](https://github.com/maxxq-org/maxxq-maven), scoped here to declared-dep resolution only (no transitive walking, no scope-downgrade rules). Resolved results are cached by raw `pomXml` content in `maven-effective-pom` (1h TTL) so a follow-up call from the same client skips the parent / DM walk including XML reparse.

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
docker run -p 8080:8080 arvindand/maven-tools-mcp:latest-http
```

### Native image

Useful when you want faster startup or a containerless deployment:

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

- proxy configuration is supported
- custom CA certificates are supported via a custom image
- `:latest-noc7` is available when Context7 is not usable

## Operational Security Notes

- no credentials are required for the default Maven Central-only path
- outbound calls use HTTPS
- the application is self-hostable and does not depend on a SaaS control plane
- vulnerability and license checks are enrichments, not hidden external state

## Configuration Notes

The main runtime configuration is under `src/main/resources/`.

Relevant configuration files include:

- `application.yaml`
- `application-docker.yaml`
- `application-http.yaml`

Key points:

- stdio and HTTP transports are intentionally separated by profile
- logging is kept minimal for stdio safety
- caches reduce repeated remote lookups

## Technical Snapshot

- Framework: Spring Boot 4.1.0
- Java: 25 (LTS)
- Protocol: MCP 2025-11-25
- POM parser: `org.apache.maven:maven-model:3.9.16` (data classes + Xpp3 reader only; no maven-model-builder, no maven-resolver)
- Default transport for desktop images: stdio
- HTTP transport available as `:latest-http`
- Data source: Maven Central metadata
- Optional enrichments: OSV.dev and Context7

## Reference Links

- [Model Context Protocol](https://modelcontextprotocol.io/)
- [MCP specification](https://github.com/modelcontextprotocol/specification)
- [Spring AI MCP reference](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-overview.html)
- [Maven Central metadata reference](https://maven.apache.org/ref/3.9.6/maven-repository-metadata/)
- [Context7 MCP server](https://github.com/upstash/context7)

## Related Docs

- [`setup.md`](setup.md)
- [`tools.md`](tools.md)
- [`troubleshooting.md`](troubleshooting.md)
