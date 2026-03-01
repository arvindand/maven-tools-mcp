---
name: Dependency Upgrade Plan
description: Generate a phased upgrade plan with breaking change analysis
---

# Dependency Upgrade Plan

Generate a phased upgrade plan for project dependencies using maven-tools-mcp.

## Instructions

1. **Analyze Current State**
   - Extract all dependencies with current versions from build file
   - Use `compare_dependency_versions` with `includeSecurityScan=true` and `stabilityFilter=STABLE_ONLY` to find available updates
   - Use `analyze_project_health` for health assessment

2. **Categorize Updates**
   - **Patch updates**: Bug fixes, safe to apply
   - **Minor updates**: New features, generally backward compatible
   - **Same-major fallback updates**: Safer near-term targets when the latest stable release is a major jump
   - **Major updates**: Breaking changes, require migration

3. **Prioritize by Risk**
   - Security vulnerabilities (highest priority)
   - End-of-life or unmaintained dependencies
   - Stale dependencies (>1 year old)
   - General updates

4. **Check for Breaking Changes**
   - For major updates, note migration requirements
   - If `same_major_stable_fallback` exists, keep that as the near-term recommendation and the major target as a separate migration track
   - Use Context7 guidance hints for migration documentation

## Upgrade Plan Format

### Phase 1: Critical Security Fixes

Dependencies with known vulnerabilities that need immediate attention.

| Dependency | Current | Target | Type | Risk |
|------------|---------|--------|------|------|

### Phase 2: Immediate Safe Updates

Patch updates and same-major fallback updates that can be taken before any major migration.

| Dependency | Current | Target |
|------------|---------|--------|

### Phase 3: Planned Minor Updates

Minor updates that are still worth testing thoroughly before deploying.

| Dependency | Current | Target | Notable Changes |
|------------|---------|--------|-----------------|

### Phase 4: Major Updates

Breaking changes, plan migration carefully.

| Dependency | Current | Target | Migration Notes |
|------------|---------|--------|-----------------|

### Build Tool Commands

**Maven:**

```xml
<!-- Update versions in pom.xml -->
```

**Gradle:**

```groovy
// Update versions in build.gradle
```

### Testing Checklist

- [ ] Run full test suite after each phase
- [ ] Check for deprecation warnings
- [ ] Verify application startup
- [ ] Test critical functionality
