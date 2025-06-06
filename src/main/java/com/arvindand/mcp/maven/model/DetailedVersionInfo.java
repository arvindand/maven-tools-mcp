package com.arvindand.mcp.maven.model;

/**
 * Represents detailed version information including counts.
 *
 * @param version the version string
 * @param type the version type
 * @param totalVersions total number of versions available
 * @param stableVersions number of stable versions available
 * @author Arvind Menon
 * @since 0.1.0
 */
public record DetailedVersionInfo(
    String version, String type, int totalVersions, int stableVersions) {}
