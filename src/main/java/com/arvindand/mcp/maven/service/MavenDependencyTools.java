package com.arvindand.mcp.maven.service;

import com.arvindand.mcp.maven.config.Context7Properties;
import com.arvindand.mcp.maven.model.BulkCheckResult;
import com.arvindand.mcp.maven.model.DependencyAge;
import com.arvindand.mcp.maven.model.DependencyAgeAnalysis;
import com.arvindand.mcp.maven.model.DependencyInfo;
import com.arvindand.mcp.maven.model.MavenCoordinate;
import com.arvindand.mcp.maven.model.MavenSearchResponse;
import com.arvindand.mcp.maven.model.ProjectHealthAnalysis;
import com.arvindand.mcp.maven.model.ReleasePatternAnalysis;
import com.arvindand.mcp.maven.model.ToolResponse;
import com.arvindand.mcp.maven.model.VersionComparison;
import com.arvindand.mcp.maven.model.VersionInfo;
import com.arvindand.mcp.maven.model.VersionInfo.VersionType;
import com.arvindand.mcp.maven.model.VersionTimelineAnalysis;
import com.arvindand.mcp.maven.model.VersionTimelineAnalysis.RecentActivity.ActivityLevel;
import com.arvindand.mcp.maven.model.VersionTimelineAnalysis.TimelineEntry.ReleaseGap;
import com.arvindand.mcp.maven.model.VersionTimelineAnalysis.VelocityTrend.TrendDirection;
import com.arvindand.mcp.maven.model.VersionsByType;
import com.arvindand.mcp.maven.util.MavenCoordinateParser;
import com.arvindand.mcp.maven.util.VersionComparator;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
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

  private static final String UNEXPECTED_ERROR = "Unexpected error";
  private static final String MAVEN_CENTRAL_ERROR = "Maven Central error: ";
  private static final String INVALID_MAVEN_COORDINATE_FORMAT = "Invalid Maven coordinate format: ";
  private static final String SUCCESS_STATUS = "success";
  private static final String ACTIVE_MAINTENANCE = "active";

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
  private static final Logger logger = LoggerFactory.getLogger(MavenDependencyTools.class);

  // Analysis constants
  private static final int DEFAULT_ANALYSIS_MONTHS = 24;
  private static final int DEFAULT_VERSION_COUNT = 20;
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

  // Stability analysis constants
  private static final int VERY_HIGH_STABILITY_THRESHOLD = 80;
  private static final int LOW_STABILITY_THRESHOLD = 50;
  private final MavenCentralService mavenCentralService;
  private final VersionComparator versionComparator;
  private final Context7Properties context7Properties;

  public MavenDependencyTools(
      MavenCentralService mavenCentralService,
      VersionComparator versionComparator,
      Context7Properties context7Properties) {
    this.mavenCentralService = mavenCentralService;
    this.versionComparator = versionComparator;
    this.context7Properties = context7Properties;
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
      return ToolResponse.Success.of(result);
    } catch (IllegalArgumentException e) {
      return ToolResponse.Error.of(INVALID_MAVEN_COORDINATE_FORMAT + e.getMessage());
    } catch (MavenCentralException e) {
      return ToolResponse.Error.of(MAVEN_CENTRAL_ERROR + e.getMessage());
    } catch (Exception e) {
      logger.error(UNEXPECTED_ERROR, e);
      return ToolResponse.Error.of(UNEXPECTED_ERROR + ": " + e.getMessage());
    }
  }

  @FunctionalInterface
  private interface ToolOperation<T> {
    T execute() throws IllegalArgumentException, MavenCentralException;
  }

  /**
   * Get the latest version of any dependency from Maven Central (works with Maven, Gradle, SBT,
   * Mill). Consolidates functionality from the former get_stable_version tool - use
   * preferStable=true for production-ready versions.
   *
   * @param dependency the dependency coordinate (groupId:artifactId)
   * @param preferStable when true, prioritize stable version in response while showing all types
   *     (default: false)
   * @return JSON response with latest versions by type
   */
  @SuppressWarnings("java:S100") // MCP tool method naming
  @Tool(
      description =
          "Get latest version of any Maven Central dependency with version type analysis. Works with all JVM build tools.")
  public ToolResponse get_latest_version(
      @ToolParam(
              description =
                  "Maven dependency coordinate in format 'groupId:artifactId' (NO version). Example: 'org.springframework:spring-core'")
          String dependency,
      @ToolParam(
              description =
                  "When true, prioritizes stable version in response while showing all types. When false, shows latest version of any type first (default: false)",
              required = false)
          boolean preferStable) {
    return executeToolOperation(
        () -> {
          MavenCoordinate coordinate = MavenCoordinateParser.parse(dependency);
          List<String> allVersions = mavenCentralService.getAllVersions(coordinate);

          if (allVersions.isEmpty()) {
            return notFoundResponse(coordinate);
          }

          return buildVersionsByType(coordinate, allVersions, preferStable);
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
          "Check if a specific dependency version exists in Maven Central and identify its stability type.")
  public ToolResponse check_version_exists(
      @ToolParam(
              description =
                  "Maven dependency coordinate in format 'groupId:artifactId' (NO version). Example: 'org.springframework:spring-core'")
          String dependency,
      @ToolParam(
              description =
                  "Specific version string to check for existence. Example: '6.1.4' or '2.7.18-SNAPSHOT'")
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
   * @param stableOnly when true, only show stable versions (default: false)
   * @return JSON response with bulk check results
   */
  @SuppressWarnings("java:S100") // MCP tool method naming
  @Tool(
      description =
          "Check latest versions for multiple dependencies with optional filtering to stable versions only.")
  public ToolResponse check_multiple_dependencies(
      @ToolParam(
              description =
                  "Comma or newline separated list of Maven dependency coordinates in format 'groupId:artifactId' (NO versions). Example: 'org.springframework:spring-core,junit:junit'")
          String dependencies,
      @ToolParam(
              description =
                  "When true, filters results to show only stable (production-ready) versions. When false, includes all version types (default: false)",
              required = false)
          boolean stableOnly) {
    return executeToolOperation(
        () -> {
          List<String> depList = parseDependencies(dependencies);

          List<BulkCheckResult> results;
          try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<BulkCheckResult>> futures =
                depList.stream()
                    .distinct()
                    .map(
                        dep ->
                            CompletableFuture.supplyAsync(
                                () ->
                                    stableOnly
                                        ? processStableVersionCheck(dep)
                                        : processComprehensiveVersionCheck(dep),
                                executor))
                    .toList();
            results = futures.stream().map(CompletableFuture::join).toList();
          }

          return results;
        });
  }

  /**
   * Compare current dependency versions with latest available and show upgrade recommendations.
   *
   * @param currentDependencies comma or newline separated list of dependency coordinates with
   *     versions
   * @param onlyStableTargets when true, only upgrade to stable versions (default: false)
   * @return JSON response with version comparison and update recommendations
   */
  @SuppressWarnings("java:S100") // MCP tool method naming
  @Tool(
      description =
          "Compare current dependency versions with latest available and provide upgrade recommendations with migration guidance.")
  public ToolResponse compare_dependency_versions(
      @ToolParam(
              description =
                  "Comma or newline separated list of dependency coordinates WITH versions in format 'groupId:artifactId:version'. Example: 'org.springframework:spring-core:6.0.0,junit:junit:4.12'")
          String currentDependencies,
      @ToolParam(
              description =
                  "When true, only suggests upgrades to stable versions for production safety. When false, suggests upgrades to latest available version of any type (default: false)",
              required = false)
          boolean onlyStableTargets) {
    return executeToolOperation(
        () -> {
          List<String> depList = parseDependencies(currentDependencies);

          List<VersionComparison.DependencyComparisonResult> results;
          try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<VersionComparison.DependencyComparisonResult>> futures =
                depList.stream()
                    .distinct()
                    .map(
                        dep ->
                            CompletableFuture.supplyAsync(
                                () -> compareDependencyVersion(dep, onlyStableTargets), executor))
                    .toList();
            results = futures.stream().map(CompletableFuture::join).toList();
          }

          VersionComparison.UpdateSummary summary = calculateUpdateSummary(results);
          return new VersionComparison(Instant.now(), results, summary);
        });
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
          "Analyze dependency age and classify freshness (fresh/current/aging/stale) with actionable maintenance insights.")
  public ToolResponse analyze_dependency_age(
      @ToolParam(
              description =
                  "Maven dependency coordinate in format 'groupId:artifactId' (NO version). Example: 'org.springframework:spring-core'")
          String dependency,
      @ToolParam(
              description =
                  "Optional maximum acceptable age threshold in days. If specified and dependency exceeds this age, additional recommendations are provided. No limit if not specified",
              required = false)
          Integer maxAgeInDays) {
    return executeToolOperation(
        () -> {
          MavenCoordinate coordinate = MavenCoordinateParser.parse(dependency);
          List<MavenSearchResponse.MavenArtifact> versions =
              mavenCentralService.getRecentVersionsWithTimestamps(coordinate, 1);

          if (versions.isEmpty()) {
            return notFoundResponse(coordinate);
          }

          MavenSearchResponse.MavenArtifact latestVersion = versions.get(0);
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
          "Analyze release patterns and maintenance activity to predict future release timeframes and project health.")
  public ToolResponse analyze_release_patterns(
      @ToolParam(
              description =
                  "Maven dependency coordinate in format 'groupId:artifactId' (NO version). Example: 'com.fasterxml.jackson.core:jackson-core'")
          String dependency,
      @ToolParam(
              description =
                  "Number of months of historical release data to analyze for patterns and predictions. Default is 24 months if not specified",
              required = false)
          Integer monthsToAnalyze) {
    return executeToolOperation(
        () -> {
          MavenCoordinate coordinate = MavenCoordinateParser.parse(dependency);
          int analysisMonths = monthsToAnalyze != null ? monthsToAnalyze : DEFAULT_ANALYSIS_MONTHS;

          List<MavenSearchResponse.MavenArtifact> allVersions =
              mavenCentralService.getAllVersionsWithTimestamps(coordinate);

          if (allVersions.isEmpty()) {
            throw new MavenCentralException(
                "No versions found for " + coordinate.toCoordinateString());
          }

          return analyzeReleasePattern(
              coordinate.toCoordinateString(), allVersions, analysisMonths);
        });
  }

  /**
   * Get enhanced version timeline with temporal analysis and release patterns.
   *
   * @param dependency the dependency coordinate (groupId:artifactId)
   * @param versionCount number of recent versions to include (default: 20)
   * @return JSON response with version timeline and temporal insights
   */
  @SuppressWarnings("java:S100") // MCP tool method naming
  @Tool(
      description =
          "Get detailed version timeline with temporal analysis, release gaps, and stability patterns.")
  public ToolResponse get_version_timeline(
      @ToolParam(
              description =
                  "Maven dependency coordinate in format 'groupId:artifactId' (NO version). Example: 'org.junit.jupiter:junit-jupiter'")
          String dependency,
      @ToolParam(
              description =
                  "Number of recent versions to include in timeline analysis. Default is 20 versions if not specified. Typical range: 10-50",
              required = false)
          Integer versionCount) {
    return executeToolOperation(
        () -> {
          MavenCoordinate coordinate = MavenCoordinateParser.parse(dependency);
          int maxVersions = versionCount != null ? versionCount : DEFAULT_VERSION_COUNT;

          List<MavenSearchResponse.MavenArtifact> versions =
              mavenCentralService.getRecentVersionsWithTimestamps(coordinate, maxVersions);

          if (versions.isEmpty()) {
            throw new MavenCentralException(
                "No versions found for " + coordinate.toCoordinateString());
          }

          return analyzeVersionTimeline(coordinate.toCoordinateString(), versions);
        });
  }

  /**
   * Analyze overall health of multiple dependencies with age and maintenance insights.
   *
   * @param dependencies comma or newline separated list of dependency coordinates
   * @param maxAgeInDays optional maximum acceptable age in days for health scoring
   * @return JSON response with project health summary and individual dependency analysis
   */
  @SuppressWarnings("java:S100") // MCP tool method naming
  @Tool(
      description =
          "Analyze overall health of multiple dependencies with combined age analysis and maintenance patterns for quick assessment.")
  public ToolResponse analyze_project_health(
      @ToolParam(
              description =
                  "Comma or newline separated list of Maven dependency coordinates in format 'groupId:artifactId' (NO versions). Example: 'org.springframework:spring-core,junit:junit'")
          String dependencies,
      @ToolParam(
              description =
                  "Optional maximum acceptable age threshold in days for health scoring. Dependencies exceeding this age receive lower health scores. No age penalty if not specified",
              required = false)
          Integer maxAgeInDays) {
    return executeToolOperation(
        () -> {
          List<String> depList = parseDependencies(dependencies);

          if (depList.isEmpty()) {
            throw new IllegalArgumentException("No dependencies provided for analysis");
          }

          // Analyze each dependency for age and patterns
          List<ProjectHealthAnalysis.DependencyHealthAnalysis> dependencyAnalyses;
          try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<ProjectHealthAnalysis.DependencyHealthAnalysis>> futures =
                depList.stream()
                    .distinct()
                    .map(
                        dep ->
                            CompletableFuture.supplyAsync(
                                () -> analyzeSimpleDependencyHealth(dep, maxAgeInDays), executor))
                    .toList();
            dependencyAnalyses = futures.stream().map(CompletableFuture::join).toList();
          }

          return buildSimpleHealthSummary(dependencyAnalyses, maxAgeInDays);
        });
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

  @SuppressWarnings("java:S1172") // preferStable is used by VersionsByType.getPreferredVersion()
  private VersionsByType buildVersionsByType(
      MavenCoordinate coordinate, List<String> allVersions, boolean preferStable) {
    Map<VersionType, String> versionsByType = HashMap.newHashMap(5);

    for (String version : allVersions) {
      VersionType type = versionComparator.getVersionType(version);
      versionsByType.putIfAbsent(type, version);
      if (versionsByType.size() == 5) break;
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

  private BulkCheckResult processStableVersionCheck(String dep) {
    try {
      MavenCoordinate coordinate = MavenCoordinateParser.parse(dep);
      List<String> allVersions = mavenCentralService.getAllVersions(coordinate);

      if (allVersions.isEmpty()) {
        return BulkCheckResult.notFound(coordinate.toCoordinateString());
      }

      List<String> stableVersions =
          allVersions.stream().filter(versionComparator::isStableVersion).toList();

      String latestStable = stableVersions.isEmpty() ? null : stableVersions.get(0);

      return latestStable != null
          ? BulkCheckResult.foundStable(
              coordinate.toCoordinateString(),
              latestStable,
              VersionType.STABLE.getDisplayName(),
              allVersions.size(),
              stableVersions.size())
          : BulkCheckResult.noStableVersion(coordinate.toCoordinateString(), allVersions.size());
    } catch (Exception e) {
      return BulkCheckResult.error(dep, e.getMessage());
    }
  }

  private VersionComparison.DependencyComparisonResult compareDependencyVersion(
      String dep, boolean onlyStableTargets) {
    try {
      MavenCoordinate coordinate = MavenCoordinateParser.parse(dep);
      String currentVersion = coordinate.version();
      String latestVersion =
          onlyStableTargets
              ? getLatestStableVersion(coordinate)
              : mavenCentralService.getLatestVersion(coordinate);

      if (latestVersion == null) {
        return VersionComparison.DependencyComparisonResult.notFound(
            coordinate.toCoordinateString(), currentVersion);
      }

      if (currentVersion == null) {
        return VersionComparison.DependencyComparisonResult.noCurrentVersion(
            coordinate.toCoordinateString());
      }

      String latestType = versionComparator.getVersionTypeString(latestVersion);
      String updateType = versionComparator.determineUpdateType(currentVersion, latestVersion);
      boolean updateAvailable = !currentVersion.equals(latestVersion);

      // Return basic comparison result

      return VersionComparison.DependencyComparisonResult.success(
          coordinate.toCoordinateString(),
          currentVersion,
          latestVersion,
          latestType,
          updateType,
          updateAvailable,
          context7Properties.enabled());
    } catch (Exception e) {
      return VersionComparison.DependencyComparisonResult.error(dep, e.getMessage());
    }
  }

  private BulkCheckResult processComprehensiveVersionCheck(String dep) {
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
          versionInfos.latestMilestone());
    } catch (Exception e) {
      logger.error("Error processing comprehensive version check for {}: {}", dep, e.getMessage());
      return BulkCheckResult.error(dep, e.getMessage());
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
        counts.getOrDefault("major", 0L).intValue(),
        counts.getOrDefault("minor", 0L).intValue(),
        counts.getOrDefault("patch", 0L).intValue(),
        counts.getOrDefault("none", 0L).intValue());
  }

  private String getLatestStableVersion(MavenCoordinate coordinate) throws MavenCentralException {
    List<String> allVersions = mavenCentralService.getAllVersions(coordinate);
    List<String> stableVersions =
        allVersions.stream().filter(versionComparator::isStableVersion).toList();
    return stableVersions.isEmpty() ? null : stableVersions.get(0);
  }

  private ReleasePatternAnalysis analyzeReleasePattern(
      String dependency, List<MavenSearchResponse.MavenArtifact> allVersions, int analysisMonths) {

    Instant now = Instant.now();
    Instant cutoffDate = now.minus((long) analysisMonths * DAYS_IN_MONTH, ChronoUnit.DAYS);

    // Filter versions within analysis period
    List<MavenSearchResponse.MavenArtifact> analysisVersions =
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

  private VersionTimelineAnalysis analyzeVersionTimeline(
      String dependency, List<MavenSearchResponse.MavenArtifact> versions) {

    Instant now = Instant.now();

    // Pre-calculate all intervals and average - single pass optimization
    long[] intervalDays = new long[versions.size()];
    List<Long> positiveIntervals = new ArrayList<>();

    for (int i = 1; i < versions.size(); i++) {
      long currentTimestamp = versions.get(i - 1).timestamp();
      long prevTimestamp = versions.get(i).timestamp();
      long interval = (currentTimestamp - prevTimestamp) / MILLISECONDS_TO_DAYS;
      intervalDays[i] = interval;
      if (interval > 0) positiveIntervals.add(interval);
    }

    double averageInterval =
        positiveIntervals.isEmpty()
            ? 0
            : positiveIntervals.stream().mapToLong(Long::longValue).average().orElse(0);

    // Build timeline entries using pre-calculated intervals
    List<VersionTimelineAnalysis.TimelineEntry> timeline = new ArrayList<>();
    for (int i = 0; i < versions.size(); i++) {
      MavenSearchResponse.MavenArtifact version = versions.get(i);
      Instant releaseDate = Instant.ofEpochMilli(version.timestamp());

      String relativeTime = VersionTimelineAnalysis.formatRelativeTime(releaseDate, now);
      VersionType versionType = versionComparator.getVersionType(version.version());

      Long daysSincePrevious = i > 0 ? intervalDays[i] : null;
      ReleaseGap gap =
          i > 0 ? ReleaseGap.classify(intervalDays[i], averageInterval) : ReleaseGap.NORMAL;

      boolean isBreakingChange =
          versionComparator.determineUpdateType("0.0.0", version.version()).equals("major");

      timeline.add(
          new VersionTimelineAnalysis.TimelineEntry(
              version.version(),
              versionType,
              releaseDate,
              relativeTime,
              daysSincePrevious,
              isBreakingChange,
              gap));
    }

    // Calculate metrics
    Instant oldestDate = Instant.ofEpochMilli(versions.get(versions.size() - 1).timestamp());
    int timeSpanMonths = (int) Duration.between(oldestDate, now).toDays() / DAYS_IN_MONTH;

    // Count recent activity with optimized stream operations
    Instant oneMonthAgo = now.minus(DAYS_IN_MONTH, ChronoUnit.DAYS);
    Instant threeMonthsAgo = now.minus(3L * DAYS_IN_MONTH, ChronoUnit.DAYS);

    long releasesLastMonth =
        versions.stream()
            .filter(v -> Instant.ofEpochMilli(v.timestamp()).isAfter(oneMonthAgo))
            .count();

    long releasesLastQuarter =
        versions.stream()
            .filter(v -> Instant.ofEpochMilli(v.timestamp()).isAfter(threeMonthsAgo))
            .count();

    // Create analysis objects
    VersionTimelineAnalysis.VelocityTrend velocityTrend =
        new VersionTimelineAnalysis.VelocityTrend(
            TrendDirection.STABLE,
            "Release velocity appears stable",
            releasesLastQuarter / 3.0,
            versions.size() / Math.max(timeSpanMonths, 1.0),
            0.0);

    long stableCount =
        timeline.stream().mapToLong(t -> t.versionType() == VersionType.STABLE ? 1 : 0).sum();
    double stablePercentage = (double) stableCount / timeline.size() * 100;

    VersionTimelineAnalysis.StabilityPattern stabilityPattern =
        new VersionTimelineAnalysis.StabilityPattern(
            stablePercentage,
            "Mix of stable and pre-release versions",
            "Regular stable releases",
            stablePercentage > 70
                ? "Good stability pattern - safe for production use"
                : "Consider waiting for stable releases");

    long lastReleaseAge = Duration.between(timeline.get(0).releaseDate(), now).toDays();

    VersionTimelineAnalysis.RecentActivity recentActivity =
        new VersionTimelineAnalysis.RecentActivity(
            (int) releasesLastMonth,
            (int) releasesLastQuarter,
            ActivityLevel.classify((int) releasesLastMonth, (int) releasesLastQuarter),
            lastReleaseAge,
            "Recent activity: " + releasesLastQuarter + " releases in last quarter");

    List<String> insights = generateTimelineInsights(timeline, recentActivity, stabilityPattern);

    return new VersionTimelineAnalysis(
        dependency,
        versions.size(),
        timeline.size(),
        timeSpanMonths,
        timeline,
        velocityTrend,
        stabilityPattern,
        recentActivity,
        insights);
  }

  private List<String> generateTimelineInsights(
      List<VersionTimelineAnalysis.TimelineEntry> timeline,
      VersionTimelineAnalysis.RecentActivity recentActivity,
      VersionTimelineAnalysis.StabilityPattern stabilityPattern) {

    List<String> insights = new ArrayList<>();

    if (recentActivity.activityLevel() == ActivityLevel.VERY_ACTIVE) {
      insights.add("High release frequency indicates active development");
    } else if (recentActivity.activityLevel() == ActivityLevel.DORMANT) {
      insights.add("No recent releases - consider checking project status");
    }

    if (stabilityPattern.stablePercentage() > VERY_HIGH_STABILITY_THRESHOLD) {
      insights.add("Strong preference for stable releases - good for production");
    } else if (stabilityPattern.stablePercentage() < LOW_STABILITY_THRESHOLD) {
      insights.add("Many pre-release versions - early-stage or experimental project");
    }

    long majorGaps =
        timeline.stream().mapToLong(t -> t.releaseGap() == ReleaseGap.MAJOR_GAP ? 1 : 0).sum();
    if (majorGaps > 0) {
      insights.add("Found " + majorGaps + " significant gaps in release schedule");
    }

    return insights;
  }

  private ProjectHealthAnalysis.DependencyHealthAnalysis analyzeSimpleDependencyHealth(
      String dependency, Integer maxAgeInDays) {
    try {
      MavenCoordinate coordinate = MavenCoordinateParser.parse(dependency);
      List<MavenSearchResponse.MavenArtifact> versions =
          mavenCentralService.getRecentVersionsWithTimestamps(coordinate, 10);

      if (versions.isEmpty()) {
        return ProjectHealthAnalysis.DependencyHealthAnalysis.notFound(dependency);
      }

      MavenSearchResponse.MavenArtifact latestVersion = versions.get(0);
      DependencyAgeAnalysis ageAnalysis =
          DependencyAgeAnalysis.fromTimestamp(
              coordinate.toCoordinateString(), latestVersion.version(), latestVersion.timestamp());

      // Simple maintenance assessment based on recent versions
      Instant now = Instant.now();
      Instant sixMonthsAgo = now.minus(6L * DAYS_IN_MONTH, ChronoUnit.DAYS);

      long recentVersions =
          versions.stream()
              .filter(v -> Instant.ofEpochMilli(v.timestamp()).isAfter(sixMonthsAgo))
              .count();

      String maintenanceStatus;
      if (recentVersions >= 3) {
        maintenanceStatus = ACTIVE_MAINTENANCE;
      } else if (recentVersions >= 1) {
        maintenanceStatus = "moderate";
      } else {
        maintenanceStatus = "slow";
      }

      // Health score (0-100)
      int healthScore = calculateSimpleHealthScore(ageAnalysis, maintenanceStatus, maxAgeInDays);

      // No upgrade strategy in basic analysis

      return ProjectHealthAnalysis.DependencyHealthAnalysis.success(
          dependency,
          latestVersion.version(),
          ageAnalysis.ageClassification().getName(),
          ageAnalysis.daysSinceLastRelease(),
          healthScore,
          maintenanceStatus,
          context7Properties.enabled());
    } catch (Exception e) {
      return ProjectHealthAnalysis.DependencyHealthAnalysis.error(dependency, e.getMessage());
    }
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
      case "moderate" -> score -= MODERATE_MAINTENANCE_PENALTY;
      case "slow" -> score -= SLOW_MAINTENANCE_PENALTY;
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
      Integer maxAgeInDays) {

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
      return new ProjectHealthAnalysis(
          "unknown",
          0,
          totalDependencies,
          0,
          new ProjectHealthAnalysis.AgeDistribution(0, 0, 0, 0),
          dependencyAnalyses,
          List.of("Unable to analyze any dependencies"));
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
        generateHealthRecommendations(metrics, successfulAnalyses, successfulDeps, maxAgeInDays);

    return new ProjectHealthAnalysis(
        overallHealth,
        (int) Math.round(averageHealthScore),
        totalDependencies,
        successfulAnalyses,
        ageDistribution,
        dependencyAnalyses,
        recommendations);
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
      Integer maxAgeInDays) {
    List<String> recommendations = new ArrayList<>();

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

  private record DependencyMetrics(
      int totalHealthScore,
      long freshCount,
      long currentCount,
      long agingCount,
      long staleCount,
      long activeMaintenanceCount) {}
}
