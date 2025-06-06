# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.1.0] - 2025-06-07

### Added

- Initial release
- MCP tools for Maven dependency management:
  - `maven_get_latest` - Get latest version
  - `maven_check_exists` - Check if version exists
  - `maven_get_stable` - Get latest stable version
  - `maven_bulk_check_latest` - Bulk version checking
  - `maven_bulk_check_stable` - Bulk stable version checking
  - `maven_analyze_pom` - POM file analysis
  - `maven_compare_versions` - Version comparison
- Caching with 5-minute TTL
- Version classification (stable, rc, beta, alpha, milestone, snapshot)
- Works with Claude Desktop and GitHub Copilot

### Technical

- Java 24, Spring Boot 3.5.0, Spring AI
- MCP Protocol 2024-11-05
- Unit and integration tests
- Maven Central API integration

[Unreleased]: https://github.com/arvindand/maven-tools-mcp/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/arvindand/maven-tools-mcp/releases/tag/v0.1.0
