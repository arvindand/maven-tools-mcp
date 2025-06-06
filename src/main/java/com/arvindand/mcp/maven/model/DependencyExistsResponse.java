package com.arvindand.mcp.maven.model;

/**
 * Represents the response for dependency existence check.
 *
 * @param exists whether the dependency version exists
 * @param version the version that was checked
 * @param type the version type
 * @author Arvind Menon
 * @since 0.1.0
 */
public record DependencyExistsResponse(boolean exists, String version, String type) {}
