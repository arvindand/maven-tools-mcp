package com.arvindand.mcp.maven.model.transitive;

import com.arvindand.mcp.maven.model.security.SecurityAssessment;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Response of {@code scan_transitive_vulnerabilities}.
 *
 * @param summary counts for the whole scan
 * @param findings vulnerable dependency versions, most severe first
 * @param unresolvedRoots roots whose dependency graph could not be fetched
 * @param warnings accuracy caveat and data-quality notices
 * @author Arvind Menon
 * @since 3.3.0
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TransitiveScanResult(
    Summary summary,
    List<Finding> findings,
    List<UnresolvedRoot> unresolvedRoots,
    List<String> warnings) {

  /** Whether a finding is an input coordinate or was pulled in by one. */
  public enum Relation {
    ROOT,
    TRANSITIVE
  }

  /** Why a root's graph is missing. */
  public enum UnresolvedReason {
    NOT_FOUND,
    UPSTREAM_ERROR,
    EMPTY_GRAPH
  }

  /**
   * Scan counters.
   *
   * @param rootsRequested distinct input coordinates
   * @param rootsResolved roots whose graph was fetched
   * @param rootsFailed roots without a graph
   * @param transitiveNodes distinct non-root dependency versions found across all graphs
   * @param nodesScanned distinct versions sent to the vulnerability scan
   * @param vulnerableCount scanned versions with known vulnerabilities
   * @param unknownCount scanned versions whose assessment was unavailable (not counted as clean)
   * @param worstSeverity most severe finding, absent when there are none
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Summary(
      int rootsRequested,
      int rootsResolved,
      int rootsFailed,
      int transitiveNodes,
      int nodesScanned,
      int vulnerableCount,
      int unknownCount,
      SecurityAssessment.Severity worstSeverity) {}

  /**
   * One vulnerable dependency version.
   *
   * @param relation ROOT for an input coordinate, TRANSITIVE otherwise
   * @param introducedBy up to a capped number of roots that pull it in, each with one shortest path
   * @param introducedByTotal total number of roots that pull it in
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Finding(
      String groupId,
      String artifactId,
      String version,
      Relation relation,
      SecurityAssessment.Severity maxSeverity,
      int vulnerabilityCount,
      List<String> criticalIds,
      List<String> highIds,
      String fixedInVersion,
      List<IntroducedBy> introducedBy,
      int introducedByTotal) {}

  /**
   * A root and one shortest path from it to the vulnerable node.
   *
   * @param root the input coordinate
   * @param path ordered {@code groupId:artifactId:version} list from root to the vulnerable node
   */
  public record IntroducedBy(String root, List<String> path) {}

  /**
   * A root that could not be resolved.
   *
   * @param root the input coordinate
   * @param reason failure class
   * @param message human-readable detail
   */
  public record UnresolvedRoot(String root, UnresolvedReason reason, String message) {}
}
