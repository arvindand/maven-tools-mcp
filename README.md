# Maven Tools MCP Server

[![Java](https://img.shields.io/badge/Java-24-orange.svg)](https://openjdk.java.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.0-green.svg)](https://spring.io/projects/spring-boot)
[![MCP Protocol](https://img.shields.io/badge/MCP-2024--11--05-blue.svg)](https://modelcontextprotocol.io/)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

A Model Context Protocol (MCP) server for Maven dependency management. Works with Claude Desktop, GitHub Copilot, and other MCP-compatible AI tools to query Maven Central.

## What it does

- Get latest or stable versions of Maven dependencies
- Check if specific versions exist
- Bulk version checking for multiple dependencies
- Compare versions and get update recommendations

## Features

- Version lookup (latest, stable, or specific versions)
- Version type classification (stable, RC, beta, alpha, milestone)
- Bulk operations for multiple dependencies
- Version comparison tools
- Caching for better performance
- Works with MCP-compatible AI assistants

> **Note:** Snapshot versions are not supported. This is because the Maven Central API does not index or provide access to snapshot artifacts. Only released versions (stable, rc, beta, alpha, milestone) are available.

## Tools Available

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

- Each field is only present if a version of that type exists for the dependency.
- This endpoint is useful for seeing all available release types, not just the latest stable.

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

**Response:**

```json
[
  {
    "dependency": "org.springframework:spring-core",
    "version": "7.0.0-M5",
    "type": "milestone",
    "status": "found",
    "error": null,
    "total_versions": 100,
    "stable_versions": 95
  },
  {
    "dependency": "com.fasterxml.jackson.core:jackson-core",
    "version": "2.19.0",
    "type": "stable",
    "status": "found",
    "error": null,
    "total_versions": 100,
    "stable_versions": 82
  },
  {
    "dependency": "junit:junit",
    "version": "4.13.2",
    "type": "stable",
    "status": "found",
    "error": null,
    "total_versions": 32,
    "stable_versions": 32
  }
]
```

### `maven_bulk_check_stable`

Checks latest stable versions for multiple dependencies.

**Parameters:**

- `dependencies` (string, required): Comma-separated list of Maven coordinates

**Example:**

```json
{
  "dependencies": "org.springframework:spring-core,com.fasterxml.jackson.core:jackson-core"
}
```

**Response:**

```json
[
  {
    "dependency": "org.springframework:spring-core",
    "version": "6.2.7",
    "type": "stable",
    "status": "found",
    "error": null,
    "total_versions": 100,
    "stable_versions": 95
  },
  {
    "dependency": "com.fasterxml.jackson.core:jackson-core",
    "version": "2.19.0",
    "type": "stable",
    "status": "found",
    "error": null,
    "total_versions": 100,
    "stable_versions": 82
  }
]
```

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
    },
    {
      "dependency": "junit:junit:4.12",
      "current_version": "4.12",
      "latest_version": "4.13.2",
      "latest_type": "stable",
      "update_type": "minor",
      "update_available": true,
      "status": "success",
      "error": null
    }
  ],
  "update_summary": {
    "major_updates": 1,
    "minor_updates": 1,
    "patch_updates": 0,
    "no_updates": 0
  }
}
```

## Quick Start

### Prerequisites

- Java 24
- Maven 3.9+
- Internet connection for Maven Central API access

### Build & Deploy

```bash
# Clone the repository
git clone https://github.com/arvindand/maven-tools-mcp.git
cd maven-tools-mcp

# Quick build (CI-friendly - unit tests only)
mvn clean package -Pci

# Full build with all tests (requires network access)
mvn clean package -Pfull

# Build without any tests (fastest)
mvn clean package -DskipTests

# Run only unit tests (no Maven Central API calls)
mvn test

# Run only integration tests (requires network access)
mvn verify -Pintegration

# Verify the build
java -jar target/maven-tools-mcp-0.1.2-SNAPSHOT.jar
```

**Output Location:** `target/maven-tools-mcp-0.1.2-SNAPSHOT.jar`

## AI Assistant Integration

### Claude Desktop Setup

**Step 1:** Locate Configuration File

- **Windows:** `%APPDATA%\Claude\claude_desktop_config.json`
- **macOS:** `~/Library/Application Support/Claude/claude_desktop_config.json`
- **Linux:** `~/.config/Claude/claude_desktop_config.json`

**Step 2:** Add Server Configuration

```json
{
  "mcpServers": {
    "maven-tools": {
      "command": "java",      "args": [
        "-jar",
        "/absolute/path/to/maven-tools-mcp-0.1.2-SNAPSHOT.jar"
      ]
    }
  }
}
```

**Windows Example:**

```json
{
  "mcpServers": {
    "maven-tools": {
      "command": "java",      "args": [
        "-jar",
        "C:\\Users\\YourName\\Documents\\Github\\maven-tools-mcp\\target\\maven-tools-mcp-0.1.2-SNAPSHOT.jar"
      ]
    }
  }
}
```

**Step 3:** Restart Claude Desktop

**Step 4:** Test Integration

- *"What's the latest version of Spring Boot?"*
- *"Does version 3.2.0 exist for org.springframework.boot:spring-boot-starter?"*
- *"Check if Jackson 2.15.0 is available"*

### GitHub Copilot Integration

GitHub Copilot supports MCP servers through IDE extensions. Configure using the same JAR file path in your IDE's MCP settings.

### Enterprise & Custom Clients

This server implements MCP Protocol 2024-11-05 with stdio transport, making it compatible with any MCP-compliant client.

## Usage Examples

Once integrated, you can ask your AI assistant:

### Getting Started Examples

**Beginner:**

- "What's the latest version of Jackson?"
- "Is Spring Boot 3.5.0 available?"
- "Find the current stable version of JUnit"

**Intermediate:**

- "Compare my current Spring dependencies with latest stable versions"
- "What's the latest version of each: Spring Boot, Jackson, Hibernate?"
- "Check if org.springframework:spring-core:6.0.0 should be updated"

**Advanced:**

- "Analyze these dependencies for updates: Spring Boot 3.1.0, Jackson 2.15.0, JUnit 4.13.2"
- "Show me version comparison with update recommendations for my project dependencies"

### Common Workflow Examples

**Updating a Spring Boot project:**

- "What's the latest Spring Boot version and compatible Spring Framework version?"
- "Help me upgrade from Spring Boot 3.1.0 to the latest stable version"
- "I'm using Spring Boot 3.2.0 - what's the latest version I can safely upgrade to?"

**Checking project health:**

- "Compare these current versions with latest: Spring Boot 3.1.5, Jackson 2.15.2, JUnit 5.9.3"
- "What major/minor/patch updates are available for my dependencies?"
- "Show me update recommendations for my current dependency versions"

**Release planning:**

- "Show me all dependencies that have major version updates available"
- "Which of my dependencies have only patch updates available?"
- "Compare Spring Boot 3.2.0 vs 3.5.0 - what's the update impact?"

**Dependency research:**

- "What types of releases are available for Spring Boot? (stable, RC, beta, etc.)"
- "Should I use Jackson 2.18.2 or wait for 2.19.0?"
- "What's the difference between Spring Framework 6.1.4 and 6.1.4.RELEASE?"

### Troubleshooting Examples

**Version conflicts:**

- "Why can't I find Spring Boot 3.5.1?"
- "Is there a Spring Boot 3.6.0 version available yet?"
- "What's the difference between Spring Boot 3.5.0 and 3.5.0-RC1?"

**Version validation:**

- "Does Spring Boot 3.5.0 exist?"
- "Is JUnit 4.13.2 available on Maven Central?"
- "Check if these versions exist: org.springframework:spring-core:6.1.14, junit:junit:4.13.2"

### Bulk Operations Examples

**Multiple dependency checks:**

- "What are the latest versions of: Spring Boot, Jackson Core, JUnit?"
- "Check latest stable versions for: org.springframework:spring-core, com.fasterxml.jackson.core:jackson-core"
- "Find the newest versions of these dependencies: org.springframework.boot:spring-boot-starter-parent, org.springframework.ai:spring-ai-bom"

**Version comparisons:**

- "Compare my current versions with latest: org.springframework.boot:spring-boot-starter-parent:3.2.0, org.springframework.ai:spring-ai-bom:1.0.0"
- "I'm using Spring Boot 3.1.5, Jackson 2.15.2, JUnit 5.9.3 - what updates are available?"
- "Show update recommendations for: org.springframework:spring-core:6.0.0, junit:junit:4.12"

### Working with Project Files

**Pom.xml analysis:**

- "Here's my pom.xml [paste content]. Check which dependencies have updates available."
- "Analyze my project dependencies and show version recommendations: [paste pom.xml]"
- "Parse this pom.xml and tell me which dependencies are outdated: [paste content]"

**Dependency list analysis:**

- "Check these dependencies from my project: [paste dependency list]"
- "My project uses these versions: [list dependencies]. What updates are available?"

> **Note on Advanced Use Cases:** This MCP server focuses specifically on Maven Central version information and doesn't provide compatibility analysis, security advisories, or migration guidance between dependency versions. However, intelligent MCP clients like Claude may be able to bridge these gaps by combining the version data from this server with web search capabilities or other knowledge sources to provide more comprehensive dependency management advice.

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

### Running Tests

```bash
# Run all tests
mvn test

# Run specific test
mvn test -Dtest=MavenCentralServiceUnitTest

# Integration test with real Maven Central API
mvn test -Dtest=MavenMcpServerIntegrationTest
```

## References & Resources

### Model Context Protocol (MCP)

- **Official Website**: [modelcontextprotocol.io](https://modelcontextprotocol.io/)
- **GitHub Repository**: [modelcontextprotocol/specification](https://github.com/modelcontextprotocol/specification)
- **Protocol Documentation**: [MCP Specification](https://spec.modelcontextprotocol.io/)
- **Getting Started Guide**: [MCP Quickstart](https://modelcontextprotocol.io/quickstart)

### Spring AI MCP

- **Documentation**: [Spring AI MCP Reference](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-overview.html)
- **GitHub**: [spring-projects/spring-ai](https://github.com/spring-projects/spring-ai)
- **MCP Server Starter**: [spring-ai-starter-mcp-server](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html)

### Maven Central API

- **Search API**: [search.maven.org](https://search.maven.org/)
- **REST API Guide**: [Using the REST API](https://central.sonatype.org/search/rest-api-guide/)

### AI Assistants & MCP Support

- **Claude Desktop**: [Download Claude](https://claude.ai/download)
- **GitHub Copilot**: [MCP Integration Guide](https://docs.github.com/en/copilot)

## Technical Details

- **Framework**: Spring Boot 3.5.0 with [Spring AI MCP](https://docs.spring.io/spring-ai/reference/api/mcp.html)
- **MCP Protocol**: 2024-11-05
- **Java Version**: 24
- **Transport**: stdio
- **HTTP Client**: Spring Web RestClient
- **Cache**: Caffeine (1-hour TTL, 2000 entries max)
- **API**: Maven Central Search API

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Author

Arvind Menon

- GitHub: [@arvindand](https://github.com/arvindand)
- Version: 0.1.0
