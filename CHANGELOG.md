# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added (Unreleased)

### Changed (Unreleased)

### Fixed (Unreleased)

### Removed (Unreleased)

## [2.0.5] - 2026-02-23

**STDIO Docker Reliability Release** - Restores native stdio MCP startup for VS Code/Copilot after recent dependency updates and temporarily pins Logback to avoid stdout protocol pollution.

### Changed (2.0.5)

- **Native STDIO Docker Build Config**: Explicitly enables Spring AI MCP stdio transport in the `docker` profile (`application-docker.yaml`) so native stdio images respond correctly to MCP `initialize`
- **Self-Update Workflow Ignore List**: Excludes temporary MCP SDK and Logback override dependencies from automated dependency-update PRs

### Fixed (2.0.5)

- **VS Code / Copilot STDIO MCP Startup**: Temporary `logback.version` override to `1.5.22` avoids Logback `1.5.32` startup status output on stdout in native stdio images, which breaks MCP message parsing

## [2.0.4] - 2026-02-22

**Dogfooding & Context7 Compatibility Release** - Adds automated self-update workflow dogfooding, fixes Context7 MCP client startup compatibility, and improves dependency update selection behavior.

### Added (2.0.4)

- **Dogfooding Self-Update Workflow**: Weekly GitHub Actions workflow that runs a local Python dependency-update agent subproject against Maven Tools MCP itself and opens/updates a bot PR for safe minor/patch changes
- **Local Dependency Agent Subproject**: `agents/copilot-maven-tools-agent/` (Copilot SDK + Maven Tools MCP) for repository-local dogfooding automation
- **Server-side Same-Major Fallbacks**: `compare_dependency_versions` can now return a same-major stable fallback suggestion when the latest stable recommendation is a major update (e.g., Spring Boot `3.5.x` patch while `4.x` exists)

### Changed (2.0.4)

- **Context7 MCP Compatibility**: Override MCP Java SDK to `0.17.2` to fix startup failures caused by `202 Accepted` `text/plain` responses for MCP notifications
- **Context7 API Key Support**: Added optional `CONTEXT7_API_KEY` outbound auth support for Context7 MCP client requests (documented as optional; default run configs remain key-free)
- **Dogfooding Agent Selection Logic**: Keeps version semantics in Maven Tools MCP and allows configurable dependency ignores (used for temporary MCP SDK BOM override during dogfooding)
- **Dogfooding Agent Default Model**: Default Copilot model updated to `claude-haiku-4.5`

## [2.0.3] - 2026-02-03

**HTTP Transport Release** - Adds streamable HTTP transport support and Actuator health probes

### Added (2.0.3)

- **Streamable HTTP Transport**: New `http` Spring profile for HTTP-based MCP protocol
  - Endpoint: `/mcp` on port 8080
  - Compatible with HTTP-based MCP clients and container deployments
  - Run with: `--spring.profiles.active=http` or `SPRING_PROFILES_ACTIVE=http`
- **Actuator Health Endpoints**: Container-ready health probes
  - `/actuator/health/liveness` for liveness probe
  - `/actuator/health/readiness` for readiness probe
- **HTTP Docker Image Variant**: New `:latest-http` and `:{version}-http` multi-arch images with streamable HTTP transport

### Changed (2.0.3)

- Added `spring-boot-starter-actuator` dependency for health endpoints
- Build option 8 creates HTTP transport Docker image (`-http` suffix)

## [2.0.2] - 2026-01-17

**Bug Fix Release** - Fixes project health analysis and improves cache thread safety

### Fixed (2.0.2)

- **Project Health Analysis**: Fixed parameter ordering bug in `buildSimpleHealthSummary` that caused `dependencyCount` to always be 0 or incorrect values
- **VulnerabilityService Race Condition**: Fixed potential duplicate OSV requests using atomic `cache.get()` pattern instead of separate check-then-put

### Changed (2.0.2)

- **Code Cleanup**: Removed duplicate `@EnableConfigurationProperties` annotation, improved javadoc clarity

## [2.0.1] - 2026-01-10

**Stability Fixes* - Fixes occassional virtual thread race conditions due to mcp client using ASYNC mode while server uses SYNC. Now both use SYNC.

