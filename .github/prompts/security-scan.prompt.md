---
name: Security Vulnerability Scan
description: Scan dependencies for known CVEs using OSV.dev integration
---

# Security Vulnerability Scan

Scan project dependencies for known security vulnerabilities using maven-tools-mcp.

## Instructions

1. **Extract Dependencies**
   - Parse the project's build file for all dependencies with versions
   - Include both direct and managed dependencies if possible

2. **Run Security Scan**
   - Use `compare_dependency_versions` with `includeSecurityScan=true`
   - Or use `analyze_project_health` with `includeSecurityScan=true` for bulk analysis

3. **Analyze Results**
   - Review SecuritySummary in the response
   - Check for CRITICAL, HIGH, MEDIUM, LOW severity vulnerabilities
   - Note which dependencies have known CVEs

## Report Format

### Vulnerability Summary

| Severity | Count |
|----------|-------|
| Critical | X     |
| High     | X     |
| Medium   | X     |
| Low      | X     |

### Affected Dependencies

For each vulnerable dependency, list:

- Dependency coordinate and current version
- CVE identifiers
- Severity and CVSS score
- Fixed version (if available)
- Recommended action

### Remediation Plan

1. **Immediate** (Critical/High): Upgrade these first
2. **Soon** (Medium): Plan for next release
3. **Monitor** (Low): Track but lower priority

### Next Steps

- Generate upgrade commands for your build tool
- Test upgrades in development environment
- Review breaking changes for major version bumps
