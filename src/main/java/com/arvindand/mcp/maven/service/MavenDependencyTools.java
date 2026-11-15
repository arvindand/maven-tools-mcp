package com.arvindand.mcp.maven.service;

import com.arvindand.mcp.maven.MavenToolsConstants;
import com.arvindand.mcp.maven.config.Context7Properties;
import com.arvindand.mcp.maven.model.BulkCheckResult;
import com.arvindand.mcp.maven.model.Context7Guidance;
import com.arvindand.mcp.maven.model.DependencyAge;
import com.arvindand.mcp.maven.model.DependencyAgeAnalysis;
import com.arvindand.mcp.maven.model.DependencyInfo;
import com.arvindand.mcp.maven.model.MavenArtifact;
import com.arvindand.mcp.maven.model.MavenCoordinate;
import com.arvindand.mcp.maven.model.McpError;
import com.arvindand.mcp.maven.model.NeedsAttention;
import com.arvindand.mcp.maven.model.PomSecuritySummary;
import com.arvindand.mcp.maven.model.PomUpgradeRecommendation;
import com.arvindand.mcp.maven.model.ProjectHealthAnalysis;
import com.arvindand.mcp.maven.model.ReleasePatternAnalysis;
import com.arvindand.mcp.maven.model.Relocation;
import com.arvindand.mcp.maven.model.RelocationInfo;
import com.arvindand.mcp.maven.model.StabilityFilter;
import com.arvindand.mcp.maven.model.ToolResponse;
import com.arvindand.mcp.maven.model.UpgradeAction;
import com.arvindand.mcp.maven.model.UpgradeMode;
import com.arvindand.mcp.maven.model.VersionComparison;
import com.arvindand.mcp.maven.model.VersionInfo;
import com.arvindand.mcp.maven.model.VersionInfo.VersionType;
import com.arvindand.mcp.maven.model.VersionsByType;
import com.arvindand.mcp.maven.model.license.LicenseFindings;
import com.arvindand.mcp.maven.model.license.LicenseInfo;
import com.arvindand.mcp.maven.model.license.LicenseInfo.LicenseCategory;
import com.arvindand.mcp.maven.model.security.SecurityAssessment;
import com.arvindand.mcp.maven.model.security.SecurityFindings;
import com.arvindand.mcp.maven.model.security.SecuritySummary;
import com.arvindand.mcp.maven.pom.EffectiveDependency;
import com.arvindand.mcp.maven.pom.EffectivePomResolver;
import com.arvindand.mcp.maven.pom.EffectivePomResult;
import com.arvindand.mcp.maven.pom.ManagedAlternative;
import com.arvindand.mcp.maven.pom.ManagedDeclaration;
import com.arvindand.mcp.maven.pom.PluginDeclaration;
import com.arvindand.mcp.maven.pom.PluginDependencyDeclaration;
import com.arvindand.mcp.maven.pom.PropertyOverride;
import com.arvindand.mcp.maven.pom.Source;
import com.arvindand.mcp.maven.util.BoundedBatch;
import com.arvindand.mcp.maven.util.MavenCoordinateParser;
import com.arvindand.mcp.maven.util.VersionComparator;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

/**
 * Main service providing MCP tools for Maven dependency management.
 *
 * @author Arvind Menon
 * @since 0.1.0
 */
@Service
public class MavenDependencyTools {

  private static final Logger logger = LoggerFactory.getLogger(MavenDependencyTools.class);

  // Error message constants
  private static final String UNEXPECTED_ERROR = "Unexpected error";
  private static final String INVALID_MAVEN_COORDINATE_FORMAT = "Invalid Maven coordinate format: ";
  private static final String SUCCESS_STATUS = "success";
  private static final String ERROR_STATUS = "error";
  private static final String MAJOR_UPDATE_TYPE = "major";
  private static final String MINOR_UPDATE_TYPE = "minor";
  private static final String PATCH_UPDATE_TYPE = "patch";
  private static final String NO_UPDATE_TYPE = "none";
  private static final String ACTIVE_MAINTENANCE = "active";
  private static final String MODERATE_MAINTENANCE = "moderate";
  private static final String SLOW_MAINTENANCE = "slow";

  // Health level constants
  private static final String EXCELLENT_HEALTH = "excellent";
  private static final String GOOD_HEALTH = "good";
  private static final String FAIR_HEALTH = "fair";
  private static final String POOR_HEALTH = "poor";

  // Age classification constants
  private static final String FRESH_AGE = "fresh";
  private static final String CURRENT_AGE = "current";
  private static final String AGING_AGE = "aging";
  private static final String STALE_AGE = "stale";

  // Analysis constants
  private static final int DEFAULT_ANALYSIS_MONTHS = 24;
  private static final int ACCURATE_TIMESTAMP_VERSION_LIMIT = 30;
  private static final int RECENT_VERSIONS_LIMIT = 10;
  private static final int MILLISECONDS_TO_DAYS = 1000 * 60 * 60 * 24;
  private static final int DAYS_IN_MONTH = 30;

  // Health scoring constants
  private static final int PERFECT_HEALTH_SCORE = 100;
  private static final int CURRENT_VERSION_PENALTY = 10;
  private static final int AGING_VERSION_PENALTY = 30;
  private static final int STALE_VERSION_PENALTY = 60;
  private static final int MODERATE_MAINTENANCE_PENALTY = 15;
  private static final int SLOW_MAINTENANCE_PENALTY = 40;
  private static final int AGE_THRESHOLD_PENALTY = 20;

  // Health classification thresholds
  private static final int EXCELLENT_HEALTH_THRESHOLD = 80;
  private static final int GOOD_HEALTH_THRESHOLD = 65;
  private static final int FAIR_HEALTH_THRESHOLD = 50;
  private final MavenCentralService mavenCentralService;
  private final VersionComparator versionComparator;
  private final Context7Properties context7Properties;
  private final VulnerabilityService vulnerabilityService;
  private final EffectivePomResolver pomResolver;

  private static final int MAX_CONCURRENT_REQUESTS = MavenToolsConstants.MAX_CONCURRENT_REQUESTS;
  private final Semaphore batchSemaphore = new Semaphore(MAX_CONCURRENT_REQUESTS);

  /**
   * Time a bulk call may spend starting relocation lookups; see {@link RelocationLookup}. Kept well
   * under the batch deadline so the version results always have room to finish.
   */
  static final Duration DEFAULT_BULK_RELOCATION_BUDGET = Duration.ofSeconds(8);

  private Duration bulkRelocationBudget = DEFAULT_BULK_RELOCATION_BUDGET;

  /** Overrides the bulk relocation budget; for tests. */
  void setBulkRelocationBudget(Duration budget) {
    this.bulkRelocationBudget = budget;
  }

  public MavenDependencyTools(
      MavenCentralService mavenCentralService,
      VersionComparator versionComparator,
      Context7Properties context7Properties,
      VulnerabilityService vulnerabilityService,
      EffectivePomResolver pomResolver) {
    this.mavenCentralService = mavenCentralService;
    this.versionComparator = versionComparator;
    this.context7Properties = context7Properties;
    this.vulnerabilityService = vulnerabilityService;
    this.pomResolver = pomResolver;
  }

  /**
   * Common error handling for tool operations that return data objects.
   *
   * @param operation the operation to execute
   * @param <T> the return type
   * @return ToolResponse containing either success result or error
   */
  private <T> ToolResponse executeToolOperation(ToolOperation<T> operation) {
    try {
      T result = operation.execute();
      if (result instanceof ToolResponse response) {
        return response;
      }
      return ToolResponse.Success.of(result);
    } catch (IllegalArgumentException e) {
      return ToolResponse.Error.of(
          McpError.invalidInput(INVALID_MAVEN_COORDINATE_FORMAT + e.getMessage(), Map.of()));
    } catch (Exception e) {
      return toolFailure(e);
    }
  }

  /**
   * Maps a non-validation failure to a tool error. Recognised upstream failures (rate limiting,
   * open circuit, HTTP / I/O errors) become precise, retryable {@link McpError}s; anything else is
   * a genuine internal error and is logged at error level.
   */
  private static ToolResponse toolFailure(Exception e) {
    if (e instanceof BoundedBatch.DeadlineExceededException) {
      // Running lookups are left to finish and are cached, so a retry resumes where this stopped.
      logger.warn("Batch deadline exceeded: {}", e.getMessage());
      return ToolResponse.Error.of(McpError.batchTimeout());
    }
    if (MavenCentralException.isUpstreamFailure(e)) {
      MavenCentralException upstream = MavenCentralException.classify(e);
      logger.warn("Maven repository failure ({}): {}", upstream.getReason(), upstream.getMessage());
      return ToolResponse.Error.of(upstream.toMcpError());
    }
    logger.error(UNEXPECTED_ERROR, e);
    return ToolResponse.Error.of(UNEXPECTED_ERROR + ": " + e.getMessage());
  }

  /** Human-readable per-dependency message for a classified upstream failure. */
  private static String describe(MavenCentralException failure) {
    Integer hint = failure.getRetryAfterSeconds();
    boolean retryable =
        failure.getReason() == MavenCentralException.Reason.RATE_LIMITED
            || failure.getReason() == MavenCentralException.Reason.CIRCUIT_OPEN;
    return retryable && hint != null
        ? failure.getMessage() + "; retry in " + McpError.describeRetry(hint)
        : failure.getMessage();
  }

  /**
   * Collects, per call, the dependencies whose failure was a recognised upstream failure, so that a
   * bulk call in which every dependency failed that way becomes one precise tool-level error
   * instead of a success envelope full of identical per-dependency errors.
   */
  private static final class UpstreamFailures {
    private final Map<String, MavenCentralException> byDependency = new ConcurrentHashMap<>();

    /** Returns the classified failure when {@code e} is an upstream failure, else null. */
    @Nullable MavenCentralException record(String dependency, RuntimeException e) {
      if (!MavenCentralException.isUpstreamFailure(e)) {
        return null;
      }
      MavenCentralException classified = MavenCentralException.classify(e);
      byDependency.putIfAbsent(dependency, classified);
      return classified;
    }

    /** Throws the most informative failure when all {@code total} dependencies failed upstream. */
    void throwIfAllFailed(int total) {
      if (total <= 0 || byDependency.size() < total) {
        return;
      }
      MavenCentralException best = null;
      for (MavenCentralException candidate : byDependency.values()) {
        if (best == null
            || priority(candidate) < priority(best)
            || (priority(candidate) == priority(best) && hint(candidate) > hint(best))) {
          best = candidate;
        }
      }
      throw best;
    }

    private static int priority(MavenCentralException e) {
      return switch (e.getReason()) {
        case RATE_LIMITED -> 0;
        case CIRCUIT_OPEN -> 1;
        case UPSTREAM_ERROR -> 2;
        case INVALID_RESPONSE -> 3;
      };
    }

    private static int hint(MavenCentralException e) {
      return e.getRetryAfterSeconds() == null ? 0 : e.getRetryAfterSeconds();
    }
  }

  /** Per-dependency failure text and retry hint for result records. */
  private record FailureText(String message, @Nullable Integer retryAfterSeconds) {}

  private FailureText failureText(
      UpstreamFailures failures, String dependency, RuntimeException e) {
    MavenCentralException classified = failures.record(dependency, e);
    if (classified == null) {
      return new FailureText(e.getMessage(), null);
    }
    return new FailureText(describe(classified), classified.getRetryAfterSeconds());
  }

  /**
   * Best-effort relocation lookup, one per distinct {@code groupId:artifactId} per call. Looks at
   * the newest published version of the old coordinates (relocation stubs are published as the
   * newest versions) and, when it relocates, at the newest stable version of the new coordinates.
   * Any failure yields no relocation; it never fails a tool or marks a result incomplete.
   */
  private final class RelocationLookup {
    private final Map<String, CompletableFuture<Optional<RelocationInfo>>> memo =
        new ConcurrentHashMap<>();
    private final long startedNanos = System.nanoTime();
    private final long budgetNanos;

    /** Looks up every coordinate it is asked about. */
    RelocationLookup() {
      this.budgetNanos = Long.MAX_VALUE;
    }

    /**
     * Stops starting new lookups once {@code budget} has elapsed. A lookup costs a POM fetch on a
     * cold cache, which in a bulk call competes with the version lookups for the same rate limit
     * and batch deadline; relocation is supplementary, so it yields first.
     */
    RelocationLookup(Duration budget) {
      this.budgetNanos = budget.toNanos();
    }

    @Nullable RelocationInfo find(MavenCoordinate coordinate) {
      return find(coordinate.groupId(), coordinate.artifactId());
    }

    @Nullable RelocationInfo find(String groupId, String artifactId) {
      if (System.nanoTime() - startedNanos >= budgetNanos) {
        return null;
      }
      String key = groupId + ":" + artifactId;
      CompletableFuture<Optional<RelocationInfo>> mine = new CompletableFuture<>();
      CompletableFuture<Optional<RelocationInfo>> existing = memo.putIfAbsent(key, mine);
      if (existing != null) {
        return existing.join().orElse(null);
      }
      mine.complete(Optional.ofNullable(compute(groupId, artifactId)));
      return mine.join().orElse(null);
    }

    @Nullable
    private RelocationInfo compute(String groupId, String artifactId) {
      try {
        Optional<Relocation> found =
            mavenCentralService.findRelocationOfLatest(MavenCoordinate.of(groupId, artifactId));
        if (found.isEmpty()) {
          return null;
        }
        Relocation relocation = found.get();
        if (groupId.equals(relocation.groupId()) && artifactId.equals(relocation.artifactId())) {
          return null; // a version-only relocation is not a coordinate change
        }
        return RelocationInfo.of(
            relocation, latestStableOf(relocation.groupId(), relocation.artifactId()));
      } catch (RuntimeException e) {
        logger.debug("Relocation lookup failed for {}:{}: {}", groupId, artifactId, e.getMessage());
        return null;
      }
    }

