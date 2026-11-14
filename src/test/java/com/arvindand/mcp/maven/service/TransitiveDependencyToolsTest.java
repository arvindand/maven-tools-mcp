package com.arvindand.mcp.maven.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.arvindand.mcp.maven.model.MavenCoordinate;
import com.arvindand.mcp.maven.model.McpError;
import com.arvindand.mcp.maven.model.ToolResponse;
import com.arvindand.mcp.maven.model.security.SecurityAssessment;
import com.arvindand.mcp.maven.model.transitive.DepsDevGraph;
import com.arvindand.mcp.maven.model.transitive.TransitiveScanResult;
import com.arvindand.mcp.maven.model.transitive.TransitiveScanResult.Finding;
import com.arvindand.mcp.maven.model.transitive.TransitiveScanResult.UnresolvedReason;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TransitiveDependencyToolsTest {
  private final DepsDevClient depsDev = mock(DepsDevClient.class);
  private final VulnerabilityService vulnerabilities = mock(VulnerabilityService.class);
  private final TransitiveDependencyTools tools =
      new TransitiveDependencyTools(depsDev, vulnerabilities);
  private final Map<String, SecurityAssessment> assessments = new HashMap<>();

  @BeforeEach
  void scanAnswersFromMap() {
    when(vulnerabilities.scanBulk(anyList()))
        .thenAnswer(
            invocation -> {
              Map<String, SecurityAssessment> result = new HashMap<>();
              for (MavenCoordinate c : invocation.<List<MavenCoordinate>>getArgument(0)) {
                result.put(
                    c.toCoordinateString(),
                    assessments.getOrDefault(c.toCoordinateString(), SecurityAssessment.clean()));
              }
              return result;
            });
  }

  private static DepsDevGraph graph(String[] gavs, int[]... edges) {
    List<DepsDevGraph.Node> nodes = new ArrayList<>();
    for (int i = 0; i < gavs.length; i++) {
      int split = gavs[i].lastIndexOf(':');
      nodes.add(
          new DepsDevGraph.Node(
              new DepsDevGraph.VersionKey(
                  "MAVEN", gavs[i].substring(0, split), gavs[i].substring(split + 1)),
              false,
              i == 0 ? "SELF" : "INDIRECT",
              List.of()));
    }
    List<DepsDevGraph.Edge> edgeList = new ArrayList<>();
    for (int[] e : edges) edgeList.add(new DepsDevGraph.Edge(e[0], e[1]));
    return new DepsDevGraph(nodes, edgeList, "");
  }

  private void serve(String root, DepsDevGraph graph) {
    String[] p = root.split(":");
    when(depsDev.fetchGraph(MavenCoordinate.of(p[0], p[1], p[2]))).thenReturn(Optional.of(graph));
  }

  private static SecurityAssessment vulnerable(
      SecurityAssessment.Severity severity, int count, String fixed, String... critical) {
    return new SecurityAssessment(
        SecurityAssessment.Status.VULNERABLE,
        count,
        severity,
        List.of(critical),
        List.of(),
        fixed,
        "rec",
        0,
        0,
        0);
  }

  @SuppressWarnings("unchecked")
  private static TransitiveScanResult data(ToolResponse response) {
    assertThat(response).isInstanceOf(ToolResponse.Success.class);
    return ((ToolResponse.Success<TransitiveScanResult>) response).data();
  }

  private static McpError error(ToolResponse response) {
    assertThat(response).isInstanceOf(ToolResponse.Error.class);
    return ((ToolResponse.Error) response).error();
  }

  @Test
  void reportsVulnerableTransitiveWithShortestPath() {
    // root -> a -> b -> vuln, and root -> c -> vuln: the two-hop path must win.
    serve(
        "g:root:1",
        graph(
            new String[] {"g:root:1", "g:a:1", "g:b:1", "x:vuln:1.0", "g:c:1"},
            new int[] {0, 1},
            new int[] {1, 2},
            new int[] {2, 3},
            new int[] {0, 4},
            new int[] {4, 3}));
    assessments.put(
        "x:vuln:1.0", vulnerable(SecurityAssessment.Severity.CRITICAL, 2, "1.1", "CVE-1"));

    TransitiveScanResult result = data(tools.scan_transitive_vulnerabilities("g:root:1", null));

    assertThat(result.findings()).hasSize(1);
    Finding finding = result.findings().get(0);
    assertThat(finding.groupId()).isEqualTo("x");
    assertThat(finding.relation()).isEqualTo(TransitiveScanResult.Relation.TRANSITIVE);
    assertThat(finding.maxSeverity()).isEqualTo(SecurityAssessment.Severity.CRITICAL);
    assertThat(finding.criticalIds()).containsExactly("CVE-1");
    assertThat(finding.fixedInVersion()).isEqualTo("1.1");
    assertThat(finding.introducedByTotal()).isEqualTo(1);
    assertThat(finding.introducedBy()).hasSize(1);
    assertThat(finding.introducedBy().get(0).root()).isEqualTo("g:root:1");
    assertThat(finding.introducedBy().get(0).path())
        .containsExactly("g:root:1", "g:c:1", "x:vuln:1.0");
    assertThat(result.summary().rootsRequested()).isEqualTo(1);
    assertThat(result.summary().rootsResolved()).isEqualTo(1);
    assertThat(result.summary().transitiveNodes()).isEqualTo(4);
    assertThat(result.summary().vulnerableCount()).isEqualTo(1);
    assertThat(result.summary().worstSeverity()).isEqualTo(SecurityAssessment.Severity.CRITICAL);
    assertThat(result.warnings()).anyMatch(w -> w.startsWith("Accuracy:"));
  }

  @Test
  void handlesCyclesInTheGraph() {
    serve(
        "g:root:1",
        graph(
            new String[] {"g:root:1", "g:a:1", "x:vuln:1"},
            new int[] {0, 1},
            new int[] {1, 0},
            new int[] {1, 2},
            new int[] {2, 1}));
    assessments.put("x:vuln:1", vulnerable(SecurityAssessment.Severity.HIGH, 1, null));

    TransitiveScanResult result = data(tools.scan_transitive_vulnerabilities("g:root:1", true));

    assertThat(result.findings().get(0).introducedBy().get(0).path())
        .containsExactly("g:root:1", "g:a:1", "x:vuln:1");
  }

  @Test
  void includeDirectControlsRootFindings() {
    serve("g:root:1", graph(new String[] {"g:root:1", "g:dep:1"}, new int[] {0, 1}));
    assessments.put("g:root:1", vulnerable(SecurityAssessment.Severity.HIGH, 1, null));

    TransitiveScanResult withRoot = data(tools.scan_transitive_vulnerabilities("g:root:1", true));
    assertThat(withRoot.findings()).hasSize(1);
    assertThat(withRoot.findings().get(0).relation()).isEqualTo(TransitiveScanResult.Relation.ROOT);
    assertThat(withRoot.findings().get(0).introducedBy().get(0).path()).containsExactly("g:root:1");
    assertThat(withRoot.summary().nodesScanned()).isEqualTo(2);

    TransitiveScanResult withoutRoot =
        data(tools.scan_transitive_vulnerabilities("g:root:1", false));
    assertThat(withoutRoot.findings()).isEmpty();
    assertThat(withoutRoot.summary().nodesScanned()).isEqualTo(1);
    assertThat(withoutRoot.summary().worstSeverity()).isNull();
  }

  @Test
  void sharedTransitiveListsEveryRoot() {
    serve("g:one:1", graph(new String[] {"g:one:1", "x:vuln:1"}, new int[] {0, 1}));
    serve(
        "g:two:1",
        graph(new String[] {"g:two:1", "g:mid:1", "x:vuln:1"}, new int[] {0, 1}, new int[] {1, 2}));
    assessments.put("x:vuln:1", vulnerable(SecurityAssessment.Severity.HIGH, 1, null));

    TransitiveScanResult result =
        data(tools.scan_transitive_vulnerabilities("g:one:1\ng:two:1", null));

    assertThat(result.findings()).hasSize(1);
    Finding finding = result.findings().get(0);
    assertThat(finding.introducedByTotal()).isEqualTo(2);
    assertThat(finding.introducedBy())
        .extracting(TransitiveScanResult.IntroducedBy::root)
        .containsExactly("g:one:1", "g:two:1");
    assertThat(finding.introducedBy().get(1).path())
        .containsExactly("g:two:1", "g:mid:1", "x:vuln:1");
    // 4 distinct versions (two roots, mid, shared vuln); the shared one is scanned once.
    assertThat(result.summary().nodesScanned()).isEqualTo(4);
  }

  @Test
  void introducedByIsCappedButTotalIsReported() {
    StringBuilder input = new StringBuilder();
    for (int i = 0; i < 7; i++) {
      String root = "g:r" + i + ":1";
      serve(root, graph(new String[] {root, "x:vuln:1"}, new int[] {0, 1}));
      input.append(root).append(',');
    }
    assessments.put("x:vuln:1", vulnerable(SecurityAssessment.Severity.HIGH, 1, null));

    Finding finding =
        data(tools.scan_transitive_vulnerabilities(input.toString(), null)).findings().get(0);

    assertThat(finding.introducedBy()).hasSize(TransitiveDependencyTools.MAX_LISTED_ROOTS);
    assertThat(finding.introducedByTotal()).isEqualTo(7);
  }

  @Test
  void differentVersionsOfSameArtifactAreReportedSeparately() {
    serve("g:one:1", graph(new String[] {"g:one:1", "x:lib:1.0"}, new int[] {0, 1}));
    serve("g:two:1", graph(new String[] {"g:two:1", "x:lib:2.0"}, new int[] {0, 1}));
    assessments.put(
        "x:lib:1.0", vulnerable(SecurityAssessment.Severity.CRITICAL, 1, "1.5", "CVE-A"));
    assessments.put("x:lib:2.0", vulnerable(SecurityAssessment.Severity.MEDIUM, 1, null));

    TransitiveScanResult result =
        data(tools.scan_transitive_vulnerabilities("g:one:1,g:two:1", null));

    assertThat(result.findings())
        .extracting(Finding::version)
        .containsExactly("1.0", "2.0"); // most severe first
    assertThat(result.findings().get(0).introducedBy().get(0).root()).isEqualTo("g:one:1");
    assertThat(result.findings().get(1).introducedBy().get(0).root()).isEqualTo("g:two:1");
  }

  @Test
  void unresolvedRootIsReportedAlongsideResolvedOne() {
    serve("g:ok:1", graph(new String[] {"g:ok:1", "x:vuln:1"}, new int[] {0, 1}));
    when(depsDev.fetchGraph(MavenCoordinate.of("g", "missing", "9"))).thenReturn(Optional.empty());
    when(depsDev.fetchGraph(MavenCoordinate.of("g", "broken", "1")))
        .thenThrow(new IllegalStateException("secret internals"));
    assessments.put("x:vuln:1", vulnerable(SecurityAssessment.Severity.HIGH, 1, null));

    TransitiveScanResult result =
        data(tools.scan_transitive_vulnerabilities("g:ok:1,g:missing:9,g:broken:1", null));

    assertThat(result.summary().rootsRequested()).isEqualTo(3);
    assertThat(result.summary().rootsResolved()).isEqualTo(1);
    assertThat(result.summary().rootsFailed()).isEqualTo(2);
    assertThat(result.unresolvedRoots())
        .extracting(TransitiveScanResult.UnresolvedRoot::root, r -> r.reason())
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("g:missing:9", UnresolvedReason.NOT_FOUND),
            org.assertj.core.groups.Tuple.tuple("g:broken:1", UnresolvedReason.UPSTREAM_ERROR));
    assertThat(result.unresolvedRoots().get(1).message()).doesNotContain("secret");
    assertThat(result.findings()).hasSize(1);
  }

  @Test
  void depsDevDownForAllRootsIsAnErrorNotAnEmptyResult() {
    when(depsDev.fetchGraph(MavenCoordinate.of("g", "a", "1")))
        .thenThrow(new IllegalStateException("down"));
    when(depsDev.fetchGraph(MavenCoordinate.of("g", "b", "1"))).thenReturn(Optional.empty());

    McpError error = error(tools.scan_transitive_vulnerabilities("g:a:1,g:b:1", null));

    assertThat(error.code()).isEqualTo(McpError.EXTERNAL_SERVICE_UNAVAILABLE);
    assertThat(error.message()).contains("not a clean result");
    verify(vulnerabilities, org.mockito.Mockito.never()).scanBulk(anyList());
  }

  @Test
  void allRootsNotFoundIsAnExplicitNonCleanResult() {
    when(depsDev.fetchGraph(MavenCoordinate.of("g", "a", "1"))).thenReturn(Optional.empty());

    TransitiveScanResult result = data(tools.scan_transitive_vulnerabilities("g:a:1", null));

    assertThat(result.summary().rootsResolved()).isZero();
    assertThat(result.unresolvedRoots()).hasSize(1);
    assertThat(result.findings()).isEmpty();
    assertThat(result.warnings()).anyMatch(w -> w.contains("not a clean result"));
  }

  @Test
  void unknownAssessmentsAreWarnedAndNeverCountedAsClean() {
    serve(
        "g:root:1",
        graph(new String[] {"g:root:1", "x:a:1", "x:b:1"}, new int[] {0, 1}, new int[] {0, 2}));
    assessments.put("x:a:1", SecurityAssessment.unknown("down"));
    assessments.put("x:b:1", SecurityAssessment.unknown("down"));

    TransitiveScanResult result = data(tools.scan_transitive_vulnerabilities("g:root:1", null));

    assertThat(result.findings()).isEmpty();
    assertThat(result.summary().unknownCount()).isEqualTo(2);
    assertThat(result.warnings())
        .contains(
            "Security scan unavailable for 2 dependencies; they are not assessed, NOT clean. Retry"
                + " later.");
  }

  @Test
  void failedScanChunkIsReportedAsUnknown() {
    serve("g:root:1", graph(new String[] {"g:root:1", "x:a:1"}, new int[] {0, 1}));
    when(vulnerabilities.scanBulk(anyList())).thenThrow(new IllegalStateException("deadline"));

    TransitiveScanResult result = data(tools.scan_transitive_vulnerabilities("g:root:1", null));

    assertThat(result.summary().unknownCount()).isEqualTo(2);
    assertThat(result.findings()).isEmpty();
  }

  @Test
  void nodeErrorsFromDepsDevBecomeWarnings() {
    DepsDevGraph base = graph(new String[] {"g:root:1", "x:a:1"}, new int[] {0, 1});
    DepsDevGraph.Node flagged =
        new DepsDevGraph.Node(
            base.nodes().get(1).versionKey(), false, "INDIRECT", List.of("could not resolve y"));
    serve(
        "g:root:1",
        new DepsDevGraph(List.of(base.nodes().get(0), flagged), base.edges(), "partial"));

    TransitiveScanResult result = data(tools.scan_transitive_vulnerabilities("g:root:1", null));

    assertThat(result.warnings()).anyMatch(w -> w.contains("partial"));
    assertThat(result.warnings())
        .anyMatch(
            w ->
                w.contains("1 graph nodes with resolution errors")
                    && w.contains("could not resolve y"));
  }

  @Test
  void rejectsEntriesWithoutVersionWithPerEntryMessage() {
    McpError error =
        error(
            tools.scan_transitive_vulnerabilities("g:ok:1, org.foo:bar ,\nnot-a-coordinate", null));

    assertThat(error.code()).isEqualTo(McpError.INVALID_INPUT);
    assertThat(error.message())
        .contains("'org.foo:bar' has no version")
        .contains("'not-a-coordinate'");
    verify(depsDev, org.mockito.Mockito.never()).fetchGraph(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void rejectsTooManyRootsAndBlankInput() {
    String tooMany =
        String.join(",", IntStream.rangeClosed(0, 100).mapToObj(i -> "g:a" + i + ":1").toList());
    assertThat(error(tools.scan_transitive_vulnerabilities(tooMany, null)).code())
        .isEqualTo(McpError.INVALID_INPUT);
    assertThat(error(tools.scan_transitive_vulnerabilities("  ", null)).code())
        .isEqualTo(McpError.INVALID_INPUT);
    verify(depsDev, org.mockito.Mockito.never()).fetchGraph(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void duplicateRootsAreRequestedOnce() {
    serve("g:root:1", graph(new String[] {"g:root:1"}));

    TransitiveScanResult result =
        data(tools.scan_transitive_vulnerabilities("g:root:1,g:root:1", null));

    assertThat(result.summary().rootsRequested()).isEqualTo(1);
    // no transitives: the accuracy caveat is not needed
    assertThat(result.warnings()).noneMatch(w -> w.startsWith("Accuracy:"));
  }

  @Test
  void scansLargeUnionsInChunksWithinTheBulkLimit() {
    String[] gavs = new String[601];
    gavs[0] = "g:root:1";
    int[][] edges = new int[600][];
    for (int i = 1; i <= 600; i++) {
      gavs[i] = "x:dep" + i + ":1";
      edges[i - 1] = new int[] {0, i};
    }
    serve("g:root:1", graph(gavs, edges));

    TransitiveScanResult result = data(tools.scan_transitive_vulnerabilities("g:root:1", true));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<MavenCoordinate>> chunks = ArgumentCaptor.forClass(List.class);
    verify(vulnerabilities, org.mockito.Mockito.atLeast(2)).scanBulk(chunks.capture());
    assertThat(chunks.getAllValues())
        .allSatisfy(c -> assertThat(c.size()).isLessThanOrEqualTo(500));
    assertThat(chunks.getAllValues().stream().mapToInt(List::size).sum()).isEqualTo(601);
    assertThat(result.summary().nodesScanned()).isEqualTo(601);
  }

  @Test
  void rejectsUnionsAboveTheNodeCap() {
    int count = TransitiveDependencyTools.MAX_DISTINCT_NODES + 1;
    String[] gavs = new String[count + 1];
    gavs[0] = "g:root:1";
    int[][] edges = new int[count][];
    for (int i = 1; i <= count; i++) {
      gavs[i] = "x:dep" + i + ":1";
      edges[i - 1] = new int[] {0, i};
    }
    serve("g:root:1", graph(gavs, edges));

    assertThat(error(tools.scan_transitive_vulnerabilities("g:root:1", null)).code())
        .isEqualTo(McpError.INVALID_INPUT);
    verify(vulnerabilities, org.mockito.Mockito.never()).scanBulk(anyList());
  }

  @Test
  void findingsAreSortedMostSevereFirst() {
    serve(
        "g:root:1",
        graph(
            new String[] {"g:root:1", "x:low:1", "x:crit:1", "x:high:1"},
            new int[] {0, 1},
            new int[] {0, 2},
            new int[] {0, 3}));
    assessments.put("x:low:1", vulnerable(SecurityAssessment.Severity.LOW, 5, null));
    assessments.put("x:crit:1", vulnerable(SecurityAssessment.Severity.CRITICAL, 1, null));
    assessments.put("x:high:1", vulnerable(SecurityAssessment.Severity.HIGH, 1, null));

    TransitiveScanResult result = data(tools.scan_transitive_vulnerabilities("g:root:1", null));

    assertThat(result.findings())
        .extracting(Finding::artifactId)
        .containsExactly("crit", "high", "low");
    verify(vulnerabilities).scanBulk(eq(scanned(result)));
  }

  private static List<MavenCoordinate> scanned(TransitiveScanResult result) {
    return List.of(
        MavenCoordinate.of("g", "root", "1"),
        MavenCoordinate.of("x", "low", "1"),
        MavenCoordinate.of("x", "crit", "1"),
        MavenCoordinate.of("x", "high", "1"));
  }
}
