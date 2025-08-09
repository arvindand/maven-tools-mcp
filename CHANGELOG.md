# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added (Unreleased)

### Changed (Unreleased)

### Fixed (Unreleased)

### Removed (Unreleased)

## [1.3.0] - TBD

### Added (1.3.0)

- **Type-safe ToolResponse architecture**: Unified response wrapper for all MCP tools with sealed interface pattern
- **Performance optimizations**: Early-exit algorithms with 50-80% performance improvements
- **Enhanced test coverage**: Critical version parsing test scenarios for complex pre-release versions

### Changed (1.3.0)

- **BREAKING**: All MCP tool methods now return `ToolResponse` instead of JSON strings for better type safety
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

[Unreleased]: https://github.com/arvindand/maven-tools-mcp/compare/v1.3.0...HEAD
[1.3.0]: https://github.com/arvindand/maven-tools-mcp/compare/v1.2.0...v1.3.0
[1.2.0]: https://github.com/arvindand/maven-tools-mcp/compare/v1.1.1...v1.2.0
[1.1.1]: https://github.com/arvindand/maven-tools-mcp/compare/v1.1.0...v1.1.1
[1.1.0]: https://github.com/arvindand/maven-tools-mcp/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/arvindand/maven-tools-mcp/compare/v0.1.3...v1.0.0
[0.1.3]: https://github.com/arvindand/maven-tools-mcp/compare/v0.1.2...v0.1.3
[0.1.2]: https://github.com/arvindand/maven-tools-mcp/compare/v0.1.1...v0.1.2
[0.1.1]: https://github.com/arvindand/maven-tools-mcp/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/arvindand/maven-tools-mcp/releases/tag/v0.1.0
