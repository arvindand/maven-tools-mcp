package com.arvindand.mcp.maven.service;

import com.arvindand.mcp.maven.model.BulkCheckResult;
import com.arvindand.mcp.maven.model.DependencyAgeAnalysis;
import com.arvindand.mcp.maven.model.DependencyExistsResponse;
import com.arvindand.mcp.maven.model.DetailedVersionInfo;
import com.arvindand.mcp.maven.model.MavenCoordinate;
import com.arvindand.mcp.maven.model.MavenSearchResponse;
import com.arvindand.mcp.maven.model.ReleasePatternAnalysis;
import com.arvindand.mcp.maven.model.VersionComparisonResponse;
import com.arvindand.mcp.maven.model.VersionInfo;
import com.arvindand.mcp.maven.model.VersionInfo.VersionType;
import com.arvindand.mcp.maven.model.VersionTimelineAnalysis;
import com.arvindand.mcp.maven.util.MavenCoordinateParser;
import com.arvindand.mcp.maven.util.VersionComparator;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
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
  private static final Logger logger = LoggerFactory.getLogger(MavenDependencyTools.class);
  private final MavenCentralService mavenCentralService;
  private final VersionComparator versionComparator;
  private final JsonResponseService jsonResponseService;

  public MavenDependencyTools(
      MavenCentralService mavenCentralService,
      VersionComparator versionComparator,
      JsonResponseService jsonResponseService) {
    this.mavenCentralService = mavenCentralService;
    this.versionComparator = versionComparator;
    this.jsonResponseService = jsonResponseService;
  }

  /**
   * Get the latest version of any dependency from Maven Central (works with Maven, Gradle, SBT, Mill).
   *
   * @param dependency the dependency coordinate (groupId:artifactId)
   * @param preferStable when true, prioritize stable version in response (default: false)
   * @return JSON response with latest versions by type
   */
  @SuppressWarnings("java:S100") // MCP tool method naming
  @Tool(
      description =
          "Get latest version of any dependency from Maven Central (works with Maven, Gradle, SBT, Mill). "
              + "Shows ALL version types (stable, rc, beta, alpha, milestone) for comprehensive analysis. "
              + "When preferStable=true, prioritizes stable version in response while still including all types. "
              + "Format: 'groupId:artifactId' (NO version). Example: 'org.springframework:spring-core'")
  public String get_latest_version(String dependency, boolean preferStable) {
    try {
      MavenCoordinate coordinate = MavenCoordinateParser.parse(dependency);
      List<String> allVersions = mavenCentralService.getAllVersions(coordinate);

      if (allVersions.isEmpty()) {
        return notFoundResponse(coordinate);
      }

      return jsonResponseService.toJson(buildVersionsByType(coordinate, allVersions, preferStable));
    } catch (IllegalArgumentException e) {
      return jsonResponseService.createErrorResponse(
          INVALID_MAVEN_COORDINATE_FORMAT + e.getMessage());
    } catch (MavenCentralException e) {
      return jsonResponseService.createErrorResponse(MAVEN_CENTRAL_ERROR + e.getMessage());
    } catch (Exception e) {
      logger.error(UNEXPECTED_ERROR, e);
      return jsonResponseService.createErrorResponse(UNEXPECTED_ERROR + ": " + e.getMessage());
    }
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
          "Check if specific dependency version exists and identify its stability type. "
              + "Works with any JVM build tool (Maven, Gradle, SBT, Mill) using Maven Central Repository. "
              + "Format: 'groupId:artifactId' + version. Example: "
              + "dependency='org.springframework:spring-core', version='6.1.4'")
  public String check_version_exists(String dependency, String version) {
    try {
      MavenCoordinate coordinate = MavenCoordinateParser.parse(dependency);
      String versionToCheck = coordinate.version() != null ? coordinate.version() : version;

      if (versionToCheck == null || versionToCheck.trim().isEmpty()) {
        return jsonResponseService.createErrorResponse(
            "Version must be provided either in dependency string or version parameter");
      }

      boolean exists = mavenCentralService.checkVersionExists(coordinate, versionToCheck);
      String versionType = versionComparator.getVersionTypeString(versionToCheck);

      return jsonResponseService.toJson(
          new DependencyExistsResponse(exists, versionToCheck, versionType));
    } catch (IllegalArgumentException e) {
      return jsonResponseService.createErrorResponse(
          INVALID_MAVEN_COORDINATE_FORMAT + e.getMessage());
    } catch (MavenCentralException e) {
      return jsonResponseService.createErrorResponse(MAVEN_CENTRAL_ERROR + e.getMessage());
    } catch (Exception e) {
      logger.error(UNEXPECTED_ERROR, e);
      return jsonResponseService.createErrorResponse(UNEXPECTED_ERROR + ": " + e.getMessage());
    }
  }


  /**
   * Get latest stable version only - excludes alpha, beta, RC, milestone versions.
   *
   * @param dependency the dependency coordinate (groupId:artifactId)
   * @return JSON response with latest stable version details
   */
  @SuppressWarnings("java:S100") // MCP tool method naming
  @Tool(
      description =
          "Get latest stable version only - excludes alpha, beta, RC, milestone versions. "
              + "Perfect for production deployments across any JVM build tool (Maven, Gradle, SBT, Mill). "
              + "Use when you specifically need production-ready versions only. "
              + "Format: 'groupId:artifactId' (NO version). Example: 'com.fasterxml.jackson.core:jackson-core'")
  public String get_stable_version(String dependency) {
    try {
      MavenCoordinate coordinate = MavenCoordinateParser.parse(dependency);
      List<String> allVersions = mavenCentralService.getAllVersions(coordinate);

      if (allVersions.isEmpty()) {
        return notFoundResponse(coordinate);
      }

      List<String> stableVersions =
          allVersions.stream().filter(versionComparator::isStableVersion).toList();

      if (stableVersions.isEmpty()) {
        return jsonResponseService.createNotFoundResponse(
            "No stable version found for %s:%s (found %d pre-release versions)"
                .formatted(coordinate.groupId(), coordinate.artifactId(), allVersions.size()));
      }

      String latestStable = stableVersions.get(0);
      VersionType versionType = versionComparator.getVersionType(latestStable);

      return jsonResponseService.toJson(
          new DetailedVersionInfo(
              latestStable, versionType, allVersions.size(), stableVersions.size()));
    } catch (IllegalArgumentException e) {
      return jsonResponseService.createErrorResponse(
          INVALID_MAVEN_COORDINATE_FORMAT + e.getMessage());
    } catch (MavenCentralException e) {
      return jsonResponseService.createErrorResponse(MAVEN_CENTRAL_ERROR + e.getMessage());
    } catch (Exception e) {
      logger.error(UNEXPECTED_ERROR, e);
      return jsonResponseService.createErrorResponse(UNEXPECTED_ERROR + ": " + e.getMessage());
    }
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
          "Check latest versions for multiple dependencies with filtering options. "
              + "Works with any JVM build tool (Maven, Gradle, SBT, Mill) using Maven Central Repository. "
              + "When stableOnly=false, includes ALL version types for comprehensive analysis. "
              + "When stableOnly=true, filters to production-ready versions only. "
              + "Format: 'groupId:artifactId' (NO versions). Example: 'org.springframework:spring-core,junit:junit'")
  public String check_multiple_dependencies(String dependencies, boolean stableOnly) {
    try {
      List<String> depList = parseDependencies(dependencies);

      List<BulkCheckResult> results;
      try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        List<CompletableFuture<BulkCheckResult>> futures =
            depList.stream()
                .distinct()
                .map(
                    dep ->
                        CompletableFuture.supplyAsync(
                            () -> stableOnly ? processStableVersionCheck(dep) : processComprehensiveVersionCheck(dep), executor))
                .toList();
        results = futures.stream().map(CompletableFuture::join).toList();
      }

      return jsonResponseService.toJson(results);
    } catch (IllegalArgumentException e) {
      return jsonResponseService.createErrorResponse(
          INVALID_MAVEN_COORDINATE_FORMAT + e.getMessage());
    } catch (MavenCentralException e) {
      return jsonResponseService.createErrorResponse(MAVEN_CENTRAL_ERROR + e.getMessage());
    } catch (Exception e) {
      logger.error(UNEXPECTED_ERROR, e);
      return jsonResponseService.createErrorResponse(UNEXPECTED_ERROR + ": " + e.getMessage());
    }
  }


  /**
   * Get latest stable versions for multiple dependencies - perfect for production updates.
   *
   * @param dependencies comma or newline separated list of dependency coordinates
   * @return JSON response with bulk stable version check results
   */
  @SuppressWarnings("java:S100") // MCP tool method naming
  @Tool(
      description =
          "Get latest stable versions for multiple dependencies - perfect for production updates. "
              + "Excludes pre-release versions (alpha, beta, RC, milestone) for safe production deployments. "
              + "Works with any JVM build tool (Maven, Gradle, SBT, Mill) using Maven Central Repository. "
              + "Format: 'groupId:artifactId' (NO versions). Example: "
              + "'org.springframework:spring-boot-starter,com.fasterxml.jackson.core:jackson-core'")
  public String check_multiple_stable_versions(String dependencies) {
    try {
      List<String> depList = parseDependencies(dependencies);

      List<BulkCheckResult> results;
      try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        List<CompletableFuture<BulkCheckResult>> futures =
            depList.stream()
                .distinct()
                .map(
                    dep ->
                        CompletableFuture.supplyAsync(
                            () -> processStableVersionCheck(dep), executor))
                .toList();
        results = futures.stream().map(CompletableFuture::join).toList();
      }

      return jsonResponseService.toJson(results);
    } catch (IllegalArgumentException e) {
      return jsonResponseService.createErrorResponse(
          INVALID_MAVEN_COORDINATE_FORMAT + e.getMessage());
    } catch (MavenCentralException e) {
      return jsonResponseService.createErrorResponse(MAVEN_CENTRAL_ERROR + e.getMessage());
    } catch (Exception e) {
      logger.error(UNEXPECTED_ERROR, e);
      return jsonResponseService.createErrorResponse(UNEXPECTED_ERROR + ": " + e.getMessage());
    }
  }

  /**
   * Compare current dependency versions with latest available and show upgrade recommendations.
   *
   * @param currentDependencies comma or newline separated list of dependency coordinates with versions
   * @param onlyStableTargets when true, only upgrade to stable versions (default: false)
   * @return JSON response with version comparison and update recommendations
   */
  @SuppressWarnings("java:S100") // MCP tool method naming
  @Tool(
      description =
          "Compare current dependency versions with latest available and show upgrade recommendations. "
              + "Works with any JVM build tool (Maven, Gradle, SBT, Mill) using Maven Central Repository. "
              + "When onlyStableTargets=true, only suggests upgrades to stable versions for production safety. "
              + "Format: 'groupId:artifactId:version' (MUST include versions). "
              + "Example: 'org.springframework:spring-core:6.0.0,junit:junit:4.12'")
  public String compare_dependency_versions(String currentDependencies, boolean onlyStableTargets) {
    try {
      List<String> depList = parseDependencies(currentDependencies);

      List<VersionComparisonResponse.DependencyComparisonResult> results;
      try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        List<CompletableFuture<VersionComparisonResponse.DependencyComparisonResult>> futures =
            depList.stream()
                .distinct()
                .map(
                    dep ->
                        CompletableFuture.supplyAsync(
                            () -> compareDependencyVersion(dep, onlyStableTargets), executor))
                .toList();
        results = futures.stream().map(CompletableFuture::join).toList();
      }

      VersionComparisonResponse.UpdateSummary summary = calculateUpdateSummary(results);
      VersionComparisonResponse response =
          new VersionComparisonResponse(Instant.now(), results, summary);

      return jsonResponseService.toJson(response);
    } catch (IllegalArgumentException e) {
      return jsonResponseService.createErrorResponse(
          INVALID_MAVEN_COORDINATE_FORMAT + e.getMessage());
    } catch (MavenCentralException e) {
      return jsonResponseService.createErrorResponse(MAVEN_CENTRAL_ERROR + e.getMessage());
    } catch (Exception e) {
      logger.error(UNEXPECTED_ERROR, e);
      return jsonResponseService.createErrorResponse(UNEXPECTED_ERROR + ": " + e.getMessage());
    }
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
          "Analyze how old a dependency is and classify its freshness (fresh/current/aging/stale). "
              + "Provides immediate actionable insights for maintenance planning across any JVM build tool. "
              + "Use maxAgeInDays to set acceptable age threshold (optional). "
              + "Format: 'groupId:artifactId' (NO version). Example: 'org.springframework:spring-core'")
  public String analyze_dependency_age(String dependency, Integer maxAgeInDays) {
    try {
      MavenCoordinate coordinate = MavenCoordinateParser.parse(dependency);
      List<MavenSearchResponse.MavenArtifact> versions = mavenCentralService.getRecentVersionsWithTimestamps(coordinate, 1);

      if (versions.isEmpty()) {
        return notFoundResponse(coordinate);
      }

      MavenSearchResponse.MavenArtifact latestVersion = versions.get(0);
      DependencyAgeAnalysis analysis = DependencyAgeAnalysis.fromTimestamp(
          coordinate.toCoordinateString(),
          latestVersion.version(),
          latestVersion.timestamp());

      // Add custom recommendation if maxAgeInDays is specified
      if (maxAgeInDays != null && analysis.daysSinceLastRelease() > maxAgeInDays) {
        DependencyAgeAnalysis customAnalysis = new DependencyAgeAnalysis(
            analysis.dependency(),
            analysis.latestVersion(),
            analysis.ageClassification(),
            analysis.daysSinceLastRelease(),
            analysis.lastReleaseDate(),
            analysis.ageDescription(),
            "Exceeds specified age threshold of " + maxAgeInDays + " days - " + analysis.recommendation()
        );
        return jsonResponseService.toJson(customAnalysis);
      }

      return jsonResponseService.toJson(analysis);
    } catch (IllegalArgumentException e) {
      return jsonResponseService.createErrorResponse(
          INVALID_MAVEN_COORDINATE_FORMAT + e.getMessage());
    } catch (MavenCentralException e) {
      return jsonResponseService.createErrorResponse(MAVEN_CENTRAL_ERROR + e.getMessage());
    } catch (Exception e) {
      logger.error(UNEXPECTED_ERROR, e);
      return jsonResponseService.createErrorResponse(UNEXPECTED_ERROR + ": " + e.getMessage());
    }
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
          "Analyze release patterns, maintenance activity and predict next release timeframe. "
              + "Provides insights into project health based on historical release data across any JVM build tool. "
              + "Use monthsToAnalyze to specify analysis period (default: 24 months). "
              + "Format: 'groupId:artifactId' (NO version). Example: 'com.fasterxml.jackson.core:jackson-core'")
  public String analyze_release_patterns(String dependency, Integer monthsToAnalyze) {
    try {
      MavenCoordinate coordinate = MavenCoordinateParser.parse(dependency);
      int analysisMonths = monthsToAnalyze != null ? monthsToAnalyze : 24;
      
      List<MavenSearchResponse.MavenArtifact> allVersions = 
          mavenCentralService.getAllVersionsWithTimestamps(coordinate);

      if (allVersions.isEmpty()) {
        return notFoundResponse(coordinate);
      }

      ReleasePatternAnalysis analysis = analyzeReleasePattern(
          coordinate.toCoordinateString(), allVersions, analysisMonths);

      return jsonResponseService.toJson(analysis);
    } catch (IllegalArgumentException e) {
      return jsonResponseService.createErrorResponse(
          INVALID_MAVEN_COORDINATE_FORMAT + e.getMessage());
    } catch (MavenCentralException e) {
      return jsonResponseService.createErrorResponse(MAVEN_CENTRAL_ERROR + e.getMessage());
    } catch (Exception e) {
      logger.error(UNEXPECTED_ERROR, e);
      return jsonResponseService.createErrorResponse(UNEXPECTED_ERROR + ": " + e.getMessage());
    }
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
          "Get version timeline with temporal analysis, release gaps, and stability patterns. "
              + "Shows version progression with relative timestamps and trend analysis across any JVM build tool. "
              + "Use versionCount to specify how many recent versions to analyze (default: 20). "
              + "Format: 'groupId:artifactId' (NO version). Example: 'org.junit.jupiter:junit-jupiter'")
  public String get_version_timeline(String dependency, Integer versionCount) {
    try {
      MavenCoordinate coordinate = MavenCoordinateParser.parse(dependency);
      int maxVersions = versionCount != null ? versionCount : 20;
      
      List<MavenSearchResponse.MavenArtifact> versions = 
          mavenCentralService.getRecentVersionsWithTimestamps(coordinate, maxVersions);

      if (versions.isEmpty()) {
        return notFoundResponse(coordinate);
      }

      VersionTimelineAnalysis analysis = analyzeVersionTimeline(
          coordinate.toCoordinateString(), versions);

      return jsonResponseService.toJson(analysis);
    } catch (IllegalArgumentException e) {
      return jsonResponseService.createErrorResponse(
          INVALID_MAVEN_COORDINATE_FORMAT + e.getMessage());
    } catch (MavenCentralException e) {
      return jsonResponseService.createErrorResponse(MAVEN_CENTRAL_ERROR + e.getMessage());
    } catch (Exception e) {
      logger.error(UNEXPECTED_ERROR, e);
      return jsonResponseService.createErrorResponse(UNEXPECTED_ERROR + ": " + e.getMessage());
    }
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
          "Analyze overall health of multiple dependencies combining age analysis and maintenance patterns. "
              + "Provides quick health assessment with actionable insights for maintenance planning. "
              + "Works with any JVM build tool (Maven, Gradle, SBT, Mill). "
              + "Use maxAgeInDays to set acceptable age threshold (optional). "
              + "Format: 'groupId:artifactId' (NO versions). Example: 'org.springframework:spring-core,junit:junit'")
  public String analyze_project_health(String dependencies, Integer maxAgeInDays) {
    try {
      List<String> depList = parseDependencies(dependencies);

      if (depList.isEmpty()) {
        return jsonResponseService.createErrorResponse("No dependencies provided for analysis");
      }

      // Analyze each dependency for age and patterns
      List<Map<String, Object>> dependencyAnalyses;
      try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        List<CompletableFuture<Map<String, Object>>> futures = depList.stream()
            .distinct()
            .map(dep -> CompletableFuture.supplyAsync(() -> analyzeSimpleDependencyHealth(dep, maxAgeInDays), executor))
            .toList();
        dependencyAnalyses = futures.stream().map(CompletableFuture::join).toList();
      }

      Map<String, Object> healthSummary = buildSimpleHealthSummary(dependencyAnalyses, maxAgeInDays);
      return jsonResponseService.toJson(healthSummary);
    } catch (IllegalArgumentException e) {
      return jsonResponseService.createErrorResponse(
          INVALID_MAVEN_COORDINATE_FORMAT + e.getMessage());
    } catch (MavenCentralException e) {
      return jsonResponseService.createErrorResponse(MAVEN_CENTRAL_ERROR + e.getMessage());
    } catch (Exception e) {
      logger.error(UNEXPECTED_ERROR, e);
      return jsonResponseService.createErrorResponse(UNEXPECTED_ERROR + ": " + e.getMessage());
    }
  }

  private String notFoundResponse(MavenCoordinate coordinate) {
    String message =
        "No Maven dependency found for %s:%s%s"
            .formatted(
                coordinate.groupId(),
                coordinate.artifactId(),
                coordinate.packaging() != null ? ":" + coordinate.packaging() : "");
    return jsonResponseService.createNotFoundResponse(message);
  }

  private Map<String, Object> buildVersionsByType(
      MavenCoordinate coordinate, List<String> allVersions, boolean preferStable) {
    Map<VersionType, String> versionsByType = HashMap.newHashMap(5);

    for (String version : allVersions) {
      VersionType type = versionComparator.getVersionType(version);
      versionsByType.putIfAbsent(type, version);
      if (versionsByType.size() == 5) break;
    }

    Map<String, Object> result = new java.util.LinkedHashMap<>();
    result.put("dependency", coordinate.toCoordinateString());

    // If preferStable is true, put stable first
    if (preferStable && versionsByType.containsKey(VersionType.STABLE)) {
      addVersionIfPresent(result, "latest_stable", versionsByType.get(VersionType.STABLE));
      addVersionIfPresent(result, "latest_rc", versionsByType.get(VersionType.RC));
      addVersionIfPresent(result, "latest_beta", versionsByType.get(VersionType.BETA));
      addVersionIfPresent(result, "latest_alpha", versionsByType.get(VersionType.ALPHA));
      addVersionIfPresent(result, "latest_milestone", versionsByType.get(VersionType.MILESTONE));
      result.put("preferred_version", versionsByType.get(VersionType.STABLE));
    } else {
      addVersionIfPresent(result, "latest_stable", versionsByType.get(VersionType.STABLE));
      addVersionIfPresent(result, "latest_rc", versionsByType.get(VersionType.RC));
      addVersionIfPresent(result, "latest_beta", versionsByType.get(VersionType.BETA));
      addVersionIfPresent(result, "latest_alpha", versionsByType.get(VersionType.ALPHA));
      addVersionIfPresent(result, "latest_milestone", versionsByType.get(VersionType.MILESTONE));
    }

    result.put("total_versions", allVersions.size());
    return result;
  }

  private void addVersionIfPresent(Map<String, Object> result, String key, String version) {
    if (version != null) {
      result.put(key, new VersionInfo(version, versionComparator.getVersionType(version)));
    }
  }

  private List<String> parseDependencies(String dependencies) {
    if (dependencies == null || dependencies.trim().isEmpty()) {
      return List.of();
    }

    return dependencies
        .lines()
        .flatMap(line -> java.util.Arrays.stream(line.split(",")))
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

  private VersionComparisonResponse.DependencyComparisonResult compareDependencyVersion(
      String dep, boolean onlyStableTargets) {
    try {
      MavenCoordinate coordinate = MavenCoordinateParser.parse(dep);
      String currentVersion = coordinate.version();
      String latestVersion = onlyStableTargets ? getLatestStableVersion(coordinate) : mavenCentralService.getLatestVersion(coordinate);

      if (latestVersion == null) {
        return VersionComparisonResponse.DependencyComparisonResult.notFound(
            coordinate.toCoordinateString(), currentVersion);
      }

      if (currentVersion == null) {
        return VersionComparisonResponse.DependencyComparisonResult.noCurrentVersion(
            coordinate.toCoordinateString());
      }

      String latestType = versionComparator.getVersionTypeString(latestVersion);
      String updateType = versionComparator.determineUpdateType(currentVersion, latestVersion);
      boolean updateAvailable = !currentVersion.equals(latestVersion);

      return VersionComparisonResponse.DependencyComparisonResult.success(
          coordinate.toCoordinateString(),
          currentVersion,
          latestVersion,
          latestType,
          updateType,
          updateAvailable);
    } catch (Exception e) {
      return VersionComparisonResponse.DependencyComparisonResult.error(dep, e.getMessage());
    }
  }

  private BulkCheckResult processComprehensiveVersionCheck(String dep) {
    try {
      MavenCoordinate coordinate = MavenCoordinateParser.parse(dep);
      List<String> allVersions = mavenCentralService.getAllVersions(coordinate);

      if (allVersions.isEmpty()) {
        return BulkCheckResult.notFound(dep);
      }

      // Build version info for each type
      Map<VersionType, String> versionsByType = HashMap.newHashMap(5);
      for (String version : allVersions) {
        VersionType type = versionComparator.getVersionType(version);
        versionsByType.putIfAbsent(type, version);
        if (versionsByType.size() == 5) break;
      }

      // Convert to VersionInfo objects
      VersionInfo latestStable =
          versionsByType.containsKey(VersionType.STABLE)
              ? new VersionInfo(versionsByType.get(VersionType.STABLE), VersionType.STABLE)
              : null;
      VersionInfo latestRc =
          versionsByType.containsKey(VersionType.RC)
              ? new VersionInfo(versionsByType.get(VersionType.RC), VersionType.RC)
              : null;
      VersionInfo latestBeta =
          versionsByType.containsKey(VersionType.BETA)
              ? new VersionInfo(versionsByType.get(VersionType.BETA), VersionType.BETA)
              : null;
      VersionInfo latestAlpha =
          versionsByType.containsKey(VersionType.ALPHA)
              ? new VersionInfo(versionsByType.get(VersionType.ALPHA), VersionType.ALPHA)
              : null;
      VersionInfo latestMilestone =
          versionsByType.containsKey(VersionType.MILESTONE)
              ? new VersionInfo(versionsByType.get(VersionType.MILESTONE), VersionType.MILESTONE)
              : null;

      int stableCount =
          (int)
              allVersions.stream()
                  .mapToInt(v -> versionComparator.getVersionType(v) == VersionType.STABLE ? 1 : 0)
                  .sum();

      // Prefer stable version as primary, fallback to latest overall
      String primaryVersion = latestStable != null ? latestStable.version() : allVersions.get(0);
      String primaryType =
          latestStable != null
              ? VersionType.STABLE.getDisplayName()
              : versionComparator.getVersionTypeString(allVersions.get(0));

      return BulkCheckResult.foundComprehensive(
          dep,
          primaryVersion,
          primaryType,
          allVersions.size(),
          stableCount,
          latestStable,
          latestRc,
          latestBeta,
          latestAlpha,
          latestMilestone);
    } catch (Exception e) {
      logger.error("Error processing comprehensive version check for {}: {}", dep, e.getMessage());
      return BulkCheckResult.error(dep, e.getMessage());
    }
  }

  private VersionComparisonResponse.UpdateSummary calculateUpdateSummary(
      List<VersionComparisonResponse.DependencyComparisonResult> results) {

    Map<String, Long> counts =
        results.stream()
            .filter(result -> "success".equals(result.status()))
            .collect(
                Collectors.groupingBy(
                    VersionComparisonResponse.DependencyComparisonResult::updateType,
                    Collectors.counting()));

    return new VersionComparisonResponse.UpdateSummary(
        counts.getOrDefault("major", 0L).intValue(),
        counts.getOrDefault("minor", 0L).intValue(),
        counts.getOrDefault("patch", 0L).intValue(),
        counts.getOrDefault("none", 0L).intValue());
  }

  private String getLatestStableVersion(MavenCoordinate coordinate) throws MavenCentralException {
    List<String> allVersions = mavenCentralService.getAllVersions(coordinate);
    List<String> stableVersions = allVersions.stream()
        .filter(versionComparator::isStableVersion)
        .toList();
    return stableVersions.isEmpty() ? null : stableVersions.get(0);
  }

  private ReleasePatternAnalysis analyzeReleasePattern(
      String dependency, List<MavenSearchResponse.MavenArtifact> allVersions, int analysisMonths) {
    
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    LocalDateTime cutoffDate = now.minusMonths(analysisMonths);
    
    // Filter versions within analysis period
    List<MavenSearchResponse.MavenArtifact> analysisVersions = allVersions.stream()
        .filter(v -> {
          LocalDateTime releaseDate = LocalDateTime.ofInstant(
              Instant.ofEpochMilli(v.timestamp()), ZoneOffset.UTC);
          return releaseDate.isAfter(cutoffDate);
        })
        .toList();

    if (analysisVersions.isEmpty()) {
      // Fallback to all versions if none in analysis period
      analysisVersions = allVersions.stream().limit(10).toList();
    }

    // Calculate release intervals
    List<Long> intervals = new java.util.ArrayList<>();
    for (int i = 1; i < analysisVersions.size(); i++) {
      long prevTimestamp = analysisVersions.get(i).timestamp();
      long currentTimestamp = analysisVersions.get(i - 1).timestamp();
      long intervalDays = (currentTimestamp - prevTimestamp) / (1000 * 60 * 60 * 24);
      if (intervalDays > 0) intervals.add(intervalDays);
    }

    // Calculate statistics
    double averageDays = intervals.isEmpty() ? 0 : intervals.stream().mapToLong(Long::longValue).average().orElse(0);
    double releaseVelocity = averageDays > 0 ? (30.0 / averageDays) : 0;
    
    long maxInterval = intervals.isEmpty() ? 0 : intervals.stream().mapToLong(Long::longValue).max().orElse(0);
    long minInterval = intervals.isEmpty() ? 0 : intervals.stream().mapToLong(Long::longValue).min().orElse(0);
    
    LocalDateTime lastReleaseDate = LocalDateTime.ofInstant(
        Instant.ofEpochMilli(analysisVersions.get(0).timestamp()), ZoneOffset.UTC);
    long daysSinceLastRelease = java.time.Duration.between(lastReleaseDate, now).toDays();

    // Classifications
    ReleasePatternAnalysis.MaintenanceLevel maintenanceLevel = 
        ReleasePatternAnalysis.MaintenanceLevel.classify(releaseVelocity, daysSinceLastRelease);
    ReleasePatternAnalysis.ReleaseConsistency consistency = 
        ReleasePatternAnalysis.ReleaseConsistency.classify(averageDays, maxInterval, minInterval);

    // Build recent releases info
    List<ReleasePatternAnalysis.ReleaseInfo> recentReleases = analysisVersions.stream()
        .limit(10)
        .map(v -> {
          LocalDateTime releaseDate = LocalDateTime.ofInstant(
              Instant.ofEpochMilli(v.timestamp()), ZoneOffset.UTC);
          return new ReleasePatternAnalysis.ReleaseInfo(v.version(), releaseDate, null);
        })
        .toList();

    String nextReleasePrediction = ReleasePatternAnalysis.predictNextRelease(
        averageDays, daysSinceLastRelease, consistency);
    String recommendation = ReleasePatternAnalysis.generateRecommendation(
        maintenanceLevel, daysSinceLastRelease, releaseVelocity);

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
        recommendation
    );
  }

  private VersionTimelineAnalysis analyzeVersionTimeline(
      String dependency, List<MavenSearchResponse.MavenArtifact> versions) {
    
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    
    // Calculate average interval for gap analysis
    List<Long> intervals = new java.util.ArrayList<>();
    for (int i = 1; i < versions.size(); i++) {
      long prevTimestamp = versions.get(i).timestamp();
      long currentTimestamp = versions.get(i - 1).timestamp();
      long intervalDays = (currentTimestamp - prevTimestamp) / (1000 * 60 * 60 * 24);
      if (intervalDays > 0) intervals.add(intervalDays);
    }
    double averageInterval = intervals.isEmpty() ? 0 : 
        intervals.stream().mapToLong(Long::longValue).average().orElse(0);

    // Build timeline entries
    List<VersionTimelineAnalysis.TimelineEntry> timeline = new java.util.ArrayList<>();
    for (int i = 0; i < versions.size(); i++) {
      MavenSearchResponse.MavenArtifact version = versions.get(i);
      LocalDateTime releaseDate = LocalDateTime.ofInstant(
          Instant.ofEpochMilli(version.timestamp()), ZoneOffset.UTC);
      
      String relativeTime = VersionTimelineAnalysis.formatRelativeTime(releaseDate, now);
      VersionType versionType = versionComparator.getVersionType(version.version());
      
      Long daysSincePrevious = null;
      VersionTimelineAnalysis.TimelineEntry.ReleaseGap gap = 
          VersionTimelineAnalysis.TimelineEntry.ReleaseGap.NORMAL;
      
      if (i > 0) {
        long prevTimestamp = versions.get(i - 1).timestamp();
        long intervalDays = (version.timestamp() - prevTimestamp) / (1000 * 60 * 60 * 24);
        daysSincePrevious = intervalDays;
        gap = VersionTimelineAnalysis.TimelineEntry.ReleaseGap.classify(intervalDays, averageInterval);
      }
      
      boolean isBreakingChange = versionComparator.determineUpdateType("0.0.0", version.version()).equals("major");
      
      timeline.add(new VersionTimelineAnalysis.TimelineEntry(
          version.version(), versionType, releaseDate, relativeTime, 
          daysSincePrevious, isBreakingChange, gap));
    }

    // Calculate metrics
    LocalDateTime oldestDate = LocalDateTime.ofInstant(
        Instant.ofEpochMilli(versions.get(versions.size() - 1).timestamp()), ZoneOffset.UTC);
    int timeSpanMonths = (int) java.time.Duration.between(oldestDate, now).toDays() / 30;
    
    // Count recent activity
    LocalDateTime oneMonthAgo = now.minusMonths(1);
    LocalDateTime threeMonthsAgo = now.minusMonths(3);
    
    int releasesLastMonth = (int) versions.stream()
        .filter(v -> LocalDateTime.ofInstant(Instant.ofEpochMilli(v.timestamp()), ZoneOffset.UTC)
            .isAfter(oneMonthAgo))
        .count();
    int releasesLastQuarter = (int) versions.stream()
        .filter(v -> LocalDateTime.ofInstant(Instant.ofEpochMilli(v.timestamp()), ZoneOffset.UTC)
            .isAfter(threeMonthsAgo))
        .count();

    // Create analysis objects
    VersionTimelineAnalysis.VelocityTrend velocityTrend = 
        new VersionTimelineAnalysis.VelocityTrend(
            VersionTimelineAnalysis.VelocityTrend.TrendDirection.STABLE,
            "Release velocity appears stable",
            releasesLastQuarter / 3.0,
            versions.size() / Math.max(timeSpanMonths, 1.0),
            0.0);

    long stableCount = timeline.stream()
        .mapToLong(t -> t.versionType() == VersionType.STABLE ? 1 : 0)
        .sum();
    double stablePercentage = (double) stableCount / timeline.size() * 100;

    VersionTimelineAnalysis.StabilityPattern stabilityPattern = 
        new VersionTimelineAnalysis.StabilityPattern(
            stablePercentage,
            "Mix of stable and pre-release versions",
            "Regular stable releases",
            stablePercentage > 70 ? "Good stability pattern - safe for production use" :
                "Consider waiting for stable releases");

    long lastReleaseAge = java.time.Duration.between(timeline.get(0).releaseDate(), now).toDays();
    
    VersionTimelineAnalysis.RecentActivity recentActivity = 
        new VersionTimelineAnalysis.RecentActivity(
            releasesLastMonth,
            releasesLastQuarter,
            VersionTimelineAnalysis.RecentActivity.ActivityLevel.classify(releasesLastMonth, releasesLastQuarter),
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
        insights
    );
  }

  private List<String> generateTimelineInsights(
      List<VersionTimelineAnalysis.TimelineEntry> timeline,
      VersionTimelineAnalysis.RecentActivity recentActivity,
      VersionTimelineAnalysis.StabilityPattern stabilityPattern) {
    
    List<String> insights = new java.util.ArrayList<>();
    
    if (recentActivity.activityLevel() == VersionTimelineAnalysis.RecentActivity.ActivityLevel.VERY_ACTIVE) {
      insights.add("High release frequency indicates active development");
    } else if (recentActivity.activityLevel() == VersionTimelineAnalysis.RecentActivity.ActivityLevel.DORMANT) {
      insights.add("No recent releases - consider checking project status");
    }
    
    if (stabilityPattern.stablePercentage() > 80) {
      insights.add("Strong preference for stable releases - good for production");
    } else if (stabilityPattern.stablePercentage() < 50) {
      insights.add("Many pre-release versions - early-stage or experimental project");
    }
    
    long majorGaps = timeline.stream()
        .mapToLong(t -> t.releaseGap() == VersionTimelineAnalysis.TimelineEntry.ReleaseGap.MAJOR_GAP ? 1 : 0)
        .sum();
    if (majorGaps > 0) {
      insights.add("Found " + majorGaps + " significant gaps in release schedule");
    }
    
    return insights;
  }

  private Map<String, Object> analyzeSimpleDependencyHealth(String dependency, Integer maxAgeInDays) {
    try {
      MavenCoordinate coordinate = MavenCoordinateParser.parse(dependency);
      List<MavenSearchResponse.MavenArtifact> versions = 
          mavenCentralService.getRecentVersionsWithTimestamps(coordinate, 10);

      if (versions.isEmpty()) {
        Map<String, Object> result = new HashMap<>();
        result.put("dependency", dependency);
        result.put("status", "not_found");
        result.put("error", "Dependency not found in Maven Central");
        return result;
      }

      MavenSearchResponse.MavenArtifact latestVersion = versions.get(0);
      DependencyAgeAnalysis ageAnalysis = DependencyAgeAnalysis.fromTimestamp(
          coordinate.toCoordinateString(),
          latestVersion.version(),
          latestVersion.timestamp());

      // Simple maintenance assessment based on recent versions
      LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
      LocalDateTime sixMonthsAgo = now.minusMonths(6);
      
      long recentVersions = versions.stream()
          .filter(v -> LocalDateTime.ofInstant(Instant.ofEpochMilli(v.timestamp()), ZoneOffset.UTC)
              .isAfter(sixMonthsAgo))
          .count();

      String maintenanceStatus;
      if (recentVersions >= 3) {
        maintenanceStatus = "active";
      } else if (recentVersions >= 1) {
        maintenanceStatus = "moderate";
      } else {
        maintenanceStatus = "slow";
      }

      // Health score (0-100)
      int healthScore = calculateSimpleHealthScore(ageAnalysis, maintenanceStatus, maxAgeInDays);

      Map<String, Object> result = new HashMap<>();
      result.put("dependency", dependency);
      result.put("status", "success");
      result.put("latest_version", latestVersion.version());
      result.put("age_classification", ageAnalysis.ageClassification().getName());
      result.put("days_since_release", ageAnalysis.daysSinceLastRelease());
      result.put("maintenance_status", maintenanceStatus);
      result.put("health_score", healthScore);
      result.put("recent_versions_6m", recentVersions);
      result.put("recommendation", ageAnalysis.recommendation());
      
      if (maxAgeInDays != null && ageAnalysis.daysSinceLastRelease() > maxAgeInDays) {
        result.put("exceeds_age_threshold", true);
      }

      return result;
    } catch (Exception e) {
      Map<String, Object> result = new HashMap<>();
      result.put("dependency", dependency);
      result.put("status", "error");
      result.put("error", e.getMessage());
      return result;
    }
  }

  private int calculateSimpleHealthScore(
      DependencyAgeAnalysis ageAnalysis, String maintenanceStatus, Integer maxAgeInDays) {
    
    int score = 100;

    // Age penalty
    switch (ageAnalysis.ageClassification()) {
      case FRESH -> score -= 0;  // No penalty
      case CURRENT -> score -= 10;
      case AGING -> score -= 30;
      case STALE -> score -= 60;
    }

    // Maintenance penalty
    switch (maintenanceStatus) {
      case "active" -> score -= 0;  // No penalty
      case "moderate" -> score -= 15;
      case "slow" -> score -= 40;
    }

    // Custom age threshold penalty
    if (maxAgeInDays != null && ageAnalysis.daysSinceLastRelease() > maxAgeInDays) {
      score -= 20;
    }

    return Math.max(0, score);
  }

  private Map<String, Object> buildSimpleHealthSummary(
      List<Map<String, Object>> dependencyAnalyses, Integer maxAgeInDays) {
    
    Map<String, Object> summary = new HashMap<>();
    
    int totalDependencies = dependencyAnalyses.size();
    int successfulAnalyses = (int) dependencyAnalyses.stream()
        .mapToLong(dep -> "success".equals(dep.get("status")) ? 1 : 0)
        .sum();
    
    if (successfulAnalyses == 0) {
      summary.put("overall_health", "unknown");
      summary.put("message", "Unable to analyze any dependencies");
      summary.put("dependencies", dependencyAnalyses);
      return summary;
    }

    // Calculate averages and counts
    List<Map<String, Object>> successfulDeps = dependencyAnalyses.stream()
        .filter(dep -> "success".equals(dep.get("status")))
        .toList();

    double averageHealthScore = successfulDeps.stream()
        .mapToInt(dep -> (Integer) dep.get("health_score"))
        .average()
        .orElse(0);

    long freshCount = successfulDeps.stream()
        .mapToLong(dep -> "fresh".equals(dep.get("age_classification")) ? 1 : 0)
        .sum();
    long currentCount = successfulDeps.stream()
        .mapToLong(dep -> "current".equals(dep.get("age_classification")) ? 1 : 0)
        .sum();
    long agingCount = successfulDeps.stream()
        .mapToLong(dep -> "aging".equals(dep.get("age_classification")) ? 1 : 0)
        .sum();
    long staleCount = successfulDeps.stream()
        .mapToLong(dep -> "stale".equals(dep.get("age_classification")) ? 1 : 0)
        .sum();

    long activeMaintenanceCount = successfulDeps.stream()
        .mapToLong(dep -> "active".equals(dep.get("maintenance_status")) ? 1 : 0)
        .sum();

    // Overall health assessment
    String overallHealth;
    if (averageHealthScore >= 80) {
      overallHealth = "excellent";
    } else if (averageHealthScore >= 65) {
      overallHealth = "good";
    } else if (averageHealthScore >= 50) {
      overallHealth = "fair";
    } else {
      overallHealth = "poor";
    }

    // Build summary
    summary.put("overall_health", overallHealth);
    summary.put("average_health_score", Math.round(averageHealthScore));
    summary.put("total_dependencies", totalDependencies);
    summary.put("analyzed_successfully", successfulAnalyses);
    
    Map<String, Object> ageCounts = new HashMap<>();
    ageCounts.put("fresh", freshCount);
    ageCounts.put("current", currentCount);
    ageCounts.put("aging", agingCount);
    ageCounts.put("stale", staleCount);
    summary.put("age_distribution", ageCounts);
    
    summary.put("actively_maintained", activeMaintenanceCount);
    
    if (maxAgeInDays != null) {
      long exceedsThreshold = successfulDeps.stream()
          .mapToLong(dep -> dep.containsKey("exceeds_age_threshold") ? 1 : 0)
          .sum();
      summary.put("exceeds_age_threshold", exceedsThreshold);
    }

    // Key recommendations
    List<String> recommendations = new java.util.ArrayList<>();
    if (staleCount > 0) {
      recommendations.add("Review " + staleCount + " stale dependencies for alternatives");
    }
    if (agingCount > successfulAnalyses / 2) {
      recommendations.add("Consider updating aging dependencies");
    }
    if (activeMaintenanceCount < successfulAnalyses / 2) {
      recommendations.add("Monitor maintenance activity for slower-updated dependencies");
    }
    summary.put("key_recommendations", recommendations);
    summary.put("dependencies", dependencyAnalyses);

    return summary;
  }

}
