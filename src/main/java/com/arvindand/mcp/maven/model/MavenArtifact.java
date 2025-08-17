package com.arvindand.mcp.maven.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents a Maven artifact, typically containing version and timestamp information.
 *
 * @param id the unique identifier for this artifact
 * @param groupId the Maven group identifier
 * @param artifactId the Maven artifact identifier
 * @param version the artifact version
 * @param packaging the packaging type
 * @param timestamp the timestamp when this artifact was published
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MavenArtifact(
    @JsonProperty("id") String id,
    @JsonProperty("g") String groupId,
    @JsonProperty("a") String artifactId,
    @JsonProperty("v") String version,
    @JsonProperty("p") String packaging,
    @JsonProperty("timestamp") long timestamp) {}
