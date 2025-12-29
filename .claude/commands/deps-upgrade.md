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

| Dependency | Current | Latest | Update Type | Security |
|------------|---------|--------|-------------|----------|

For each dependency, show:

- Current version vs latest available
- Update type: major/minor/patch
- Security status: vulnerabilities fixed in newer versions

**Upgrade Priority:**

1. **Critical** - Security vulnerabilities or end-of-life versions
2. **High** - Major version behind with breaking changes
3. **Medium** - Minor/patch updates available
4. **Low** - Already on latest or near-latest

For major updates, include Context7 migration guidance hints if available.
