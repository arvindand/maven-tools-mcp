# Tools

Maven Tools MCP exposes 10 MCP tools: 8 core Maven/dependency tools and 2 raw Context7 documentation tools.

## Core Maven Intelligence Tools

| Tool | Purpose | Typical Use |
|------|---------|-------------|
| `get_latest_version` | Return the newest version with stability-aware selection | "What is the latest stable version of X?" |
| `check_version_exists` | Verify a specific version and classify its type | "Does 3.5.11 exist, and is it stable?" |
| `check_multiple_dependencies` | Bulk lookup for many coordinates without current versions | "Check these candidate dependencies before I add them" |
| `compare_dependency_versions` | Compare current versions against available upgrades | "Which dependencies in this pom can be upgraded?" |
| `analyze_dependency_age` | Classify freshness and maintenance age | "Is this dependency getting stale?" |
| `analyze_release_patterns` | Look at release cadence and maintenance behavior | "Does this library still look actively maintained?" |
| `get_version_timeline` | Return recent versions with timing signals | "Show me the recent release history" |
| `analyze_project_health` | Run a broader audit across a dependency set | "Give me a health overview for this project" |

## Raw Context7 Documentation Tools

| Tool | Purpose | Notes |
|------|---------|-------|
| `resolve-library-id` | Find a Context7 documentation library identifier | Used before `query-docs` |
| `query-docs` | Fetch documentation and examples by library ID | Works best when the client asks focused questions |

Context7 is enabled in the default images. If your environment blocks it, use `:latest-noc7`.

## Common Parameters

### Stability filter

Several tools support `stabilityFilter`:

- `ALL` - include all version types
- `STABLE_ONLY` - only production-ready releases
- `PREFER_STABLE` - prefer stable releases while still considering others

`PREFER_STABLE` is the most user-friendly default for simple lookups. `STABLE_ONLY` is best when you are preparing changes that should stay conservative.

Version responses are classified using the server's built-in stability types:

- `stable`
- `rc`
- `beta`
- `alpha`
- `milestone`
- `snapshot`

### Analytical parameters

- `maxAgeInDays` - adjust how aggressively age checks classify a dependency as old
- `monthsToAnalyze` - change the release-pattern lookback window
- `versionCount` - choose how many recent versions to inspect in timeline analysis

### Security and license parameters

- `includeSecurityScan` - include OSV.dev vulnerability scanning (default: `true`)
- `includeLicenseScan` - include license detection and analysis (default: `true`)

These are most useful in `compare_dependency_versions` and `analyze_project_health` when you want a fuller upgrade or compliance picture.

## Response Behavior

The tools are designed for conversational clients and agents:

- responses are structured JSON rather than scraped HTML
- version stability is classified directly
- upgrade results include major/minor/patch context
- some responses include guidance about when to consult Context7 docs

The project aims to keep the server focused on dependency intelligence so the client can make decisions with clean inputs instead of custom parsing.

That boundary matters for recommendation-style questions. Maven Tools MCP can ground the client with current dependency facts, but it is the calling model that decides how to weigh tradeoffs, compare alternatives, and combine these results with documentation or broader research.

For the strongest results on library-choice questions:

- use Maven Tools MCP for current versions, stability, and upgrade context
- use the exposed Context7 tools for library documentation when available
- use client-side web search, if your MCP client supports it, for context that is not in Maven metadata or Context7

## Practical Guidance

### Good uses

- checking what changed since the version you currently run
- deciding whether an update is low-risk or should wait for a planned upgrade window
- auditing a project for stale or weakly maintained dependencies
- feeding an AI assistant current dependency data before it edits code

### Less helpful uses

- trivial one-off lookups when you already know the exact dependency and version you want
- non-JVM ecosystems that do not rely on Maven coordinates
- private artifact repositories that are not mirrored through Maven Central

## Related Docs

- [`examples.md`](examples.md)
- [`setup.md`](setup.md)
- [`dogfooding.md`](dogfooding.md)
