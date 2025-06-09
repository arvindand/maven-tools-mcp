# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.1.2] - 2025-06-09

### Added in 0.1.2

- Docker support for MCP server deployment with pre-built images and Docker Compose

### Changed in 0.1.2

- Enhanced build tooling and documentation for Docker deployment
- Use maven-artifact for version comparisons

## [0.1.1] - 2025-06-08

### Added in 0.1.1

- Virtual thread support for optimal I/O-bound performance in bulk operations

### Changed in 0.1.1

- Extended cache TTL from 1 hour to 24 hours for optimal performance

### Removed in 0.1.1

- Support for 'snapshot' version type in all tools, API responses, and documentation. Only stable, rc, beta, alpha, and milestone are now supported.
  - Reason: The underlying Maven Central API used for dependency search does not manage or return snapshot versions, so accurate and reliable snapshot support is not possible.
- All code, documentation, and tests for the `maven_analyze_pom` tool (POM file analysis) have been removed.
  - Reason: Full POM analysis is not efficient or LLM-friendly, and is out of scope for this project. Only direct dependency/version tools are supported.

## [0.1.0] - 2025-06-07

### Added in 0.1.0

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

### Technical in 0.1.0

- Java 24, Spring Boot 3.5.0, Spring AI
- MCP Protocol 2024-11-05
- Unit and integration tests
- Maven Central API integration

[Unreleased]: https://github.com/arvindand/maven-tools-mcp/compare/v0.1.2...HEAD
[0.1.2]: https://github.com/arvindand/maven-tools-mcp/compare/v0.1.1...v0.1.2
[0.1.1]: https://github.com/arvindand/maven-tools-mcp/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/arvindand/maven-tools-mcp/releases/tag/v0.1.0