### Changed (2.0.1)

- **MCP Client Transport**: Changed from ASYNC to SYNC transport as per spring ai guidelines

- **MCP Client Dependency**: Switched from `spring-ai-starter-mcp-client-webflux` to `spring-ai-starter-mcp-client`
  - Standard HTTP client replaces reactive WebFlux client
  
- **Context7 Guidance Hints**: Now available in both image variants
  - `context7.hints.enabled` property controls orchestration instructions in responses
  - Enabled by default in both `:latest` and `:latest-noc7` images
  - Provides actionable guidance even when Context7 tools are unavailable

### Fixed (2.0.1)

- **"Failed to enqueue message" Runtime Error**: Fixed `RuntimeException` from `StdioServerTransportProvider` when multiple async tool calls were made concurrently (e.g., Context7 lookups). Root cause was SYNC server with ASYNC client transport mismatch in MCP Java SDK.

## [2.0.0] - 2025-12-29

**Security & License Intelligence Release** - Adds vulnerability scanning via OSV.dev and license compliance analysis to existing tools

### Added (2.0.0)

- **Vulnerability Scanning (OSV.dev)**:
  - `VulnerabilityService` with OSV.dev API integration for CVE/vulnerability detection
  - `VulnerabilityInfo` model with CVSS severity mapping (CRITICAL, HIGH, MEDIUM, LOW)
  - `SecurityAssessment` with factory methods for clean/unknown/vulnerable states
  - `SecuritySummary` aggregate with builder pattern for bulk results
  - `SecurityFindings` for project health integration with `requiresAction()` helper
  - Caffeine caching (6-hour TTL, 5000 max entries) for vulnerability data
  - Circuit breaker and rate limiter (5 req/s) for OSV API resilience
  - Bounded parallelism with Semaphore (max 5 concurrent scans)

- **License Compliance Analysis**:
  - `LicenseInfo` model with automatic categorization (PERMISSIVE, WEAK_COPYLEFT, STRONG_COPYLEFT, UNKNOWN)
  - `LicenseFindings` aggregate with builder pattern and `needsReview()` helper
  - POM-based license parsing via regex extraction from Maven Central
  - Support for common licenses: Apache, MIT, BSD, GPL, LGPL, MPL, EPL, CDDL, etc.

- **Enhanced Tool Parameters**:
  - `includeSecurityScan` parameter on `compare_dependency_versions` (default: true)
  - `includeSecurityScan` parameter on `analyze_project_health` (default: true)
  - `includeLicenseScan` parameter on `analyze_project_health` (default: true)

- **New Error Codes**:
  - `SECURITY_CHECK_FAILED` for OSV API failures
  - `LICENSE_CHECK_FAILED` for license parsing failures
  - `RATE_LIMITED` for API throttling scenarios

- **Custom Commands & Prompts**:
  - Claude Code slash commands in `.claude/commands/`: `/deps-check`, `/deps-health`, `/deps-upgrade`, `/deps-age`
  - GitHub Copilot prompts in `.github/prompts/`: dependency-audit, security-scan, upgrade-plan
  - Pre-built workflows for common dependency management tasks

### Changed (2.0.0)

- **Response Models Enhanced**:
  - `VersionComparison` now includes optional `SecuritySummary` field
  - `ProjectHealthAnalysis` now includes `SecurityFindings` and `LicenseFindings` fields
  - `DependencyHealthAnalysis` now includes optional `SecurityAssessment` field
  - All changes are backward-compatible via overloaded constructors

- **Health Recommendations**: `buildSimpleHealthSummary()` and `generateHealthRecommendations()` now factor in security and license findings

- **Code Quality**:
  - Added `SUGGESTION` constant in `McpError` to eliminate string literal duplication
  - License categorization checks weak copyleft patterns before strong copyleft for correct LGPL handling

- **Updated Dependencies**:
  - Spring Boot parent updated to 3.5.9 (from 3.5.8)
  - OkHttp updated to 5.3.2 (from 5.2.1)
  - Maven Artifact updated to 3.9.12 (from 3.9.11)

