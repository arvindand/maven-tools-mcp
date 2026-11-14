package com.arvindand.mcp.maven.service;

import com.arvindand.mcp.maven.MavenToolsConstants;
import com.arvindand.mcp.maven.model.MavenCoordinate;
import com.arvindand.mcp.maven.model.McpError;
import com.arvindand.mcp.maven.model.ToolResponse;
import com.arvindand.mcp.maven.model.security.SecurityAssessment;
import com.arvindand.mcp.maven.model.transitive.DepsDevGraph;
import com.arvindand.mcp.maven.model.transitive.TransitiveScanResult;
import com.arvindand.mcp.maven.model.transitive.TransitiveScanResult.Finding;
import com.arvindand.mcp.maven.model.transitive.TransitiveScanResult.IntroducedBy;
import com.arvindand.mcp.maven.model.transitive.TransitiveScanResult.Relation;
import com.arvindand.mcp.maven.model.transitive.TransitiveScanResult.Summary;
import com.arvindand.mcp.maven.model.transitive.TransitiveScanResult.UnresolvedReason;
import com.arvindand.mcp.maven.model.transitive.TransitiveScanResult.UnresolvedRoot;
import com.arvindand.mcp.maven.util.BoundedBatch;
import com.arvindand.mcp.maven.util.MavenCoordinateParser;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Semaphore;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

/**
 * MCP tool that finds known vulnerabilities in the transitive dependencies of given coordinates.
 * Dependency graphs come from deps.dev (no local resolver); vulnerabilities from {@link
 * VulnerabilityService}.
 *
 * @author Arvind Menon
 * @since 3.3.0
 */
@Service
public class TransitiveDependencyTools {

  private static final Logger logger = LoggerFactory.getLogger(TransitiveDependencyTools.class);

  static final int MAX_ROOTS = 100;
  static final int MAX_DISTINCT_NODES = 1000;

  /** Below {@code scanBulk}'s 500 limit so a chunk fits its 60s budget at the OSV rate limit. */
  static final int SCAN_CHUNK_SIZE = 250;

  static final int MAX_LISTED_ROOTS = 5;
  private static final int MAX_LISTED_NODE_ERRORS = 5;

  static final String ACCURACY_CAVEAT =
      "Accuracy: each root's graph is deps.dev's resolution of that root on its own. It does not"
          + " apply your project's dependencyManagement, BOM imports, exclusions or version"
          + " mediation between roots, so your project may resolve different versions of a"
          + " transitive. Treat findings as leads to confirm with the real dependency tree"
          + " (mvn dependency:tree / gradle dependencies).";

  private final DepsDevClient depsDevClient;
  private final VulnerabilityService vulnerabilityService;
  private final Semaphore permits = new Semaphore(MavenToolsConstants.MAX_CONCURRENT_REQUESTS);

  public TransitiveDependencyTools(
      DepsDevClient depsDevClient, VulnerabilityService vulnerabilityService) {
    this.depsDevClient = depsDevClient;
    this.vulnerabilityService = vulnerabilityService;
  }

