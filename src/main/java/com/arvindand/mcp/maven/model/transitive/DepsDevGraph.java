package com.arvindand.mcp.maven.model.transitive;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Resolved dependency graph for one package version, as served by the deps.dev {@code
 * GetDependencies} endpoint. Only the fields this server uses are mapped; edge requirements are
 * dropped to keep cached graphs small.
 *
 * @param nodes graph nodes; the first node is the queried version itself
 * @param edges directed edges as indexes into {@code nodes}
 * @param error graph-level error reported by deps.dev, empty when none
 * @author Arvind Menon
 * @since 3.3.0
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DepsDevGraph(List<Node> nodes, List<Edge> edges, String error) {

  /** One resolved package version in the graph. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Node(
      VersionKey versionKey, boolean bundled, String relation, List<String> errors) {}

  /** Package identity; for Maven {@code name} is {@code groupId:artifactId}. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record VersionKey(String system, String name, String version) {}

  /** A dependency edge between two node indexes. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Edge(int fromNode, int toNode) {}
}