    @Nullable
    private String latestStableOf(String groupId, String artifactId) {
      try {
        List<String> versions =
            mavenCentralService.getAllVersions(MavenCoordinate.of(groupId, artifactId));
        return versions == null
            ? null
            : versions.stream().filter(versionComparator::isStableVersion).findFirst().orElse(null);
      } catch (RuntimeException e) {
        logger.debug(
            "Latest version lookup failed for {}:{}: {}", groupId, artifactId, e.getMessage());
        return null;
      }
    }
  }

  @FunctionalInterface
  private interface ToolOperation<T> {
    T execute() throws IllegalArgumentException, MavenCentralException;
  }

  /**
   * Get the latest version of any dependency from Maven Central (works with Maven, Gradle, SBT,
   * Mill).
   *
   * @param dependency the dependency coordinate (groupId:artifactId)
   * @param stabilityFilter controls version filtering: ALL, STABLE_ONLY, or PREFER_STABLE (default)
   * @return JSON response with latest versions by type
   */
  @SuppressWarnings("java:S100") // MCP tool method naming
  @Tool(
      description =
          "Single dependency. Returns newest versions by type (stable/rc/beta/alpha/milestone) and"
              + " any relocation to new coordinates. Set"
              + " stabilityFilter to ALL, STABLE_ONLY, or PREFER_STABLE (default). Use when asked:"
              + " 'what's the latest version of X?' Results span platform variants; for an"
              + " existing dependency use compare_dependency_versions to preserve its variant."
              + " Works with all JVM build tools.")
  public ToolResponse get_latest_version(
      @ToolParam(
              description =
                  "Maven dependency coordinate in format 'groupId:artifactId' (NO version)."
                      + " Example: 'org.springframework:spring-core'")
          String dependency,
      @ToolParam(
              description =
                  "Stability filter: ALL (all versions), STABLE_ONLY (production-ready only), or"
                      + " PREFER_STABLE (prioritize stable, show others too). Default:"
                      + " PREFER_STABLE",
              required = false)
          @Nullable StabilityFilter stabilityFilter) {
    return executeToolOperation(
        () -> {
          MavenCoordinate coordinate = MavenCoordinateParser.parse(dependency);
          List<String> allVersions = mavenCentralService.getAllVersions(coordinate);

          if (allVersions.isEmpty()) {
            return notFoundResponse(coordinate);
          }

          StabilityFilter filter =
              stabilityFilter != null ? stabilityFilter : StabilityFilter.PREFER_STABLE;
          return buildVersionsByType(coordinate, allVersions, filter)
              .withRelocation(new RelocationLookup().find(coordinate));
        });
  }

  /**
   * Check if specific dependency version exists and identify its stability type.
   *
   * @param dependency the dependency coordinate (groupId:artifactId)
   * @param version the version to check
   * @return JSON response with existence status and version type
   */
  @SuppressWarnings("java:S100") // MCP tool method naming
  @Tool(
      description =
          "Single dependency + version. Validates existence on Maven Central and classifies its"
              + " stability (stable/rc/beta/alpha/milestone/snapshot). Use when asked: 'does X:Y"
              + " exist?' or 'is version V stable?'")
  public ToolResponse check_version_exists(
      @ToolParam(
              description =
                  "Maven dependency coordinate in format 'groupId:artifactId' (NO version)."
                      + " Example: 'org.springframework:spring-core'")
          String dependency,
      @ToolParam(
              description =
                  "Specific version string to check for existence. Example: '6.1.4' or"
                      + " '2.7.18-SNAPSHOT'")
          String version) {
    return executeToolOperation(
        () -> {
          MavenCoordinate coordinate = MavenCoordinateParser.parse(dependency);
          String versionToCheck = coordinate.version() != null ? coordinate.version() : version;

          if (versionToCheck == null || versionToCheck.trim().isEmpty()) {
            throw new IllegalArgumentException(
                "Version must be provided either in dependency string or version parameter");
          }

          boolean exists = mavenCentralService.checkVersionExists(coordinate, versionToCheck);
          String versionType = versionComparator.getVersionTypeString(versionToCheck);

          return DependencyInfo.success(
              coordinate,
              versionToCheck,
              exists,
              versionType,
              versionComparator.isStableVersion(versionToCheck),
              null);
        });
  }

  /**
   * Check latest versions for multiple dependencies with filtering options.
   *
   * @param dependencies comma or newline separated list of dependency coordinates
   * @param stabilityFilter controls version filtering: ALL, STABLE_ONLY, or PREFER_STABLE
   * @return JSON response with bulk check results
   */
  @SuppressWarnings("java:S100") // MCP tool method naming
  @Tool(
      description =
          "Bulk lookup (NO versions in input). Returns latest versions by type (and any"
              + " relocation) for each dependency. Use when you have a list of dependencies without versions and need to"
              + " find what versions are available. For upgrade checks, use compare_dependency_versions"
              + " instead.")
  public ToolResponse check_multiple_dependencies(
      @ToolParam(
              description =
                  "Comma or newline separated list of Maven dependency coordinates in format"
                      + " 'groupId:artifactId' (NO versions). Example:"
                      + " 'org.springframework:spring-core,junit:junit'")
          String dependencies,
      @ToolParam(
              description =
                  "Stability filter: ALL (all versions), STABLE_ONLY (production-ready only), or"
                      + " PREFER_STABLE (prioritize stable). Default: ALL",
              required = false)
          @Nullable StabilityFilter stabilityFilter) {
    return executeToolOperation(
        () -> {
          List<String> depList = parseDependencies(dependencies);
          StabilityFilter filter = stabilityFilter != null ? stabilityFilter : StabilityFilter.ALL;

          List<String> distinct = depList.stream().distinct().toList();
          UpstreamFailures failures = new UpstreamFailures();
          RelocationLookup relocations = new RelocationLookup(bulkRelocationBudget);
          List<BulkCheckResult> results =
              processBatchWithBackpressure(
                  distinct, dep -> processVersionCheck(dep, filter, failures, relocations));
          failures.throwIfAllFailed(distinct.size());
          return results;
        });
  }

  /**
   * Compare current dependency versions with latest available and show upgrade recommendations.
   *
   * @param currentDependencies comma or newline separated list of dependency coordinates with
   *     versions
   * @param stabilityFilter controls upgrade targets: ALL, STABLE_ONLY, or PREFER_STABLE
   * @param includeSecurityScan whether to scan for vulnerabilities (default: true)
   * @return JSON response with version comparison and update recommendations
   */
  @SuppressWarnings("java:S100") // MCP tool method naming
  @Tool(
      description =
          "Bulk upgrade check (versions REQUIRED in input). Compares current versions to latest and"
              + " suggests upgrades (major/minor/patch), flags relocated artifacts. Includes CVE"
              + " vulnerability scanning."
              + " Use when user provides their current dependency versions and wants upgrade advice.")
  public ToolResponse compare_dependency_versions(
      @ToolParam(
              description =
                  "Comma or newline separated list of dependency coordinates WITH versions in"
                      + " format 'groupId:artifactId:version'. Example:"
                      + " 'org.springframework:spring-core:6.0.0,junit:junit:4.12'")
          String currentDependencies,
      @ToolParam(
              description =
                  "Stability filter: ALL (any version), STABLE_ONLY (production-ready only), or"
                      + " PREFER_STABLE (prioritize stable). Default: ALL",
              required = false)
          @Nullable StabilityFilter stabilityFilter,
      @ToolParam(
              description =
                  "Whether to scan dependencies for known vulnerabilities using OSV.dev. Default:"
                      + " true",
              required = false)
          @Nullable Boolean includeSecurityScan) {
    return executeToolOperation(
        () -> {
          List<String> depList = parseDependencies(currentDependencies);
          StabilityFilter filter = stabilityFilter != null ? stabilityFilter : StabilityFilter.ALL;
          boolean scanSecurity = includeSecurityScan == null || includeSecurityScan;

          final Map<String, SecurityAssessment> securityAssessments =
              scanSecurity ? scanDependenciesForVulnerabilityAssessments(depList) : Map.of();
          final SecuritySummary securitySummary =
              scanSecurity ? SecuritySummary.fromAssessments(securityAssessments) : null;

          List<String> distinct = depList.stream().distinct().toList();
          UpstreamFailures failures = new UpstreamFailures();
          RelocationLookup relocations = new RelocationLookup(bulkRelocationBudget);
          List<VersionComparison.DependencyComparisonResult> results =
              processBatchWithBackpressure(
                  distinct,
                  dep ->
                      compareDependencyVersion(
                          dep, filter, securityAssessments, failures, relocations));
          failures.throwIfAllFailed(distinct.size());

          VersionComparison.UpdateSummary summary = calculateUpdateSummary(results);

          return new VersionComparison(Instant.now(), results, summary, securitySummary);
        });
  }

  private Map<String, SecurityAssessment> scanDependenciesForVulnerabilityAssessments(
      List<String> dependencies) {
    List<MavenCoordinate> coordinates =
        dependencies.stream()
            .map(
                dep -> {
                  try {
                    return MavenCoordinateParser.parse(dep);
                  } catch (RuntimeException _) {
                    return null;
                  }
                })
            .filter(coord -> coord != null && coord.version() != null)
            .toList();

    if (coordinates.isEmpty()) {
      return Map.of();
    }

    return vulnerabilityService.scanBulk(coordinates);
  }

  /**
   * Analyze dependency age and freshness classification with actionable insights.
   *
   * @param dependency the dependency coordinate (groupId:artifactId)
   * @param maxAgeInDays optional maximum acceptable age in days (default: no limit)
   * @return JSON response with age analysis and recommendations
   */
  @SuppressWarnings("java:S100") // MCP tool method naming
  @Tool(
      description =
          "Single dependency. Returns days since last release and freshness"
              + " (fresh/current/aging/stale), with actionable insights. Use when asked about 'how"
              + " old' or 'last release' of a library.")
  public ToolResponse analyze_dependency_age(
      @ToolParam(
              description =
                  "Maven dependency coordinate in format 'groupId:artifactId' (NO version)."
                      + " Example: 'org.springframework:spring-core'")
          String dependency,
      @ToolParam(
              description =
                  "Optional maximum acceptable age threshold in days. If specified and dependency"
                      + " exceeds this age, additional recommendations are provided. No limit if"
                      + " not specified",
              required = false)
          @Nullable Integer maxAgeInDays) {
    return executeToolOperation(
        () -> {
          MavenCoordinate coordinate = MavenCoordinateParser.parse(dependency);
          List<MavenArtifact> versions =
              mavenCentralService.getRecentVersionsWithAccurateTimestamps(coordinate, 1);

          if (versions.isEmpty()) {
            return notFoundResponse(coordinate);
          }

          MavenArtifact latestVersion = versions.get(0);
          DependencyAgeAnalysis basicAnalysis =
              DependencyAgeAnalysis.fromTimestamp(
                  coordinate.toCoordinateString(),
                  latestVersion.version(),
                  latestVersion.timestamp());

          // Add custom recommendation if maxAgeInDays is specified
          DependencyAgeAnalysis analysis = basicAnalysis;
          if (maxAgeInDays != null && basicAnalysis.daysSinceLastRelease() > maxAgeInDays) {
            analysis =
                new DependencyAgeAnalysis(
                    basicAnalysis.dependency(),
                    basicAnalysis.latestVersion(),
                    basicAnalysis.ageClassification(),
                    basicAnalysis.daysSinceLastRelease(),
                    basicAnalysis.lastReleaseDate(),
                    basicAnalysis.ageDescription(),
                    "Exceeds specified age threshold of "
                        + maxAgeInDays
                        + " days - "
                        + basicAnalysis.recommendation());
          }

          // Create response with basic analysis
          return DependencyAge.from(analysis, context7Properties.enabled());
        });
  }

  /**
   * Analyze release patterns and maintenance activity to predict future releases.
   *
   * @param dependency the dependency coordinate (groupId:artifactId)
   * @param monthsToAnalyze number of months of history to analyze (default: 24)
   * @return JSON response with release pattern analysis and predictions
   */
  @SuppressWarnings("java:S100") // MCP tool method naming
  @Tool(
      description =
          "Single dependency. Analyzes historical releases to infer cadence, consistency, and"
              + " likely next-release timeframe. Useful for maintenance and planning.")
  public ToolResponse analyze_release_patterns(
      @ToolParam(
              description =
                  "Maven dependency coordinate in format 'groupId:artifactId' (NO version)."
                      + " Example: 'com.fasterxml.jackson.core:jackson-core'")
          String dependency,
      @ToolParam(
              description =
                  "Number of months of historical release data to analyze for patterns and"
                      + " predictions. Default is 24 months if not specified",
              required = false)
          @Nullable Integer monthsToAnalyze) {
    return executeToolOperation(
        () -> {
          MavenCoordinate coordinate = MavenCoordinateParser.parse(dependency);
          int analysisMonths = monthsToAnalyze != null ? monthsToAnalyze : DEFAULT_ANALYSIS_MONTHS;

          List<MavenArtifact> allVersions =
              mavenCentralService.getRecentVersionsWithAccurateTimestamps(
                  coordinate, ACCURATE_TIMESTAMP_VERSION_LIMIT);

          if (allVersions.isEmpty()) {
            return notFoundResponse(coordinate);
          }

          return analyzeReleasePattern(
              coordinate.toCoordinateString(), allVersions, analysisMonths);
        });
  }