  /**
   * Finds known vulnerabilities in the transitive dependencies of the given direct dependencies.
   *
   * @param dependencies comma or newline separated {@code groupId:artifactId:version} coordinates
   * @param includeDirect whether findings on the input coordinates themselves are reported
   * @return JSON response with findings, summary, unresolved roots and warnings
   */
  @SuppressWarnings("java:S100") // MCP tool method naming
  @Tool(
      description =
          "Finds known vulnerabilities (OSV) in the TRANSITIVE dependencies of your direct"
              + " dependencies, with the shortest path showing which root pulls each one in."
              + " Input: direct dependencies as 'groupId:artifactId:version' (versions required)."
              + " Graphs come from deps.dev resolving each root alone, without your"
              + " dependencyManagement, BOMs, exclusions or version mediation, so treat findings as"
              + " leads to confirm with 'mvn dependency:tree' / 'gradle dependencies'. Use for"
              + " 'am I exposed through a transitive?'; compare_dependency_versions only covers the"
              + " declared dependencies and upgrades.")
  public ToolResponse scan_transitive_vulnerabilities(
      @ToolParam(
              description =
                  "Comma or newline separated direct dependencies WITH versions in format"
                      + " 'groupId:artifactId:version'. Max 100. Example:"
                      + " 'org.springframework.boot:spring-boot-starter-web:3.3.0,junit:junit:4.12'")
          String dependencies,
      @ToolParam(
              description =
                  "Whether findings on the listed dependencies themselves are included (relation"
                      + " ROOT). Default: true",
              required = false)
          @Nullable Boolean includeDirect) {
    try {
      boolean direct = includeDirect == null || includeDirect;
      return scan(dependencies, direct);
    } catch (IllegalStateException e) {
      logger.warn("Transitive scan did not complete: {}", e.getMessage());
      return ToolResponse.Error.of(
          new McpError(
              McpError.EXTERNAL_SERVICE_UNAVAILABLE,
              "deps.dev scan did not complete: " + e.getMessage(),
              Map.of("service", "deps.dev"),
              30));
    } catch (Exception e) {
      logger.error("Unexpected error", e);
      return ToolResponse.Error.of("Unexpected error: " + e.getMessage());
    }
  }

  private ToolResponse scan(String dependencies, boolean includeDirect) {
    ParsedRoots parsed = parseRoots(dependencies);
    if (!parsed.errors().isEmpty()) {
      return ToolResponse.Error.of(
          McpError.invalidInput(
              "Invalid dependencies: " + String.join("; ", parsed.errors()),
              Map.of("errors", parsed.errors(), "expectedFormat", "groupId:artifactId:version")));
    }
    List<MavenCoordinate> roots = parsed.roots();
    if (roots.size() > MAX_ROOTS) {
      return ToolResponse.Error.of(
          McpError.invalidInput(
              "At most " + MAX_ROOTS + " dependencies per request; got " + roots.size(),
              Map.of("maxDependencies", MAX_ROOTS)));
    }

    List<RootOutcome> outcomes =
        BoundedBatch.map(
            roots,
            this::fetch,
            permits,
            Duration.ofSeconds(MavenToolsConstants.DEFAULT_BATCH_TIMEOUT_SECONDS));

    List<UnresolvedRoot> unresolved =
        outcomes.stream().map(RootOutcome::failure).filter(f -> f != null).toList();
    int resolved = outcomes.size() - unresolved.size();
    boolean anyUpstreamError =
        unresolved.stream().anyMatch(u -> u.reason() == UnresolvedReason.UPSTREAM_ERROR);
    if (resolved == 0 && anyUpstreamError) {
      return ToolResponse.Error.of(
          new McpError(
              McpError.EXTERNAL_SERVICE_UNAVAILABLE,
              "deps.dev is unavailable; no dependency graph could be fetched. No vulnerability"
                  + " scan was performed, so this is not a clean result.",
              Map.of("service", "deps.dev", "unresolvedRoots", unresolved),
              60));
    }

    Collector collector = new Collector();
    for (RootOutcome outcome : outcomes) {
      if (outcome.graph() != null) collector.add(outcome.root(), outcome.graph());
    }
    Set<String> rootKeys = new HashSet<>();
    roots.forEach(r -> rootKeys.add(r.toCoordinateString()));
    Map<String, NodeAgg> union = collector.union;
    if (union.size() > MAX_DISTINCT_NODES) {
      return ToolResponse.Error.of(
          McpError.invalidInput(
              "The dependency graphs contain "
                  + union.size()
                  + " distinct versions (limit "
                  + MAX_DISTINCT_NODES
                  + "); split the roots into smaller groups",
              Map.of("distinctNodes", union.size(), "limit", MAX_DISTINCT_NODES)));
    }

    List<String> scanKeys =
        union.keySet().stream().filter(k -> includeDirect || !rootKeys.contains(k)).toList();
    Map<String, SecurityAssessment> assessments = scanInChunks(scanKeys, union);

    List<Finding> findings = new ArrayList<>();
    int unknown = 0;
    for (String key : scanKeys) {
      SecurityAssessment assessment = assessments.get(key);
      if (assessment == null || assessment.status() == SecurityAssessment.Status.UNKNOWN) {
        unknown++;
      } else if (assessment.status() == SecurityAssessment.Status.VULNERABLE) {
        findings.add(toFinding(union.get(key), assessment, rootKeys.contains(key)));
      }
    }
    findings.sort(
        Comparator.comparing((Finding f) -> f.maxSeverity().ordinal())
            .thenComparing(Comparator.comparingInt(Finding::vulnerabilityCount).reversed())
            .thenComparing(f -> f.groupId() + ":" + f.artifactId() + ":" + f.version()));

    int transitiveNodes = (int) union.keySet().stream().filter(k -> !rootKeys.contains(k)).count();
    List<String> warnings = new ArrayList<>();
    if (transitiveNodes > 0) warnings.add(ACCURACY_CAVEAT);
    if (unknown > 0) {
      warnings.add(
          "Security scan unavailable for "
              + unknown
              + " dependencies; they are not assessed, NOT clean. Retry later.");
    }
    if (resolved == 0) {
      warnings.add(
          "No dependency graph could be resolved; no vulnerability scan was performed. This is"
              + " not a clean result.");
    }
    warnings.addAll(collector.warnings());

    Summary summary =
        new Summary(
            roots.size(),
            resolved,
            unresolved.size(),
            transitiveNodes,
            scanKeys.size(),
            findings.size(),
            unknown,
            findings.isEmpty() ? null : findings.get(0).maxSeverity());
    return ToolResponse.Success.of(
        new TransitiveScanResult(summary, findings, unresolved, warnings));
  }

