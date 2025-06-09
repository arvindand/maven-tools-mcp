package com.arvindand.mcp.maven.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Represents the response from Maven Central search API.
 *
 * @param response the response data containing search results
 * @author Arvind Menon
 * @since 0.1.0
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MavenSearchResponse(@JsonProperty("response") ResponseData response) {

  /**
   * Response data wrapper containing the actual search results.
   *
   * @param docs the list of Maven artifacts found
   * @param numFound the total number of results found
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ResponseData(
      @JsonProperty("docs") List<MavenArtifact> docs, @JsonProperty("numFound") int numFound) {}

  /**
   * Represents a Maven artifact in the search results.
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
}
