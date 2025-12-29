---
name: Dependency Upgrade Plan
description: Generate a phased upgrade plan with breaking change analysis
---

# Dependency Upgrade Plan

Generate a phased upgrade plan for project dependencies using maven-tools-mcp.

## Instructions

1. **Analyze Current State**
   - Extract all dependencies with current versions from build file
   - Use `compare_dependency_versions` to find available updates
   - Use `analyze_project_health` for health assessment

2. **Categorize Updates**
   - **Patch updates**: Bug fixes, safe to apply
   - **Minor updates**: New features, generally backward compatible
   - **Major updates**: Breaking changes, require migration

3. **Prioritize by Risk**
   - Security vulnerabilities (highest priority)
   - End-of-life or unmaintained dependencies
   - Stale dependencies (>1 year old)
   - General updates

4. **Check for Breaking Changes**
   - For major updates, note migration requirements
   - Use Context7 guidance hints for migration documentation

## Upgrade Plan Format

### Phase 1: Critical Security Fixes

Dependencies with known vulnerabilities that need immediate attention.

| Dependency | Current | Target | Type | Risk |
|------------|---------|--------|------|------|

### Phase 2: Patch Updates

Safe updates with bug fixes only.

| Dependency | Current | Target |
|------------|---------|--------|

### Phase 3: Minor Updates

New features, test thoroughly before deploying.

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
