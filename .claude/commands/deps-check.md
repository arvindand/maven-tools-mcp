---
description: Check latest versions for Maven/Gradle dependencies
argument-hint: [groupId:artifactId, ...]
---
Use the maven-tools-mcp MCP server to check latest versions for dependencies.

**Dependencies to check:** $ARGUMENTS

If no dependencies are specified, scan the current project for:

- `pom.xml` (Maven projects)
- `build.gradle` or `build.gradle.kts` (Gradle projects)

Extract all dependency coordinates (groupId:artifactId format) and use the `check_multiple_dependencies` tool with `stabilityFilter=PREFER_STABLE`.

**Format the response as a table:**

| Dependency | Recommended Version | Recommended Type | Notes |
|------------|---------------------|------------------|-------|

For each dependency:

- show the tool's primary recommended version (`version`) and type
- if `latest_stable.version` differs from the primary recommendation, mention it in the notes
- if there is a notable prerelease (`latest_rc.version`, `latest_beta.version`, `latest_alpha.version`, or `latest_milestone.version`), mention it only when relevant
- include `not_found` or `error` status clearly instead of forcing a normal version row

Include a summary of how many dependencies are current, how many have updates available, and how many could not be resolved.