- **Context7 MCP Server 2.0.0 Compatibility**:
  - Updated orchestration instructions for new tool names (`query-docs` replaces `get-library-docs`)
  - Added required `query` parameter to `resolve-library-id` instructions
  - Updated parameter names (`libraryId` replaces `context7CompatibleLibraryID`)
  - Simplified query-based approach (removed `topic`, `mode`, `page`, `limit` references)

- **Improved Tool Descriptions**:
  - Clearer input requirements: "NO versions in input" vs "versions REQUIRED in input"
  - Cross-references between related tools to reduce LLM confusion
  - `analyze_project_health` marked as "PREFERRED for full audits"

## [1.5.3] - 2025-12-16

**MCP Protocol Compatibility Release** - Fixes MCP server startup failure with latest Copilot/Claude clients by upgrading to Spring AI 1.1.2 GA.

### Changed (1.5.3)

- **Updated Dependencies**:
  - Spring AI updated to 1.1.2 (from 1.1.0-M3) - stable GA release with MCP protocol fixes
  - Spring Boot parent updated to 3.5.8 (from 3.5.6)

### Fixed (1.5.3)

- **MCP Protocol Compatibility**: Fixed `Unrecognized field "form"` error in `McpSchema$ClientCapabilities$Elicitation` that prevented server startup with latest MCP clients (GitHub Copilot, Claude Desktop)

## [1.5.2] - 2025-11-07

**Docker Metadata Accuracy Release** - Ensures published images surface the actual build timestamp for auditability across all deployment targets.

### Changed (1.5.2)

- **CI/CD Workflow**: Set `spring-boot.build-image.createdDate=now` during Docker image builds so Docker Hub reports the real creation time instead of the reproducible-build epoch

### Fixed (1.5.2)

- **Image Timestamp Drift**: Eliminated misleading "45 years ago" timestamps on published images by stamping actual build times during image creation

## [1.5.1] - 2025-10-22

**Corporate Environment Support Release** - Adds dual-image build strategy and comprehensive documentation for corporate networks with SSL inspection/MITM proxies.

### Added (1.5.1)

- **Corporate Certificate Guide**: Complete documentation (`CORPORATE-CERTIFICATES.md`) for building custom native images with corporate SSL certificates using Paketo buildpack certificate bindings
- **Dual-Image Build Strategy**: CI/CD now builds 4 image variants (amd64/arm64, with/without Context7) to support different deployment scenarios
- **Spring Profiles for Context7 Control**:
  - `application-no-context7.yaml` - Disables Context7 integration for corporate environments
  - `application-docker.yaml` - Controls Spring Boot banner for clean MCP protocol compliance
- **Image Variants Documentation**: Added comprehensive table in README explaining when to use each image tag (`latest`, `latest-noc7`, `<version>`, `<version>-noc7`)

### Changed (1.5.1)

- **Build Scripts**: Updated all Unix and Windows build scripts to support `-noc7` image builds with Spring profile activation
- **README**: Added troubleshooting section for corporate SSL environments and link to certificate guide
- **CI/CD Workflow**: Enhanced to build and publish 4 multi-architecture image manifests
- **Updated Dependencies**:
  - Resilience4j updated to 2.3.0 (from 2.2.0)

### Fixed (1.5.1)

- **SSL Handshake Issues**: Resolved Context7 connection failures in corporate environments with SSL inspection by providing `-noc7` image variants and custom certificate build solution
- **MCP Protocol Interference**: Fixed Spring Boot banner appearing before logback initialization by using `application-docker.yaml` profile

## [1.5.0] - 2025-10-19

**Performance & Resilience Release** - Introduces OkHttp 5 for HTTP/2 support, circuit breaker patterns, and improved reliability. Includes code quality improvements and dependency updates.

### Added (1.5.0)

- **OkHttp5 Integration**: Direct HTTP/2 support with connection pooling for improved performance and resource efficiency (5.2.1 - latest stable)
- **Resilience4j Patterns**: Added circuit breaker, retry, and rate limiter patterns to `MavenCentralService` for improved reliability
- **Connection Pool Configuration**: New `maven.central.connection-pool-size` property (default: 50) for tuning OkHttp connection pooling
- **Spring Configuration Metadata**: Added `maven.central.connection-pool-size` property documentation for IDE autocomplete support

### Changed (1.5.0)