  /**
   * Analyze overall health of multiple dependencies with age and maintenance insights.
   *
   * @param dependencies comma or newline separated list of dependency coordinates
   * @param maxAgeInDays optional maximum acceptable age in days for health scoring
   * @param stabilityFilter controls recommendations: ALL, STABLE_ONLY, or PREFER_STABLE
   * @param includeSecurityScan whether to scan for vulnerabilities (default: true)
   * @param includeLicenseScan whether to check license information (default: true)
   * @return JSON response with project health summary and individual dependency analysis
   */
  @SuppressWarnings("java:S100") // MCP tool method naming
  @Tool(
      description =
          "Bulk project view (PREFERRED for full audits). Summarizes health across many"
              + " dependencies using age and maintenance patterns. Includes CVE vulnerability"
              + " scanning, license compliance and relocation hints. Include versions (groupId:artifactId:version) to"
              + " audit what you actually run; without one the newest version per stabilityFilter"
              + " is scanned (reported as scannedVersion). Use this instead of"
              + " check_multiple_dependencies when you need security/license info or overall"
              + " project health assessment.")
  public ToolResponse analyze_project_health(
      @ToolParam(
              description =
                  "Comma or newline separated Maven coordinates 'groupId:artifactId' or"
                      + " 'groupId:artifactId:version' (versions optional, recommended for a real"
                      + " audit). Example: 'org.springframework:spring-core:6.0.0,junit:junit'")
          String dependencies,
      @ToolParam(
              description =
                  "Optional maximum acceptable age threshold in days for health scoring."
                      + " Dependencies exceeding this age receive lower health scores. No age"
                      + " penalty if not specified",
              required = false)
          @Nullable Integer maxAgeInDays,
      @ToolParam(
              description =
                  "Stability filter for the latest version (and the scanned version when none is"
                      + " given): ALL, STABLE_ONLY, or PREFER_STABLE. Default: PREFER_STABLE",
              required = false)
          @Nullable StabilityFilter stabilityFilter,
      @ToolParam(
              description =
                  "Whether to scan dependencies for known vulnerabilities using OSV.dev. Default:"
                      + " true",
              required = false)
          @Nullable Boolean includeSecurityScan,
      @ToolParam(
              description = "Whether to check license information for dependencies. Default: true",
              required = false)
          @Nullable Boolean includeLicenseScan) {
    return executeToolOperation(
        () -> {
          List<String> depList = parseDependencies(dependencies);

          if (depList.isEmpty()) {
            throw new IllegalArgumentException("No dependencies provided for analysis");
          }

          boolean scanSecurity = includeSecurityScan == null || includeSecurityScan;
          boolean scanLicenses = includeLicenseScan == null || includeLicenseScan;
          StabilityFilter filter =
              stabilityFilter != null ? stabilityFilter : StabilityFilter.PREFER_STABLE;

          List<String> distinctDeps = depList.stream().distinct().toList();
          // Decide once, per dependency, which version is audited (supplied or latest per filter)
          UpstreamFailures failures = new UpstreamFailures();
          RelocationLookup relocations = new RelocationLookup(bulkRelocationBudget);
          final Map<String, HealthTarget> targets =
              resolveHealthTargets(distinctDeps, filter, failures);
          failures.throwIfAllFailed(distinctDeps.size());

          final Map<String, SecurityAssessment> securityByDependency =
              scanSecurity ? scanTargetSecurity(targets) : Map.of();
          final Map<String, List<LicenseInfo>> licensesByDependency =
              scanLicenses ? scanTargetLicenses(targets) : Map.of();

          // Analyze each dependency for age and patterns
          List<ProjectHealthAnalysis.DependencyHealthAnalysis> dependencyAnalyses =
              processBatchWithBackpressure(
                  distinctDeps,
                  dep ->
                      analyzeDependencyHealthWithSecurityAndLicenses(
                          dep,
                          targets.get(dep),
                          maxAgeInDays,
                          scanSecurity,
                          securityByDependency,
                          scanLicenses,
                          licensesByDependency,
                          failures,
                          relocations));
          failures.throwIfAllFailed(distinctDeps.size());

          // Perform security scan if requested
          SecurityFindings securityFindings = null;
          if (scanSecurity) {
            SecuritySummary summary = SecuritySummary.fromAssessments(securityByDependency);
            securityFindings = SecurityFindings.fromSummary(summary);
          }

          // Perform license scan if requested
          LicenseFindings licenseFindings = null;
          if (scanLicenses) {
            licenseFindings = buildLicenseFindings(licensesByDependency);
          }

          return buildSimpleHealthSummary(
              dependencyAnalyses, maxAgeInDays, securityFindings, licenseFindings);
        });
  }

  private <T> List<T> processBatchWithBackpressure(
      List<String> items, Function<String, T> processor) {
    return BoundedBatch.map(
        items,
        processor,
        batchSemaphore,
        java.time.Duration.ofSeconds(MavenToolsConstants.DEFAULT_BATCH_TIMEOUT_SECONDS));
  }

  private ProjectHealthAnalysis.DependencyHealthAnalysis
      analyzeDependencyHealthWithSecurityAndLicenses(
          String dependency,
          HealthTarget target,
          Integer maxAgeInDays,
          boolean scanSecurity,
          Map<String, SecurityAssessment> securityByDependency,
          boolean scanLicenses,
          Map<String, List<LicenseInfo>> licensesByDependency,
          UpstreamFailures failures,
          RelocationLookup relocations) {
    try {
      if (target == null) {
        return ProjectHealthAnalysis.DependencyHealthAnalysis.error(
            dependency, "Dependency could not be resolved");
      }
      if (!HealthTarget.OK.equals(target.status())) {
        return ProjectHealthAnalysis.DependencyHealthAnalysis.builder()
            .dependency(dependency)
            .status(target.status())
            .currentVersion(target.currentVersion())
            .error(target.error())
            .retryAfterSeconds(target.retryAfterSeconds())
            .build();
      }

      MavenCoordinate coordinate = target.coordinate();
      String scannedKey = target.scannedKey();

      List<MavenArtifact> versions =
          mavenCentralService.getRecentVersionsWithAccurateTimestamps(coordinate, 10);
      if (versions.isEmpty()) {
        return ProjectHealthAnalysis.DependencyHealthAnalysis.notFound(dependency);
      }

      // Freshness describes the latest version honouring the stability filter; maintenance
      // describes the artifact's recent release history.
      List<String> notes = new ArrayList<>();
      if (target.note() != null) {
        notes.add(target.note());
      }
      MavenArtifact latestArtifact =
          target.latestVersion() != null
              ? findArtifactTimestamp(coordinate, versions, target.latestVersion())
              : null;
      if (latestArtifact == null) {
        latestArtifact = versions.get(0);
        if (target.latestVersion() != null) {
          notes.add(
              "Release date of "
                  + target.latestVersion()
                  + " unavailable; freshness is based on the newest published version "
                  + latestArtifact.version());
        }
      }
      DependencyAgeAnalysis ageAnalysis =
          DependencyAgeAnalysis.fromTimestamp(
              coordinate.toCoordinateString(),
              latestArtifact.version(),
              latestArtifact.timestamp());

      String maintenanceStatus = determineMaintenanceStatus(versions);
      int freshnessScore = calculateFreshnessScore(ageAnalysis, maxAgeInDays);
      int maintenanceScore = calculateMaintenanceScore(maintenanceStatus);

      SecurityAssessment security =
          resolveSecurityAssessment(scanSecurity, scannedKey, securityByDependency);
      List<LicenseInfo> licenses = resolveLicenses(scanLicenses, scannedKey, licensesByDependency);

      int healthScore =
          (scanSecurity || scanLicenses)
              ? calculateHealthScoreWithSecurityAndLicense(
                  freshnessScore, maintenanceScore, security, licenses)
              : calculateSimpleHealthScore(ageAnalysis, maintenanceStatus, maxAgeInDays);

      String ageName = ageAnalysis.ageClassification().getName();
      var builder =
          ProjectHealthAnalysis.DependencyHealthAnalysis.builder()
              .dependency(dependency)
              .currentVersion(target.currentVersion())
              .scannedVersion(target.scannedVersion())
              .latestVersion(target.latestVersion())
              .ageClassification(ageName)
              .daysSinceRelease(ageAnalysis.daysSinceLastRelease())
              .healthScore(healthScore)
              .maintenanceLevel(maintenanceStatus)
              .security(security)
              .licenses((scanSecurity || scanLicenses) ? licenses : null)
              .note(notes.isEmpty() ? null : String.join("; ", notes))
              .relocation(relocations.find(coordinate));

      String updateType = null;
      boolean updateAvailable = false;
      if (target.currentVersion() != null && target.latestVersion() != null) {
        updateType =
            versionComparator.determineUpdateType(target.currentVersion(), target.latestVersion());
        updateAvailable =
            versionComparator.compare(target.currentVersion(), target.latestVersion()) < 0;
        builder.updateAvailable(updateAvailable).updateType(updateType);
      }
      if (target.currentVersion() != null) {
        MavenArtifact current =
            findArtifactTimestamp(coordinate, versions, target.currentVersion());
        if (current != null) {
          builder.currentVersionAgeDays(
              ChronoUnit.DAYS.between(Instant.ofEpochMilli(current.timestamp()), Instant.now()));
        }
      }

      if (context7Properties.enabled()) {
        if (updateAvailable) {
          builder.context7Query(Context7Guidance.migrationQuery(dependency, updateType));
        } else if (AGING_AGE.equals(ageName) || STALE_AGE.equals(ageName)) {
          builder.context7Query(Context7Guidance.modernizationQuery(dependency, ageName));
        }
      }

      return builder.build();
    } catch (RuntimeException e) {
      FailureText failure = failureText(failures, dependency, e);
      return ProjectHealthAnalysis.DependencyHealthAnalysis.builder()
          .dependency(dependency)
          .status(ERROR_STATUS)
          .error(failure.message())
          .retryAfterSeconds(failure.retryAfterSeconds())
          .build();
    }
  }

  /**
   * Finds a version's release timestamp in the already fetched recent versions, falling back to a
   * single (cached) lookup. Best effort: returns null when unavailable.
   */
  private MavenArtifact findArtifactTimestamp(
      MavenCoordinate coordinate, List<MavenArtifact> recent, String version) {
    for (MavenArtifact artifact : recent) {
      if (version.equals(artifact.version())) {
        return artifact.timestamp() > 0 ? artifact : null;
      }
    }
    try {
      MavenArtifact fetched = mavenCentralService.fetchArtifactWithTimestamp(coordinate, version);
      return fetched != null && fetched.timestamp() > 0 ? fetched : null;
    } catch (RuntimeException e) {
      logger.debug("Could not fetch release date of {}:{}", coordinate, version, e);
      return null;
    }
  }

  private String determineMaintenanceStatus(List<MavenArtifact> versions) {
    Instant now = Instant.now();
    Instant sixMonthsAgo = now.minus(6L * DAYS_IN_MONTH, ChronoUnit.DAYS);

    long recentVersions =
        versions.stream()
            .filter(v -> Instant.ofEpochMilli(v.timestamp()).isAfter(sixMonthsAgo))
            .count();

    if (recentVersions >= 3) {
      return ACTIVE_MAINTENANCE;
    }
    if (recentVersions >= 1) {
      return MODERATE_MAINTENANCE;
    }
    return SLOW_MAINTENANCE;
  }

  private SecurityAssessment resolveSecurityAssessment(
      boolean scanSecurity, String gaKey, Map<String, SecurityAssessment> securityByDependency) {
    if (!scanSecurity) {
      return null;
    }
    SecurityAssessment security =
        securityByDependency != null ? securityByDependency.get(gaKey) : null;
    return security != null
        ? security
        : SecurityAssessment.unknown("Security assessment unavailable");
  }

  private List<LicenseInfo> resolveLicenses(
      boolean scanLicenses, String gaKey, Map<String, List<LicenseInfo>> licensesByDependency) {
    if (!scanLicenses) {
      return List.of();
    }
    return licensesByDependency != null
        ? licensesByDependency.getOrDefault(gaKey, List.of())
        : List.of();
  }

  private static String gaOf(String dependency) {
    String[] parts = dependency.split(":");
    return parts.length >= 2 ? parts[0] + ":" + parts[1] : dependency;
  }

  private int calculateFreshnessScore(DependencyAgeAnalysis ageAnalysis, Integer maxAgeInDays) {
    int score = PERFECT_HEALTH_SCORE;

    switch (ageAnalysis.ageClassification()) {
      case FRESH -> score -= 0;
      case CURRENT -> score -= CURRENT_VERSION_PENALTY;
      case AGING -> score -= AGING_VERSION_PENALTY;
      case STALE -> score -= STALE_VERSION_PENALTY;
    }

    if (maxAgeInDays != null && ageAnalysis.daysSinceLastRelease() > maxAgeInDays) {
      score -= AGE_THRESHOLD_PENALTY;
    }

    return Math.max(0, score);
  }

  private int calculateMaintenanceScore(String maintenanceStatus) {
    int score = PERFECT_HEALTH_SCORE;

    switch (maintenanceStatus) {
      case ACTIVE_MAINTENANCE -> score -= 0;
      case MODERATE_MAINTENANCE -> score -= MODERATE_MAINTENANCE_PENALTY;
      case SLOW_MAINTENANCE -> score -= SLOW_MAINTENANCE_PENALTY;
      default -> {
        // Unknown maintenance status - no penalty
      }
    }

    return Math.max(0, score);
  }

  /**
   * Updated health score calculation including security and license.
   *
   * <p>Weights: freshness 30%, maintenance 25%, security 35%, license 10%.
   */
  private int calculateHealthScoreWithSecurityAndLicense(
      int freshnessScore,
      int maintenanceScore,
      SecurityAssessment security,
      List<LicenseInfo> licenses) {
    int securityScore = calculateSecurityScore(security);
    int licenseScore = calculateLicenseScore(licenses);

    return (int)
        Math.round(
            freshnessScore * 0.30
                + maintenanceScore * 0.25
                + securityScore * 0.35
                + licenseScore * 0.10);
  }

  private int calculateSecurityScore(SecurityAssessment security) {
    if (security == null) {
      return 100;
    }

    return switch (security.status()) {
      case OK -> 100;
      case UNKNOWN -> 70;
      case VULNERABLE -> {
        int penalty =
            security.criticalCves().size() * MavenToolsConstants.SECURITY_WEIGHT_CRITICAL
                + security.highCves().size() * MavenToolsConstants.SECURITY_WEIGHT_HIGH;
        yield Math.max(0, 100 - penalty);
      }
    };
  }

