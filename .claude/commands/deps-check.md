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

| Dependency | Latest Stable | Latest Any | Version Type |
|------------|---------------|------------|--------------|

Include a summary of how many dependencies are up-to-date vs have updates available.