- **HTTP Client Architecture**: Introduced OkHttp 5.2.1 as the primary HTTP client (replacing SimpleClientHttpRequestFactory) for HTTP/2 support and improved connection pooling
- **Updated Dependencies**:
  - Spring Boot parent updated to 3.5.6 (from 3.5.4)
  - fmt-maven-plugin updated to 2.29 (from 2.27)
- **MCP Client Transport**: Changed from SYNC SSE client to ASYNC streamable-http transport for better performance and compatibility

### Removed (1.5.0)

- **Test Utilities**: Removed unused `ClientStdio` test class (62 lines)
- **Legacy REST Configuration**: Removed direct `SimpleClientHttpRequestFactory` bean in favor of OkHttp5-backed RestClient

## [1.4.0] - 2025-08-17

**Direct Maven Repository Access Release** - All existing MCP tools and features are fully retained with significantly improved accuracy and performance by reading maven-metadata.xml files directly from Maven Central instead of using the search API.

### Added (1.4.0)

- **Maven Metadata XML Parsing:** Now reads maven-metadata.xml files directly from Maven Central repository for accurate version information
- **Jackson XML Support:** Added XML parsing capabilities for universal JVM build tool support
- **Direct Repository Tests:** Comprehensive test coverage for maven-metadata.xml access functionality
- **Improved Version Accuracy:** Eliminates search API delays and version ordering quirks

### Changed (1.4.0)

- **Data Source:** Now uses `https://repo1.maven.org/maven2` maven-metadata.xml files instead of `search.maven.org` Solr API
- **Timestamp Accuracy:** Implemented a more accurate timestamp retrieval method that fetches real timestamps for recent versions via HTTP HEAD requests.
- **Enhanced Performance:** Smaller XML metadata files provide faster response times than large JSON search results
- **Simplified Configuration:** Removed complex strategy patterns for cleaner, more maintainable codebase
- **Updated Dependencies:** Added Jackson XML module for maven-metadata.xml parsing support

### Fixed (1.4.0)

- **Version Ordering Issues:** Direct repository access provides accurate version ordering without Solr search index delays
- **Date-like Version Anomalies:** Fixed incorrect "latest" results for artifacts like commons-io with date-like versions
- **JGit Release Classification:** Service pack releases with `-r` suffix now correctly classified as stable
- **Timestamp Analysis:** Repository metadata provides authoritative version information for analytical features
- **Error Handling:** Graceful null returns for non-existent artifacts instead of exceptions for better API consistency
- **Date/Time Processing:** Improved timestamp parsing using proper Java time APIs for more accurate analytical features

### Removed (1.4.0)

- **Search API Dependency:** Eliminated reliance on `search.maven.org/solrsearch/select` for core functionality
- **Unused Models:** Removed the obsolete `MavenSearchResponse` model after refactoring.
- **Strategy Configuration:** Removed complex strategy patterns for simplified architecture  
- **Legacy Properties:** Deprecated `maven.central.base-url` in favor of `maven.central.repository-base-url`

## [1.3.0] - 2025-08-09

### Added (1.3.0)

- **Type-safe ToolResponse architecture**: Unified response wrapper for all MCP tools with sealed interface pattern
- **Performance optimizations**: Early-exit algorithms with 50-80% performance improvements
- **Enhanced test coverage**: Critical version parsing test scenarios for complex pre-release versions
- **Simplified Context7 orchestration**: Clear step-by-step tool usage instructions with web search fallback

### Changed (1.3.0)

- **BREAKING**: All MCP tool methods now return `ToolResponse` instead of JSON strings for better type safety
- **Context7Guidance model**: Simplified from 5 fields to single `orchestrationInstructions` field for better clarity
- **Library name resolution**: Now uses Maven artifactId directly instead of ecosystem-specific mapping for universal coverage
- **Response format**: Consistent response structure with `ToolResponse.Success<T>` and `ToolResponse.Error`
- **User-Agent Header**: Updated to Maven-Tools-MCP/1.3.0
- **Native image configuration**: Updated reflection hints to include ToolResponse and nested records
- **Algorithm optimizations**: Implemented early-exit optimization in version type classification
- **Stream operations**: Replaced manual loops with optimized stream operations where beneficial
- **Time calculations**: Deduplicated redundant timestamp arithmetic operations

