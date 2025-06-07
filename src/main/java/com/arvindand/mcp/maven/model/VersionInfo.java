package com.arvindand.mcp.maven.model;

/**
 * Represents version information for a Maven dependency.
 *
 * @param version the version string
 * @param type the version type (stable, rc, alpha, beta, milestone)
 * @author Arvind Menon
 * @since 0.1.0
 */
public record VersionInfo(String version, String type) {}
