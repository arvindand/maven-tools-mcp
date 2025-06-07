package com.arvindand.mcp.maven.service;

import com.arvindand.mcp.maven.model.BulkCheckResult;
import com.arvindand.mcp.maven.model.DependencyExistsResponse;
import com.arvindand.mcp.maven.model.DetailedVersionInfo;
import com.arvindand.mcp.maven.model.MavenCoordinate;
import com.arvindand.mcp.maven.model.VersionComparisonResponse;
import com.arvindand.mcp.maven.model.VersionInfo;
import com.arvindand.mcp.maven.util.MavenCoordinateParser;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

/**
 * Service providing Maven dependency tools for MCP. Exposes Maven dependency lookup functionality
 * as MCP tools using @Tool annotations.
 *
 * @author Arvind Menon
 * @since 0.1.0
 */
@Service
public class MavenDependencyTools {
  private static final Logger logger = LoggerFactory.getLogger(MavenDependencyTools.class);

  private final MavenCentralService mavenCentralService;
  private final VersionAnalysisService versionAnalysisService;
  private final JsonResponseService jsonResponseService;

  // Constants
  private static final String INVALID_COORDINATE_ERROR = "Invalid Maven coordinate format: ";
  private static final String VERSION_REQUIRED_ERROR =
      "Version must be provided either in dependency string or version parameter";
  // Version type constants
  private static final String STABLE_TYPE = "stable";
  private static final String RC_TYPE = "rc";
  private static final String BETA_TYPE = "beta";
  private static final String ALPHA_TYPE = "alpha";
  private static final String MILESTONE_TYPE = "milestone";

  private static final String STATUS_SUCCESS = "success";

  public MavenDependencyTools(
      MavenCentralService mavenCentralService,
      VersionAnalysisService versionAnalysisService,
      JsonResponseService jsonResponseService) {
    this.mavenCentralService = mavenCentralService;
    this.versionAnalysisService = versionAnalysisService;
    this.jsonResponseService = jsonResponseService;
  }

  @SuppressWarnings("java:S100")
  @Tool(
      description =
          """
          Get the latest version of a Maven dependency from Maven Central for each version type \
          (stable, rc, beta, alpha, milestone)\
          """)
  public String maven_get_latest(String dependency) {
    logger.debug("Getting latest versions by type for dependency: {}", dependency);

    return executeWithErrorHandling(
        () -> {
          MavenCoordinate coordinate = MavenCoordinateParser.parse(dependency);
          List<String> allVersions = mavenCentralService.getAllVersions(coordinate);

          if (allVersions.isEmpty()) {
            return createNotFoundResponse(coordinate);
          }

          VersionsByType versionsByType = categorizeVersionsByType(allVersions);
          Map<String, Object> result =
              buildVersionResponse(coordinate, versionsByType, allVersions.size());

          return jsonResponseService.toJson(result);
        },
        "Error fetching latest versions: ");
  }

  @SuppressWarnings("java:S100")
  @Tool(
      description =
          """
          Check if a specific version of a Maven dependency exists in Maven Central with version \
          type information\
          """)
  public String maven_check_exists(String dependency, String version) {
    logger.debug("Checking version existence for dependency: {}, version: {}", dependency, version);

    return executeWithErrorHandling(
        () -> {
          MavenCoordinate coordinate = MavenCoordinateParser.parse(dependency);
          String versionToCheck = coordinate.version() != null ? coordinate.version() : version;

          if (versionToCheck == null || versionToCheck.trim().isEmpty()) {
            logger.error(VERSION_REQUIRED_ERROR);
            return jsonResponseService.createErrorResponse(VERSION_REQUIRED_ERROR);
          }

          boolean exists = mavenCentralService.checkVersionExists(coordinate, versionToCheck);
          String versionType = versionAnalysisService.getVersionType(versionToCheck);
          DependencyExistsResponse result =
              new DependencyExistsResponse(exists, versionToCheck, versionType);

          return jsonResponseService.toJson(result);
        },
        "Error checking version existence: ");
  }

  @SuppressWarnings("java:S100")
  @Tool(
      description =
          """
          Get the latest stable version of a Maven dependency from Maven Central \
          (excludes pre-release versions)\
          """)
  public String maven_get_stable(String dependency) {
    logger.debug("Getting latest stable version for dependency: {}", dependency);

    return executeWithErrorHandling(
        () -> {
          MavenCoordinate coordinate = MavenCoordinateParser.parse(dependency);
          List<String> allVersions = mavenCentralService.getAllVersions(coordinate);

          if (allVersions.isEmpty()) {
            return createNotFoundResponse(coordinate);
          }

          List<String> stableVersions =
              allVersions.stream().filter(versionAnalysisService::isStableVersion).toList();

          String latestStable = stableVersions.isEmpty() ? null : stableVersions.get(0);

          if (latestStable == null) {
            String message =
                String.format(
                    "No stable version found for %s:%s (found %d pre-release versions)",
                    coordinate.groupId(), coordinate.artifactId(), allVersions.size());
            logger.warn(message);
            return jsonResponseService.createNotFoundResponse(message);
          }

          String versionType = versionAnalysisService.getVersionType(latestStable);
          DetailedVersionInfo result =
              new DetailedVersionInfo(
                  latestStable, versionType, allVersions.size(), stableVersions.size());

          return jsonResponseService.toJson(result);
        },
        "Error fetching latest stable version: ");
  }

