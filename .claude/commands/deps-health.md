---
description: Comprehensive dependency health audit with security and license info
argument-hint: [groupId:artifactId, ...]
---
Perform a full dependency health analysis using the maven-tools-mcp MCP server.

**Dependencies to analyze:** $ARGUMENTS

If no dependencies are specified, extract them from the project's build file (`pom.xml` or `build.gradle`).

**Analysis Steps:**

1. Call `analyze_project_health` with:
   - `includeSecurityScan: true` - Check for CVE vulnerabilities via OSV.dev
   - `includeLicenseScan: true` - Detect license types and compliance issues
   - `stabilityFilter: PREFER_STABLE` - Focus on production-ready versions

2. Summarize findings in these sections:

**Health Score Overview:**

- Overall project health score (0-100)
- Dependency age breakdown (fresh/current/aging/stale counts)

**Security Findings:**

- Total vulnerabilities found
- Critical/High severity issues requiring immediate action
- Dependencies with known CVEs

**License Compliance:**

- License type distribution (permissive/copyleft/unknown)
- Any licenses requiring legal review

**Recommendations:**

- Prioritized list of dependencies to upgrade
- Context7 documentation hints for major upgrades if available