  private int calculateLicenseScore(List<LicenseInfo> licenses) {
    if (licenses == null || licenses.isEmpty()) {
      return 50;
    }

    boolean hasStrongCopyleft =
        licenses.stream().anyMatch(l -> l.category() == LicenseCategory.STRONG_COPYLEFT);
    if (hasStrongCopyleft) {
      return 30;
    }

    boolean allPermissive =
        licenses.stream().allMatch(l -> l.category() == LicenseCategory.PERMISSIVE);
    return allPermissive ? 100 : 70;
  }

  /**
   * The version of one dependency that an audit looks at.
   *
   * @param coordinate parsed input coordinate (carries the supplied version, if any)
   * @param currentVersion version supplied by the caller, or null
   * @param scannedVersion version security/license scans run against (current, else latest)
   * @param latestVersion newest version honouring the stability filter, or null
   * @param status {@link #OK} or the status to report for the dependency
   * @param error message when status is not OK
   * @param note optional clarification for the report
   * @param retryAfterSeconds retry hint when the failure was a retryable upstream failure
   */
  private record HealthTarget(
      MavenCoordinate coordinate,
      String currentVersion,
      String scannedVersion,
      String latestVersion,
      String status,
      String error,
      String note,
      @Nullable Integer retryAfterSeconds) {

    static final String OK = SUCCESS_STATUS;

    static HealthTarget failed(
        MavenCoordinate coordinate, String status, String error, String currentVersion) {
      return failed(coordinate, status, error, currentVersion, null);
    }

    static HealthTarget failed(
        MavenCoordinate coordinate,
        String status,
        String error,
        String currentVersion,
        @Nullable Integer retryAfterSeconds) {
      return new HealthTarget(
          coordinate, currentVersion, null, null, status, error, null, retryAfterSeconds);
    }

    String scannedKey() {
      return coordinate.groupId() + ":" + coordinate.artifactId() + ":" + scannedVersion;
    }
  }

  private Map<String, HealthTarget> resolveHealthTargets(
      List<String> dependencies, StabilityFilter filter, UpstreamFailures failures) {
    Map<String, HealthTarget> targets = new LinkedHashMap<>();
    for (String dep : dependencies) {
      MavenCoordinate coord = null;
      try {
        coord = MavenCoordinateParser.parse(dep);
        targets.put(dep, resolveHealthTarget(coord, filter));
      } catch (RuntimeException e) {
        logger.debug("Could not resolve version for {}: {}", dep, e.getMessage());
        FailureText failure = failureText(failures, dep, e);
        if (coord != null) {
          targets.put(
              dep,
              HealthTarget.failed(
                  coord,
                  ERROR_STATUS,
                  failure.message(),
                  coord.version(),
                  failure.retryAfterSeconds()));
        }
      }
    }
    return targets;
  }

  private HealthTarget resolveHealthTarget(MavenCoordinate coord, StabilityFilter filter) {
    String current = coord.version();
    List<String> allVersions = mavenCentralService.getAllVersions(coord);
    if (allVersions.isEmpty()) {
      return HealthTarget.failed(
          coord, "not_found", "Dependency not found in Maven Central", current);
    }
    if (current != null && !allVersions.contains(current)) {
      return HealthTarget.failed(
          coord,
          ERROR_STATUS,
          "Version %s of %s:%s does not exist in the repository"
              .formatted(current, coord.groupId(), coord.artifactId()),
          current);
    }

    String latest = getLatestCompatibleVersion(coord, filter != StabilityFilter.ALL);
    String note = null;
    if (latest == null && filter != StabilityFilter.STABLE_ONLY) {
      latest = getLatestCompatibleVersion(coord, false);
      note = "No stable release exists; the newest pre-release is used as latest";
    }

    if (current != null) {
      if (latest == null) {
        note = "No stable release available for stabilityFilter=STABLE_ONLY";
      }
      return new HealthTarget(coord, current, current, latest, HealthTarget.OK, null, note, null);
    }
    if (latest == null) {
      return HealthTarget.failed(
          coord,
          "no_stable_version",
          "No stable version exists and stabilityFilter=STABLE_ONLY; nothing was scanned",
          null);
    }
    return new HealthTarget(coord, null, latest, latest, HealthTarget.OK, null, note, null);
  }

  private Map<String, SecurityAssessment> scanTargetSecurity(Map<String, HealthTarget> targets) {
    Map<String, MavenCoordinate> toScan = new LinkedHashMap<>();
    for (HealthTarget target : targets.values()) {
      if (HealthTarget.OK.equals(target.status())) {
        toScan.putIfAbsent(
            target.scannedKey(),
            MavenCoordinate.of(
                target.coordinate().groupId(),
                target.coordinate().artifactId(),
                target.scannedVersion()));
      }
    }
    if (toScan.isEmpty()) {
      return Map.of();
    }
    return vulnerabilityService.scanBulk(List.copyOf(toScan.values()));
  }

  private Map<String, List<LicenseInfo>> scanTargetLicenses(Map<String, HealthTarget> targets) {
    Map<String, List<LicenseInfo>> byKey = new LinkedHashMap<>();
    for (HealthTarget target : targets.values()) {
      if (!HealthTarget.OK.equals(target.status())) {
        continue;
      }
      String key = target.scannedKey();
      if (byKey.containsKey(key)) {
        continue;
      }
      try {
        byKey.put(
            key,
            mavenCentralService.getLicenses(
                MavenCoordinate.of(
                    target.coordinate().groupId(),
                    target.coordinate().artifactId(),
                    target.scannedVersion())));
      } catch (Exception e) {
        logger.debug("Could not scan license for {}: {}", key, e.getMessage());
      }
    }
    return Map.copyOf(byKey);
  }

  private LicenseFindings buildLicenseFindings(
      Map<String, List<LicenseInfo>> licensesByDependency) {
    if (licensesByDependency == null || licensesByDependency.isEmpty()) {
      return LicenseFindings.empty();
    }

    LicenseFindings.Builder builder = LicenseFindings.builder();

    for (var entry : licensesByDependency.entrySet()) {
      String dependency = entry.getKey();
      List<LicenseInfo> licenses = entry.getValue();

      if (licenses == null || licenses.isEmpty()) {
        builder.addUnknown(dependency);
        continue;
      }

      LicenseInfo license = licenses.get(0);
      if (license.isPermissive()) {
        builder.addPermissive();
      } else if (license.isCopyleft()) {
        builder.addCopyleft(dependency, license.name());
      } else {
        builder.addUnknown(dependency);
      }
    }

    return builder.build();
  }

  private ToolResponse notFoundResponse(MavenCoordinate coordinate) {
    String message =
        "No Maven dependency found for %s:%s%s"
            .formatted(
                coordinate.groupId(),
                coordinate.artifactId(),
                coordinate.packaging() != null ? ":" + coordinate.packaging() : "");
    return ToolResponse.Error.notFound(message);
  }

  private VersionsByType buildVersionsByType(
      MavenCoordinate coordinate, List<String> allVersions, StabilityFilter stabilityFilter) {
    Map<VersionType, String> versionsByType = HashMap.newHashMap(5);
    boolean stableOnly = stabilityFilter == StabilityFilter.STABLE_ONLY;

    for (String version : allVersions) {
      VersionType type = versionComparator.getVersionType(version);
      if (!stableOnly || type == VersionType.STABLE) {
        versionsByType.putIfAbsent(type, version);
      }

      boolean done =
          stableOnly ? versionsByType.containsKey(VersionType.STABLE) : versionsByType.size() == 5;
      if (done) {
        break;
      }
    }

    return VersionsByType.create(
        coordinate.toCoordinateString(),
        createVersionInfo(versionsByType.get(VersionType.STABLE)),
        createVersionInfo(versionsByType.get(VersionType.RC)),
        createVersionInfo(versionsByType.get(VersionType.BETA)),
        createVersionInfo(versionsByType.get(VersionType.ALPHA)),
        createVersionInfo(versionsByType.get(VersionType.MILESTONE)),
        allVersions.size());
  }

  private Optional<VersionInfo> createVersionInfo(String version) {
    return version != null
        ? Optional.of(new VersionInfo(version, versionComparator.getVersionType(version)))
        : Optional.empty();
  }

  private List<String> parseDependencies(String dependencies) {
    if (dependencies != null && dependencies.length() > 1_048_576) {
      throw new IllegalArgumentException("Dependency input exceeds one MiB of characters");
    }
    if (dependencies == null || dependencies.trim().isEmpty()) {
      return List.of();
    }

    return dependencies
        .lines()
        .flatMap(line -> Arrays.stream(line.split(",")))
        .map(String::trim)
        .filter(dep -> !dep.isEmpty())
        .toList();
  }

  private BulkCheckResult processVersionCheck(
      String dep, StabilityFilter filter, UpstreamFailures failures, RelocationLookup relocations) {
    return switch (filter) {
      case STABLE_ONLY -> processStableVersionCheck(dep, failures, relocations);
      case ALL, PREFER_STABLE -> processComprehensiveVersionCheck(dep, failures, relocations);
    };
  }

  private BulkCheckResult processStableVersionCheck(
      String dep, UpstreamFailures failures, RelocationLookup relocations) {
    try {
      MavenCoordinate coordinate = MavenCoordinateParser.parse(dep);
      List<String> allVersions = mavenCentralService.getAllVersions(coordinate);

      if (allVersions.isEmpty()) {
        return BulkCheckResult.notFound(coordinate.toCoordinateString());
      }

      List<String> stableVersions =
          allVersions.stream().filter(versionComparator::isStableVersion).toList();

      String latestStable = stableVersions.isEmpty() ? null : stableVersions.get(0);

      BulkCheckResult result =
          latestStable != null
              ? BulkCheckResult.foundStable(
                  coordinate.toCoordinateString(),
                  latestStable,
                  VersionType.STABLE.getDisplayName(),
                  allVersions.size(),
                  stableVersions.size())
              : BulkCheckResult.noStableVersion(
                  coordinate.toCoordinateString(), allVersions.size());
      return result.withRelocation(relocations.find(coordinate));
    } catch (RuntimeException e) {
      FailureText failure = failureText(failures, dep, e);
      return BulkCheckResult.error(dep, failure.message(), failure.retryAfterSeconds());
    }
  }

  private VersionComparison.DependencyComparisonResult compareDependencyVersion(
      String dep,
      StabilityFilter stabilityFilter,
      Map<String, SecurityAssessment> securityAssessments,
      UpstreamFailures failures,
      RelocationLookup relocations) {
    try {
      MavenCoordinate coordinate = MavenCoordinateParser.parse(dep);
      String currentVersion = coordinate.version();
      String latestVersion =
          getLatestCompatibleVersion(coordinate, stabilityFilter == StabilityFilter.STABLE_ONLY);

      if (latestVersion == null) {
        return VersionComparison.DependencyComparisonResult.notFound(
            coordinate.toCoordinateString(), currentVersion);
      }

      if (currentVersion == null) {
        return VersionComparison.DependencyComparisonResult.noCurrentVersion(
                coordinate.toCoordinateString())
            .withRelocation(relocations.find(coordinate));
      }

      String latestType = versionComparator.getVersionTypeString(latestVersion);
      String updateType = versionComparator.determineUpdateType(currentVersion, latestVersion);
      boolean updateAvailable = versionComparator.compare(currentVersion, latestVersion) < 0;
      VersionComparison.SameMajorStableFallback sameMajorStableFallback =
          buildSameMajorStableFallback(coordinate, currentVersion, latestVersion, stabilityFilter);

      SecurityAssessment security = null;
      if (securityAssessments != null && !securityAssessments.isEmpty()) {
        security = securityAssessments.get(coordinate.toCoordinateString());
      }

      RelocationInfo relocation = relocations.find(coordinate);
      if (security != null) {
        return VersionComparison.DependencyComparisonResult.successWithSecurity(
                coordinate.toCoordinateString(),
                currentVersion,
                latestVersion,
                latestType,
                updateType,
                updateAvailable,
                security,
                context7Properties.enabled(),
                sameMajorStableFallback)
            .withRelocation(relocation);
      }

      return VersionComparison.DependencyComparisonResult.success(
              coordinate.toCoordinateString(),
              currentVersion,
              latestVersion,
              latestType,
              updateType,
              updateAvailable,
              context7Properties.enabled(),
              sameMajorStableFallback)
          .withRelocation(relocation);
    } catch (RuntimeException e) {
      FailureText failure = failureText(failures, dep, e);
      return VersionComparison.DependencyComparisonResult.error(
          dep, failure.message(), failure.retryAfterSeconds());
    }
  }

  private VersionComparison.SameMajorStableFallback buildSameMajorStableFallback(
      MavenCoordinate coordinate,
      String currentVersion,
      String latestVersion,
      StabilityFilter stabilityFilter)
      throws MavenCentralException {
    if (stabilityFilter != StabilityFilter.STABLE_ONLY) {
      return null;
    }

    if (!MAJOR_UPDATE_TYPE.equals(
        versionComparator.determineUpdateType(currentVersion, latestVersion))) {
      return null;
    }

    Integer currentMajor = extractMajorVersion(currentVersion);
    if (currentMajor == null) {
      return null;
    }

    return mavenCentralService.getAllVersions(coordinate).stream()
        .takeWhile(candidate -> !candidate.equals(currentVersion))
        .filter(versionComparator::isStableVersion)
        .filter(candidate -> versionComparator.preservesReleaseVariant(currentVersion, candidate))
        .filter(candidate -> currentMajor.equals(extractMajorVersion(candidate)))
        .filter(candidate -> versionComparator.compare(currentVersion, candidate) < 0)
        .map(
            candidate ->
                Map.entry(
                    candidate, versionComparator.determineUpdateType(currentVersion, candidate)))
        .filter(
            candidateAndType ->
                MINOR_UPDATE_TYPE.equals(candidateAndType.getValue())
                    || PATCH_UPDATE_TYPE.equals(candidateAndType.getValue()))
        .findFirst()
        .map(
            candidateAndType ->
                new VersionComparison.SameMajorStableFallback(
                    candidateAndType.getKey(), candidateAndType.getValue()))
        .orElse(null);
  }