### Fixed (1.3.0)

- **Critical version parsing bug**: Fixed corruption of pre-release versions (e.g., "2.0.0-M1" was becoming "2-milestone-1")
- **Cache configuration**: Resolved type collision issues by using separate cache instances per region
- **SonarQube warnings**: Resolved string literal duplication, cognitive complexity, and primitive null comparison issues

### Removed (1.3.0)

- **Unused code**: Removed duplicate ToolResponseOperation interface and associated error handling method
- **Obsolete dependencies**: Cleaned up JsonResponseService references and inline imports
- **Context7 complexity**: Eliminated redundant guidance fields (suggestedSearch, searchHints, complexity, documentationFocus)
- **Ecosystem-specific logic**: Removed 70+ lines of hardcoded Spring/Hibernate/Jackson library mapping for maintainability

## [1.2.0] - 2025-07-24

### Added (1.2.0)

- **Guided Delegation Architecture**: Context7 guidance hints in Maven tool responses for intelligent LLM orchestration
- `Context7Properties` configuration with `context7.enabled` setting (defaults to true)
- `Context7Guidance` model with smart search suggestions and ecosystem-specific hints
- Context7 guidance integration in response models (when context7.enabled=true):
  - `VersionComparisonResponse` includes migration guidance hints for updates
  - `DependencyAgeResponse` includes modernization guidance for aging/stale dependencies  
  - `ProjectHealthAnalysis` includes upgrade guidance hints for health issues
- Raw Context7 MCP tools automatically exposed via Spring AI MCP client:
  - `resolve-library-id` - Library search and resolution (when context7.enabled=true)
  - `get-library-docs` - Documentation retrieval with topic queries (when context7.enabled=true)
- Enhanced tool descriptions with Context7 guidance references
- Type-safe record models: `ProjectHealthAnalysis`, `VersionsByType` replacing HashMap usage
- `JacksonConfig` with JDK8 and JSR310 module registration for serialization support

### Changed (1.2.0)

- **Simplified Architecture**: Eliminated complex internal Context7 integration (688 lines removed)
- **Tool Consolidation**: Reduced from 10 to 8 tools for better usability:
  - Enhanced `get_latest_version` to replace `get_stable_version` with `preferStable` parameter
  - Enhanced `check_multiple_dependencies` to replace `check_multiple_stable_versions` with `stableOnly` parameter
- **Guided Delegation**: Maven tools now provide Context7 guidance hints instead of internal documentation calls
- **Conditional Context7 Guidance**: Guidance hints only included when `context7.enabled=true` (enabled by default)
- Removed Context7 parameters from Maven tools (includeMigrationGuidance, includeUpgradeStrategy, includeModernizationGuidance)
- Context7 integration enabled by default (context7.enabled=true) with clean responses when disabled
- Updated tool descriptions to reference separate Context7 tool usage for documentation needs

### Fixed (1.2.0)

- Eliminated Context7 data quality issues through guided delegation approach
- Simplified maintenance by removing complex MCP client orchestration
- Enhanced transparency - LLMs can adapt when Context7 returns incorrect results

### Removed (1.2.0)

- **Complex Context7 Integration**: Removed 688 lines of internal Context7 integration code:
  - `DocumentationEnrichmentService` (replaced with guided delegation)
  - `DocumentationEnrichmentProperties` configuration
  - Complex MCP client orchestration and error handling
- **Context7 Tool Parameters**: Removed from Maven tool method signatures:
  - `includeMigrationGuidance` parameter
  - `includeUpgradeStrategy` parameter  
  - `includeModernizationGuidance` parameter
- **Deprecated Tools**: Removed redundant tools to reduce cognitive overload:
  - `get_stable_version` (functionality moved to `get_latest_version` with `preferStable=true`)
  - `check_multiple_stable_versions` (functionality moved to `check_multiple_dependencies` with `stableOnly=true`)

## [1.1.1] - 2025-07-23

### Fixed (1.1.1)

- Native image configuration for analytical intelligence models
- Added reflection hints for all v1.1.0 analytical model classes (DependencyAgeAnalysis, ReleasePatternAnalysis, VersionTimelineAnalysis)
- Proper native image compatibility for new analytical features

