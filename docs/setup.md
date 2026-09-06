# Setup

This guide covers the supported ways to run Maven Tools MCP and connect it to MCP-capable clients.

For a shorter quick-start, see the main [`README.md`](../README.md).

## Recommended Image Variants

| Tag | Transport | Context7 | Best For |
|-----|-----------|----------|----------|
| `:latest` | STDIO | Yes | Default desktop MCP usage |
| `:latest-noc7` | STDIO | No | Networks where Context7 is blocked or unwanted |
| `:latest-http` | HTTP | Yes | Streamable HTTP clients and sidecar workflows |

Version-pinned equivalents are `:3.2.2`, `:3.2.2-noc7` and `:3.2.2-http`; all three support Linux AMD64 and ARM64. The JVM `-jvm` image is built locally through the helpers and is not published by the release workflow.

`CONTEXT7_API_KEY` is optional. You can start without it. Pass it only if your environment requires Context7 authentication or you want to avoid anonymous limits.

## Claude Desktop

**Prerequisite:** Docker installed and running.

Add this to your Claude Desktop configuration file:

- macOS: `~/Library/Application Support/Claude/claude_desktop_config.json`
- Windows: `%APPDATA%\Claude\claude_desktop_config.json`
- Linux: `~/.config/Claude/claude_desktop_config.json`

```json
{
  "mcpServers": {
    "maven-tools": {
      "command": "docker",
      "args": [
        "run", "-i", "--rm",
        "arvindand/maven-tools-mcp:latest"
      ]
    }
  }
}
```

If you need to pass a Context7 API key, add `"-e", "CONTEXT7_API_KEY"` before the image name and export the variable before launching Claude Desktop.

If your network blocks Context7, switch to `arvindand/maven-tools-mcp:latest-noc7`.

## VS Code + GitHub Copilot

Create `.vscode/mcp.json` in your workspace:

```json
{
  "servers": {
    "maven-tools": {
      "type": "stdio",
      "command": "docker",
      "args": ["run", "-i", "--rm", "arvindand/maven-tools-mcp:latest"]
    }
  }
}
```

If you need a Context7 API key, add `"-e", "CONTEXT7_API_KEY"` before the image name.

In Copilot Chat, enable Agent mode and make sure the server is enabled in the Tools list.

## HTTP Transport

For HTTP-based MCP clients or sidecar workflows, use the `-http` image:

```bash
docker run --rm -p 127.0.0.1:8080:8080 arvindand/maven-tools-mcp:latest-http
```

Connect the MCP client to `http://127.0.0.1:8080/mcp`. This is Streamable HTTP; use an MCP client rather than a browser GET to exercise the protocol. The server does not authenticate incoming HTTP clients. For remote use, place it behind an authenticated TLS gateway and restrict direct network access.

Health endpoints:

- `http://127.0.0.1:8080/actuator/health/liveness`
- `http://127.0.0.1:8080/actuator/health/readiness`

Optional with Context7 API key:

```bash
docker run --rm -p 127.0.0.1:8080:8080 -e CONTEXT7_API_KEY arvindand/maven-tools-mcp:latest-http
```

## Build From Source

**Prerequisites:**

- Java 25
- The checked-in Maven wrapper (`./mvnw`, or `mvnw.cmd` on Windows); a separate Maven installation is not required

```bash
git clone https://github.com/arvindand/maven-tools-mcp.git
cd maven-tools-mcp
./mvnw clean package -Pci
```

For a fuller test build:

```bash
./mvnw clean verify -Pfull
```

The release workflow publishes native Docker images, not standalone native binaries or downloadable JAR assets. For environments without Docker, build the JAR locally. Replace `<version>` below with the project version (`3.2.2` for this release).

Run the JAR:

```bash
java -jar target/maven-tools-mcp-<version>.jar
# Disable Context7 entirely
java -jar target/maven-tools-mcp-<version>.jar --spring.profiles.active=no-context7
# Streamable HTTP on localhost
java -jar target/maven-tools-mcp-<version>.jar --spring.profiles.active=http --server.address=127.0.0.1
```

