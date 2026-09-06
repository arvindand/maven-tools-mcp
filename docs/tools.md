# Tools

The default image exposes 11 MCP tools: 9 core Maven/dependency tools and 2 Context7 documentation tools. The `-noc7` image exposes only the 9 core tools.

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

`analyze_pom_dependencies` takes a whole POM (raw XML) rather than a coordinate (the same applies to `recommend_pom_upgrades`). It uses Apache Maven Model Builder to walk the parent chain, apply `<properties>` interpolation (including `${project.version}` / `${project.parent.version}`, scoped per-POM so an imported BOM's `${project.version}` resolves to that BOM's version, not the importer's), merge `<dependencyManagement>` with Maven precedence, and resolve `<scope>import</scope>` BOMs against Maven Central. Direct dependency-management entries override imports; otherwise the first applicable imported BOM wins. Both `project.*` and ordinary properties inside an imported BOM use that BOM's model, so defining a same-named property in the importer does not rewrite the BOM. For each declared dependency it returns:

- `effectiveVersion` — the resolved version in the supported effective-model context
- `source` — `EXPLICIT` (declared inline with a version), `MANAGED` (no version here, inherited), or `EXPLICIT_OVERRIDE` (declared AND inherited)
- `managedBy` — which BOM or parent supplied the version, when applicable
- `directlyEditable` — whether there is a unique root declaration eligible for a mechanical version edit
- `conflicts[]` — losing candidates when multiple BOMs at the same level disagree (e.g., Spring Boot + Spring Cloud + Jackson BOM all managing `jackson-databind`). Surfaced as raw data so the caller can decide whether to pin the version explicitly; the resolver does not recommend an action.

The tool also returns the resolved `parentChain`, direct `rootImportedBoms[]`, directly editable `rootManagedDeclarations[]`, editable `rootPluginDependencyDeclarations[]` from both build plugins and plugin management, and a `warnings[]` array listing every silent-drop site. An owned declaration appears only when its version is literal or is an exact property reference owned by the input POM.

Analysis covers declarations in the input POM, not a transitive dependency graph. Active-by-default profile dependencies can appear in analysis, but profile-only declarations are not mechanically editable. Other activation conditions are not evaluated; warnings can originate from parents and imported BOMs. See [architecture limits](architecture.md#caches-and-input-limits).

For multi-module / monorepo projects, pass an optional `sideloadedPoms: string[]` of additional POM XML strings (sibling modules, unreleased parents). The resolver indexes each by its self-declared GAV and tries the bundle before falling back to Maven Central — so a child whose parent is not yet published still resolves cleanly.

### Why this matters for upgrades

The classification is the upgrade policy:

- **`EXPLICIT`** + a newer same-major minor/patch on Maven Central → bump the version inline.
- **`MANAGED`** + the *user-controllable* managing BOM has a newer same-major minor/patch on Maven Central → bump the **BOM**, not the dep. One BOM bump can pick up dozens of patch updates for free.
- **`EXPLICIT_OVERRIDE`** → judgement call. The override exists for a reason (security pin, framework workaround, etc.). The tool surfaces every candidate version the override is choosing against, including from competing BOMs — useful context for a human or LLM reviewing the override.

"User-controllable" means the BOM appears directly in the input POM — either as the `<parent>` or as an entry in the root POM's `<dependencyManagement>` imports. Transitively-imported BOMs (e.g., `jackson-bom` inherited through `spring-boot-dependencies`) are silently skipped because the caller has no `<version>` to edit; their upgrades surface via whichever user-controllable knob pulls them in.

`recommend_pom_upgrades` applies this policy and returns a split response so the right consumer reads the right part:

- **`deterministicActions[]`** — mechanical edits a non-LLM agent applies directly. Each entry has `kind` (`explicit_bump`, `bom_bump`, `managed_decl_bump`, or `plugin_dep_bump`), `groupId`, `artifactId`, `current`, `target`, and `updateType`. Owned declarations carry `editTarget`, optional `propertyName`, and `declaredIn`; plugin dependency actions also carry `ownerGroupId` and `ownerArtifactId`.
- **`needsAttention[]`** — items that need judgment. `kind: "major_available"` for majors (with `currentMajorLatest` so the model can choose to stay same-major); `kind: "conflict"` when two BOMs disagree (with every `candidate` version + `latestOnCentral`); `kind: "explicit_override"` (with `managingCandidates` + `latestOnCentral`). Major entries use `latestStable`; conflicts and explicit overrides use `latestOnCentral`.

Use `mode: MINOR_PATCH` (default) to keep majors in the review lane, or `mode: ALL` to treat majors as deterministic too (rarely the right call).

## Raw Context7 Documentation Tools

| Tool | Purpose | Notes |
|------|---------|-------|
| `resolve_library_id` | Find a Context7 documentation library identifier | Used before `query_docs` |
| `query_docs` | Fetch documentation and examples by library ID | Works best when the client asks focused questions |

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
- `monthsToAnalyze` - reported analysis period for release-pattern responses (default: 24 months). In 3.2.2 the sample is the ten most recent versions; it is not filtered to that period.

There is no `get_version_timeline` tool or `versionCount` parameter. Use `recentReleases` from `analyze_release_patterns` for the available version/date history.

### Security and license parameters

- `includeSecurityScan` - include OSV.dev vulnerability scanning in `compare_dependency_versions` and `analyze_project_health` (default: `true`)
- `includeLicenseScan` - include license detection and analysis in `analyze_project_health` (default: `true`)

These are most useful in `compare_dependency_versions` and `analyze_project_health` when you want a fuller upgrade or compliance picture.

## Response Behavior

The tools are designed for conversational clients and agents:

- core tool responses use a JSON envelope: `{"status":"success","data":...}` or an application-error envelope with `status` and `error`
- inspect the application `status` as well as MCP `isError`; an MCP call can succeed while its payload reports an input or upstream error
- POM response fields use camelCase (`deterministicActions`, `needsAttention`, `effectiveVersion`); other analytical records may use snake_case, so follow the actual tool schema/payload
- version stability is classified directly
- upgrade results include major/minor/patch context
- some responses include guidance about when to consult Context7 docs

Security results distinguish a successful scan from `UNKNOWN` when upstream data is unavailable or incomplete. CVSS v2/v3/v4 vectors are scored with a maintained library. Recommended remediation versions are checked against OSV; a successful scan is an advisory snapshot, not a guarantee that a dependency is vulnerability-free.

In 3.2.2, `analyze_project_health` has a known field mismatch: `analysis_date` contains an overall health label (for example, `poor`), not a timestamp. There is no top-level `overall_health` or `average_health_score`; use the per-dependency `healthScore` values and `age_distribution`, and do not parse `analysis_date` as a date.

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
- arbitrary private repositories beyond the one configured endpoint; see [private repository setup](setup.md#private-repository-authentication)

## Related Docs

- [`examples.md`](examples.md)
- [`setup.md`](setup.md)
- [`dogfooding.md`](dogfooding.md)