  private Integer extractMajorVersion(String version) {
    int[] numericParts = versionComparator.parseVersion(version).numericParts();
    return numericParts.length > 0 ? numericParts[0] : null;
  }

  private BulkCheckResult processComprehensiveVersionCheck(
      String dep, UpstreamFailures failures, RelocationLookup relocations) {
    try {
      MavenCoordinate coordinate = MavenCoordinateParser.parse(dep);
      List<String> allVersions = mavenCentralService.getAllVersions(coordinate);
      if (allVersions.isEmpty()) {
        return BulkCheckResult.notFound(dep);
      }

      Map<VersionType, String> versionsByType = buildVersionsByType(allVersions);
      VersionInfoCollection versionInfos = createVersionInfoCollection(versionsByType);
      int stableCount = countStableVersions(allVersions);

      String primaryVersion =
          versionInfos.latestStable() != null
              ? versionInfos.latestStable().version()
              : allVersions.get(0);
      String primaryType =
          versionInfos.latestStable() != null
              ? VersionType.STABLE.getDisplayName()
              : versionComparator.getVersionTypeString(allVersions.get(0));

      return BulkCheckResult.foundComprehensive(
              dep,
              primaryVersion,
              primaryType,
              allVersions.size(),
              stableCount,
              versionInfos.latestStable(),
              versionInfos.latestRc(),
              versionInfos.latestBeta(),
              versionInfos.latestAlpha(),
              versionInfos.latestMilestone())
          .withRelocation(relocations.find(coordinate));
    } catch (RuntimeException e) {
      FailureText failure = failureText(failures, dep, e);
      if (failures.byDependency.containsKey(dep)) {
        logger.warn("Version check failed for {}: {}", dep, failure.message());
      } else {
        logger.error(
            "Error processing comprehensive version check for {}: {}", dep, e.getMessage());
      }
      return BulkCheckResult.error(dep, failure.message(), failure.retryAfterSeconds());
    }
  }

  private Map<VersionType, String> buildVersionsByType(List<String> allVersions) {
    Map<VersionType, String> versionsByType = HashMap.newHashMap(5);
    Set<VersionType> remainingTypes = EnumSet.allOf(VersionType.class);

    for (String version : allVersions) {
      VersionType type = versionComparator.getVersionType(version);
      if (remainingTypes.contains(type)) {
        versionsByType.putIfAbsent(type, version);
        remainingTypes.remove(type);
        if (remainingTypes.isEmpty()) break;
      }
    }
    return versionsByType;
  }

  private VersionInfoCollection createVersionInfoCollection(
      Map<VersionType, String> versionsByType) {
    return new VersionInfoCollection(
        createVersionInfo(versionsByType, VersionType.STABLE),
        createVersionInfo(versionsByType, VersionType.RC),
        createVersionInfo(versionsByType, VersionType.BETA),
        createVersionInfo(versionsByType, VersionType.ALPHA),
        createVersionInfo(versionsByType, VersionType.MILESTONE));
  }

  private VersionInfo createVersionInfo(Map<VersionType, String> versionsByType, VersionType type) {
    return versionsByType.containsKey(type)
        ? new VersionInfo(versionsByType.get(type), type)
        : null;
  }

  private int countStableVersions(List<String> allVersions) {
    return allVersions.stream()
        .mapToInt(v -> versionComparator.getVersionType(v) == VersionType.STABLE ? 1 : 0)
        .sum();
  }

  private record VersionInfoCollection(
      VersionInfo latestStable,
      VersionInfo latestRc,
      VersionInfo latestBeta,
      VersionInfo latestAlpha,
      VersionInfo latestMilestone) {}

  private VersionComparison.UpdateSummary calculateUpdateSummary(
      List<VersionComparison.DependencyComparisonResult> results) {

    Map<String, Long> counts =
        results.stream()
            .filter(result -> SUCCESS_STATUS.equals(result.status()))
            .collect(
                Collectors.groupingBy(
                    VersionComparison.DependencyComparisonResult::updateType,
                    Collectors.counting()));

    return new VersionComparison.UpdateSummary(
        counts.getOrDefault(MAJOR_UPDATE_TYPE, 0L).intValue(),
        counts.getOrDefault(MINOR_UPDATE_TYPE, 0L).intValue(),
        counts.getOrDefault(PATCH_UPDATE_TYPE, 0L).intValue(),
        counts.getOrDefault(NO_UPDATE_TYPE, 0L).intValue());
  }

  private String getLatestCompatibleVersion(MavenCoordinate coordinate, boolean stableOnly)
      throws MavenCentralException {
    return mavenCentralService.getAllVersions(coordinate).stream()
        .filter(candidate -> !stableOnly || versionComparator.isStableVersion(candidate))
        .filter(
            candidate ->
                coordinate.version() == null
                    || versionComparator.preservesReleaseVariant(coordinate.version(), candidate))
        .findFirst()
        .orElse(null);
  }

  private ReleasePatternAnalysis analyzeReleasePattern(
      String dependency, List<MavenArtifact> allVersions, int analysisMonths) {

    Instant now = Instant.now();
    Instant cutoffDate = now.minus((long) analysisMonths * DAYS_IN_MONTH, ChronoUnit.DAYS);

    // Filter versions within analysis period
    List<MavenArtifact> analysisVersions =
        allVersions.stream()
            .filter(
                v -> {
                  Instant releaseDate = Instant.ofEpochMilli(v.timestamp());
                  return releaseDate.isAfter(cutoffDate);
                })
            .toList();

    if (analysisVersions.isEmpty()) {
      // Fallback to all versions if none in analysis period
      analysisVersions = allVersions.stream().limit(RECENT_VERSIONS_LIMIT).toList();
    }

    // Calculate release intervals
    List<Long> intervals = new ArrayList<>();
    for (int i = 1; i < analysisVersions.size(); i++) {
      long prevTimestamp = analysisVersions.get(i).timestamp();
      long currentTimestamp = analysisVersions.get(i - 1).timestamp();
      long intervalDays = (currentTimestamp - prevTimestamp) / MILLISECONDS_TO_DAYS;
      if (intervalDays > 0) intervals.add(intervalDays);
    }

    // Calculate statistics in single pass
    double averageDays;
    long maxInterval;
    long minInterval;
    if (intervals.isEmpty()) {
      averageDays = 0;
      maxInterval = 0;
      minInterval = 0;
    } else {
      var stats = intervals.stream().mapToLong(Long::longValue).summaryStatistics();
      averageDays = stats.getAverage();
      maxInterval = stats.getMax();
      minInterval = stats.getMin();
    }
    double releaseVelocity = averageDays > 0 ? (DAYS_IN_MONTH / averageDays) : 0;

    Instant lastReleaseDate = Instant.ofEpochMilli(analysisVersions.get(0).timestamp());
    long daysSinceLastRelease = Duration.between(lastReleaseDate, now).toDays();

    // Classifications
    ReleasePatternAnalysis.MaintenanceLevel maintenanceLevel =
        ReleasePatternAnalysis.MaintenanceLevel.classify(releaseVelocity, daysSinceLastRelease);
    ReleasePatternAnalysis.ReleaseConsistency consistency =
        ReleasePatternAnalysis.ReleaseConsistency.classify(averageDays, maxInterval, minInterval);

    // Build recent releases info
    List<ReleasePatternAnalysis.ReleaseInfo> recentReleases =
        analysisVersions.stream()
            .limit(10)
            .map(
                v -> {
                  Instant releaseDate = Instant.ofEpochMilli(v.timestamp());
                  return new ReleasePatternAnalysis.ReleaseInfo(v.version(), releaseDate, null);
                })
            .toList();

    String nextReleasePrediction =
        ReleasePatternAnalysis.predictNextRelease(averageDays, daysSinceLastRelease, consistency);
    String recommendation = ReleasePatternAnalysis.generateRecommendation(maintenanceLevel);

    return new ReleasePatternAnalysis(
        dependency,
        analysisVersions.size(),
        analysisMonths,
        averageDays,
        releaseVelocity,
        maintenanceLevel,
        consistency,
        lastReleaseDate,
        nextReleasePrediction,
        recentReleases,
        recommendation);
  }

  private int calculateSimpleHealthScore(
      DependencyAgeAnalysis ageAnalysis, String maintenanceStatus, Integer maxAgeInDays) {

    int score = PERFECT_HEALTH_SCORE;

    // Age penalty
    switch (ageAnalysis.ageClassification()) {
      case FRESH -> score -= 0; // No penalty
      case CURRENT -> score -= CURRENT_VERSION_PENALTY;
      case AGING -> score -= AGING_VERSION_PENALTY;
      case STALE -> score -= STALE_VERSION_PENALTY;
    }

    // Maintenance penalty
    switch (maintenanceStatus) {
      case ACTIVE_MAINTENANCE -> score -= 0; // No penalty
      case MODERATE_MAINTENANCE -> score -= MODERATE_MAINTENANCE_PENALTY;
      case SLOW_MAINTENANCE -> score -= SLOW_MAINTENANCE_PENALTY;
      default -> {
        // Unknown maintenance status - no penalty
      }
    }

    // Custom age threshold penalty
    if (maxAgeInDays != null && ageAnalysis.daysSinceLastRelease() > maxAgeInDays) {
      score -= AGE_THRESHOLD_PENALTY;
    }

    return Math.max(0, score);
  }

  private ProjectHealthAnalysis buildSimpleHealthSummary(
      List<ProjectHealthAnalysis.DependencyHealthAnalysis> dependencyAnalyses,
      Integer maxAgeInDays,
      SecurityFindings securityFindings,
      LicenseFindings licenseFindings) {

    int totalDependencies = dependencyAnalyses.size();
    int successfulAnalyses =
        (int)
            dependencyAnalyses.stream()
                .mapToLong(dep -> SUCCESS_STATUS.equals(dep.status()) ? 1 : 0)
                .sum();

    // Calculate averages and counts in single pass
    List<ProjectHealthAnalysis.DependencyHealthAnalysis> successfulDeps =
        dependencyAnalyses.stream().filter(dep -> SUCCESS_STATUS.equals(dep.status())).toList();

    if (successfulDeps.isEmpty()) {
      return ProjectHealthAnalysis.of(
          "unknown",
          null,
          totalDependencies,
          0,
          totalDependencies,
          new ProjectHealthAnalysis.AgeDistribution(0, 0, 0, 0),
          dependencyAnalyses,
          List.of("Unable to analyze any dependencies"),
          securityFindings,
          licenseFindings);
    }

    // Single pass through successful dependencies
    DependencyMetrics metrics = calculateDependencyMetrics(successfulDeps);

    double averageHealthScore = metrics.totalHealthScore() / (double) successfulDeps.size();
    String overallHealth = determineOverallHealth(averageHealthScore);

    // Create age distribution
    ProjectHealthAnalysis.AgeDistribution ageDistribution =
        new ProjectHealthAnalysis.AgeDistribution(
            (int) metrics.freshCount(),
            (int) metrics.currentCount(),
            (int) metrics.agingCount(),
            (int) metrics.staleCount());

    // Generate recommendations
    List<String> recommendations =
        generateHealthRecommendations(
            metrics,
            successfulAnalyses,
            successfulDeps,
            maxAgeInDays,
            securityFindings,
            licenseFindings);

    return ProjectHealthAnalysis.of(
        overallHealth,
        (int) Math.round(averageHealthScore),
        totalDependencies,
        successfulAnalyses,
        totalDependencies - successfulAnalyses,
        ageDistribution,
        dependencyAnalyses,
        recommendations,
        securityFindings,
        licenseFindings);
  }

  private DependencyMetrics calculateDependencyMetrics(
      List<ProjectHealthAnalysis.DependencyHealthAnalysis> successfulDeps) {
    // Calculate all metrics in optimized stream operations
    int totalHealthScore =
        successfulDeps.stream()
            .mapToInt(ProjectHealthAnalysis.DependencyHealthAnalysis::healthScore)
            .sum();

    Map<String, Long> ageCounts =
        successfulDeps.stream()
            .collect(
                Collectors.groupingBy(
                    ProjectHealthAnalysis.DependencyHealthAnalysis::ageClassification,
                    Collectors.counting()));

    long activeMaintenanceCount =
        successfulDeps.stream()
            .filter(dep -> ACTIVE_MAINTENANCE.equals(dep.maintenanceLevel()))
            .count();

    return new DependencyMetrics(
        totalHealthScore,
        ageCounts.getOrDefault(FRESH_AGE, 0L),
        ageCounts.getOrDefault(CURRENT_AGE, 0L),
        ageCounts.getOrDefault(AGING_AGE, 0L),
        ageCounts.getOrDefault(STALE_AGE, 0L),
        activeMaintenanceCount);
  }

  private String determineOverallHealth(double averageHealthScore) {
    if (averageHealthScore >= EXCELLENT_HEALTH_THRESHOLD) {
      return EXCELLENT_HEALTH;
    } else if (averageHealthScore >= GOOD_HEALTH_THRESHOLD) {
      return GOOD_HEALTH;
    } else if (averageHealthScore >= FAIR_HEALTH_THRESHOLD) {
      return FAIR_HEALTH;
    } else {
      return POOR_HEALTH;
    }
  }

