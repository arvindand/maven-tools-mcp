package com.arvindand.mcp.maven.model;

import org.jspecify.annotations.Nullable;

/**
 * A relocation published in a POM's {@code distributionManagement/relocation}: the artifact moved
 * to new coordinates. Values are already resolved against the original coordinate (omitted groupId
 * and artifactId default to the original's, per Maven semantics).
 *
 * @param groupId target groupId, never null
 * @param artifactId target artifactId, never null
 * @param version target version; null means "same version as the relocated artifact"
 * @param message free-text explanation from the POM, may be null
 * @since 3.2.5
 */
public record Relocation(
    String groupId, String artifactId, @Nullable String version, @Nullable String message) {}