  @SuppressWarnings("java:S100")
  @Tool(
      description =
          """
          Check latest versions for multiple Maven dependencies at once - optimizes bulk \
          dependency analysis with comprehensive version information\
          """)
  public String maven_bulk_check_latest(String dependencies) {
    logger.debug("Bulk checking latest versions for dependencies: {}", dependencies);

    return executeWithErrorHandling(
        () -> {
          List<String> depList = parseDependencyList(dependencies);
          long startTime = System.currentTimeMillis();

          List<BulkCheckResult> results =
              depList.stream().distinct().parallel().map(this::processLatestVersionCheck).toList();

          if (logger.isDebugEnabled()) {
            long duration = System.currentTimeMillis() - startTime;
            logger.debug(
                "Bulk check completed for {} dependencies in {}ms", depList.size(), duration);
          }

          return jsonResponseService.toJson(results);
        },
        "Error in bulk dependency check: ");
  }

  @SuppressWarnings("java:S100")
  @Tool(
      description =
          """
          Check latest stable versions for multiple Maven dependencies at once - ideal for \
          production dependency analysis\
          """)
  public String maven_bulk_check_stable(String dependencies) {
    logger.debug("Bulk checking stable versions for dependencies: {}", dependencies);

    return executeWithErrorHandling(
        () -> {
          List<String> depList = parseDependencyList(dependencies);
          long startTime = System.currentTimeMillis();

          List<BulkCheckResult> results =
              depList.stream().distinct().parallel().map(this::processStableVersionCheck).toList();

          if (logger.isDebugEnabled()) {
            long duration = System.currentTimeMillis() - startTime;
            logger.debug(
                "Bulk stable check completed for {} dependencies in {}ms",
                depList.size(),
                duration);
          }

          return jsonResponseService.toJson(results);
        },
        "Error in bulk stable dependency check: ");
  }

  @SuppressWarnings("java:S100")
  @Tool(
      description =
          """
          Compare current dependencies with latest versions and provide update recommendations \
          with risk analysis\
          """)
  public String maven_compare_versions(String currentDependencies) {
    logger.debug("Comparing dependency versions: {}", currentDependencies);

    return executeWithErrorHandling(
        () -> {
          List<String> depList = parseDependencyList(currentDependencies);
          long startTime = System.currentTimeMillis();

          List<VersionComparisonResponse.DependencyComparisonResult> comparisonResults =
              depList.stream().distinct().parallel().map(this::compareDependencyVersion).toList();

          VersionComparisonResponse.UpdateSummary summary =
              calculateComparisonSummary(comparisonResults);
          VersionComparisonResponse response =
              new VersionComparisonResponse(Instant.now(), comparisonResults, summary);

          if (logger.isDebugEnabled()) {
            long duration = System.currentTimeMillis() - startTime;
            logger.debug(
                "Version comparison completed: {} major, {} minor, {} patch updates available in"
                    + " {}ms",
                summary.majorUpdates(),
                summary.minorUpdates(),
                summary.patchUpdates(),
                duration);
          }

          return jsonResponseService.toJson(response);
        },
        "Error comparing versions: ");
  }

  private String executeWithErrorHandling(
      java.util.function.Supplier<String> operation, String errorMessagePrefix) {
    try {
      return operation.get();
    } catch (IllegalArgumentException e) {
      String errorMessage = INVALID_COORDINATE_ERROR + e.getMessage();
      logger.error(errorMessage);
      return jsonResponseService.createErrorResponse(errorMessage);
    } catch (Exception e) {
      String errorMessage = errorMessagePrefix + e.getMessage();
      logger.error(errorMessage, e);
      return jsonResponseService.createErrorResponse(errorMessage);
    }
  }

  private String createNotFoundResponse(MavenCoordinate coordinate) {
    String message =
        String.format(
            "No Maven dependency found for %s:%s%s",
            coordinate.groupId(),
            coordinate.artifactId(),
            coordinate.packaging() != null ? ":" + coordinate.packaging() : "");
    logger.warn(message);
    return jsonResponseService.createNotFoundResponse(message);
  }

  private record VersionsByType(
      String stable, String rc, String beta, String alpha, String milestone) {}

