# Maven Tools MCP Server

[![Java](https://img.shields.io/badge/Java-24-orange.svg)](https://openjdk.java.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.11-green.svg)](https://spring.io/projects/spring-boot)
[![MCP Protocol](https://img.shields.io/badge/MCP-2025--06--18-blue.svg)](https://modelcontextprotocol.io/)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![GitHub release (latest by date)](https://img.shields.io/github/v/release/arvindand/maven-tools-mcp)](https://github.com/arvindand/maven-tools-mcp/releases)
[![Docker](https://img.shields.io/badge/Docker-Multi--Arch-blue.svg)](https://hub.docker.com/r/arvindand/maven-tools-mcp)
[![Docker Pulls](https://img.shields.io/docker/pulls/arvindand/maven-tools-mcp)](https://hub.docker.com/r/arvindand/maven-tools-mcp)
[![GitHub stars](https://img.shields.io/github/stars/arvindand/maven-tools-mcp?style=social)](https://github.com/arvindand/maven-tools-mcp/stargazers)

Maven Tools MCP Server gives MCP-capable clients a practical way to inspect JVM dependencies using live Maven Central data.

It is built for developers and agents that need more than a plain version lookup: stability filtering, upgrade comparisons, dependency health signals, license data, CVE checks, and optional documentation lookups through Context7.

![Demo](assets/demo.gif)

## What It Helps With

Use Maven Tools MCP when you want to:

- check the latest stable version of a library without leaving your editor
- compare your current dependency set against what is available now
- plan upgrades with major/minor/patch context
- audit a project for stale, risky, or weakly maintained dependencies
- give an AI assistant structured, current dependency metadata instead of making it scrape docs or web pages

This project works with any JVM build tool that relies on Maven Central. The inputs are standard Maven coordinates, so the same data applies to Maven, Gradle, SBT, and Mill projects.

## Why It Matters

This project is most useful when a plain package search is not enough.

- it gives MCP clients structured dependency data instead of making them scrape web pages
- it keeps upgrade checks grounded in current Maven Central metadata
- it adds stability, age, CVE, and license signals in one place
- it works well alongside agent workflows that need dependency facts before they edit code or open PRs

## Emerging Use Case

One of the more interesting uses of this project is agent-driven dependency maintenance.

The core server does not open PRs by itself, but it gives an agent enough current dependency context to make safer update decisions than a blind version-bump workflow. This repository's own weekly self-update flow is the clearest example: GitHub Actions orchestrates the run, a bounded AI client performs the dependency update task, and the result is a reviewable PR.

That is also why the dogfooding setup matters beyond this repository. It demonstrates, in a small and concrete way, the same shape that broader GitHub Agentic Workflows can build on: a workflow orchestrator, an AI worker, structured tool output, and a human-reviewed change at the end.

## Quick Start

### Claude Desktop

Add this to your Claude Desktop config:

```json
{
  "mcpServers": {
    "maven-tools": {
      "command": "docker",
      "args": ["run", "-i", "--rm", "arvindand/maven-tools-mcp:latest"]
    }
  }
}
```

### VS Code + GitHub Copilot

Create `.vscode/mcp.json` in your workspace:

```json
{
  "servers": {
    "maven-tools": {
      "type": "stdio",
      "command": "docker",
      "args": ["run", "-i", "--rm", "arvindand/maven-tools-mcp:latest"]
    }
  }
}
```

### Image Variants

| Tag | Transport | Context7 | Best For |
|-----|-----------|----------|----------|
| `:latest` | STDIO | Yes | Default desktop MCP usage |
| `:latest-noc7` | STDIO | No | Networks where Context7 is blocked or not wanted |
| `:latest-http` | HTTP | Yes | Streamable HTTP clients and sidecar workflows |

`CONTEXT7_API_KEY` is optional. Most setups can start without it. If your environment requires Context7 auth, or you want to avoid anonymous limits, pass it through Docker with `-e CONTEXT7_API_KEY`.

For fuller setup guidance, including JAR/native usage, Docker Compose, and environment notes, see [`docs/setup.md`](docs/setup.md).

## Core Tools

The server exposes 10 MCP tools.

### Maven intelligence tools

| Tool | What It Does |
|------|---------------|
| `get_latest_version` | Find the latest version with stability-aware selection |
| `check_version_exists` | Verify a specific version and classify its stability |
| `check_multiple_dependencies` | Bulk lookup for dependency coordinates |
| `compare_dependency_versions` | Compare current versions against available upgrades |
| `analyze_dependency_age` | Classify how old a dependency is |
| `analyze_release_patterns` | Look at release cadence and maintenance signals |
| `get_version_timeline` | Inspect recent release history and gaps |
| `analyze_project_health` | Run a broader dependency health audit |

### Context7 documentation tools

| Tool | What It Does |
|------|---------------|
| `resolve-library-id` | Find a documentation library identifier |
| `query-docs` | Fetch docs by Context7 library ID |

For parameters, examples, and tool-by-tool notes, see [`docs/tools.md`](docs/tools.md).

## Example

A common prompt in Copilot or Claude is:

> Check all latest versions of the dependencies in my `pom.xml` and call out anything risky.

A good response from this server gives the client structured information such as:

- current version vs latest version
- whether the upgrade is major, minor, or patch
- whether the newest release is stable
- whether the dependency looks fresh, aging, or stale
- whether there are known CVEs or license concerns worth noticing

That keeps the workflow grounded in live repository data instead of guesswork.

For broader questions like "which library should I choose?", the useful pattern is: let the model use Maven Tools MCP for current coordinates, version/stability signals, and upgrade context, then combine that with Context7 docs (available through the default image's exposed tools) and, when needed, client-side web search for ecosystem context that this server does not provide on its own.

For more prompt examples, see [`docs/examples.md`](docs/examples.md). There is also a [`maven-tools` skill in the separate `agent-skills` repository](https://github.com/arvindand/agent-skills/tree/main/skills/maven-tools) that gives agents general guidance for using Maven Tools MCP effectively across varied use cases, while the local prompt examples and dogfooding agent define more specific or deterministic paths.

## Dogfooding

This repository runs a weekly self-update workflow that uses a local Python agent against its own `pom.xml` and opens a reviewable PR for safe dependency updates.

That flow is documented in [`docs/dogfooding.md`](docs/dogfooding.md), including:

- the GitHub Actions workflow
- the agent subproject under `agents/copilot-maven-tools-agent/`
- required `COPILOT_BOT_PAT` setup
- manual trigger instructions

## FAQ

- **Does this replace Renovate or Dependabot?** For Maven Central-based JVM projects, it can. Maven Tools MCP is the dependency intelligence layer, and the replacement behavior comes from the agent workflow built on top of it. In this repository, the weekly self-update workflow already replaces routine blind update PRs for safe minor and patch upgrades while leaving major upgrades for manual review.
- **Does it work offline?** Not fully. Uncached queries need network access to Maven Central.
- **Does it work for Gradle or other JVM build tools?** Yes, as long as the project depends on libraries that are resolved through Maven Central coordinates.

For a few more usage notes, see the FAQ section in [`docs/examples.md`](docs/examples.md#faq).

## More Docs

- [`docs/setup.md`](docs/setup.md) - installation, client configuration, image variants, build-from-source options
- [`docs/tools.md`](docs/tools.md) - full tool catalog, parameters, and response behavior
- [`docs/examples.md`](docs/examples.md) - practical prompts, advanced use cases, reusable commands, and FAQ notes
- [`docs/dogfooding.md`](docs/dogfooding.md) - weekly self-update workflow and agent integration
- [`docs/troubleshooting.md`](docs/troubleshooting.md) - common environment issues and fixes
- [`docs/architecture.md`](docs/architecture.md) - design principles, transport/runtime options, and technical notes
- [`CORPORATE-CERTIFICATES.md`](CORPORATE-CERTIFICATES.md) - custom CA certificate support for locked-down networks

## Further Reading

- [How I Connected Claude to Maven Central (and Why You Should Too)](https://dev.to/arvindand/how-i-connected-claude-to-maven-central-and-why-you-should-too-2clo)
- [Guided Delegation: Adding Context7 Documentation to My Maven Tools MCP Server](https://dev.to/arvindand/guided-delegation-adding-context7-documentation-to-my-maven-tools-mcp-server-572l)

## Contributing

If you want to build or test locally, start with [`docs/setup.md`](docs/setup.md#build-from-source) and the helper scripts in [`build/`](build/).

Project history and release notes live in [`CHANGELOG.md`](CHANGELOG.md).

## License

This project is licensed under the MIT License. See [`LICENSE`](LICENSE).

## Author

Arvind Menon

- GitHub: [@arvindand](https://github.com/arvindand)
