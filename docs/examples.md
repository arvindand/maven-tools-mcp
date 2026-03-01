# Examples

This page keeps the longer prompt and workflow examples out of the main README while preserving the practical usage patterns.

## Important Boundary

Maven Tools MCP provides dependency intelligence, not standalone product judgment.

That means:

- the server can supply current coordinates, stability signals, upgrade classifications, and project-health data
- the calling model still has to reason about tradeoffs when you ask broader questions like "which library should I choose?"
- the best results usually come when the client combines Maven Tools MCP with the raw Context7 tools exposed by the default image and, if the client supports it, web search for ecosystem context that is outside Maven metadata

In practice, library-choice prompts work well when the model uses this server to stay current and grounded, then uses documentation and general research to justify the recommendation.

## Everyday Prompts

### Version and upgrade checks

- "Check all latest versions of the dependencies in my `pom.xml`."
- "Compare my current Spring Boot stack with the latest stable releases."
- "Show me which of these dependencies are patch-only upgrades vs major upgrades."

### Dependency selection

- "I need caching for a Spring Boot service at moderate throughput. Compare Redis and Caffeine."
- "I am adding JSON, HTTP, and time-series support to a new service. What libraries should I consider?"

### Health and compliance

- "Give me a health assessment for my key dependencies."
- "Scan these dependencies for CVEs and call out license risks."

## Richer Example Flows

### Starting a new feature

Ask:

> I'm building a REST API for IoT data ingestion. I need high-throughput JSON parsing, time-series data structures, and an async HTTP client. What libraries fit well with Spring Boot?

Expected value when the client uses Maven Tools MCP well:

- current Maven coordinates for suitable libraries
- guidance on what is already covered by Spring Boot
- version and stability awareness
- useful follow-up documentation targets when deeper library docs matter

### Choosing between alternatives

Ask:

> Should I use Redis or Caffeine for caching around 10k req/min in Spring Boot?

Useful output here includes:

- current versions
- comparative tradeoffs
- whether one option is overkill for the stated workload
- suggested dependency coordinates for the recommended path

This kind of answer depends on model reasoning. Maven Tools MCP provides the current dependency facts; the recommendation quality improves when the client also reads Context7 docs and falls back to web search when it needs performance, adoption, or framework-guidance context.

### Reviewing upgrade work

Ask:

> I want to upgrade Spring Boot. What will change, and what should I check before touching code?

Useful output here includes:

- whether the next step is a patch/minor/major move
- which related dependencies may also need attention
- whether the client should consult Context7 for migration docs
- clear separation of safe updates vs changes that need manual review

## Advanced Analysis Examples

### Dependency age

A response from `analyze_dependency_age` may look like:

```json
{
  "dependency": "org.springframework.boot:spring-boot-starter",
  "age_classification": "current",
  "days_since_release": 45,
  "recommendation": "Actively maintained - consider updating if needed"
}
```

### Release pattern analysis

A response from `analyze_release_patterns` may look like:

```json
{
  "dependency": "com.fasterxml.jackson.core:jackson-core",
  "maintenance_level": "active",
  "release_velocity": 1.2,
  "next_release_prediction": "Expected in 3 weeks"
}
```

### Project health

A response from `analyze_project_health` may look like:

```json
{
  "overall_health": "good",
  "average_health_score": 78,
  "age_distribution": {
    "fresh": 2,
    "current": 8,
    "aging": 3,
    "stale": 1
  }
}
```

## Example Commands And Prompts

These files are kept here as example entry points and showcase material.

They are useful for demonstrating how someone might ask for Maven Tools MCP-driven analysis in Claude Code or GitHub Copilot, but they are not the canonical source of orchestration behavior.

Use this split when thinking about maintenance:

- local `.claude/commands/` and `.github/prompts/` are illustrative examples
- the [`maven-tools` skill in the separate `agent-skills` repository](https://github.com/arvindand/agent-skills/tree/main/skills/maven-tools) gives agents general guidance for using Maven Tools MCP across varied use cases
- the local Copilot dependency agent described in [`dogfooding.md`](dogfooding.md) defines the repository's more specific and deterministic automation path

### Claude Code slash commands

The repository includes command helpers in `.claude/commands/`:

| Command | Purpose |
|---------|---------|
| `/deps-check` | Quick version lookup for specific dependencies |
| `/deps-health` | Health audit with security and license analysis |
| `/deps-upgrade` | Upgrade recommendations with breaking-change awareness |
| `/deps-age` | Freshness and maintenance activity analysis |

Examples:

```bash
/deps-check org.springframework:spring-core,com.google.guava:guava
/deps-health
/deps-upgrade org.springframework:spring-core:6.0.0,junit:junit:4.13.2
```

### GitHub Copilot prompts

The repository includes prompt templates in `.github/prompts/`:

| Prompt | Purpose |
|--------|---------|
| `dependency-audit.prompt.md` | Dependency audit with health scoring |
| `security-scan.prompt.md` | CVE scanning and remediation guidance |
| `upgrade-plan.prompt.md` | Phased upgrade planning |

If you want broader agent guidance that can adapt across projects and prompts, prefer the `maven-tools` skill in the separate `agent-skills` repository. If you need deterministic repo automation, prefer the explicit policy in the local Copilot agent instead of copying these example prompts into CI.

## FAQ

### Does this replace Renovate or Dependabot?

For Maven Central-based JVM projects, it can. Maven Tools MCP provides the dependency intelligence, and an agent workflow built on top of it can use that context to make safer update decisions than a blind version-bump bot.

That is already how this repository's weekly self-update workflow works:

- it checks real current vs available versions
- it auto-applies stable minor and patch updates
- it avoids auto-applying major upgrades
- it opens a single reviewable PR and relies on normal CI

It is still not a universal drop-in replacement across every package ecosystem, but for Maven Central-driven JVM projects it is a realistic replacement for routine dependency bot PRs.

### Does it work offline?

Not fully. It depends on live Maven Central access for uncached queries. Cached results can still help for previously queried dependencies.

### Does it support non-Maven build tools?

Yes for JVM projects that use Maven Central data. The tool speaks Maven coordinates, which are shared across Maven, Gradle, SBT, and Mill.

## Related Docs

- [`tools.md`](tools.md)
- [`setup.md`](setup.md)
- [`dogfooding.md`](dogfooding.md)
