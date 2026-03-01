---
description: Get upgrade recommendations for current dependencies with versions
argument-hint: [groupId:artifactId:version, ...]
---
Analyze dependencies for upgrade recommendations using the maven-tools-mcp MCP server.

**Dependencies to check (with current versions):** $ARGUMENTS

If no dependencies are specified, extract them with versions from the project's build file.

**Analysis:**

Use `compare_dependency_versions` with:

- `includeSecurityScan: true` - Include CVE vulnerability check
- `stabilityFilter: STABLE_ONLY` - Only recommend stable version upgrades

**Report Format:**

| Dependency | Current | Recommended Now | Recommended Type | Latest Stable | Security |
|------------|---------|-----------------|------------------|---------------|----------|

For each dependency, show:

- Current version vs recommended immediate target
- Recommended type from `update_type`, or from `same_major_stable_fallback.update_type` when a fallback is present
- Latest stable version separately using the top-level `latest_version` when it differs from the immediate recommendation
- If `same_major_stable_fallback` is present, use `same_major_stable_fallback.latest_version` as the recommended safe-now target and keep the top-level major path as manual review
- Security status: vulnerabilities fixed in newer versions

**Upgrade Priority:**

1. **Critical** - Security vulnerabilities requiring action now
2. **High** - Safe same-major fallback, minor, or patch updates available
3. **Medium** - Major upgrades requiring planning and migration work
4. **Low** - Already on latest or near-latest

For major updates, keep the breaking-change path separate from the immediate recommendation. If documentation lookup is needed, include Context7 migration guidance hints if available.
