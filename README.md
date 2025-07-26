# Maven Tools MCP Server

[![Java](https://img.shields.io/badge/Java-24-orange.svg)](https://openjdk.java.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.3-green.svg)](https://spring.io/projects/spring-boot)
[![MCP Protocol](https://img.shields.io/badge/MCP-2024--11--05-blue.svg)](https://modelcontextprotocol.io/)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![GitHub release (latest by date)](https://img.shields.io/github/v/release/arvindand/maven-tools-mcp)](https://github.com/arvindand/maven-tools-mcp/releases)
[![Docker](https://img.shields.io/badge/Docker-Multi--Arch-blue.svg)](https://hub.docker.com/r/arvindand/maven-tools-mcp)
[![GitHub stars](https://img.shields.io/github/stars/arvindand/maven-tools-mcp?style=social)](https://github.com/arvindand/maven-tools-mcp/stargazers)

**Universal JVM dependency intelligence for any build tool using Maven Central Repository**

A Model Context Protocol (MCP) server that provides AI assistants with dependency analysis. Get instant, accurate dependency information for Maven, Gradle, SBT, Mill, and any JVM build tool that's faster and more reliable than web searches.

## 🎯 Why This Matters

**Problem:** Managing dependencies across complex projects requires deep analysis beyond simple version lookups
**Solution:** Dependency intelligence with bulk operations, trend analysis, and risk assessment for any JVM build tool

## ⚡ Quick Demo

<img src="assets/demo.gif" alt="Demo GIF"/>

Ask your AI assistant:
- *"Check all dependencies in this build file for latest versions"* (paste your build.gradle, pom.xml, build.sbt)
- *"What's the latest Spring Boot version?"*
- *"Which dependencies in my project need updates?"* (any build tool)
- *"Show me only stable versions for production deployment"*
- *"How old are my dependencies and which ones need attention?"* (new in v1.1.0)
- *"Analyze the release patterns for my key dependencies"* (new in v1.1.0)
- *"Give me a health check for all my project dependencies"* (new in v1.1.0)

## 🔧 Supported Build Tools

Working with **any build tool** that uses Maven Central Repository:

| Build Tool | Dependency Format | Example Usage |
|------------|------------------|---------------|
| **Maven** | `groupId:artifactId:version` | `org.springframework:spring-core:6.2.8` |
| **Gradle** | `implementation("group:artifact:version")` | Uses same Maven coordinates |
| **SBT** | `libraryDependencies += "group" % "artifact" % "version"` | Same groupId:artifactId format |
| **Mill** | `ivy"group:artifact:version"` | Same Maven Central lookup |

**All tools use standard Maven coordinates** - just provide `groupId:artifactId` and we handle the rest.

## ⚡ Competitive Advantages

### vs Simple Lookup Tools
- ✅ **Bulk Operations** - Analyze 20+ dependencies in one call
- ✅ **Version Comparison** - Understand upgrade impact (major/minor/patch)
- ✅ **Stability Filtering** - Choose stable-only or include pre-release versions
- ✅ **Enterprise Performance** - <100ms cached responses, native images
- ✅ **Analytical Intelligence** - Age analysis, release patterns, project health scoring (v1.1.0)

### vs Manual Dependency Management
- ✅ **Risk Assessment** - Identify breaking changes before upgrading
- ✅ **Universal Support** - Works with any JVM build tool
- ✅ **Complete Analysis** - All version types with intelligent prioritization
- ✅ **Maintenance Intelligence** - Predict maintenance activity and sustainability (v1.1.0)

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

**Core Dependency Intelligence:**
- Get latest or stable versions of Maven dependencies
- Check if specific versions exist
- Bulk version checking for multiple dependencies
- Compare versions and get update recommendations

**Advanced Analytics (v1.1.0):**
- Analyze dependency age and freshness (fresh/current/aging/stale)
- Assess maintenance activity and release patterns
- Predict next release timeframes
- Comprehensive project health scoring with risk assessment

## Available Tools

### Core Dependency Tools (v1.0.0)

| Tool | Purpose | Key Features |
|------|---------|--------------|
| `get_latest_version` | Get newest version by type with stability preferences | preferStable parameter, all version types |
| `get_stable_version` | Get latest stable only (production-ready) | Production-safe, excludes pre-release |
| `check_version_exists` | Verify if specific version exists with type info | Works with any JVM build tool |
| `check_multiple_dependencies` | Check multiple dependencies with filtering | stableOnly parameter, bulk operations |
| `check_multiple_stable_versions` | Stable versions for many dependencies | Perfect for production updates |
| `compare_dependency_versions` | Compare current vs latest with upgrade recommendations | onlyStableTargets parameter, risk assessment |

### Analytical Intelligence Tools (v1.1.0)

| Tool | Purpose | Key Features |
|------|---------|--------------|
| `analyze_dependency_age` | Classify dependencies as fresh/current/aging/stale | maxAgeInDays parameter, actionable insights |
| `analyze_release_patterns` | Analyze maintenance activity and predict releases | monthsToAnalyze parameter, velocity trends |
| `get_version_timeline` | Enhanced version timeline with temporal analysis | versionCount parameter, release gap detection |
| `analyze_project_health` | Comprehensive health analysis for multiple dependencies | Health scoring, bulk analysis, recommendations |

### Tool Parameters

**Core Parameters:**
- `preferStable` - Prioritize stable versions in analysis
- `stableOnly` - Filter to production-ready versions only
- `onlyStableTargets` - Only suggest upgrades to stable versions

**Analytical Parameters (v1.1.0):**
- `maxAgeInDays` - Set acceptable age threshold for dependencies
- `monthsToAnalyze` - Specify analysis period for release patterns (default: 24)
- `versionCount` - Number of recent versions to analyze in timeline (default: 20)
- `includeRecommendations` - Include detailed recommendations in health analysis

**Universal Compatibility:**
All tools work with standard Maven coordinates (`groupId:artifactId`) and support any JVM build tool.

### `get_latest_version`

Get latest version of any dependency from Maven Central (works with Maven, Gradle, SBT, Mill) with stability preferences.

**Parameters:**
- `dependency` (string, required): Maven coordinate in format `groupId:artifactId` (NO version)
- `preferStable` (boolean, optional): When true, prioritizes stable version in response (default: false)

**Examples:**
```json
{
  "dependency": "org.springframework:spring-core",
  "preferStable": false
}
```

```json
{
  "dependency": "org.springframework:spring-boot",
  "preferStable": true
}
```

**Response:**
```json
{
  "dependency": "org.springframework:spring-core",
  "latest_stable": { "version": "6.2.7", "type": "stable" },
  "latest_rc": { "version": "7.0.0-RC1", "type": "rc" },
  "latest_beta": { "version": "7.0.0-beta1", "type": "beta" },
  "latest_alpha": { "version": "7.0.0-alpha1", "type": "alpha" },
  "latest_milestone": { "version": "7.0.0-M5", "type": "milestone" },
  "total_versions": 100
}
```

### `check_version_exists`

Check if specific dependency version exists and identify its stability type. Works with any JVM build tool.

**Parameters:**
- `dependency` (string, required): Maven coordinate in format `groupId:artifactId` (NO version)
- `version` (string, required): Version to check

**Example:**
```json
{
  "dependency": "org.jetbrains.kotlin:kotlin-stdlib",
  "version": "1.9.0"
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

### `get_stable_version`

Get latest stable version only - excludes alpha, beta, RC, milestone versions. Perfect for production deployments.

**Parameters:**
- `dependency` (string, required): Maven coordinate in format `groupId:artifactId` (NO version)

**Example:**
```json
{
  "dependency": "com.squareup.retrofit2:retrofit"
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

### `check_multiple_dependencies`

Check latest versions for multiple dependencies with filtering options. Works with any JVM build tool.

**Parameters:**
- `dependencies` (string, required): Comma- or newline-separated list of Maven coordinates (NO versions)
- `stableOnly` (boolean, optional): When true, filters to production-ready versions only (default: false)

**Examples:**
```json
{
  "dependencies": "org.jetbrains.kotlin:kotlin-stdlib,com.squareup.retrofit2:retrofit,org.apache.spark:spark-core_2.13",
  "stableOnly": false
}
```

```json
{
  "dependencies": "org.springframework:spring-boot,com.fasterxml.jackson.core:jackson-core",
  "stableOnly": true
}
```

**Response (array):**
```json
[
  {
    "dependency": "org.springframework:spring-core",
    "primary_version": "6.2.7",
    "primary_type": "stable",
    "total_versions": 100,
    "stable_versions": 82,
    "latest_stable": { "version": "6.2.7", "type": "stable" },
    "latest_rc": { "version": "7.0.0-RC1", "type": "rc" },
    "latest_beta": null,
    "latest_alpha": null,
    "latest_milestone": { "version": "7.0.0-M5", "type": "milestone" }
  },
  // ...more results
]
```

### `check_multiple_stable_versions`

Get latest stable versions for multiple dependencies - perfect for production updates.

**Parameters:**
- `dependencies` (string, required): Comma- or newline-separated list of Maven coordinates (NO versions)

**Example:**
```json
{
  "dependencies": "org.scala-lang:scala-library,org.apache.kafka:kafka_2.13,io.vertx:vertx-core"
}
```

**Response (array):**
```json
[
  {
    "dependency": "org.springframework:spring-boot-starter",
    "primary_version": "3.5.3",
    "primary_type": "stable",
    "total_versions": 50,
    "stable_versions": 40
  },
  // ...more results
]
```

### `compare_dependency_versions`

Compare current dependency versions with latest available and show upgrade recommendations with safety controls.

**Parameters:**
- `currentDependencies` (string, required): Comma- or newline-separated list of Maven coordinates with versions (`groupId:artifactId:version`)
- `onlyStableTargets` (boolean, optional): When true, only suggests upgrades to stable versions (default: false)

**Examples:**
```json
{
  "currentDependencies": "org.jetbrains.kotlin:kotlin-stdlib:1.8.0,com.squareup.retrofit2:retrofit:2.9.0",
  "onlyStableTargets": false
}
```

```json
{
  "currentDependencies": "org.springframework:spring-boot:2.7.0,org.hibernate:hibernate-core:5.6.0",
  "onlyStableTargets": true
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
- "Get latest Spring Boot version but prioritize stable releases"
- "Check if Kotlin 1.9.0 exists and what stability type it is"
- "Show me latest stable version of Retrofit for production deployment"

**Multi-Build Tool Support:**
- "Check these Gradle dependencies: org.jetbrains.kotlin:kotlin-stdlib,com.squareup.retrofit2:retrofit"
- "I need stable versions only for my SBT project dependencies" 
- "Compare my Maven versions but only suggest stable upgrades for production"

**Advanced Stability Controls:**
- "Check multiple dependencies but filter to stable versions only"
- "Compare my current versions with onlyStableTargets=true for safety"
- "Get complete analysis but prefer stable versions in results"

## 🚀 Real-World Use Cases

### Gradle Project Analysis
**Action:** Paste your build.gradle: *"Analyze my Gradle dependencies for outdated versions"*  
**Result:** Universal dependency analysis in seconds across any build tool

### Security Response  
**Action:** *"Show me latest stable versions for these affected dependencies"*  
**Result:** Instant security patch identification with production-safe recommendations

### Multi-Build Tool Projects
**Action:** *"What are the latest stable versions for Spring Boot, Spring Security, and Jackson for both Maven and Gradle?"*  
**Result:** Universal dependency intelligence across all JVM build tools

### Migration Planning with Risk Assessment
**Action:** *"Compare my current versions but only suggest stable upgrades for production safety"*  
**Result:** Risk-assessed upgrade recommendations with stability filtering

## 🆚 Why Not Just Web Search?

| Scenario | Web Search | Maven Tools MCP |
|----------|------------|------------------|
| Single dependency lookup | 3-5 seconds | <100ms (cached) |
| 20 dependencies across build tools | 60+ seconds | <500ms |
| Data accuracy | Variable/outdated | 100% current |
| Bulk operations | Manual, error-prone | Native support |
| Version classification | Manual parsing | Automatic (stable/RC/beta) |
| Stability filtering | Not available | Built-in (stableOnly, preferStable) |
| Build tool compatibility | Tool-specific searches | Universal JVM support |

## ✨ New Analytical Intelligence Examples (v1.1.0)

### Dependency Age Analysis
**Usage:** *"How old is my Spring Boot dependency and should I update it?"*  
**Tool:** `analyze_dependency_age`
```json
{
  "dependency": "org.springframework.boot:spring-boot-starter",
  "age_classification": "current",
  "days_since_release": 45,
  "recommendation": "Actively maintained - consider updating if needed"
}
```

### Release Pattern Analysis  
**Usage:** *"What's the maintenance pattern for Jackson? When might the next release be?"*  
**Tool:** `analyze_release_patterns`
```json
{
  "dependency": "com.fasterxml.jackson.core:jackson-core",
  "maintenance_level": "active",
  "release_velocity": 1.2,
  "next_release_prediction": "Expected in 3 weeks"
}
```

### Project Health Check
**Usage:** *"Give me a health assessment for all my key dependencies"*  
**Tool:** `analyze_project_health`
```json
{
  "overall_health": "good",
  "average_health_score": 78,
  "age_distribution": {"fresh": 2, "current": 8, "aging": 3, "stale": 1}
}
```

### Version Timeline Intelligence
**Usage:** *"Show me the recent release timeline for JUnit with gap analysis"*  
**Tool:** `get_version_timeline`
```json
{
  "insights": ["High release frequency indicates active development"],
  "recent_activity": {"activity_level": "active", "releases_last_quarter": 4}
}
```

## Features

- Version lookup (latest, stable, or specific versions)
- Version type classification (stable, RC, beta, alpha, milestone)
- Bulk operations for multiple dependencies
- Version comparison tools
- **Dependency age analysis with actionable insights** (v1.1.0)
- **Maintenance pattern analysis and predictions** (v1.1.0)
- **Project health scoring and recommendations** (v1.1.0)
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
java -jar target/maven-tools-mcp-1.1.1.jar
```

**Claude Desktop configuration for JAR:**
```json
{
  "mcpServers": {
    "maven-tools": {
      "command": "java",
      "args": [
        "-jar",
        "/absolute/path/to/maven-tools-mcp-1.1.1.jar"
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

- **Framework**: Spring Boot 3.5.3 with [Spring AI MCP](https://docs.spring.io/spring-ai/reference/api/mcp.html)
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
- Version: 1.1.1