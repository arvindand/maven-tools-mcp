# Dogfooding

Maven Tools MCP runs its own dependency agent against this repository on a schedule. The goal is intentionally modest: safe, reviewable dependency upkeep rather than full autonomous upgrades.

## What The Workflow Does

The workflow is [`dependency-agent-self-update.yml`](../.github/workflows/dependency-agent-self-update.yml).

On each scheduled or manual run it:

1. Starts the Maven Tools MCP HTTP sidecar with `arvindand/maven-tools-mcp:latest-http`
2. Runs the local Python agent in [`agents/copilot-maven-tools-agent/`](../agents/copilot-maven-tools-agent/)
3. Hands the raw `pom.xml` to the `recommend_pom_upgrades` MCP tool and applies the returned `deterministicActions[]` directly
4. Ignores dependencies that are intentionally pinned for temporary operational reasons
5. Creates or updates a persistent PR branch: `bot/dependency-agent-self-update`
6. Relies on normal repository CI to validate the resulting PR

Major updates, multi-BOM conflicts, and explicit overrides land in `needsAttention[]` and are not auto-applied. Manual `major` mode runs route the analysis through the Copilot SDK so the model can frame migration risk and review context without touching the deterministic weekly update path.

## Agent Layout

The dependency agent lives in [`agents/copilot-maven-tools-agent/`](../agents/copilot-maven-tools-agent/).

It is a local Python subproject that:

- reads the repository `pom.xml` and hands it to the `recommend_pom_upgrades` MCP tool
- applies the returned `deterministicActions[]` (mechanical `<version>` edits) directly
- displays `needsAttention[]` (majors, conflicts, explicit overrides) for visibility
- uses the Copilot SDK only in major-review mode
- leaves PR creation to GitHub Actions

That split keeps the agent focused on applying server-decided edits while GitHub Actions handles branch and PR mechanics. There is no Python POM parsing — the server is the single source of truth for what "the effective POM" means.

## Deterministic And Major Modes

Routine `minor_patch` runs use a direct MCP JSON-RPC client. The workflow sends one `recommend_pom_upgrades` call with the raw POM XML, applies every entry in `deterministicActions[]`, and surfaces `needsAttention[]` for the PR description. There is no model session in that path and no per-coordinate fan-out.

`major` mode is report-only and uses the GitHub Copilot SDK with Maven Tools MCP attached. That keeps model judgement available for breaking-change context, migration planning, and ambiguous ecosystem decisions while making sure the weekly self-update PR remains deterministic.

This gives the project direct control over:

- MCP client lifecycle for deterministic updates
- prompt/tool orchestration only where it adds value
- output parsing and version filtering
- portability to other AI providers later if needed

The result is a small reference implementation for tool-first dependency automation, with an AI session reserved for major-upgrade judgement rather than routine version bumps.

## Parallel To GitHub Agentic Workflows

The shape is similar to GitHub Agentic Workflows:

- GitHub Actions orchestrates the run
- a bounded tool client performs deterministic minor/patch updates
- an AI client is reserved for report-only major-upgrade review
- the result is a reviewable PR

The difference is that this repository uses a hand-authored workflow plus a purpose-built Python agent rather than a compiled workflow format.

That makes this workflow a practical precursor, not just a comparison point. It already demonstrates the core pattern in a narrow, useful domain: structured dependency intelligence feeding a bounded tool task inside CI, with model judgement available only for major-upgrade planning and the output constrained to a reviewable pull request. In that sense, the dogfooding setup is an example of how a larger GitHub Agentic Workflows-style future can be approached incrementally with today's tooling.

## Required Setup

To use the same workflow in your fork or another repository:

1. Create a fine-grained PAT named `COPILOT_BOT_PAT`
2. Grant these repository permissions:
   - Contents: write
   - Pull requests: write
   - Metadata: read
3. Grant GitHub Copilot "Requests" permission if you want manual `major` mode reports
4. Add the token as a repository secret in **Settings -> Secrets and variables -> Actions**

## Manual Trigger

### GitHub UI

Open **Actions -> Dependency Agent Self-Update -> Run workflow**.

### GitHub CLI

```bash
gh workflow run dependency-agent-self-update.yml
```

Dry run (analyze only, no PR):

```bash
gh workflow run dependency-agent-self-update.yml -f dry_run=true
```

Major review mode:

```bash
gh workflow run dependency-agent-self-update.yml -f mode=major -f dry_run=true
```

## Operational Notes

- `CONTEXT7_API_KEY` is optional for the HTTP sidecar image
- scheduled runs default to `minor_patch` mode and do not require a Copilot SDK session
- manual `major` runs are dry-run/report-only by convention and require Copilot Requests permission
- the workflow keeps a single persistent bot branch instead of creating a new branch every run
- repository CI remains the source of truth for build verification
- temporary dependency overrides should be added to the workflow ignore list when they should not be auto-updated

## Related Docs

- [`setup.md`](setup.md)
- [`tools.md`](tools.md)
- [`examples.md`](examples.md)