## [1.1.0] - 2025-07-23

### Added (1.1.0)

- **Analytical Intelligence Tools**: Four new MCP tools for advanced dependency analysis
  - `analyze_dependency_age` - Classify dependencies as fresh/current/aging/stale with actionable insights
  - `analyze_release_patterns` - Analyze maintenance activity, release velocity, and predict next releases
  - `get_version_timeline` - Enhanced version timeline with temporal analysis and release gap detection
  - `analyze_project_health` - Comprehensive health scoring for multiple dependencies with risk assessment
- **Enhanced MavenCentralService**: Added timestamp-aware methods for age analysis (`getAllVersionsWithTimestamps`, `getRecentVersionsWithTimestamps`)
- **New Model Classes**: Added comprehensive analytical data structures (`DependencyAgeAnalysis`, `ReleasePatternAnalysis`, `VersionTimelineAnalysis`)
- **Virtual Thread Support**: Concurrent bulk analysis for improved performance
- **New Parameters**: Added analytical parameters (`maxAgeInDays`, `monthsToAnalyze`, `versionCount`)

### Changed (1.1.0)

- **Enhanced Tool Descriptions**: Updated existing tool descriptions for better clarity and universal JVM build tool support
- **Improved Documentation**: Updated README.md and CLAUDE.md with analytical intelligence examples and scope decisions
- **User-Agent Header**: Updated to Maven-Tools-MCP/1.1.0

### Performance

- **Bulk Analysis**: Added concurrent processing for multiple dependency health analysis
- **Caching**: Analytical tools leverage existing 24-hour cache infrastructure
- **Memory Optimization**: Efficient data structures for timeline and pattern analysis

## [1.0.0] - 2025-07-23

### Breaking Changes

This major release updates tool names and adds stability parameters while maintaining compatibility with all JVM build tools.

### ⚠️ BREAKING CHANGES

**Tool Renaming for Universal Appeal:**

- `maven_get_latest` → `get_latest_version`
- `maven_get_stable` → `get_stable_version`
- `maven_check_exists` → `check_version_exists`
- `maven_bulk_check_latest` → `check_multiple_dependencies`
- `maven_bulk_check_stable` → `check_multiple_stable_versions`
- `maven_compare_versions` → `compare_dependency_versions`

### Added (1.0.0)

**New Tool Parameters:**

- `preferStable` parameter for `get_latest_version` - prioritizes stable versions in comprehensive analysis
- `stableOnly` parameter for `check_multiple_dependencies` - filters to production-ready versions only
- `onlyStableTargets` parameter for `compare_dependency_versions` - only suggests stable upgrades for production safety

**JVM Build Tool Support:**

- Support for Maven, Gradle, SBT, Mill, and any JVM build tool
- Standard Maven coordinate format for all tools
- Cross-platform examples and documentation

**Stability Controls:**

- Stability preference controls across all tools
- Filtering options for production deployments
- Upgrade safety controls

### Changed (1.0.0)

**Documentation Updates:**

- Application name remains `maven-tools-mcp` for consistency
- Tool descriptions updated for JVM ecosystem support
- Multi-build tool examples and scenarios
- Examples include Kotlin, Scala, Retrofit, Spark dependencies

**README Updates:**

- Build tool support matrix
- Usage examples with stability controls
- Multi-build tool use cases
- Production deployment examples

**Technical:**

- Updated tool method names and parameters
- Version updated to 1.0.0
- Test suite updated for new signatures

## [0.1.3] - 2025-06-30

### Added (0.1.3)

- Comprehensive version info in dependency check results and improved JSON serialization
- Support for multiple builder platforms in Docker configuration (native AMD64 and ARM64 builds)
- Helpful hints and format examples in tool descriptions for better LLM guidance
- Demo GIF and improved documentation for setup and usage

### Changed (0.1.3)

- Refactored and clarified README with detailed command descriptions, examples, and improved user guidance
- Upgraded Spring Boot to 3.5.3
- Internal refactoring for maintainability and clarity
- Improved formatting and readability of tool descriptions

### Fixed (0.1.3)

- Docker build and manifest creation for multi-architecture images
- Build scripts and Docker image configuration for reliability and compatibility
- Response structure for bulk and compare tools