  private VersionsByType categorizeVersionsByType(List<String> allVersions) {
    Map<String, String> firstOfType = HashMap.newHashMap(5);

    for (String version : allVersions) {
      String type = versionAnalysisService.getVersionType(version);
      firstOfType.putIfAbsent(type, version);

      // Early exit once we have all 5 types (versions are already sorted, so these are the latest)
      if (firstOfType.size() == 5) break;
    }

    return new VersionsByType(
        firstOfType.get(STABLE_TYPE),
        firstOfType.get(RC_TYPE),
        firstOfType.get(BETA_TYPE),
        firstOfType.get(ALPHA_TYPE),
        firstOfType.get(MILESTONE_TYPE));
  }

  private Map<String, Object> buildVersionResponse(
      MavenCoordinate coordinate, VersionsByType versions, int totalVersions) {
    Map<String, Object> result = new java.util.LinkedHashMap<>();
    result.put("dependency", coordinate.toCoordinateString());

    addVersionIfPresent(result, "latest_stable", versions.stable(), STABLE_TYPE);
    addVersionIfPresent(result, "latest_rc", versions.rc(), RC_TYPE);
    addVersionIfPresent(result, "latest_beta", versions.beta(), BETA_TYPE);
    addVersionIfPresent(result, "latest_alpha", versions.alpha(), ALPHA_TYPE);
    addVersionIfPresent(result, "latest_milestone", versions.milestone(), MILESTONE_TYPE);

    result.put("total_versions", totalVersions);
    return result;
  }

  private void addVersionIfPresent(
      Map<String, Object> result, String key, String version, String type) {
    if (version != null) {
      result.put(key, new VersionInfo(version, type));
    }
  }

  private List<String> parseDependencyList(String dependencies) {
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

  private BulkCheckResult processLatestVersionCheck(String dep) {
    try {
      MavenCoordinate coordinate = MavenCoordinateParser.parse(dep);
      List<String> allVersions = mavenCentralService.getAllVersions(coordinate);

      if (allVersions.isEmpty()) {
        return BulkCheckResult.notFound(coordinate.toCoordinateString());
      }

      String latestVersion = allVersions.get(0);
      String versionType = versionAnalysisService.getVersionType(latestVersion);
      int stableVersionCount =
          (int) allVersions.stream().filter(versionAnalysisService::isStableVersion).count();

      return BulkCheckResult.foundWithCounts(
          coordinate.toCoordinateString(),
          latestVersion,
          versionType,
          allVersions.size(),
          stableVersionCount);
    } catch (Exception e) {
      return BulkCheckResult.error(dep, e.getMessage());
    }
  }

  private BulkCheckResult processStableVersionCheck(String dep) {
    try {
      MavenCoordinate coordinate = MavenCoordinateParser.parse(dep);
      List<String> allVersions = mavenCentralService.getAllVersions(coordinate);

      if (allVersions.isEmpty()) {
        return BulkCheckResult.notFound(coordinate.toCoordinateString());
      }

      List<String> stableVersions =
          allVersions.stream().filter(versionAnalysisService::isStableVersion).toList();

      String latestStable = stableVersions.isEmpty() ? null : stableVersions.get(0);

      return latestStable != null
          ? BulkCheckResult.foundStable(
              coordinate.toCoordinateString(),
              latestStable,
              STABLE_TYPE,
              allVersions.size(),
              stableVersions.size())
          : BulkCheckResult.noStableVersion(coordinate.toCoordinateString(), allVersions.size());
    } catch (Exception e) {
      return BulkCheckResult.error(dep, e.getMessage());
    }
  }

  private VersionComparisonResponse.DependencyComparisonResult compareDependencyVersion(
      String dep) {
    try {
      MavenCoordinate coordinate = MavenCoordinateParser.parse(dep);
      String currentVersion = coordinate.version();
      String latestVersion = mavenCentralService.getLatestVersion(coordinate);

      if (latestVersion == null) {
        return VersionComparisonResponse.DependencyComparisonResult.notFound(
            coordinate.toCoordinateString(), currentVersion);
      }

      if (currentVersion == null) {
        return VersionComparisonResponse.DependencyComparisonResult.noCurrentVersion(
            coordinate.toCoordinateString());
      }

      String latestType = versionAnalysisService.getVersionType(latestVersion);
      String updateType = versionAnalysisService.determineUpdateType(currentVersion, latestVersion);
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

  private VersionComparisonResponse.UpdateSummary calculateComparisonSummary(
      List<VersionComparisonResponse.DependencyComparisonResult> results) {

    Map<String, Long> counts =
        results.stream()
            .filter(result -> STATUS_SUCCESS.equals(result.status()))
            .collect(
                java.util.stream.Collectors.groupingBy(
                    VersionComparisonResponse.DependencyComparisonResult::updateType,
                    java.util.stream.Collectors.counting()));

    return new VersionComparisonResponse.UpdateSummary(
        counts.getOrDefault("major", 0L).intValue(),
        counts.getOrDefault("minor", 0L).intValue(),
        counts.getOrDefault("patch", 0L).intValue(),
        counts.getOrDefault("none", 0L).intValue());
  }
}