  private List<String> generateHealthRecommendations(
      DependencyMetrics metrics,
      int successfulAnalyses,
      List<ProjectHealthAnalysis.DependencyHealthAnalysis> successfulDeps,
      Integer maxAgeInDays,
      SecurityFindings securityFindings,
      LicenseFindings licenseFindings) {
    List<String> recommendations = new ArrayList<>();

    // Security recommendations (highest priority)
    if (securityFindings != null && securityFindings.requiresAction()) {
      recommendations.add(
          "URGENT: "
              + securityFindings.vulnerableCount()
              + " dependencies have security vulnerabilities");
    }

    // License recommendations
    if (licenseFindings != null && licenseFindings.needsReview()) {
      if (licenseFindings.copyleftCount() > 0) {
        recommendations.add(
            "Review " + licenseFindings.copyleftCount() + " dependencies with copyleft licenses");
      }
      if (licenseFindings.unknownCount() > 0) {
        recommendations.add(
            "Check license information for " + licenseFindings.unknownCount() + " dependencies");
      }
    }

    List<ProjectHealthAnalysis.DependencyHealthAnalysis> relocated =
        successfulDeps.stream().filter(dep -> dep.relocation() != null).toList();
    if (!relocated.isEmpty()) {
      ProjectHealthAnalysis.DependencyHealthAnalysis first = relocated.get(0);
      recommendations.add(
          relocated.size()
              + (relocated.size() == 1 ? " dependency is" : " dependencies are")
              + " relocated to new coordinates (for example "
              + gaOf(first.dependency())
              + " -> "
              + first.relocation().coordinate()
              + "); switching groupId/artifactId may require code changes");
    }

    if (metrics.staleCount() > 0) {
      recommendations.add(
          "Review " + metrics.staleCount() + " stale dependencies for alternatives");
    }
    if (metrics.agingCount() > successfulAnalyses / 2) {
      recommendations.add("Consider updating aging dependencies");
    }
    if (metrics.activeMaintenanceCount() < successfulAnalyses / 2) {
      recommendations.add("Monitor maintenance activity for slower-updated dependencies");
    }

    // Add age-specific recommendations when custom threshold is set
    if (maxAgeInDays != null) {
      long exceedsThreshold =
          successfulDeps.stream()
              .filter(
                  dep ->
                      STALE_AGE.equals(dep.ageClassification())
                          || AGING_AGE.equals(dep.ageClassification()))
              .count();
      if (exceedsThreshold > 0) {
        recommendations.add(
            "Found "
                + exceedsThreshold
                + " dependencies exceeding your "
                + maxAgeInDays
                + "-day age threshold");
      }
    }

    return recommendations;
  }

  /**
   * Analyze a Maven POM and return each declared dependency with its effective version, the source
   * of that version, and the managing BOM / parent coordinate where applicable.
   *
   * @param pomXml the primary POM to analyze (raw XML)
   * @param sideloadedPoms optional bundle of additional POMs (sibling modules, unreleased parents)
   *     used before falling back to Maven Central. Null or empty is treated as single-POM analysis.
   * @return JSON response wrapping an effective POM analysis result
   */
  @SuppressWarnings("java:S100") // MCP tool method naming
  @Tool(
      description =
          "POM-aware dependency analysis. Takes raw pom.xml content and returns per-dependency"
              + " effective versions classified as EXPLICIT (declared in this POM),"
              + " MANAGED (inherited from a parent or BOM), or EXPLICIT_OVERRIDE (declared here"
              + " AND managed elsewhere). Resolves parent POMs, ${name}/${project.version}"
              + " placeholders, dependencyManagement, and <scope>import</scope> BOM imports"
              + " against Maven Central. Optional sideloadedPoms accepts a bundle of additional"
              + " POMs (monorepo siblings, unreleased parents) used before falling back to Maven"
              + " Central. Use when asked: 'analyze my pom.xml', 'what versions does this POM"
              + " actually resolve to?', or for multi-module monorepos. Returns the parent chain,"
              + " resolved dependencies, directly editable root dependency-management, plugin"
              + " (rootPluginDeclarations) and plugin dependency declarations, propertyOverrides"
              + " (root properties"
              + " shadowing an ancestor property used as a version), and warnings.")
  public ToolResponse analyze_pom_dependencies(
      @ToolParam(description = "Raw <project>...</project> XML content of the POM to analyze.")
          String pomXml,
      @ToolParam(
              description =
                  "Optional bundle of additional POM XML strings (sibling modules, unreleased"
                      + " parents). Each is indexed by its self-declared groupId:artifactId:version"
                      + " and tried before Maven Central. Pass null or an empty array for"
                      + " single-POM analysis.",
              required = false)
          @Nullable List<String> sideloadedPoms) {
    try {
      EffectivePomResult result =
          (sideloadedPoms == null || sideloadedPoms.isEmpty())
              ? pomResolver.resolve(pomXml)
              : pomResolver.resolve(pomXml, sideloadedPoms);
      return ToolResponse.Success.of(result);
    } catch (IllegalArgumentException e) {
      return ToolResponse.Error.of("Invalid POM input: " + e.getMessage());
    } catch (Exception e) {
      return toolFailure(e);
    }
  }

  /**
   * Recommend POM upgrades, split into a mechanical action list (for non-LLM agents that apply the
   * edits directly) and a needs-attention list (for human / LLM review).
   *
   * @param pomXml the primary POM XML to analyze
   * @param mode {@link UpgradeMode#MINOR_PATCH} (default) or {@link UpgradeMode#ALL}
   * @param sideloadedPoms optional bundle of additional POMs (monorepo siblings, unreleased
   *     parents) tried before Maven Central
   * @param includeSecurityScan whether to scan dependency versions for vulnerabilities (default
   *     true)
   * @param minReleaseAgeDays optional release cooldown: actions only target versions published at
   *     least this many days ago (default: none; values {@code <= 0} mean none)
   * @return JSON response wrapping {@link PomUpgradeRecommendation}
   */
  @SuppressWarnings("java:S100") // MCP tool method naming
  @Tool(
      description =
          "POM-aware upgrade recommender. Takes raw pom.xml content and returns (1)"
              + " deterministicActions — mechanical version/property edits a non-LLM agent can"
              + " apply (explicit_bump, bom_bump, managed_decl_bump, plugin_dep_bump,"
              + " plugin_bump); actions"
              + " for vulnerable versions carry a security object with targetStatus; (2)"
              + " needsAttention — vulnerable (known CVEs, first), relocated (declared dependency moved to"
              + " new coordinates), major_available (alongside the"
              + " same-major action when one exists), property_override (a root property pinning a"
              + " parent-managed version, e.g. snakeyaml.version),"
              + " conflict and explicit_override entries needing review, each with the Maven"
              + " Central latest; plus securitySummary. Mode MINOR_PATCH (default) keeps majors in"
              + " needsAttention; ALL makes them deterministic. minReleaseAgeDays skips too-new"
              + " versions (heldBackFrom). incomplete=true: lookups failed, results partial. Use"
              + " for 'recommend upgrades for my pom.xml' or automated dependency-update"
              + " workflows.")
  public ToolResponse recommend_pom_upgrades(
      @ToolParam(description = "Raw <project>...</project> XML content of the POM to analyze.")
          String pomXml,
      @ToolParam(
              description =
                  "Upgrade mode: MINOR_PATCH (default — only same-major minor / patch upgrades"
                      + " are deterministic; majors go to needs_attention) or ALL (majors count"
                      + " as deterministic too).",
              required = false)
          @Nullable UpgradeMode mode,
      @ToolParam(
              description =
                  "Optional bundle of additional POM XML strings (sibling modules, unreleased"
                      + " parents). Each is indexed by its self-declared groupId:artifactId:version"
                      + " and tried before Maven Central.",
              required = false)
          @Nullable List<String> sideloadedPoms,
      @ToolParam(
              description =
                  "Scan dependency versions for known vulnerabilities (OSV) and annotate actions /"
                      + " add 'vulnerable' entries. Default true.",
              required = false)
          @Nullable Boolean includeSecurityScan,
      @ToolParam(
              description =
                  "Only target versions published at least this many days ago (e.g. 7)."
                      + " Default: no cooldown.",
              required = false)
          @Nullable Integer minReleaseAgeDays) {
    try {
      EffectivePomResult resolved =
          (sideloadedPoms == null || sideloadedPoms.isEmpty())
              ? pomResolver.resolve(pomXml)
              : pomResolver.resolve(pomXml, sideloadedPoms);
      UpgradeMode effectiveMode = mode != null ? mode : UpgradeMode.MINOR_PATCH;
      boolean scan = includeSecurityScan == null || includeSecurityScan;
      return ToolResponse.Success.of(
          buildPomUpgradeRecommendation(
              resolved, effectiveMode, scan, new RecommendationRun(minReleaseAgeDays)));
    } catch (IllegalArgumentException e) {
      return ToolResponse.Error.of("Invalid POM input: " + e.getMessage());
    } catch (Exception e) {
      return toolFailure(e);
    }
  }

  private PomUpgradeRecommendation buildPomUpgradeRecommendation(
      EffectivePomResult resolved,
      UpgradeMode mode,
      boolean includeSecurityScan,
      RecommendationRun run) {
    List<UpgradeAction> actions = new ArrayList<>();
    List<NeedsAttention> attention = new ArrayList<>();
    // Best effort and never mechanical; ordered after vulnerable and before every other kind.
    List<NeedsAttention> relocated = buildRelocatedAttention(resolved);

    // Only BOMs the user can edit in their own POM are actionable: the direct <parent> and the
    // root POM's direct <dependencyManagement> imports. Transitively-imported BOMs (e.g.,
    // jackson-bom inherited through spring-boot-dependencies) are skipped — the user has no
    // <version> to edit. Their upgrades surface through whichever user-controllable knob brings
    // them in.
    Map<String, MavenCoordinate> userControllableBoms = new LinkedHashMap<>();
    if (!resolved.parentChain().isEmpty()) {
      MavenCoordinate directParent = resolved.parentChain().get(0);
      userControllableBoms.put(
          directParent.groupId() + ":" + directParent.artifactId(), directParent);
    }
    for (MavenCoordinate bom : resolved.rootImportedBoms()) {
      userControllableBoms.putIfAbsent(bom.groupId() + ":" + bom.artifactId(), bom);
    }

    // First pass: classify each user-controllable BOM (parent + root imports). This covers BOMs
    // even when they don't directly manage any classified dep — bumping the parent is itself a
    // useful upgrade signal.
    for (MavenCoordinate bom : userControllableBoms.values()) {
      classifyBomCandidate(run, bom, mode, actions, attention);
    }

    // Direct non-import dependency-management declarations are independent user-owned knobs. They
    // remain actionable even when the platform POM has no normal <dependencies> usage of its own.
    for (ManagedDeclaration declaration : resolved.rootManagedDeclarations()) {
      classifyManagedDeclarationCandidate(run, declaration, mode, actions, attention);
    }

    for (PluginDependencyDeclaration declaration : resolved.rootPluginDependencyDeclarations()) {
      classifyPluginDependencyCandidate(run, declaration, mode, actions, attention);
    }

    // Plugins' own versions. The same plugin may be declared in both <plugins> and
    // <pluginManagement>: a shared property yields one action, separate literals one per site.
    Set<String> seenPlugins = new HashSet<>();
    for (PluginDeclaration declaration : resolved.rootPluginDeclarations()) {
      if (seenPlugins.add(pluginDeclarationKey(declaration))) {
        classifyPluginCandidate(run, declaration, mode, actions, attention);
      }
    }

    // Second pass: per-dep classification. MANAGED deps whose managedBy is user-controllable are
    // covered by the BOM pass above; transitively-managed deps are silently skipped (their
    // upgrade lives behind a user-controllable knob already classified).
    for (EffectiveDependency dep : resolved.dependencies()) {
      classifyDependencyCandidate(run, dep, mode, actions, attention);
    }

    // Property pins are never mechanical: a pin exists for a reason.
    for (PropertyOverride override : resolved.propertyOverrides()) {
      toPropertyOverrideAttention(run, override).ifPresent(attention::add);
    }

    List<String> warnings = new ArrayList<>(resolved.warnings());
    resolved.dependencies().stream()
        .filter(
            dependency -> !dependency.directlyEditable() && dependency.source() == Source.EXPLICIT)
        .forEach(
            dependency ->
                warnings.add(
                    "Dependency "
                        + dependency.groupId()
                        + ":"
                        + dependency.artifactId()
                        + " requires a manual profile, inherited-property or artifact-variant edit"));

    if (!includeSecurityScan) {
      warnings.addAll(run.warnings());
      List<NeedsAttention> unscanned = new ArrayList<>(relocated);
      unscanned.addAll(attention);
      return new PomUpgradeRecommendation(
          actions, unscanned, warnings, null, run.incomplete() ? Boolean.TRUE : null);
    }
    SecurityOutcome security = applySecurityScan(run, resolved, actions);
    if (security.summary().unknown() > 0) {
      warnings.add(
          "Security scan unavailable for " + security.summary().unknown() + " dependencies");
    }
    warnings.addAll(run.warnings());
    List<NeedsAttention> ordered = new ArrayList<>(security.vulnerable());
    ordered.addAll(relocated);
    ordered.addAll(attention);
    return new PomUpgradeRecommendation(
        security.actions(),
        ordered,
        warnings,
        security.summary(),
        run.incomplete() ? Boolean.TRUE : null);
  }

  /**
   * Builds {@code relocated} entries for dependencies the POM declares itself (EXPLICIT,
   * EXPLICIT_OVERRIDE) and for its own dependency-management declarations. BOM-managed (MANAGED)
   * dependencies, BOMs and plugins are not looked up. Lookups run in the bounded batch, one per
   * distinct {@code groupId:artifactId}; any failure just yields no entry.
   */
  private List<NeedsAttention> buildRelocatedAttention(EffectivePomResult resolved) {
    record Declared(String groupId, String artifactId, String current, String source) {}
    Set<Declared> declared = new LinkedHashSet<>();
    for (EffectiveDependency dep : resolved.dependencies()) {
      if (dep.source() == Source.EXPLICIT || dep.source() == Source.EXPLICIT_OVERRIDE) {
        declared.add(
            new Declared(
                dep.groupId(), dep.artifactId(), dep.effectiveVersion(), dep.source().name()));
      }
    }
    for (ManagedDeclaration declaration : resolved.rootManagedDeclarations()) {
      declared.add(
          new Declared(
              declaration.groupId(),
              declaration.artifactId(),
              declaration.version(),
              "MANAGED_DECLARATION"));
    }
    if (declared.isEmpty()) {
      return List.of();
    }
    List<String> distinct =
        declared.stream().map(d -> d.groupId() + ":" + d.artifactId()).distinct().toList();
    Map<String, RelocationInfo> found = new HashMap<>();
    try {
      RelocationLookup lookup = new RelocationLookup();
      List<RelocationInfo> infos =
          processBatchWithBackpressure(
              distinct,
              ga -> {
                String[] parts = ga.split(":", 2);
                return lookup.find(parts[0], parts[1]);
              });
      for (int i = 0; i < distinct.size(); i++) {
        if (infos.get(i) != null) {
          found.put(distinct.get(i), infos.get(i));
        }
      }
    } catch (RuntimeException e) {
      logger.debug("Relocation lookups skipped: {}", e.getMessage());
      return List.of();
    }
    List<NeedsAttention> out = new ArrayList<>();
    for (Declared d : declared) {
      RelocationInfo info = found.get(d.groupId() + ":" + d.artifactId());
      if (info != null) {
        out.add(
            new NeedsAttention.Relocated(
                d.groupId(),
                d.artifactId(),
                d.current(),
                d.source(),
                info.coordinate(),
                info.latestVersion(),
                info.message()));
      }
    }
    return out;
  }

