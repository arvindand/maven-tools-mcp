# Maven Tools MCP Server

[![Java](https://img.shields.io/badge/Java-24-orange.svg)](https://openjdk.java.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.6-green.svg)](https://spring.io/projects/spring-boot)
[![MCP Protocol](https://img.shields.io/badge/MCP-2024--11--05-blue.svg)](https://modelcontextprotocol.io/)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![GitHub release (latest by date)](https://img.shields.io/github/v/release/arvindand/maven-tools-mcp)](https://github.com/arvindand/maven-tools-mcp/releases)
[![Docker](https://img.shields.io/badge/Docker-Multi--Arch-blue.svg)](https://hub.docker.com/r/arvindand/maven-tools-mcp)
[![GitHub stars](https://img.shields.io/github/stars/arvindand/maven-tools-mcp?style=social)](https://github.com/arvindand/maven-tools-mcp/stargazers)

## Universal Maven Central dependency intelligence for JVM build tools

MCP server providing AI assistants with Maven Central dependency intelligence for all JVM build tools (Maven, Gradle, SBT, Mill). Get instant, accurate dependency information by reading maven-metadata.xml files directly from Maven Central repository - faster and more reliable than web searches or search APIs. Features Context7 integration for documentation support.

## 🎯 Why This Matters

- **Problem:** Dependency management involves time-intensive manual searches across Maven Central for version updates and compatibility analysis
- **Solution:** AI-assisted dependency intelligence with instant bulk analysis, trend insights, and risk assessment for any JVM build tool

## ⚡ Quick Demo

![Demo GIF](assets/demo.gif)

Ask your AI assistant:

- *"Check all dependencies in this build file for latest versions"* (paste your build.gradle, pom.xml, build.sbt)
- *"What's the latest Spring Boot version?"*
- *"Which dependencies in my project need updates?"* (any build tool)
- *"Show me only stable versions for production deployment"*
- *"How old are my dependencies and which ones need attention?"*
- *"Analyze the release patterns for my key dependencies"*
- *"Give me a health check for all my project dependencies"*
- *"How do I upgrade Spring Boot from 2.7.0 to the latest version? Show me migration guidance"*
- *"Check these dependencies for upgrades and suggest documentation searches"* (paste your pom.xml/build.gradle)
- *"I'm still using Jackson 2.12.0. Should I upgrade and how?"*

## 🔧 Supported Build Tools

Working with **any build tool** that uses Maven Central Repository:

| Build Tool | Dependency Format | Example Usage |
|------------|------------------|---------------|
| **Maven** | `groupId:artifactId:version` | `org.springframework:spring-core:6.2.8` |
| **Gradle** | `implementation("group:artifact:version")` | Uses same Maven coordinates |
| **SBT** | `libraryDependencies += "group" % "artifact" % "version"` | Same groupId:artifactId format |
| **Mill** | `ivy"group:artifact:version"` | Same Maven Central lookup |

**All tools use standard Maven coordinates** - just provide `groupId:artifactId` and we handle the rest.

## ⚡ Advantages

### vs Simple Lookup Tools

- ✅ **Bulk Operations** - Analyze 20+ dependencies in one call
- ✅ **Version Comparison** - Understand upgrade impact (major/minor/patch)
- ✅ **Stability Filtering** - Choose stable-only or include pre-release versions
- ✅ **Enterprise Performance** - <100ms cached responses, native images
- ✅ **Analytical Intelligence** - Age analysis, release patterns, project health scoring
- ✅ **Context7 Orchestration** - Clear tool orchestration instructions with web search fallback

### vs Manual Dependency Management

- ✅ **Risk Assessment** - Identify breaking changes before upgrading
- ✅ **Universal Support** - Works with any JVM build tool
- ✅ **Complete Analysis** - All version types with intelligent prioritization
- ✅ **Maintenance Intelligence** - Predict maintenance activity and sustainability

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
        "run", "-i", "--rm",
        "arvindand/maven-tools-mcp:latest"
      ]
    }
  }
}
```

**Step 3:** Restart Claude Desktop

**Prerequisites:** Docker installed and running

**Note:** The Docker image supports both AMD64 (Intel/AMD) and ARM64 (Apple Silicon) architectures. Docker automatically selects the correct version for your platform.

**Troubleshooting:** Context7 integration is enabled by default and contacts `https://mcp.context7.com` during startup. If your network blocks this URL the server prints a Spring stack trace to `stdout`, which causes the MCP handshake to fail. Use the Context7-free native image instead: `arvindand/maven-tools-mcp:latest-noc7`. (Environment-variable toggles only work when running the JVM jar directly.)

**Corporate Networks with SSL Inspection:** If you need Context7 integration but your network uses SSL inspection (MITM proxies), you can build a custom image with your corporate certificates. See the [Corporate Certificate Guide](CORPORATE-CERTIFICATES.md) for detailed instructions.

## Setup for VS Code with GitHub Copilot

**Option 1: Workspace Configuration** - Create `.vscode/mcp.json`:

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

**Option 2: User Settings** - Add to your VS Code settings:

```json
{
  "mcp": {
    "servers": {
      "maven-tools": {
        "type": "stdio", 
        "command": "docker",
        "args": ["run", "-i", "--rm", "arvindand/maven-tools-mcp:latest"]
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

**Advanced Analytics:**

- Analyze dependency age and freshness (fresh/current/aging/stale)
- Assess maintenance activity and release patterns
- Predict next release timeframes
- Comprehensive project health scoring with risk assessment

## Available Tools

### Core Maven Intelligence Tools (8 tools)

| Tool | Purpose | Key Features |
|------|---------|--------------|
| `get_latest_version` | Get newest version by type with stability preferences | preferStable parameter, all version types |
| `check_version_exists` | Verify if specific version exists with type info | Works with any JVM build tool |
| `check_multiple_dependencies` | Check multiple dependencies with filtering | stableOnly parameter, bulk operations |
| `compare_dependency_versions` | Compare current vs latest with upgrade recommendations | includeMigrationGuidance flag |
| `analyze_dependency_age` | Classify dependencies as fresh/current/aging/stale | includeModernizationGuidance flag |
| `analyze_release_patterns` | Analyze maintenance activity and predict releases | monthsToAnalyze parameter, velocity trends |
| `get_version_timeline` | Enhanced version timeline with temporal analysis | versionCount parameter, release gap detection |
| `analyze_project_health` | Comprehensive health analysis for multiple dependencies | includeUpgradeStrategy flag |

### Raw Context7 Documentation Tools (2 tools - Enabled by Default)

| Tool | Purpose | Key Features |
|------|---------|--------------|
| `resolve-library-id` | Search for library documentation | Always available (context7.enabled=true by default) |
| `get-library-docs` | Get library documentation by ID | Always available (context7.enabled=true by default) |

### Tool Parameters

**Core Parameters:**

- `preferStable` - Prioritize stable versions in analysis
- `stableOnly` - Filter to production-ready versions only
- `onlyStableTargets` - Only suggest upgrades to stable versions

**Analytical Parameters:**

- `maxAgeInDays` - Set acceptable age threshold for dependencies
- `monthsToAnalyze` - Specify analysis period for release patterns (default: 24)
- `versionCount` - Number of recent versions to analyze in timeline (default: 20)
- `includeRecommendations` - Include detailed recommendations in health analysis

**Context7 Integration:**

Context7 integration is **enabled by default** (`context7.enabled=true`). Maven tools automatically include explicit orchestration instructions in response models when upgrades or modernization are needed. Additionally, the server acts as an MCP client to expose raw Context7 tools (`resolve-library-id`, `get-library-docs`) directly to your AI assistant. When disabled, responses contain only core dependency analysis without orchestration instructions or Context7 tools.

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

### Raw Context7 MCP Tools

**Note:** Context7 integration is enabled by default. The following raw Context7 MCP tools are automatically available through the server's dual MCP architecture (acting as both MCP server and MCP client):

### `resolve-library-id`

Search for library documentation using intelligent name resolution.

**Parameters:**

- `libraryName` (string, required): Search term for library lookup (e.g., "spring boot", "testcontainers")

**Example:**

```json
{
  "libraryName": "testcontainers postgresql"
}
```

### `get-library-docs`

Get comprehensive documentation for a library using its Context7 ID.

**Parameters:**

- `context7CompatibleLibraryID` (string, required): Context7-compatible library ID (from resolve-library-id)
- `topic` (string, optional): Topic for focused documentation (e.g., "setup", "migration", "configuration")
- `tokens` (integer, optional): Maximum tokens to retrieve (default: 10000)

**Example:**

```json
{
  "context7CompatibleLibraryID": "/testcontainers/testcontainers-java",
  "topic": "postgresql setup",
  "tokens": 5000
}
```

These tools are automatically available by default through Spring AI MCP client integration. The server acts as both an MCP server (exposing Maven tools) and an MCP client (exposing Context7 tools), providing a unified interface for both dependency analysis and documentation access.

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

## ✨ Advanced Features Examples

### Analytical Intelligence & Documentation Enrichment

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
- **Dependency age analysis with actionable insights**
- **Maintenance pattern analysis and predictions**
- **Project health scoring and recommendations**
- **Context7 migration guidance and upgrade strategies**
- **Documentation enrichment for complex upgrades**
- Caching for better performance
- Works with MCP-compatible AI assistants

> **Note:** Snapshot versions are not supported. This is because the Maven Central API does not index or provide access to snapshot artifacts. Only released versions (stable, rc, beta, alpha, milestone) are available.

## Context7 Guided Delegation Architecture

**Default Behavior:** Context7 integration is **enabled by default**. The server acts as both an MCP server (providing Maven tools) and an MCP client (exposing Context7 tools), giving your AI assistant access to both dependency intelligence and documentation guidance in a single connection. When disabled (`context7.enabled=false`), Maven tools work independently without Context7 guidance hints or raw Context7 tools.

### Dual MCP Architecture

Maven Tools MCP uses a **dual MCP architecture** with guided delegation for Context7 integration:

1. **MCP Server:** Provides 8 Maven dependency analysis tools with intelligent Context7 guidance hints
2. **MCP Client:** Acts as Context7 MCP client to expose raw Context7 tools (`resolve-library-id`, `get-library-docs`)
3. **Intelligent Integration:** Maven tools include smart Context7 search suggestions when upgrades/modernization are needed
4. **Direct Access:** Your AI assistant can use both Maven analysis AND Context7 documentation tools in a single connection

This dual architecture provides both dependency intelligence and documentation access through one MCP server connection, with intelligent guidance for effective Context7 tool usage.

### Context7 Tools (Enabled by Default)

Context7 tools are automatically enabled by default. To disable Context7 integration entirely, use the `-noc7` image variant:

```json
{
  "mcpServers": {
    "maven-tools": {
      "command": "docker",
      "args": [
        "run", "-i", "--rm",
        "arvindand/maven-tools-mcp:latest-noc7"
      ]
    }
  }
}
```

**Note:** Environment variable toggles (`-e CONTEXT7_ENABLED=false`) only work when running the JVM jar directly, not with native images. Native images have configuration compiled in at build time. Use the `-noc7` image variants for a pure Maven tools experience without Context7 integration.

### Image Variants

| Image Tag | Contents | When to Use |
|-----------|----------|-------------|
| `arvindand/maven-tools-mcp:latest` | Native image with Context7 tools enabled | Default experience with documentation integration |
| `arvindand/maven-tools-mcp:latest-noc7` | Native image without Context7 client/tools | Environments without outbound access to `mcp.context7.com` or where only Maven tools are desired |
| `arvindand/maven-tools-mcp:<version>` | Version-specific multi-arch image (Context7 enabled) | Pin to an exact release |
| `arvindand/maven-tools-mcp:<version>-noc7` | Version-specific image without Context7 | Pin to exact release without Context7 |

### Context7 Orchestration Instructions

**Intelligent LLM Orchestration:**

Maven Tools MCP provides explicit orchestration instructions in response models to help LLMs effectively use the raw Context7 MCP tools when documentation is needed. These clear step-by-step instructions include web search fallback for resilient documentation access.

**Context7 Orchestration Example:**

**Usage:** *"Compare my Spring Boot version and show upgrade path"*

**Tool:** `compare_dependency_versions`

```json
{
  "dependencies": [{
    "dependency": "org.springframework.boot:spring-boot-starter:2.7.0",
    "current_version": "2.7.0",
    "latest_version": "3.2.0", 
    "update_type": "major",
    "context7_guidance": {
      "orchestration_instructions": "Use resolve-library-id tool with libraryName='spring-boot-starter' to find documentation ID. Then use get-library-docs tool with the returned Context7 ID and topic='migration guide' to get upgrade instructions. If Context7 doesn't provide sufficient information, perform a web search for 'spring-boot-starter major version upgrade guide'."
    }
  }]
}
```

**Modernization Guidance Example:**

**Usage:** *"Analyze my aging dependencies with modernization suggestions"*

**Tool:** `analyze_dependency_age`

```json
{
  "dependency": "org.hibernate:hibernate-core",
  "age_classification": "AGING",
  "days_since_last_release": 180,
  "recommendation": "Consider upgrading - dependency is showing age",
  "context7_guidance": {
    "orchestration_instructions": "Use resolve-library-id tool with libraryName='hibernate-core' to find documentation ID. Then use get-library-docs tool with the returned Context7 ID and topic='modern usage and best practices' to get modernization guidance. If Context7 doesn't provide sufficient information, perform a web search for 'hibernate-core latest features best practices'."
  }
}
```

## Performance Notes

- **Cache effectiveness:** ~90% of repeated requests served from cache
- **Recommended batch sizes:** 10-20 dependencies for bulk operations
- **First requests:** Build cache (normal), subsequent requests much faster
- **Cache duration:** 24 hours

## 🤔 Frequently Asked Questions

**Q: How is this different from Dependabot/Renovate?**  
A: Those tools create automated PRs. This gives you instant, interactive dependency intelligence through your AI assistant for decision-making and planning.

**Q: How much time does this actually save?**  
A: For single dependencies: from 3-5 seconds (web search) to <100ms. For 20+ dependencies: from 60+ seconds of manual searching to <500ms bulk analysis.

**Q: Why not just search Maven Central directly?**  
A: This reads maven-metadata.xml files directly from Maven Central repository, providing structured, cached responses optimized for AI consumption with intelligent version classification and bulk operations - plus the time savings above.

**Q: Can this replace my IDE's dependency management?**  
A: No, it complements your IDE by providing instant dependency intelligence during natural conversations with AI assistants for planning and decision-making.

**Q: What AI assistants does this work with?**  
A: Any MCP-compatible assistant including Claude Desktop, GitHub Copilot, and other MCP clients. Works through natural conversation.

**Q: Does it work with private Maven repositories?**  
A: Currently only Maven Central.

**Q: What about Gradle dependencies?**  
A: Maven Central hosts both Maven and Gradle dependencies, so it works for Gradle projects too (using Maven coordinates).

**Q: What is Context7 and how does the guided delegation work?**  
A: Context7 is an MCP server by Upstash that provides up-to-date documentation and code examples. Maven Tools MCP uses a guided delegation architecture - our tools provide explicit orchestration instructions to help your AI assistant effectively use the raw Context7 tools when documentation is needed. This includes clear step-by-step tool usage instructions with web search fallback for resilient documentation access.

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
java -jar target/maven-tools-mcp-1.5.1-SNAPSHOT.jar
```

**Claude Desktop configuration for JAR:**

```json
{
  "mcpServers": {
    "maven-tools": {
      "command": "java",
      "args": [
        "-jar",
        "/absolute/path/to/maven-tools-mcp-1.5.1-SNAPSHOT.jar"
      ]
    }
  }
}
```

### Build Scripts

For easier builds, use the provided scripts in the `build/` folder:

**Linux/macOS:**

```bash
cd build
./build.sh        # Complete build helper
./build-docker.sh # Docker-focused helper
```

**Windows:**

```cmd
cd build
build.cmd         # Complete build helper
build-docker.cmd  # Docker-focused helper
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

# Maven Central Repository settings
maven:
  central:
    repository-base-url: https://repo1.maven.org/maven2
    timeout: 10s
    max-results: 100

# Logging (minimal for MCP stdio transport)
logging:
  level:
    root: ERROR
```

## Technical Details

- **Framework**: Spring Boot 3.5.6 with [Spring AI MCP](https://docs.spring.io/spring-ai/reference/api/mcp.html)
- **MCP Protocol**: 2024-11-05
- **Java Version**: 24
- **Transport**: stdio
- **HTTP Client**: OkHttp 5.2.1 with HTTP/2 support
- **Cache**: Caffeine (24-hour TTL, 2000 entries max)
- **Resilience**: Circuit breaker, retry, and rate limiter patterns
- **Data Source**: Maven Central Repository (maven-metadata.xml files)

## References & Resources

### Model Context Protocol (MCP)

- **Official Website**: [modelcontextprotocol.io](https://modelcontextprotocol.io/)
- **GitHub Repository**: [modelcontextprotocol/specification](https://github.com/modelcontextprotocol/specification)
- **Protocol Documentation**: [MCP Specification](https://spec.modelcontextprotocol.io/)

### Spring AI MCP

- **Documentation**: [Spring AI MCP Reference](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-overview.html)
- **GitHub**: [spring-projects/spring-ai](https://github.com/spring-projects/spring-ai)

### Maven Central Repository

- **Repository**: [repo1.maven.org](https://repo1.maven.org/maven2/)
- **Metadata Format**: [Maven Metadata XML Reference](https://maven.apache.org/ref/3.9.6/maven-repository-metadata/)
- **Search API**: [search.maven.org](https://search.maven.org/) (not used in v1.4.0+)

### Context7 MCP Server

- **GitHub Repository**: [upstash/context7](https://github.com/upstash/context7)
- **NPM Package**: [@upstash/context7-mcp](https://www.npmjs.com/package/@upstash/context7-mcp)
- **Documentation**: [Upstash Context7 Blog](https://upstash.com/blog/context7-mcp)

## 📝 Community & Discussion

**Blog Posts:**

- [How I Connected Claude to Maven Central (and Why You Should Too)](https://dev.to/arvindand/how-i-connected-claude-to-maven-central-and-why-you-should-too-2clo)
- [Guided Delegation: Adding Context7 Documentation to My Maven Tools MCP Server](https://dev.to/arvindand/guided-delegation-adding-context7-documentation-to-my-maven-tools-mcp-server-572l)

### Get Involved

- 💬 **Discuss:** Share your experiences and ask questions [on dev.to](https://dev.to/arvindand/how-i-connected-claude-to-maven-central-and-why-you-should-too-2clo)
- 🐛 **Issues:** [Report bugs or request features](https://github.com/arvindand/maven-tools-mcp/issues)
- ⭐ **Support:** Star this repo if it improves your workflow

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Author

Arvind Menon

- GitHub: [@arvindand](https://github.com/arvindand)
- Version: 1.5.1-SNAPSHOT
