package com.arvindand.mcp.maven.service;

import com.arvindand.mcp.maven.model.BulkCheckResult;
import com.arvindand.mcp.maven.model.DependencyExistsResponse;
import com.arvindand.mcp.maven.model.DetailedVersionInfo;
import com.arvindand.mcp.maven.model.MavenCoordinate;
import com.arvindand.mcp.maven.model.PomAnalysisResponse;
import com.arvindand.mcp.maven.model.VersionComparisonResponse;
import com.arvindand.mcp.maven.model.VersionInfo;
import com.arvindand.mcp.maven.util.MavenCoordinateParser;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
  private final PomParsingService pomParsingService;
  private final JsonResponseService jsonResponseService;

  // Error message constants
  private static final String INVALID_COORDINATE_ERROR = "Invalid Maven coordinate format: ";

  /** Constructs a new MavenDependencyTools with the specified services. */
  public MavenDependencyTools(
      MavenCentralService mavenCentralService,
      VersionAnalysisService versionAnalysisService,
      PomParsingService pomParsingService,
      JsonResponseService jsonResponseService) {
    this.mavenCentralService = mavenCentralService;
    this.versionAnalysisService = versionAnalysisService;
    this.pomParsingService = pomParsingService;
    this.jsonResponseService = jsonResponseService;
  }

  /**
   * Gets the latest version of a Maven dependency for each version type (stable, rc, beta, alpha,
   * milestone, snapshot).
   */
  @SuppressWarnings("java:S100") // Method name follows MCP tool naming convention
  @Tool(
      description =
          "Get the latest version of a Maven dependency from Maven Central for each version type"
              + " (stable, rc, beta, alpha, milestone, snapshot)")
  public String maven_get_latest(String dependency) {
    logger.debug("Getting latest versions by type for dependency: {}", dependency);

    try {
      MavenCoordinate coordinate = MavenCoordinateParser.parse(dependency);
      List<String> allVersions = mavenCentralService.getAllVersions(coordinate);

      if (allVersions.isEmpty()) {
        String message =
            String.format(
                "No Maven dependency found for %s:%s%s",
                coordinate.groupId(),
                coordinate.artifactId(),
                coordinate.packaging() != null ? ":" + coordinate.packaging() : "");
        logger.warn(message);
        return jsonResponseService.createNotFoundResponse(message);
      }

      // Find the latest for each type
      String latestStable = null,
          latestRc = null,
          latestBeta = null,
          latestAlpha = null,
          latestMilestone = null,
          latestSnapshot = null;
      for (String v : allVersions) {
        String type = versionAnalysisService.getVersionType(v);
        if ("stable".equals(type) && latestStable == null) latestStable = v;
        else if ("rc".equals(type) && latestRc == null) latestRc = v;
        else if ("beta".equals(type) && latestBeta == null) latestBeta = v;
        else if ("alpha".equals(type) && latestAlpha == null) latestAlpha = v;
        else if ("milestone".equals(type) && latestMilestone == null) latestMilestone = v;
        else if ("snapshot".equals(type) && latestSnapshot == null) latestSnapshot = v;
      }

      var result = new java.util.LinkedHashMap<String, Object>();
      result.put("dependency", coordinate.toCoordinateString());
      if (latestStable != null)
        result.put("latest_stable", new VersionInfo(latestStable, "stable"));
      if (latestRc != null) result.put("latest_rc", new VersionInfo(latestRc, "rc"));
      if (latestBeta != null) result.put("latest_beta", new VersionInfo(latestBeta, "beta"));
      if (latestAlpha != null) result.put("latest_alpha", new VersionInfo(latestAlpha, "alpha"));
      if (latestMilestone != null)
        result.put("latest_milestone", new VersionInfo(latestMilestone, "milestone"));
      if (latestSnapshot != null)
        result.put("latest_snapshot", new VersionInfo(latestSnapshot, "snapshot"));
      result.put("total_versions", allVersions.size());

      logger.info("Latest versions by type for {}: {}", coordinate.toCoordinateString(), result);
      return jsonResponseService.toJson(result);

    } catch (IllegalArgumentException e) {
      String errorMessage = INVALID_COORDINATE_ERROR + e.getMessage();
      logger.error(errorMessage);
      return jsonResponseService.createErrorResponse(errorMessage);
    } catch (Exception e) {
      String errorMessage = "Error fetching latest versions: " + e.getMessage();
      logger.error(errorMessage, e);
      return jsonResponseService.createErrorResponse(errorMessage);
    }
  }

  /** Checks if a specific version of a Maven dependency exists with version type information. */
  @SuppressWarnings("java:S100") // Method name follows MCP tool naming convention
  @Tool(
      description =
          "Check if a specific version of a Maven dependency exists in Maven Central with version"
              + " type information")
  public String maven_check_exists(String dependency, String version) {
    logger.debug("Checking version existence for dependency: {}, version: {}", dependency, version);

    try {
      MavenCoordinate coordinate = MavenCoordinateParser.parse(dependency);
      String versionToCheck = coordinate.version() != null ? coordinate.version() : version;

      if (versionToCheck == null || versionToCheck.trim().isEmpty()) {
        String errorMessage =
            "Version must be provided either in dependency string or version parameter";
        logger.error(errorMessage);
        return jsonResponseService.createErrorResponse(errorMessage);
      }

      boolean exists = mavenCentralService.checkVersionExists(coordinate, versionToCheck);
      String versionType = versionAnalysisService.getVersionType(versionToCheck);
      DependencyExistsResponse result =
          new DependencyExistsResponse(exists, versionToCheck, versionType);
      if (logger.isInfoEnabled()) {
        logger.info(
            "Version {} for {} exists: {} (type: {})",
            versionToCheck,
            coordinate.toCoordinateString(),
            exists,
            versionType);
      }

      return jsonResponseService.toJson(result);

    } catch (IllegalArgumentException e) {
      String errorMessage = INVALID_COORDINATE_ERROR + e.getMessage();
      logger.error(errorMessage);
      return jsonResponseService.createErrorResponse(errorMessage);
    } catch (Exception e) {
      String errorMessage = "Error checking version existence: " + e.getMessage();
      logger.error(errorMessage, e);
      return jsonResponseService.createErrorResponse(errorMessage);
    }
  }

  /** Gets the latest stable version of a Maven dependency (excludes pre-release versions). */
  @SuppressWarnings("java:S100") // Method name follows MCP tool naming convention
  @Tool(
      description =
          "Get the latest stable version of a Maven dependency from Maven Central (excludes"
              + " pre-release versions)")
  public String maven_get_stable(String dependency) {
    logger.debug("Getting latest stable version for dependency: {}", dependency);

    try {
      MavenCoordinate coordinate = MavenCoordinateParser.parse(dependency);
      List<String> allVersions = mavenCentralService.getAllVersions(coordinate);

      if (allVersions.isEmpty()) {
        String message =
            String.format(
                "No Maven dependency found for %s:%s%s",
                coordinate.groupId(),
                coordinate.artifactId(),
                coordinate.packaging() != null ? ":" + coordinate.packaging() : "");
        logger.warn(message);
        return jsonResponseService.createNotFoundResponse(message);
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
      if (logger.isInfoEnabled()) {
        logger.info(
            "Latest stable version for {}: {} (type: {})",
            coordinate.toCoordinateString(),
            latestStable,
            versionType);
      }

      return jsonResponseService.toJson(result);

    } catch (IllegalArgumentException e) {
      String errorMessage = INVALID_COORDINATE_ERROR + e.getMessage();
      logger.error(errorMessage);
      return jsonResponseService.createErrorResponse(errorMessage);
    } catch (Exception e) {
      String errorMessage = "Error fetching latest stable version: " + e.getMessage();
      logger.error(errorMessage, e);
      return jsonResponseService.createErrorResponse(errorMessage);
    }
  }

  /** Checks latest versions for multiple dependencies at once. */
  @SuppressWarnings("java:S100") // Method name follows MCP tool naming convention
  @Tool(
      description =
          "Check latest versions for multiple Maven dependencies at once - optimizes bulk"
              + " dependency analysis")
  public String maven_bulk_check_latest(String dependencies) {
    logger.debug("Bulk checking latest versions for dependencies: {}", dependencies);

    try {
      String[] depArray = dependencies.split(",");
      List<BulkCheckResult> results = new ArrayList<>();

      for (String dep : depArray) {
        dep = dep.trim();
        if (dep.isEmpty()) continue;

        results.add(processLatestVersionCheck(dep));
      }

      logger.info("Bulk check completed for {} dependencies", depArray.length);
      return jsonResponseService.toJson(results);

    } catch (Exception e) {
      String errorMessage = "Error in bulk dependency check: " + e.getMessage();
      logger.error(errorMessage, e);
      return jsonResponseService.createErrorResponse(errorMessage);
    }
  }

  private BulkCheckResult processLatestVersionCheck(String dep) {
    try {
      MavenCoordinate coordinate = MavenCoordinateParser.parse(dep);
      String latestVersion = mavenCentralService.getLatestVersion(coordinate);

      if (latestVersion != null) {
        String versionType = versionAnalysisService.getVersionType(latestVersion);
        return BulkCheckResult.found(coordinate.toCoordinateString(), latestVersion, versionType);
      } else {
        return BulkCheckResult.notFound(coordinate.toCoordinateString());
      }
    } catch (Exception e) {
      return BulkCheckResult.error(dep, e.getMessage());
    }
  }

  /** Checks stable versions for multiple dependencies at once. */
  @SuppressWarnings("java:S100") // Method name follows MCP tool naming convention
  @Tool(
      description =
          "Check latest stable versions for multiple Maven dependencies at once - ideal for"
              + " production dependency analysis")
  public String maven_bulk_check_stable(String dependencies) {
    logger.debug("Bulk checking stable versions for dependencies: {}", dependencies);

    try {
      String[] depArray = dependencies.split(",");
      List<BulkCheckResult> results = new ArrayList<>();

      for (String dep : depArray) {
        dep = dep.trim();
        if (dep.isEmpty()) continue;

        results.add(processStableVersionCheck(dep));
      }

      logger.info("Bulk stable check completed for {} dependencies", depArray.length);
      return jsonResponseService.toJson(results);

    } catch (Exception e) {
      String errorMessage = "Error in bulk stable dependency check: " + e.getMessage();
      logger.error(errorMessage, e);
      return jsonResponseService.createErrorResponse(errorMessage);
    }
  }

  private BulkCheckResult processStableVersionCheck(String dep) {
    try {
      MavenCoordinate coordinate = MavenCoordinateParser.parse(dep);
      List<String> allVersions = mavenCentralService.getAllVersions(coordinate);

      if (!allVersions.isEmpty()) {
        List<String> stableVersions =
            allVersions.stream().filter(versionAnalysisService::isStableVersion).toList();
        String latestStable = stableVersions.isEmpty() ? null : stableVersions.get(0);

        if (latestStable != null) {
          return BulkCheckResult.foundStable(
              coordinate.toCoordinateString(),
              latestStable,
              "stable",
              allVersions.size(),
              stableVersions.size());
        } else {
          return BulkCheckResult.noStableVersion(
              coordinate.toCoordinateString(), allVersions.size());
        }
      } else {
        return BulkCheckResult.notFound(coordinate.toCoordinateString());
      }
    } catch (Exception e) {
      return BulkCheckResult.error(dep, e.getMessage());
    }
  }

  /** Analyzes a POM file content and provides comprehensive dependency update recommendations. */
  @SuppressWarnings("java:S100") // Method name follows MCP tool naming convention
  @Tool(
      description =
          "Analyze a POM file and provide comprehensive dependency update recommendations with"
              + " version analysis")
  public String maven_analyze_pom(String pomContent) {
    logger.debug("Analyzing POM file content (length: {} characters)", pomContent.length());

    try {
      List<String> dependencies = pomParsingService.extractDependenciesFromPom(pomContent);
      if (dependencies.isEmpty()) {
        return jsonResponseService.createNotFoundResponse("No dependencies found in POM content");
      }

      List<PomAnalysisResponse.DependencyAnalysisResult> analysisResults = new ArrayList<>();
      int outdatedCount = 0;
      int upToDateCount = 0;
      int errorCount = 0;

      for (String dep : dependencies) {
        PomAnalysisResponse.DependencyAnalysisResult result = analyzeDependency(dep);
        analysisResults.add(result);
        switch (result.status()) {
          case "found" -> {
            if (result.isOutdated()) {
              outdatedCount++;
            } else {
              upToDateCount++;
            }
          }
          case "error", "not_found" -> errorCount++;
          default -> errorCount++; // Handle unexpected status values
        }
      }

      PomAnalysisResponse.AnalysisSummary summary =
          new PomAnalysisResponse.AnalysisSummary(
              dependencies.size(), outdatedCount, upToDateCount, errorCount);

      PomAnalysisResponse response =
          new PomAnalysisResponse(Instant.now(), dependencies.size(), analysisResults, summary);

      logger.info(
          "POM analysis completed: {} dependencies ({} outdated, {} up-to-date, {} errors)",
          dependencies.size(),
          outdatedCount,
          upToDateCount,
          errorCount);

      return jsonResponseService.toJson(response);

    } catch (Exception e) {
      String errorMessage = "Error analyzing POM: " + e.getMessage();
      logger.error(errorMessage, e);
      return jsonResponseService.createErrorResponse(errorMessage);
    }
  }

  private PomAnalysisResponse.DependencyAnalysisResult analyzeDependency(String dep) {
    try {
      MavenCoordinate coordinate = MavenCoordinateParser.parse(dep);
      String latestVersion = mavenCentralService.getLatestVersion(coordinate);
      String currentVersion = coordinate.version();

      if (latestVersion != null) {
        String latestType = versionAnalysisService.getVersionType(latestVersion);

        // Get latest stable version for comparison
        List<String> allVersions = mavenCentralService.getAllVersions(coordinate);
        List<String> stableVersions =
            allVersions.stream().filter(versionAnalysisService::isStableVersion).toList();
        String latestStable = stableVersions.isEmpty() ? null : stableVersions.get(0);

        boolean isOutdated = currentVersion != null && !currentVersion.equals(latestVersion);

        return PomAnalysisResponse.DependencyAnalysisResult.found(
            coordinate.toCoordinateString(),
            currentVersion,
            latestVersion,
            latestType,
            latestStable,
            isOutdated);
      } else {
        return PomAnalysisResponse.DependencyAnalysisResult.notFound(
            coordinate.toCoordinateString(), currentVersion);
      }
    } catch (Exception e) {
      return PomAnalysisResponse.DependencyAnalysisResult.error(dep, e.getMessage());
    }
  }

  /** Compares current project dependencies with their latest versions. */
  @SuppressWarnings("java:S100") // Method name follows MCP tool naming convention
  @Tool(
      description =
          "Compare current dependencies with latest versions and provide update recommendations"
              + " with risk analysis")
  public String maven_compare_versions(String currentDependencies) {
    logger.debug("Comparing dependency versions: {}", currentDependencies);

    try {
      String[] depArray = currentDependencies.split(",");
      List<VersionComparisonResponse.DependencyComparisonResult> comparisonResults =
          new ArrayList<>();

      int majorUpdates = 0;
      int minorUpdates = 0;
      int patchUpdates = 0;
      int noUpdates = 0;

      for (String dep : depArray) {
        dep = dep.trim();
        if (dep.isEmpty()) continue;

        VersionComparisonResponse.DependencyComparisonResult result = compareDependencyVersion(dep);
        comparisonResults.add(result);

        if ("success".equals(result.status())) {
          switch (result.updateType()) {
            case "major" -> majorUpdates++;
            case "minor" -> minorUpdates++;
            case "patch" -> patchUpdates++;
            default -> noUpdates++;
          }
        }
      }

      VersionComparisonResponse.UpdateSummary summary =
          new VersionComparisonResponse.UpdateSummary(
              majorUpdates, minorUpdates, patchUpdates, noUpdates);

      VersionComparisonResponse response =
          new VersionComparisonResponse(Instant.now(), comparisonResults, summary);

      logger.info(
          "Version comparison completed: {} major, {} minor, {} patch updates available",
          majorUpdates,
          minorUpdates,
          patchUpdates);

      return jsonResponseService.toJson(response);

    } catch (Exception e) {
      String errorMessage = "Error comparing versions: " + e.getMessage();
      logger.error(errorMessage, e);
      return jsonResponseService.createErrorResponse(errorMessage);
    }
  }

  private VersionComparisonResponse.DependencyComparisonResult compareDependencyVersion(
      String dep) {
    try {
      MavenCoordinate coordinate = MavenCoordinateParser.parse(dep);
      String currentVersion = coordinate.version();
      String latestVersion = mavenCentralService.getLatestVersion(coordinate);

      if (latestVersion != null && currentVersion != null) {
        String latestType = versionAnalysisService.getVersionType(latestVersion);
        String updateType =
            versionAnalysisService.determineUpdateType(currentVersion, latestVersion);
        boolean updateAvailable = !currentVersion.equals(latestVersion);

        return VersionComparisonResponse.DependencyComparisonResult.success(
            coordinate.toCoordinateString(),
            currentVersion,
            latestVersion,
            latestType,
            updateType,
            updateAvailable);
      } else if (latestVersion == null) {
        return VersionComparisonResponse.DependencyComparisonResult.notFound(
            coordinate.toCoordinateString(), currentVersion);
      } else {
        return VersionComparisonResponse.DependencyComparisonResult.noCurrentVersion(
            coordinate.toCoordinateString());
      }
    } catch (Exception e) {
      return VersionComparisonResponse.DependencyComparisonResult.error(dep, e.getMessage());
    }
  }
}
