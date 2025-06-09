package com.arvindand.mcp.maven.model;

import com.arvindand.mcp.maven.model.VersionInfo.VersionType;

/**
 * Represents detailed version information including counts.
 *
 * @param version the version string
 * @param type the version type (type-safe enum)
 * @param totalVersions total number of versions available
 * @param stableVersions number of stable versions available
 * @author Arvind Menon
 * @since 0.1.0
 */
public record DetailedVersionInfo(
    String version, VersionType type, int totalVersions, int stableVersions) {}
