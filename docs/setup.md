# Setup

This guide covers the supported ways to run Maven Tools MCP and connect it to MCP-capable clients.

For a shorter quick-start, see the main [`README.md`](../README.md).

## Recommended Image Variants

| Tag | Transport | Context7 | Best For |
|-----|-----------|----------|----------|
| `:latest` | STDIO | Yes | Default desktop MCP usage |
| `:latest-noc7` | STDIO | No | Networks where Context7 is blocked or unwanted |
| `:latest-http` | HTTP | Yes | Streamable HTTP clients and sidecar workflows |

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
docker run -p 8080:8080 arvindand/maven-tools-mcp:latest-http
```

Health endpoints:

- `http://127.0.0.1:8080/actuator/health/liveness`
- `http://127.0.0.1:8080/actuator/health/readiness`

Optional with Context7 API key:

```bash
docker run -p 8080:8080 -e CONTEXT7_API_KEY arvindand/maven-tools-mcp:latest-http
```

## Native Binary Or JAR

For environments where Docker is restricted, you can run the packaged application directly.

## Build From Source

**Prerequisites:**

- Java 24
- Maven 3.9+

```bash
git clone https://github.com/arvindand/maven-tools-mcp.git
cd maven-tools-mcp
./mvnw clean package -Pci
```

For a fuller test build:

```bash
./mvnw clean package -Pfull
```

Run the JAR:

```bash
java -jar target/maven-tools-mcp-<version>.jar
```

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
        "run", "--rm", "maven-tools-mcp"
      ]
    }
  }
}
```

For development-only background usage:

```bash
docker compose up -d
```

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
    timeout: 10s
    max-results: 100

logging:
  level:
    root: ERROR
```

Context7-specific settings can also be configured through Spring properties:

```yaml
context7:
  enabled: true
  api-key: ${CONTEXT7_API_KEY:}
```

If `context7.enabled` is set to `false`, the server skips the raw Context7 tools and the extra documentation-oriented guidance tied to Context7.

## Related Docs

- [`tools.md`](tools.md)
- [`troubleshooting.md`](troubleshooting.md)
- [`architecture.md`](architecture.md)
- [`../CORPORATE-CERTIFICATES.md`](../CORPORATE-CERTIFICATES.md)
