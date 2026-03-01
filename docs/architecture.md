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

- Framework: Spring Boot 3.5.11
- Java: 24
- Protocol: MCP 2025-06-18
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