  private static String pluginDeclarationKey(PluginDeclaration declaration) {
    boolean property = ManagedDeclaration.EDIT_TARGET_PROPERTY.equals(declaration.editTarget());
    return String.join(
        "|",
        declaration.groupId(),
        declaration.artifactId(),
        declaration.version(),
        declaration.editTarget(),
        property ? declaration.propertyName() : declaration.declaredIn());
  }

  /**
   * Per-run collector threaded through the classification helpers: lookup failures, repository
   * misses, release-cooldown state and the aggregated warnings derived from them. Not thread-safe;
   * a run is classified sequentially.
   */
  private static final class RecommendationRun {
    private static final int MAX_LISTED = 10;
    private static final int MAX_TIMESTAMP_LOOKUPS = 5;
    private static final long MILLIS_PER_DAY = 86_400_000L;

    private final Integer minReleaseAgeDays;
    private final long cutoffEpochMillis;
    private final Map<String, MavenCentralException> lookupFailures = new LinkedHashMap<>();
    private final Set<String> notFound = new LinkedHashSet<>();
    private final Set<String> cooldownBlocked = new LinkedHashSet<>();
    private final Map<String, Boolean> eligibility = new HashMap<>();

    RecommendationRun(@Nullable Integer minReleaseAgeDays) {
      this.minReleaseAgeDays =
          minReleaseAgeDays != null && minReleaseAgeDays > 0 ? minReleaseAgeDays : null;
      this.cutoffEpochMillis =
          this.minReleaseAgeDays == null
              ? 0L
              : System.currentTimeMillis() - this.minReleaseAgeDays * MILLIS_PER_DAY;
    }

    boolean cooldownEnabled() {
      return minReleaseAgeDays != null;
    }

    /** True only when a lookup threw; repository misses alone do not make a run incomplete. */
    boolean incomplete() {
      return !lookupFailures.isEmpty();
    }

    /** Records a failed lookup with its classified cause; the first cause per key is kept. */
    void recordFailure(String key, RuntimeException failure) {
      lookupFailures.putIfAbsent(key, MavenCentralException.classify(failure));
    }

    List<String> warnings() {
      List<String> out = new ArrayList<>();
      Map<MavenCentralException.Reason, Set<String>> byReason = new LinkedHashMap<>();
      Map<MavenCentralException.Reason, Integer> hints = new HashMap<>();
      lookupFailures.forEach(
          (key, failure) -> {
            byReason.computeIfAbsent(failure.getReason(), r -> new LinkedHashSet<>()).add(key);
            if (failure.getRetryAfterSeconds() != null) {
              hints.merge(failure.getReason(), failure.getRetryAfterSeconds(), Math::max);
            }
          });
      byReason.forEach(
          (reason, keys) ->
              out.add(
                  "Version lookup failed for "
                      + keys.size()
                      + (keys.size() == 1 ? " coordinate" : " coordinates")
                      + " ("
                      + failureLabel(reason, hints.get(reason))
                      + "): "
                      + listed(keys)));
      if (!notFound.isEmpty()) {
        out.add("Not found in the configured repository (skipped): " + listed(notFound));
      }
      if (!cooldownBlocked.isEmpty()) {
        out.add(
            "Release cooldown (minReleaseAgeDays="
                + minReleaseAgeDays
                + "): no upgrade emitted for "
                + cooldownBlocked.size()
                + (cooldownBlocked.size() == 1 ? " coordinate" : " coordinates")
                + " because newer versions are too recent or have no usable publish time: "
                + listed(cooldownBlocked));
      }
      return out;
    }

    private static String failureLabel(
        MavenCentralException.Reason reason, @Nullable Integer retryAfterSeconds) {
      String retry =
          retryAfterSeconds == null
              ? ""
              : "; retry in " + McpError.describeRetry(retryAfterSeconds);
      return switch (reason) {
        case RATE_LIMITED -> "repository rate limited" + retry;
        case CIRCUIT_OPEN -> "repository circuit breaker open" + retry;
        case UPSTREAM_ERROR, INVALID_RESPONSE -> "upstream error";
      };
    }

    private static String listed(Set<String> values) {
      List<String> all = List.copyOf(values);
      String head = String.join(", ", all.subList(0, Math.min(all.size(), MAX_LISTED)));
      return all.size() > MAX_LISTED ? head + " and " + (all.size() - MAX_LISTED) + " more" : head;
    }
  }

  private record SecurityOutcome(
      List<UpgradeAction> actions,
      List<NeedsAttention.Vulnerable> vulnerable,
      PomSecuritySummary summary) {}

  /**
   * Scans every resolved declared dependency, annotates deterministic actions whose current version
   * is vulnerable (scanning only those targets), and builds the {@code vulnerable} entries.
   */
  private SecurityOutcome applySecurityScan(
      RecommendationRun run, EffectivePomResult resolved, List<UpgradeAction> actions) {
    Map<String, MavenCoordinate> toScan = new LinkedHashMap<>();
    for (EffectiveDependency dep : resolved.dependencies()) {
      MavenCoordinate coordinate =
          MavenCoordinate.of(dep.groupId(), dep.artifactId(), dep.effectiveVersion());
      toScan.putIfAbsent(coordinate.toCoordinateString(), coordinate);
    }
    Map<String, SecurityAssessment> assessments = safeScanBulk(toScan.values());

    int vulnerableCount = 0;
    int cleanCount = 0;
    int unknownCount = 0;
    SecurityAssessment.Severity worst = null;
    for (String key : toScan.keySet()) {
      SecurityAssessment assessment = assessments.get(key);
      SecurityAssessment.Status status = statusOf(assessment);
      if (status == SecurityAssessment.Status.VULNERABLE) {
        vulnerableCount++;
        SecurityAssessment.Severity severity = severityOf(assessment);
        if (worst == null || severity.ordinal() < worst.ordinal()) {
          worst = severity;
        }
      } else if (status == SecurityAssessment.Status.OK) {
        cleanCount++;
      } else {
        unknownCount++;
      }
    }
    PomSecuritySummary summary =
        new PomSecuritySummary(toScan.size(), vulnerableCount, cleanCount, unknownCount, worst);

    // Second scan: only the targets of actions whose current version is vulnerable.
    Map<String, MavenCoordinate> targets = new LinkedHashMap<>();
    for (UpgradeAction action : actions) {
      String key = actionCurrentKey(action);
      if (!isScannedKind(action)) {
        continue;
      }
      if (statusOf(assessments.get(key)) == SecurityAssessment.Status.VULNERABLE) {
        MavenCoordinate target =
            MavenCoordinate.of(action.groupId(), action.artifactId(), action.target());
        targets.putIfAbsent(target.toCoordinateString(), target);
      }
    }
    Map<String, SecurityAssessment> targetAssessments =
        targets.isEmpty() ? Map.of() : safeScanBulk(targets.values());

    List<UpgradeAction> annotated = new ArrayList<>(actions.size());
    for (UpgradeAction action : actions) {
      SecurityAssessment current = assessments.get(actionCurrentKey(action));
      if (!isScannedKind(action) || statusOf(current) != SecurityAssessment.Status.VULNERABLE) {
        annotated.add(action);
        continue;
      }
      String targetKey =
          MavenCoordinate.of(action.groupId(), action.artifactId(), action.target())
              .toCoordinateString();
      annotated.add(
          action.withSecurity(
              new UpgradeAction.Security(
                  severityOf(current),
                  current.vulnerabilityCount(),
                  severeIds(current),
                  statusOf(targetAssessments.get(targetKey)))));
    }

    List<NeedsAttention.Vulnerable> vulnerable = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (EffectiveDependency dep : resolved.dependencies()) {
      MavenCoordinate coordinate =
          MavenCoordinate.of(dep.groupId(), dep.artifactId(), dep.effectiveVersion());
      SecurityAssessment assessment = assessments.get(coordinate.toCoordinateString());
      if (statusOf(assessment) != SecurityAssessment.Status.VULNERABLE
          || !seen.add(coordinate.toCoordinateString() + "|" + dep.source())
          || isFixedByDeterministicAction(dep, annotated)) {
        continue;
      }
      vulnerable.add(
          new NeedsAttention.Vulnerable(
              dep.groupId(),
              dep.artifactId(),
              dep.effectiveVersion(),
              dep.source().name(),
              dep.managedBy().map(MavenCoordinate::toCoordinateString).orElse(null),
              severityOf(assessment),
              assessment.vulnerabilityCount(),
              assessment.criticalCves() == null ? List.of() : assessment.criticalCves(),
              assessment.highCves() == null ? List.of() : assessment.highCves(),
              assessment.fixedInVersion(),
              latestStable(run, coordinate)));
    }
    vulnerable.sort(
        Comparator.comparingInt((NeedsAttention.Vulnerable v) -> v.maxSeverity().ordinal())
            .thenComparing(
                Comparator.comparingInt(NeedsAttention.Vulnerable::vulnerabilityCount).reversed())
            .thenComparing(v -> v.groupId() + ":" + v.artifactId()));
    return new SecurityOutcome(annotated, vulnerable, summary);
  }

  private Map<String, SecurityAssessment> safeScanBulk(Collection<MavenCoordinate> coordinates) {
    try {
      Map<String, SecurityAssessment> result =
          vulnerabilityService.scanBulk(List.copyOf(coordinates));
      return result == null ? Map.of() : result;
    } catch (RuntimeException e) {
      logger.warn("Security scan failed for POM dependencies: {}", e.getMessage());
      return Map.of();
    }
  }

  /** Missing or null assessments are UNKNOWN, never clean. */
  private static SecurityAssessment.Status statusOf(SecurityAssessment assessment) {
    return assessment == null || assessment.status() == null
        ? SecurityAssessment.Status.UNKNOWN
        : assessment.status();
  }

  private static SecurityAssessment.Severity severityOf(SecurityAssessment assessment) {
    return assessment.maxSeverity() == null
        ? SecurityAssessment.Severity.UNKNOWN
        : assessment.maxSeverity();
  }

  private static List<String> severeIds(SecurityAssessment assessment) {
    List<String> ids = new ArrayList<>();
    if (assessment.criticalCves() != null) ids.addAll(assessment.criticalCves());
    if (assessment.highCves() != null) ids.addAll(assessment.highCves());
    return ids;
  }

  /** A plugin's own version is not a scanned project dependency. */
  private static boolean isScannedKind(UpgradeAction action) {
    return !UpgradeAction.KIND_PLUGIN_BUMP.equals(action.kind());
  }

  private static String actionCurrentKey(UpgradeAction action) {
    return MavenCoordinate.of(action.groupId(), action.artifactId(), action.current())
        .toCoordinateString();
  }

  private static boolean isFixedByDeterministicAction(
      EffectiveDependency dep, List<UpgradeAction> actions) {
    return actions.stream()
        .anyMatch(
            action ->
                action.security() != null
                    // A plugin's own dependency does not change the project dependency.
                    && !UpgradeAction.KIND_PLUGIN_DEP_BUMP.equals(action.kind())
                    && action.security().targetStatus() == SecurityAssessment.Status.OK
                    && action.groupId().equals(dep.groupId())
                    && action.artifactId().equals(dep.artifactId())
                    && dep.effectiveVersion().equals(action.current()));
  }

  /** Builds the {@code property_override} entry; null when the override lists no coordinates. */
  private Optional<NeedsAttention> toPropertyOverrideAttention(
      RecommendationRun run, PropertyOverride override) {
    if (override.affects().isEmpty()) {
      return Optional.empty();
    }
    String[] first = override.affects().get(0).split(":", 2);
    String pinned = override.pinnedValue();
    String inherited = override.inheritedValue();
    String relation = NeedsAttention.PropertyOverride.UNKNOWN;
    if (pinned != null && inherited != null && !pinned.contains("${")) {
      int comparison = versionComparator.compare(pinned, inherited);
      relation =
          comparison < 0
              ? NeedsAttention.PropertyOverride.BEHIND
              : comparison > 0
                  ? NeedsAttention.PropertyOverride.AHEAD
                  : NeedsAttention.PropertyOverride.EQUAL;
    }
    String latest =
        latestStable(
            run,
            MavenCoordinate.of(
                first[0], first[1], pinned != null && !pinned.contains("${") ? pinned : null));
    return Optional.of(
        new NeedsAttention.PropertyOverride(
            first[0],
            first[1],
            override.propertyName(),
            pinned,
            inherited,
            override.definedIn(),
            override.affects(),
            override.affectedCount(),
            relation,
            latest));
  }

  /**
   * Looks up the latest stable version of a managing BOM and either emits a {@link UpgradeAction
   * bom_bump} action or a {@link NeedsAttention.MajorAvailable major_available} entry. Lookup
   * failures and missing artifacts are recorded on the run and surface as aggregated warnings.
   */
  private void classifyBomCandidate(
      RecommendationRun run,
      MavenCoordinate bom,
      UpgradeMode mode,
      List<UpgradeAction> actions,
      List<NeedsAttention> attention) {
    if (bom.version() == null) {
      return;
    }
    classifyUpgrade(
        run,
        bom,
        bom.version(),
        latestStable(run, bom),
        mode,
        Source.MANAGED.name(),
        bom.toCoordinateString(),
        (target, updateType) ->
            UpgradeAction.bomBump(
                bom.groupId(), bom.artifactId(), bom.version(), target, updateType),
        actions,
        attention);
  }