  private Map<String, SecurityAssessment> scanInChunks(
      List<String> keys, Map<String, NodeAgg> union) {
    Map<String, SecurityAssessment> assessments = new HashMap<>();
    for (int from = 0; from < keys.size(); from += SCAN_CHUNK_SIZE) {
      List<String> chunk = keys.subList(from, Math.min(keys.size(), from + SCAN_CHUNK_SIZE));
      try {
        assessments.putAll(
            vulnerabilityService.scanBulk(
                chunk.stream().map(k -> union.get(k).coordinate).toList()));
      } catch (RuntimeException ex) {
        // Missing assessments are reported as unknown, never as clean.
        logger.warn("Vulnerability scan chunk failed: {}", ex.getClass().getSimpleName());
      }
    }
    return assessments;
  }

  private static Finding toFinding(NodeAgg node, SecurityAssessment assessment, boolean isRoot) {
    List<Map.Entry<String, List<String>>> paths = new ArrayList<>(node.pathsByRoot.entrySet());
    paths.sort(
        Comparator.comparingInt((Map.Entry<String, List<String>> e) -> e.getValue().size())
            .thenComparing(Map.Entry::getKey));
    List<IntroducedBy> introducedBy =
        paths.stream()
            .limit(MAX_LISTED_ROOTS)
            .map(e -> new IntroducedBy(e.getKey(), e.getValue()))
            .toList();
    SecurityAssessment.Severity severity =
        assessment.maxSeverity() != null
            ? assessment.maxSeverity()
            : SecurityAssessment.Severity.UNKNOWN;
    return new Finding(
        node.coordinate.groupId(),
        node.coordinate.artifactId(),
        node.coordinate.version(),
        isRoot ? Relation.ROOT : Relation.TRANSITIVE,
        severity,
        assessment.vulnerabilityCount(),
        assessment.criticalCves(),
        assessment.highCves(),
        assessment.fixedInVersion(),
        introducedBy,
        paths.size());
  }