The JAR speaks STDIO by default, so it works with the Claude Desktop config below. The HTTP command above exposes the MCP endpoint at `http://127.0.0.1:8080/mcp`.

Example Claude Desktop config for the JAR:

```json
{
  "mcpServers": {
    "maven-tools": {
      "command": "java",
      "args": ["-jar", "/absolute/path/to/target/maven-tools-mcp-<version>.jar"]
    }
  }
}
```

## Docker Compose

If you prefer Docker Compose for local testing:

```json
{
  "mcpServers": {
    "maven-tools": {
      "command": "docker",
      "args": [
        "compose", "-f", "/absolute/path/to/docker-compose.yml",
        "run", "--rm", "-T", "maven-tools-mcp"
      ]
    }
  }
}
```

The checked-in Compose service uses STDIO. Keep it attached to the MCP client; `docker compose up -d` does not create an HTTP endpoint. For a background HTTP service, use the `latest-http` image as described above.

## Build Helpers

The `build/` directory includes helper scripts for local packaging.

### Linux / macOS

```bash
cd build
./build.sh
./build-docker.sh
```

### Windows

```cmd
cd build
build.cmd
build-docker.cmd
```

## Configuration Notes

Runtime configuration comes from the Spring configuration files under `src/main/resources/`.

Common settings include:

```yaml
spring:
  cache:
    type: caffeine

maven:
  central:
    repository-base-url: https://repo1.maven.org/maven2
    timeout: 8s

logging:
  level:
    root: ERROR
```

### Private Repository Authentication

You can point the server at any Maven-compatible repository (Nexus, Artifactory, GitHub Packages) by overriding the base URL and providing authentication credentials via environment variables:

**Bearer auth** (for repositories that accept bearer tokens):

```bash
docker run -i --rm \
  -e MAVEN_CENTRAL_REPOSITORY_BASE_URL=https://artifactory.example.com/artifactory/maven-virtual \
  -e MAVEN_CENTRAL_AUTH_TYPE=bearer \
  -e MAVEN_CENTRAL_AUTH_TOKEN=your-token \
  arvindand/maven-tools-mcp:latest
```

**Basic auth** (Nexus, Artifactory):

```bash
docker run -i --rm \
  -e MAVEN_CENTRAL_REPOSITORY_BASE_URL=https://nexus.internal.corp/repository/maven/ \
  -e MAVEN_CENTRAL_AUTH_TYPE=basic \
  -e MAVEN_CENTRAL_AUTH_USERNAME=admin \
  -e MAVEN_CENTRAL_AUTH_PASSWORD=secret \
  arvindand/maven-tools-mcp:latest
```

The same settings work for the JAR, local JVM image and native images. Version lookups need standard `maven-metadata.xml`; POM analysis also needs accessible parent/BOM POM files. Configure one repository or repository-manager aggregate that can serve the required artifacts.

Credentials stay on the configured origin and are not sent to OSV. Redirects are rejected, so use the final HTTPS repository URL. POM-declared repositories, Maven `settings.xml` mirrors and local parent paths are not used. See [architecture limits](architecture.md#caches-and-input-limits) for response and input bounds.

Context7-specific settings can also be configured through Spring properties:

```yaml
context7:
  enabled: true
  api-key: ${CONTEXT7_API_KEY:}
```

For JVM/JAR runs, use the `no-context7` profile, or set both `CONTEXT7_ENABLED=false` and `SPRING_AI_MCP_CLIENT_ENABLED=false`. The first controls hints/tool exposure; the second disables the outbound MCP client. For native runs, use the separately compiled `-noc7` image.

## Related Docs

- [`tools.md`](tools.md)
- [`troubleshooting.md`](troubleshooting.md)
- [`architecture.md`](architecture.md)
- [`../CORPORATE-CERTIFICATES.md`](../CORPORATE-CERTIFICATES.md)
