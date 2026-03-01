---
name: Dependency Audit
description: Comprehensive dependency audit with health scoring, security, and license analysis
---

# Dependency Audit

Perform a comprehensive dependency audit for this project using the maven-tools-mcp MCP server.

## Instructions

1. **Identify Dependencies**
   - Scan `pom.xml` (Maven) or `build.gradle`/`build.gradle.kts` (Gradle)
   - Extract all dependency coordinates (groupId:artifactId format)

2. **Health Analysis**
   - Use `analyze_project_health` with `includeSecurityScan=true` and `includeLicenseScan=true`
   - Assess overall project health score
   - Identify stale or aging dependencies

3. **Version Check**
   - Use `compare_dependency_versions` with `includeSecurityScan=true` and `stabilityFilter=STABLE_ONLY`
   - Classify updates as safe-now (patch/minor/same-major fallback) vs manual-review (major)
   - If `same_major_stable_fallback` is present, treat it as the conservative near-term target and keep the major path separate

4. **Security Scan**
   - Review CVE vulnerabilities from OSV.dev integration
   - Flag critical and high severity issues

5. **License Review**
   - Check license compliance (permissive vs copyleft)
   - Identify any licenses requiring legal review

## Report Format

### Executive Summary

- Health score: X/100
- Risk level: [low/medium/high/critical]
- Total dependencies: X
- Updates available: X

### Immediate Attention Required

List dependencies with security vulnerabilities or end-of-life status.

### Recommended Upgrades

Prioritized list of upgrades sorted by risk and importance.

- Separate safe-now upgrades from major migration paths
- If a same-major fallback exists, show it as the immediate recommendation and keep the major target as a later option

### License Compliance

Summary of license types and any compliance concerns.
