# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.1-SNAPSHOT]

### Removed

- Support for 'snapshot' version type in all tools, API responses, and documentation. Only stable, rc, beta, alpha, and milestone are now supported.
  - Reason: The underlying Maven Central API used for dependency search does not manage or return snapshot versions, so accurate and reliable snapshot support is not possible.
- All code, documentation, and tests for the `maven_analyze_pom` tool (POM file analysis) have been removed.
  - Reason: Full POM analysis is not efficient or LLM-friendly, and is out of scope for this project. Only direct dependency/version tools are supported.

- Work in progress for next release.

## [0.1.0] - 2025-06-07

### Added

- Initial release
- MCP tools for Maven dependency management:
  - `maven_get_latest` - Get latest version (stable, rc, beta, alpha, milestone)
  - `maven_check_exists` - Check if version exists
  - `maven_get_stable` - Get latest stable version
  - `maven_bulk_check_latest` - Bulk version checking
  - `maven_bulk_check_stable` - Bulk stable version checking
  - `maven_analyze_pom` - POM file analysis
  - `maven_compare_versions` - Version comparison
- Caching with 5-minute TTL
- Version classification (stable, rc, beta, alpha, milestone)
- Works with Claude Desktop and GitHub Copilot

### Technical

- Java 24, Spring Boot 3.5.0, Spring AI
- MCP Protocol 2024-11-05
- Unit and integration tests
- Maven Central API integration

[0.1.1-SNAPSHOT]: https://github.com/arvindand/maven-tools-mcp/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/arvindand/maven-tools-mcp/releases/tag/v0.1.0
