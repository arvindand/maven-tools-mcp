# Dogfooding

Maven Tools MCP runs its own dependency agent against this repository on a schedule. The goal is intentionally modest: safe, reviewable dependency upkeep rather than full autonomous upgrades.

## What The Workflow Does

The workflow is [`dependency-agent-self-update.yml`](../.github/workflows/dependency-agent-self-update.yml).

On each scheduled or manual run it:

1. Starts the Maven Tools MCP HTTP sidecar with `arvindand/maven-tools-mcp:latest-http`
2. Runs the local Python agent in [`agents/copilot-maven-tools-agent/`](../agents/copilot-maven-tools-agent/)
3. Applies stable minor and patch updates to the root `pom.xml`
4. Ignores dependencies that are intentionally pinned for temporary operational reasons
5. Creates or updates a persistent PR branch: `bot/dependency-agent-self-update`
6. Relies on normal repository CI to validate the resulting PR

Major updates are reported in the workflow logs but are not auto-applied.

## Agent Layout

The dependency agent lives in [`agents/copilot-maven-tools-agent/`](../agents/copilot-maven-tools-agent/).

It is a local Python subproject that:

- parses the repository `pom.xml`
- calls Maven Tools MCP for dependency intelligence
- applies safe updates
- leaves PR creation to GitHub Actions

That split keeps the agent focused on dependency selection and file changes while GitHub Actions handles branch and PR mechanics.

## Why Use The Copilot SDK

The agent uses the GitHub Copilot SDK directly rather than a higher-level Copilot agent wrapper.

That gives the project direct control over:

- MCP client lifecycle
- prompt/tool orchestration
- output parsing
- portability to other AI providers later if needed

The result is a small reference implementation for SDK-driven dependency automation rather than a framework-heavy workflow.

## Parallel To GitHub Agentic Workflows

The shape is similar to GitHub Agentic Workflows:

- GitHub Actions orchestrates the run
- an AI client performs a bounded task
- the result is a reviewable PR

The difference is that this repository uses a hand-authored workflow plus a purpose-built Python agent rather than a compiled workflow format.

That makes this workflow a practical precursor, not just a comparison point. It already demonstrates the core pattern in a narrow, useful domain: structured dependency intelligence feeding a bounded agent task inside CI, with the output constrained to a reviewable pull request. In that sense, the dogfooding setup is an example of how a larger GitHub Agentic Workflows-style future can be approached incrementally with today's tooling.

## Required Setup

To use the same workflow in your fork or another repository:

1. Create a fine-grained PAT named `COPILOT_BOT_PAT`
2. Grant these repository permissions:
   - Contents: write
   - Pull requests: write
   - Metadata: read
3. Grant GitHub Copilot "Requests" permission
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

## Operational Notes

- `CONTEXT7_API_KEY` is optional for the HTTP sidecar image
- the workflow keeps a single persistent bot branch instead of creating a new branch every run
- repository CI remains the source of truth for build verification
- temporary dependency overrides should be added to the workflow ignore list when they should not be auto-updated

## Related Docs

- [`setup.md`](setup.md)
- [`tools.md`](tools.md)
- [`examples.md`](examples.md)
