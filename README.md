# Maven Tools MCP Server

[![Java](https://img.shields.io/badge/Java-24-orange.svg)](https://openjdk.java.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.3-green.svg)](https://spring.io/projects/spring-boot)
[![MCP Protocol](https://img.shields.io/badge/MCP-2024--11--05-blue.svg)](https://modelcontextprotocol.io/)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

A Model Context Protocol (MCP) server that supercharges AI assistants with real-time Maven Central intelligence. Get instant, accurate dependency information that's faster and more reliable than web searches.

## 🎯 Why This Matters

**Problem:** AI assistants get outdated or inaccurate Maven dependency info from web searches
**Solution:** Direct Maven Central API access with intelligent caching and bulk operations

## ⚡ Quick Demo

Ask your AI assistant:
- *"Check all dependencies in this pom.xml for latest versions"* (paste your pom.xml)
- *"What's the latest Spring Boot version and all available types?"*
- *"Which dependencies in my project need updates?"* (paste pom.xml)
- *"Are there any beta or RC versions I should consider?"*

**vs Web Search:**
- ✅ **100ms response** (cached) vs 3-5 seconds
- ✅ **Bulk operations** (20 deps at once) vs individual lookups  
- ✅ **Structured JSON** vs parsing web content
- ✅ **Always current** vs outdated search results

## Setup for Claude Desktop

**Step 1:** Locate your Claude Desktop configuration file
- **Windows:** `%APPDATA%\Claude\claude_desktop_config.json`
- **macOS:** `~/Library/Application Support/Claude/claude_desktop_config.json`
- **Linux:** `~/.config/Claude/claude_desktop_config.json`

**Step 2:** Add this configuration (using pre-built Docker image):

```json
{
  "mcpServers": {
    "maven-tools": {
      "command": "docker",
      "args": [
        "run", "-i", "--rm", "-e", "SPRING_PROFILES_ACTIVE=docker",
        "arvindand/maven-tools-mcp:latest"
      ]
    }
  }
}
```

**Step 3:** Restart Claude Desktop

**Prerequisites:** Docker installed and running

**Note:** The Docker image supports both AMD64 (Intel/AMD) and ARM64 (Apple Silicon) architectures. Docker automatically selects the correct version for your platform.

## Setup for VS Code with GitHub Copilot

**Option 1: Workspace Configuration** - Create `.vscode/mcp.json`:

```json
{
  "servers": {
    "maven-tools": {
      "type": "stdio",
      "command": "docker",
      "args": ["run", "-i", "--rm", "-e", "SPRING_PROFILES_ACTIVE=docker", "arvindand/maven-tools-mcp:latest"]
    }
  }
}
```

**Option 2: User Settings** - Add to your VS Code settings:

```json
{
  "mcp": {
    "servers": {
      "maven-tools": {
        "type": "stdio", 
        "command": "docker",
        "args": ["run", "-i", "--rm", "-e", "SPRING_PROFILES_ACTIVE=docker", "arvindand/maven-tools-mcp:latest"]
      }
    }
  }
}
```

**Usage:** Open Chat view (Ctrl+Alt+I), select Agent mode, then use the Tools button to enable Maven tools.

## What it does

- Get latest or stable versions of Maven dependencies
- Check if specific versions exist
- Bulk version checking for multiple dependencies
- Compare versions and get update recommendations

## Available Tools

| Tool | Purpose | Example |
|------|---------|---------|
| `maven_get_latest` | Get newest version by type | Latest Spring Boot with all release types |
| `maven_get_stable` | Get latest stable only | Production-ready Jackson version |
| `maven_check_exists` | Verify specific version | Does Spring Boot 3.5.0 exist? |
| `maven_bulk_check_latest` | Check multiple at once | Update status for entire project |
| `maven_bulk_check_stable` | Stable versions for many | Production update candidates |
| `maven_compare_versions` | Upgrade analysis | Compare current vs available versions |

### `maven_get_latest`

Retrieves the latest available version of a Maven dependency from Maven Central for each version type (stable, rc, beta, alpha, milestone).

**Parameters:**
- `dependency` (string, required): Maven coordinate in format `groupId:artifactId`

**Example:**
```json
{
  "dependency": "org.springframework:spring-core"
}
```

**Response:**
```json
{
  "dependency": "org.springframework:spring-core",
  "latest_stable": { "version": "6.2.7", "type": "stable" },
  "latest_milestone": { "version": "7.0.0-M5", "type": "milestone" },
  "total_versions": 100
}
```

### `maven_check_exists`

Checks if a specific version of a Maven dependency exists in Maven Central with version type information.

**Parameters:**
- `dependency` (string, required): Maven coordinate in format `groupId:artifactId`
- `version` (string, required): Version to check

**Example:**
```json
{
  "dependency": "org.springframework:spring-core",
  "version": "6.0.0"
}
```

**Response:**
```json
{
  "exists": true,
  "version": "6.0.0",
  "type": "stable"
}
```

### `maven_get_stable`

Retrieves the latest stable version of a Maven dependency (excludes RCs, alphas, betas, milestones).

**Parameters:**
- `dependency` (string, required): Maven coordinate in format `groupId:artifactId`

**Example:**
```json
{
  "dependency": "com.fasterxml.jackson.core:jackson-core"
}
```

**Response:**
```json
{
  "version": "2.19.0",
  "type": "stable",
  "total_versions": 100,
  "stable_versions": 82
}
```

### `maven_bulk_check_latest`

Checks latest versions for multiple dependencies in a single call.

**Parameters:**
- `dependencies` (string, required): Comma-separated list of Maven coordinates

**Example:**
```json
{
  "dependencies": "org.springframework:spring-core,com.fasterxml.jackson.core:jackson-core,junit:junit"
}
```

### `maven_bulk_check_stable`

Checks latest stable versions for multiple dependencies.

**Parameters:**
- `dependencies` (string, required): Comma-separated list of Maven coordinates

### `maven_compare_versions`

Compares current dependencies with their latest versions and provides update recommendations.

**Parameters:**
- `currentDependencies` (string, required): Comma-separated list of current dependencies with versions

**Example:**
```json
{
  "currentDependencies": "org.springframework:spring-core:6.0.0,junit:junit:4.12"
}
```

**Response:**
```json
{
  "comparison_date": "2025-06-07T22:38:47Z",
  "dependencies": [
    {
      "dependency": "org.springframework:spring-core:6.0.0",
      "current_version": "6.0.0",
      "latest_version": "7.0.0-M5",
      "latest_type": "milestone",
      "update_type": "major",
      "update_available": true,
      "status": "success",
      "error": null
    }
  ],
  "update_summary": {
    "major_updates": 1,
    "minor_updates": 0,
    "patch_updates": 0,
    "no_updates": 0
  }
}
```

## Usage Examples

### Getting Started Examples

**Simple Questions:**
- "What's the latest Spring Boot version?"
- "Show me all version types for Jackson"
- "Is Spring Boot 3.5.0 available?"

**Paste Your pom.xml:**
- "Check all dependencies in this pom.xml for latest versions" 
- "Which dependencies need updates?" (paste pom.xml)
- "Are there any pre-release versions I should consider?"

**Follow-up Intelligence:**
- "What type of updates would these be - major, minor, or patch?"
- "Should I use the RC version of Spring Boot?"
- "Compare my current versions with what's available"

## 🚀 Real-World Use Cases

### Project Dependency Audit
**Action:** Paste your pom.xml and ask: *"Which dependencies are outdated and what updates are available?"*  
**Result:** Complete project analysis in 2 seconds vs 10+ minutes manually

### Security Response  
**Action:** Paste affected pom.xml: *"We got a security alert - show me latest versions for all dependencies"*  
**Result:** Instant security patch identification with comprehensive analysis

### New Project Setup
**Action:** *"What are the latest stable versions for Spring Boot, Spring Security, and Jackson?"*  
**Result:** Current tech stack recommendations with version compatibility

### Migration Planning
**Action:** Paste current pom.xml: *"I'm upgrading to Spring Boot 3.x - what's the migration path?"*  
**Result:** Step-by-step upgrade analysis with compatibility matrix

## 🆚 Why Not Just Web Search?

| Scenario | Web Search | Maven Tools MCP |
|----------|------------|-----------------|
| Single dependency lookup | 3-5 seconds | <100ms (cached) |
| 20 dependencies | 60+ seconds | <500ms |
| Data accuracy | Variable/outdated | 100% current |
| Bulk operations | Manual, error-prone | Native support |
| Version classification | Manual parsing | Automatic (stable/RC/beta) |
| Semantic analysis | Not available | Major/minor/patch detection |

## Features

- Version lookup (latest, stable, or specific versions)
- Version type classification (stable, RC, beta, alpha, milestone)
- Bulk operations for multiple dependencies
- Version comparison tools
- Caching for better performance
- Works with MCP-compatible AI assistants

> **Note:** Snapshot versions are not supported. This is because the Maven Central API does not index or provide access to snapshot artifacts. Only released versions (stable, rc, beta, alpha, milestone) are available.

## Performance Notes

- **Cache effectiveness:** ~90% of repeated requests served from cache
- **Recommended batch sizes:** 10-20 dependencies for bulk operations
- **First requests:** Build cache (normal), subsequent requests much faster
- **Cache duration:** 24 hours

## 🤔 Frequently Asked Questions

**Q: How is this different from Dependabot/Renovate?**  
A: Those tools create automated PRs. This gives you instant, interactive dependency intelligence through your AI assistant for decision-making and planning.

**Q: Why not just search Maven Central directly?**  
A: This provides structured, cached responses optimized for AI consumption with intelligent version classification and bulk operations.

**Q: Can this replace my IDE's dependency management?**  
A: No, it complements your IDE by providing instant dependency intelligence during conversations with AI assistants.

**Q: Does it work with private Maven repositories?**  
A: Currently only Maven Central.

**Q: What about Gradle dependencies?**  
A: Maven Central hosts both Maven and Gradle dependencies, so it works for Gradle projects too (using Maven coordinates).

## Alternative Setup Methods

### Using Docker Compose

**Alternative Claude Desktop configuration** (if you prefer compose):

Download `docker-compose.yml` and configure:

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

**For development/testing only:**
```bash
docker compose up -d  # Runs server in background for testing
```

### Build from Source (for contributors)

**Prerequisites:**
- Java 24
- Maven 3.9+

```bash
# Clone the repository
git clone https://github.com/arvindand/maven-tools-mcp.git
cd maven-tools-mcp

# Quick build (CI-friendly - unit tests only)
./mvnw clean package -Pci

# Full build with all tests (requires network access)
./mvnw clean package -Pfull

# Run the JAR
java -jar target/maven-tools-mcp-0.1.3-SNAPSHOT.jar
```

**Claude Desktop configuration for JAR:**
```json
{
  "mcpServers": {
    "maven-tools": {
      "command": "java",
      "args": [
        "-jar",
        "/absolute/path/to/maven-tools-mcp-0.1.3-SNAPSHOT.jar"
      ]
    }
  }
}
```

### Build Scripts

For easier builds, use the provided scripts in the `build/` folder:

```bash
# Linux/macOS - Complete build helper
./build/build.sh

# Windows - Complete build helper
.\build\build-windows.cmd
```

## Enterprise & Custom Clients

This server implements MCP Protocol 2024-11-05 with stdio transport, making it compatible with any MCP-compliant client.

## Configuration

The server can be configured via `application.yaml`:

```yaml
# Cache configuration
spring:
  cache:
    type: caffeine
    caffeine:
      spec: maximumSize=2000,expireAfterWrite=3600s

# Maven Central API settings
maven:
  central:
    base-url: https://search.maven.org/solrsearch/select
    timeout: 10s
    max-results: 100

# Logging (minimal for MCP stdio transport)
logging:
  level:
    root: ERROR
```

## Technical Details

- **Framework**: Spring Boot 3.5.0 with [Spring AI MCP](https://docs.spring.io/spring-ai/reference/api/mcp.html)
- **MCP Protocol**: 2024-11-05
- **Java Version**: 24
- **Transport**: stdio
- **HTTP Client**: Spring Web RestClient
- **Cache**: Caffeine (24-hour TTL, 2000 entries max)
- **API**: Maven Central Search API

## References & Resources

### Model Context Protocol (MCP)
- **Official Website**: [modelcontextprotocol.io](https://modelcontextprotocol.io/)
- **GitHub Repository**: [modelcontextprotocol/specification](https://github.com/modelcontextprotocol/specification)
- **Protocol Documentation**: [MCP Specification](https://spec.modelcontextprotocol.io/)

### Spring AI MCP
- **Documentation**: [Spring AI MCP Reference](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-overview.html)
- **GitHub**: [spring-projects/spring-ai](https://github.com/spring-projects/spring-ai)

### Maven Central API
- **Search API**: [search.maven.org](https://search.maven.org/)
- **REST API Guide**: [Using the REST API](https://central.sonatype.org/search/rest-api-guide/)

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Author

Arvind Menon

- GitHub: [@arvindand](https://github.com/arvindand)
- Version: 0.1.3-SNAPSHOT