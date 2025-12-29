---
description: Analyze dependency freshness and maintenance activity
argument-hint: [groupId:artifactId]
---
Analyze the age and maintenance status of a dependency using maven-tools-mcp.

**Dependency to analyze:** $ARGUMENTS

**Analysis Steps:**

1. Use `analyze_dependency_age` to get:
   - Days since last release
   - Age classification (fresh/current/aging/stale)
   - Actionable recommendations

2. Use `analyze_release_patterns` to get:
   - Release frequency and velocity
   - Average time between releases
   - Maintenance level (active/moderate/slow/inactive)
   - Predicted next release timeframe

3. Use `get_version_timeline` to show:
   - Recent version history
   - Release gaps and patterns
   - Stability trends

**Report Format:**

**Freshness Status:**

- Last release: X days ago
- Classification: [fresh/current/aging/stale]

**Maintenance Activity:**

- Release velocity: X releases per year
- Maintenance level: [active/moderate/slow/inactive]
- Next release prediction: [timeframe]

**Version Timeline:**
Show the last 5-10 releases with dates and version types.

**Recommendation:**
Based on the analysis, provide guidance on whether this dependency is actively maintained and safe for long-term use.