  /** Classifies a direct root dependency-management declaration with a known edit target. */
  private void classifyManagedDeclarationCandidate(
      RecommendationRun run,
      ManagedDeclaration declaration,
      UpgradeMode mode,
      List<UpgradeAction> actions,
      List<NeedsAttention> attention) {
    MavenCoordinate coordinate =
        MavenCoordinate.of(declaration.groupId(), declaration.artifactId(), declaration.version());
    classifyUpgrade(
        run,
        coordinate,
        declaration.version(),
        latestStable(run, coordinate),
        mode,
        "MANAGED_DECLARATION",
        null,
        (target, updateType) ->
            UpgradeAction.managedDeclarationBump(
                declaration.groupId(),
                declaration.artifactId(),
                declaration.version(),
                target,
                updateType,
                declaration.editTarget(),
                declaration.propertyName()),
        actions,
        attention);
  }

  /** Classifies a direct build/plugin dependency declaration with a known edit target. */
  private void classifyPluginDependencyCandidate(
      RecommendationRun run,
      PluginDependencyDeclaration declaration,
      UpgradeMode mode,
      List<UpgradeAction> actions,
      List<NeedsAttention> attention) {
    MavenCoordinate coordinate =
        MavenCoordinate.of(declaration.groupId(), declaration.artifactId(), declaration.version());
    classifyUpgrade(
        run,
        coordinate,
        declaration.version(),
        latestStable(run, coordinate),
        mode,
        "PLUGIN_DEPENDENCY",
        null,
        (target, updateType) ->
            UpgradeAction.pluginDependencyBump(
                declaration.groupId(),
                declaration.artifactId(),
                declaration.version(),
                target,
                updateType,
                declaration.editTarget(),
                declaration.propertyName(),
                declaration.declaredIn(),
                declaration.pluginGroupId(),
                declaration.pluginArtifactId()),
        actions,
        attention);
  }

  /** Classifies a build plugin's own version declaration with a known edit target. */
  private void classifyPluginCandidate(
      RecommendationRun run,
      PluginDeclaration declaration,
      UpgradeMode mode,
      List<UpgradeAction> actions,
      List<NeedsAttention> attention) {
    MavenCoordinate coordinate =
        MavenCoordinate.of(declaration.groupId(), declaration.artifactId(), declaration.version());
    classifyUpgrade(
        run,
        coordinate,
        declaration.version(),
        latestStable(run, coordinate),
        mode,
        "PLUGIN",
        null,
        (target, updateType) ->
            UpgradeAction.pluginBump(
                declaration.groupId(),
                declaration.artifactId(),
                declaration.version(),
                target,
                updateType,
                declaration.editTarget(),
                declaration.propertyName(),
                declaration.declaredIn()),
        actions,
        attention);
  }

  /**
   * Classifies a single declared dependency: surfaces conflicts and explicit overrides into {@code
   * needsAttention} regardless of upgrade availability; for EXPLICIT deps emits a {@link
   * UpgradeAction explicit_bump} (plus a {@code major_available} entry when a newer major exists in
   * {@code MINOR_PATCH} mode); MANAGED deps without conflicts are covered by the upstream BOM bump
   * pass and do not produce per-dep actions here.
   */
  private void classifyDependencyCandidate(
      RecommendationRun run,
      EffectiveDependency dep,
      UpgradeMode mode,
      List<UpgradeAction> actions,
      List<NeedsAttention> attention) {
    // Fast-exit MANAGED deps without conflicts before the Maven Central lookup — their upgrade
    // path runs through the user-controllable BOM classified upstream, so this would be a wasted
    // network call (even though cached, still serializes through the rate limiter).
    if (dep.source() == Source.MANAGED && dep.conflicts().isEmpty()) {
      return;
    }

    MavenCoordinate coordinate =
        MavenCoordinate.of(dep.groupId(), dep.artifactId(), dep.effectiveVersion());
    String latestOnCentral = latestStable(run, coordinate);

    if (!dep.conflicts().isEmpty() && dep.source() != Source.EXPLICIT_OVERRIDE) {
      // MANAGED dep with multiple BOMs disagreeing — surface the conflict.
      attention.add(
          new NeedsAttention.Conflict(
              dep.groupId(),
              dep.artifactId(),
              dep.effectiveVersion(),
              dep.managedBy().map(MavenCoordinate::toCoordinateString).orElse(null),
              toCandidates(dep, /* includeWinner= */ true),
              latestOnCentral));
      return;
    }

    if (dep.source() == Source.EXPLICIT_OVERRIDE) {
      attention.add(
          new NeedsAttention.ExplicitOverride(
              dep.groupId(),
              dep.artifactId(),
              dep.effectiveVersion(),
              toCandidates(dep, /* includeWinner= */ false),
              latestOnCentral));
      return;
    }

    // Profile declarations and artifact variants remain visible in analysis but need manual edits.
    if (!dep.directlyEditable()) {
      return;
    }

    classifyUpgrade(
        run,
        coordinate,
        dep.effectiveVersion(),
        latestOnCentral,
        mode,
        Source.EXPLICIT.name(),
        null,
        (target, updateType) ->
            UpgradeAction.explicitBump(
                dep.groupId(), dep.artifactId(), dep.effectiveVersion(), target, updateType),
        actions,
        attention);
  }

  /**
   * Shared upgrade classification for every edit kind. A minor / patch upgrade yields the action
   * built by {@code actionFactory}. In {@code MINOR_PATCH} mode a major upgrade yields a {@code
   * major_available} entry and — when a newer same-major stable version exists — also the normal
   * action targeting that version, so a newer major never blocks same-major updates. {@code ALL}
   * mode promotes the major itself to an action. With a release cooldown the action target may be
   * an older eligible version (see {@link #selectTarget}); {@code major_available} stays
   * unfiltered.
   */
  private void classifyUpgrade(
      RecommendationRun run,
      MavenCoordinate coordinate,
      String current,
      @Nullable String latest,
      UpgradeMode mode,
      String majorSource,
      @Nullable String majorManagedBy,
      BiFunction<String, String, UpgradeAction> actionFactory,
      List<UpgradeAction> actions,
      List<NeedsAttention> attention) {
    if (latest == null || versionComparator.compare(current, latest) >= 0) {
      return;
    }
    String updateType = versionComparator.determineUpdateType(current, latest);
    boolean minorPatchOnly = mode == UpgradeMode.MINOR_PATCH;
    if (MAJOR_UPDATE_TYPE.equals(updateType) && minorPatchOnly) {
      String sameMajor = findCurrentMajorLatest(run, coordinate, current);
      NeedsAttention.MajorAvailable major =
          new NeedsAttention.MajorAvailable(
              coordinate.groupId(),
              coordinate.artifactId(),
              current,
              sameMajor != null ? sameMajor : current,
              latest,
              majorSource,
              majorManagedBy);
      if (!attention.contains(major)) {
        attention.add(major);
      }
      if (sameMajor != null && versionComparator.compare(current, sameMajor) < 0) {
        String sameMajorType = versionComparator.determineUpdateType(current, sameMajor);
        if (MINOR_UPDATE_TYPE.equals(sameMajorType) || PATCH_UPDATE_TYPE.equals(sameMajorType)) {
          addAction(
              run, coordinate, current, sameMajor, sameMajorType, true, actionFactory, actions);
        }
      }
    } else if (List.of(MAJOR_UPDATE_TYPE, MINOR_UPDATE_TYPE, PATCH_UPDATE_TYPE)
        .contains(updateType)) {
      addAction(
          run, coordinate, current, latest, updateType, minorPatchOnly, actionFactory, actions);
    }
  }

  private void addAction(
      RecommendationRun run,
      MavenCoordinate coordinate,
      String current,
      String preferred,
      String preferredType,
      boolean minorPatchOnly,
      BiFunction<String, String, UpgradeAction> actionFactory,
      List<UpgradeAction> actions) {
    if (!run.cooldownEnabled()) {
      actions.add(actionFactory.apply(preferred, preferredType));
      return;
    }
    String target =
        selectTarget(run, coordinate, current, preferred, preferredType, minorPatchOnly);
    if (target == null) {
      run.cooldownBlocked.add(
          coordinate.groupId() + ":" + coordinate.artifactId() + ":" + preferred);
      return;
    }
    if (target.equals(preferred)) {
      actions.add(actionFactory.apply(preferred, preferredType));
    } else {
      actions.add(
          actionFactory
              .apply(target, versionComparator.determineUpdateType(current, target))
              .withHeldBackFrom(preferred));
    }
  }

  /**
   * Picks the newest version that satisfies the release cooldown: {@code preferred} when it is old
   * enough, else the newest older upgrade of the same kind (stable, same variant, still newer than
   * {@code current}, and a minor / patch upgrade when {@code minorPatchOnly}). At most {@link
   * RecommendationRun#MAX_TIMESTAMP_LOOKUPS} publish-time lookups are spent per call. Returns null
   * when nothing qualifies; unknown or failed publish times never qualify.
   */
  @Nullable
  private String selectTarget(
      RecommendationRun run,
      MavenCoordinate coordinate,
      String current,
      String preferred,
      String preferredType,
      boolean minorPatchOnly) {
    List<String> candidates = new ArrayList<>();
    candidates.add(preferred);
    lookupVersions(run, coordinate).stream()
        .filter(versionComparator::isStableVersion)
        .filter(candidate -> versionComparator.preservesReleaseVariant(current, candidate))
        .filter(candidate -> versionComparator.compare(current, candidate) < 0)
        .filter(candidate -> versionComparator.compare(candidate, preferred) < 0)
        .filter(
            candidate -> {
              String type = versionComparator.determineUpdateType(current, candidate);
              return MINOR_UPDATE_TYPE.equals(type)
                  || PATCH_UPDATE_TYPE.equals(type)
                  || (!minorPatchOnly && MAJOR_UPDATE_TYPE.equals(type));
            })
        .sorted(versionComparator.reversed())
        .limit(RecommendationRun.MAX_TIMESTAMP_LOOKUPS - 1L)
        .forEach(candidates::add);
    for (String candidate : candidates) {
      if (isOldEnough(run, coordinate, candidate)) {
        return candidate;
      }
    }
    return null;
  }

  /** Fails closed: a failed lookup or a missing / non-positive timestamp is not eligible. */
  private boolean isOldEnough(RecommendationRun run, MavenCoordinate coordinate, String version) {
    String key = coordinate.groupId() + ":" + coordinate.artifactId() + ":" + version;
    Boolean known = run.eligibility.get(key);
    if (known != null) {
      return known;
    }
    boolean eligible = false;
    try {
      MavenArtifact artifact = mavenCentralService.fetchArtifactWithTimestamp(coordinate, version);
      eligible =
          artifact != null
              && artifact.timestamp() > 0
              && artifact.timestamp() <= run.cutoffEpochMillis;
    } catch (RuntimeException e) {
      logger.debug("Publish-time lookup failed for {}: {}", key, e.getMessage());
      run.recordFailure(coordinate.groupId() + ":" + coordinate.artifactId(), e);
    }
    run.eligibility.put(key, eligible);
    return eligible;
  }

  /**
   * Fetches all versions of a coordinate, newest first. A throw is recorded as an upstream failure
   * and an empty answer as "not found in the configured repository"; both return an empty list.
   */
  private List<String> lookupVersions(RecommendationRun run, MavenCoordinate coordinate) {
    String key = coordinate.groupId() + ":" + coordinate.artifactId();
    try {
      List<String> versions = mavenCentralService.getAllVersions(coordinate);
      if (versions == null || versions.isEmpty()) {
        run.notFound.add(key);
        return List.of();
      }
      return versions;
    } catch (RuntimeException e) {
      logger.debug("Version lookup failed for {}: {}", key, e.getMessage());
      run.recordFailure(key, e);
      return List.of();
    }
  }

  /** Latest stable version preserving {@code coordinate}'s release variant, or null. */
  @Nullable
  private String latestStable(RecommendationRun run, MavenCoordinate coordinate) {
    return lookupVersions(run, coordinate).stream()
        .filter(versionComparator::isStableVersion)
        .filter(
            candidate ->
                coordinate.version() == null
                    || versionComparator.preservesReleaseVariant(coordinate.version(), candidate))
        .findFirst()
        .orElse(null);
  }

  /**
   * Finds the latest stable version sharing the same major as {@code current}, or null if no newer
   * same-major stable version exists.
   */
  private String findCurrentMajorLatest(
      RecommendationRun run, MavenCoordinate coordinate, String current) {
    Integer currentMajor = extractMajorVersion(current);
    if (currentMajor == null) {
      return null;
    }
    return lookupVersions(run, coordinate).stream()
        .filter(versionComparator::isStableVersion)
        .filter(candidate -> versionComparator.preservesReleaseVariant(current, candidate))
        .filter(candidate -> currentMajor.equals(extractMajorVersion(candidate)))
        .filter(candidate -> versionComparator.compare(current, candidate) <= 0)
        .findFirst()
        .orElse(null);
  }

  private static List<NeedsAttention.Candidate> toCandidates(
      EffectiveDependency dep, boolean includeWinner) {
    List<NeedsAttention.Candidate> out = new ArrayList<>();
    if (includeWinner) {
      dep.managedBy()
          .ifPresent(
              bom ->
                  out.add(
                      new NeedsAttention.Candidate(
                          dep.effectiveVersion(), bom.toCoordinateString())));
    }
    for (ManagedAlternative alt : dep.conflicts()) {
      out.add(new NeedsAttention.Candidate(alt.version(), alt.managedBy().toCoordinateString()));
    }
    return out;
  }

  private record DependencyMetrics(
      int totalHealthScore,
      long freshCount,
      long currentCount,
      long agingCount,
      long staleCount,
      long activeMaintenanceCount) {}
}
