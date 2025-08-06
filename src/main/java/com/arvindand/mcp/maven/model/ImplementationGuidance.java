package com.arvindand.mcp.maven.model;

import java.util.List;

/**
 * Represents implementation guidance and practical examples for dependency setup and integration.
 *
 * @param dependency the Maven coordinate for this guidance
 * @param version the specific version this guidance applies to
 * @param context the type of guidance provided (setup, configuration, integration, etc.)
 * @param setupInstructions step-by-step setup instructions
 * @param configurationExamples code examples for configuration
 * @param integrationPatterns common integration patterns and best practices
 * @param commonIssues troubleshooting for common setup issues
 * @param documentationUrl URL to official documentation
 * @author Arvind Menon
 * @since 1.2.0
 */
public record ImplementationGuidance(
    String dependency,
    String version,
    String context,
    List<String> setupInstructions,
    List<String> configurationExamples,
    List<String> integrationPatterns,
    List<String> commonIssues,
    String documentationUrl) {}
