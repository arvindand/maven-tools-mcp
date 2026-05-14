# Tools

Maven Tools MCP exposes 11 MCP tools: 9 core Maven/dependency tools and 2 raw Context7 documentation tools.

## Core Maven Intelligence Tools

| Tool | Purpose | Typical Use |
|------|---------|-------------|
| `get_latest_version` | Return the newest version with stability-aware selection | "What is the latest stable version of X?" |
| `check_version_exists` | Verify a specific version and classify its type | "Does 3.5.11 exist, and is it stable?" |
| `check_multiple_dependencies` | Bulk lookup for many coordinates without current versions | "Check these candidate dependencies before I add them" |
| `compare_dependency_versions` | Compare current versions against available upgrades | "Which dependencies in this pom can be upgraded?" |
| `analyze_dependency_age` | Classify freshness and maintenance age | "Is this dependency getting stale?" |
| `analyze_release_patterns` | Look at release cadence and maintenance behavior | "Does this library still look actively maintained?" |
| `analyze_project_health` | Run a broader audit across a dependency set | "Give me a health overview for this project" |
| `analyze_pom_dependencies` | Resolve a whole POM into per-dep effective versions + classification | "What versions does my pom.xml actually resolve to, and which ones are BOM-managed?" |
| `recommend_pom_upgrades` | Build on the resolver to produce a deterministic action list + a human-review list | "What can I safely bump in my pom.xml?" |

## POM-Aware Analysis

`analyze_pom_dependencies` is the only tool that takes a whole POM (raw XML) rather than a coordinate. It walks the parent chain, applies `<properties>` interpolation (including `${project.version}` / `${project.parent.version}`), merges `<dependencyManagement>` with closest-ancestor-wins semantics, and resolves `<scope>import</scope>` BOMs against Maven Central. For each declared dependency it returns:

- `effectiveVersion` — what would be used at build time
- `source` — `EXPLICIT` (declared inline with a version), `MANAGED` (no version here, inherited), or `EXPLICIT_OVERRIDE` (declared AND inherited)
- `managedBy` — which BOM or parent supplied the version, when applicable
- `conflicts[]` — losing candidates when multiple BOMs at the same level disagree (e.g., Spring Boot + Spring Cloud + Jackson BOM all managing `jackson-databind`). Surfaced as raw data so the caller can decide whether to pin the version explicitly; the resolver does not recommend an action.

The tool also returns the resolved `parentChain` and a `warnings[]` array listing every silent-drop site (unreachable parents, unresolvable property placeholders, depth-cap exhaustion, failed BOM fetches).

For multi-module / monorepo projects, pass an optional `sideloadedPoms: string[]` of additional POM XML strings (sibling modules, unreleased parents). The resolver indexes each by its self-declared GAV and tries the bundle before falling back to Maven Central — so a child whose parent is not yet published still resolves cleanly.

### Why this matters for upgrades

The classification is the upgrade policy:

- **`EXPLICIT`** + a newer same-major minor/patch on Maven Central → bump the version inline.
- **`MANAGED`** + a newer same-major minor/patch of the *managing BOM* that ships a newer version of this dep → bump the **BOM**, not the dep. One BOM bump can pick up dozens of patch updates for free.
- **`EXPLICIT_OVERRIDE`** → judgement call. The override exists for a reason (security pin, framework workaround, etc.). The tool surfaces every candidate version the override is choosing against, including from competing BOMs — useful context for a human or LLM reviewing the override.

`recommend_pom_upgrades` applies this policy and returns a split response so the right consumer reads the right part:

- **`deterministic_actions[]`** — mechanical `<version>` edits a non-LLM agent applies directly. Each entry has `kind` (`explicit_bump` or `bom_bump`), `groupId`, `artifactId`, `current`, `target`, `updateType`. The agent's loop is `for action in deterministic_actions: edit_pom(action)` — no MCP follow-up calls, no Maven XML parsing in Python.
- **`needs_attention[]`** — items that need judgment. `kind: "major_available"` for majors (with `currentMajorLatest` so the model can choose to stay same-major); `kind: "conflict"` when two BOMs disagree (with every `candidate` version + `latestOnCentral`); `kind: "explicit_override"` (with `managingCandidates` + `latestOnCentral`). Every entry carries the Maven Central latest so the LLM has full context in one round-trip — no follow-up `compare_dependency_versions` calls needed.

Use `mode: MINOR_PATCH` (default) to keep majors in the review lane, or `mode: ALL` to treat majors as deterministic too (rarely the right call).

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
