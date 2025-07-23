package com.arvindand.mcp.maven.service;

import com.arvindand.mcp.maven.model.BulkCheckResult;
import com.arvindand.mcp.maven.model.DependencyExistsResponse;
import com.arvindand.mcp.maven.model.DetailedVersionInfo;
import com.arvindand.mcp.maven.model.MavenCoordinate;
import com.arvindand.mcp.maven.model.VersionComparisonResponse;
import com.arvindand.mcp.maven.model.VersionInfo;
import com.arvindand.mcp.maven.model.VersionInfo.VersionType;
import com.arvindand.mcp.maven.util.MavenCoordinateParser;
import com.arvindand.mcp.maven.util.VersionComparator;
import java.time.Instant;
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

}
