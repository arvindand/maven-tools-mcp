# Maven Tools Dependency Agent

Automated dependency upgrade agent for Maven projects using Maven Tools MCP directly for deterministic updates and the GitHub Copilot SDK only for major-upgrade review mode.

This agent is part of the [maven-tools-mcp](https://github.com/arvindand/maven-tools-mcp) dogfooding setup — it runs weekly to keep its own dependencies up to date.

## What It Does

1. Hands the raw `pom.xml` to the Maven Tools MCP `recommend_pom_upgrades` tool — the server walks the parent chain, resolves managed versions, and returns a `deterministicActions[]` list plus a `needsAttention[]` list
2. Applies each `deterministicActions[]` entry directly to `pom.xml` (`explicit_bump` and `bom_bump` both collapse to a single `<version>` edit; the parent block is matched by groupId+artifactId)
3. Displays `needsAttention[]` (major upgrades, multi-BOM conflicts, explicit overrides) for visibility — these never auto-apply
4. Uses the Copilot SDK only in `major` mode to surface major updates for manual review
5. Leaves build validation to the repo's normal PR CI

No Python POM parsing — the server is the single source of truth for what "the effective POM" means. PR creation is handled externally by GitHub Actions (`peter-evans/create-pull-request`).

## Usage

### Prerequisites

- Python 3.12+
- `COPILOT_GITHUB_TOKEN` (or `GH_TOKEN` / `GITHUB_TOKEN`) environment variable with Copilot Requests permission, only for `--mode major`
- Running Maven Tools MCP server (HTTP transport recommended for CI)

### Install

```bash
pip install .
```

### Run

```bash
# Dry run - analyze only, no changes
maven-agent -f pom.xml --dry-run

# Apply minor/patch updates using HTTP transport (CI mode)
maven-agent -f pom.xml --http --mcp-url http://localhost:8080/mcp

# Report only - use Copilot SDK + MCP for major upgrade review
maven-agent -f pom.xml --mode major --dry-run
```

### CLI Options

| Option | Default | Description |
|--------|---------|-------------|
| `-f, --pom-file` | `pom.xml` | Path to POM file |
| `-m, --mode` | `minor_patch` | `minor_patch` (direct MCP auto-apply, server mode `MINOR_PATCH`), `major` (Copilot SDK report only), `all` (direct MCP auto-apply, server mode `ALL` — majors treated as deterministic) |
| `--dry-run` | false | Analyze only, no changes |
| `--http` | false | Use HTTP transport (connect to running MCP server) |
| `--mcp-url` | `http://localhost:8080/mcp` | MCP server URL for HTTP transport |
| `-v, --verbose` | false | Enable verbose logging |

## How It's Used in CI

The weekly self-update workflow (`.github/workflows/dependency-agent-self-update.yml`):

1. Starts the Maven Tools MCP HTTP sidecar (`arvindand/maven-tools-mcp:latest-http`)
2. Runs this agent against the root `pom.xml` with `--http --mode minor_patch`
3. If `pom.xml` changed, creates or updates a persistent PR branch via `peter-evans/create-pull-request`
4. CI validates the build on the PR (unit tests + package)

Manual `major` mode runs still start the same MCP sidecar, but route the analysis through the Copilot SDK. That keeps routine minor/patch updates deterministic while reserving model judgement for major-version migration context.

Required secret: `COPILOT_BOT_PAT` — a fine-grained PAT with:

- Repository contents: write
- Pull requests: write
- Copilot Requests permission, used by the dependency agent only in `major` mode

## Running Tests

```bash
pip install ".[dev]"
pytest
```

## Architecture

```text
src/
  analysis/dependency.py   - POM parsing and data models
  mcp/direct_client.py     - Direct MCP client for deterministic tool calls
  copilot/sdk_client.py    - GitHub Copilot SDK client for major-review mode
scripts/
  upgrade.py               - Main CLI and upgrade workflow
tests/
  test_analysis.py         - Unit tests for POM parsing
```