  private RootOutcome fetch(MavenCoordinate root) {
    String key = root.toCoordinateString();
    try {
      Optional<DepsDevGraph> graph = depsDevClient.fetchGraph(root);
      if (graph.isEmpty()) {
        return RootOutcome.failed(
            root,
            new UnresolvedRoot(
                key,
                UnresolvedReason.NOT_FOUND,
                "deps.dev has no dependency graph for this version"));
      }
      List<DepsDevGraph.Node> nodes = graph.get().nodes();
      if (nodes == null || nodes.isEmpty()) {
        return RootOutcome.failed(
            root,
            new UnresolvedRoot(
                key, UnresolvedReason.EMPTY_GRAPH, "deps.dev returned a graph without nodes"));
      }
      return new RootOutcome(root, graph.get(), null);
    } catch (RuntimeException ex) {
      logger.warn("deps.dev request failed for {}: {}", key, ex.getClass().getSimpleName());
      return RootOutcome.failed(
          root,
          new UnresolvedRoot(
              key,
              UnresolvedReason.UPSTREAM_ERROR,
              "deps.dev request failed (" + ex.getClass().getSimpleName() + "); retry later"));
    }
  }

  private static ParsedRoots parseRoots(String dependencies) {
    List<String> errors = new ArrayList<>();
    Set<MavenCoordinate> roots = new LinkedHashSet<>();
    if (dependencies == null || dependencies.isBlank()) {
      return new ParsedRoots(
          List.of(), List.of("dependencies must contain at least one groupId:artifactId:version"));
    }
    for (String raw : dependencies.split("[,\\r\\n]+")) {
      String entry = raw.trim();
      if (entry.isEmpty()) continue;
      try {
        MavenCoordinate coordinate = MavenCoordinateParser.parse(entry);
        if (coordinate.version() == null) {
          errors.add("'" + entry + "' has no version; use groupId:artifactId:version");
        } else if (coordinate.packaging() != null || coordinate.classifier() != null) {
          errors.add("'" + entry + "': only groupId:artifactId:version is supported");
        } else {
          roots.add(coordinate);
        }
      } catch (IllegalArgumentException ex) {
        errors.add("'" + entry + "': " + ex.getMessage());
      }
    }
    if (roots.isEmpty() && errors.isEmpty()) {
      errors.add("dependencies must contain at least one groupId:artifactId:version");
    }
    return new ParsedRoots(List.copyOf(roots), errors);
  }

  private record ParsedRoots(List<MavenCoordinate> roots, List<String> errors) {}

  private record RootOutcome(MavenCoordinate root, DepsDevGraph graph, UnresolvedRoot failure) {
    static RootOutcome failed(MavenCoordinate root, UnresolvedRoot failure) {
      return new RootOutcome(root, null, failure);
    }
  }

  /** One distinct {@code g:a:v} with, per root, one shortest path from that root. */
  private static final class NodeAgg {
    final MavenCoordinate coordinate;
    final Map<String, List<String>> pathsByRoot = new LinkedHashMap<>();

    NodeAgg(MavenCoordinate coordinate) {
      this.coordinate = coordinate;
    }
  }

  /** Unions the nodes of all graphs and records data-quality notices. */
  private static final class Collector {
    final Map<String, NodeAgg> union = new LinkedHashMap<>();
    private final List<String> graphErrors = new ArrayList<>();
    private final List<String> nodeErrors = new ArrayList<>();
    private int nodesWithErrors;
    private int unreachable;
    private int ignored;