## [0.1.2] - 2025-06-09

### Added (0.1.2)

- Docker support for MCP server deployment with pre-built images and Docker Compose

### Changed (0.1.2)

- Enhanced build tooling and documentation for Docker deployment
- Use maven-artifact for version comparisons

## [0.1.1] - 2025-06-08

### Added (0.1.1)

- Virtual thread support for optimal I/O-bound performance in bulk operations

### Changed (0.1.1)

- Extended cache TTL from 1 hour to 24 hours for optimal performance

### Removed (0.1.1)

- Support for 'snapshot' version type in all tools, API responses, and documentation. Only stable, rc, beta, alpha, and milestone are now supported.
  - Reason: The underlying Maven Central API used for dependency search does not manage or return snapshot versions, so accurate and reliable snapshot support is not possible.
- All code, documentation, and tests for the `maven_analyze_pom` tool (POM file analysis) have been removed.
  - Reason: Full POM analysis is not efficient or LLM-friendly, and is out of scope for this project. Only direct dependency/version tools are supported.

## [0.1.0] - 2025-06-07

### Added (0.1.0)

- Initial release
- MCP tools for Maven dependency management:
  - `maven_get_latest` - Get latest version (stable, rc, beta, alpha, milestone)
  - `maven_check_exists` - Check if version exists
  - `maven_get_stable` - Get latest stable version
  - `maven_bulk_check_latest` - Bulk version checking
  - `maven_bulk_check_stable` - Bulk stable version checking
  - `maven_analyze_pom` - POM file analysis
  - `maven_compare_versions` - Version comparison
- Caching with 1 hour TTL
- Version classification (stable, rc, beta, alpha, milestone)
- Works with Claude Desktop and GitHub Copilot

### Technical (0.1.0)

- Java 24, Spring Boot 3.5.4, Spring AI
- MCP Protocol 2024-11-05
- Unit and integration tests
- Maven Central API integration

[Unreleased]: https://github.com/arvindand/maven-tools-mcp/compare/v2.0.5...HEAD
[2.0.5]: https://github.com/arvindand/maven-tools-mcp/compare/v2.0.4...v2.0.5
[2.0.4]: https://github.com/arvindand/maven-tools-mcp/compare/v2.0.3...v2.0.4
[2.0.3]: https://github.com/arvindand/maven-tools-mcp/compare/v2.0.2...v2.0.3
[2.0.2]: https://github.com/arvindand/maven-tools-mcp/compare/v2.0.1...v2.0.2
[2.0.1]: https://github.com/arvindand/maven-tools-mcp/compare/v2.0.0...v2.0.1
[2.0.0]: https://github.com/arvindand/maven-tools-mcp/compare/v1.5.3...v2.0.0
[1.5.3]: https://github.com/arvindand/maven-tools-mcp/compare/v1.5.2...v1.5.3
[1.5.2]: https://github.com/arvindand/maven-tools-mcp/compare/v1.5.1...v1.5.2
[1.5.1]: https://github.com/arvindand/maven-tools-mcp/compare/v1.5.0...v1.5.1
[1.5.0]: https://github.com/arvindand/maven-tools-mcp/compare/v1.4.0...v1.5.0
[1.4.0]: https://github.com/arvindand/maven-tools-mcp/compare/v1.3.0...v1.4.0
[1.3.0]: https://github.com/arvindand/maven-tools-mcp/compare/v1.2.0...v1.3.0
[1.2.0]: https://github.com/arvindand/maven-tools-mcp/compare/v1.1.1...v1.2.0
[1.1.1]: https://github.com/arvindand/maven-tools-mcp/compare/v1.1.0...v1.1.1
[1.1.0]: https://github.com/arvindand/maven-tools-mcp/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/arvindand/maven-tools-mcp/compare/v0.1.3...v1.0.0
[0.1.3]: https://github.com/arvindand/maven-tools-mcp/compare/v0.1.2...v0.1.3
[0.1.2]: https://github.com/arvindand/maven-tools-mcp/compare/v0.1.1...v0.1.2
[0.1.1]: https://github.com/arvindand/maven-tools-mcp/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/arvindand/maven-tools-mcp/releases/tag/v0.1.0