    void add(MavenCoordinate root, DepsDevGraph graph) {
      String rootKey = root.toCoordinateString();
      List<DepsDevGraph.Node> nodes = graph.nodes();
      if (graph.error() != null && !graph.error().isBlank()) {
        graphErrors.add(
            "deps.dev reported an error for the graph of " + rootKey + ": " + graph.error());
      }
      int[] parent = shortestPathParents(nodes, graph.edges());
      for (int i = 0; i < nodes.size(); i++) {
        DepsDevGraph.Node node = nodes.get(i);
        if (node.errors() != null && !node.errors().isEmpty()) {
          nodesWithErrors++;
          if (nodeErrors.size() < MAX_LISTED_NODE_ERRORS) {
            nodeErrors.add(label(node) + " (in graph of " + rootKey + "): " + node.errors().get(0));
          }
        }
        if (parent[i] == UNREACHED) {
          unreachable++;
          continue;
        }
        MavenCoordinate coordinate = i == 0 ? root : mavenCoordinate(node);
        if (coordinate == null) {
          ignored++;
          continue;
        }
        List<String> path = new ArrayList<>();
        for (int at = i; at != -1; at = parent[at]) {
          path.add(at == 0 ? rootKey : label(nodes.get(at)));
        }
        Collections.reverse(path);
        NodeAgg agg =
            union.computeIfAbsent(coordinate.toCoordinateString(), k -> new NodeAgg(coordinate));
        agg.pathsByRoot.merge(rootKey, List.copyOf(path), (a, b) -> b.size() < a.size() ? b : a);
      }
    }

    List<String> warnings() {
      List<String> warnings = new ArrayList<>(graphErrors);
      if (nodesWithErrors > 0) {
        warnings.add(
            "deps.dev flagged "
                + nodesWithErrors
                + " graph nodes with resolution errors, so those graphs may be incomplete: "
                + String.join("; ", nodeErrors));
      }
      if (unreachable > 0) {
        warnings.add(unreachable + " graph nodes were unreachable from their root and ignored.");
      }
      if (ignored > 0) {
        warnings.add(
            ignored
                + " graph nodes were not plain Maven artifacts (e.g. bundled) and were skipped.");
      }
      return warnings;
    }

    private static final int UNREACHED = -2;

    /** BFS from node 0; returns each node's predecessor (-1 for the root, -2 if unreachable). */
    private static int[] shortestPathParents(
        List<DepsDevGraph.Node> nodes, List<DepsDevGraph.Edge> edges) {
      int n = nodes.size();
      List<List<Integer>> adjacency = new ArrayList<>();
      for (int i = 0; i < n; i++) adjacency.add(new ArrayList<>());
      if (edges != null) {
        for (DepsDevGraph.Edge edge : edges) {
          if (edge.fromNode() >= 0
              && edge.fromNode() < n
              && edge.toNode() >= 0
              && edge.toNode() < n) {
            adjacency.get(edge.fromNode()).add(edge.toNode());
          }
        }
      }
      int[] parent = new int[n];
      java.util.Arrays.fill(parent, UNREACHED);
      parent[0] = -1;
      ArrayDeque<Integer> queue = new ArrayDeque<>(List.of(0));
      while (!queue.isEmpty()) {
        int current = queue.poll();
        for (int next : adjacency.get(current)) {
          if (parent[next] == UNREACHED) {
            parent[next] = current;
            queue.add(next);
          }
        }
      }
      return parent;
    }

    private static String label(DepsDevGraph.Node node) {
      DepsDevGraph.VersionKey key = node.versionKey();
      return key == null ? "?" : key.name() + ":" + key.version();
    }

    private static MavenCoordinate mavenCoordinate(DepsDevGraph.Node node) {
      DepsDevGraph.VersionKey key = node.versionKey();
      if (node.bundled()
          || key == null
          || !"MAVEN".equalsIgnoreCase(key.system())
          || key.name() == null
          || key.version() == null
          || key.version().isBlank()) {
        return null;
      }
      String[] parts = key.name().split(":", -1);
      if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) return null;
      return MavenCoordinate.of(parts[0], parts[1], key.version());
    }
  }
}
