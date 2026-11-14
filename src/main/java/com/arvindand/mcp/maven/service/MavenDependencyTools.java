package com.arvindand.mcp.maven.service;

import com.arvindand.mcp.maven.MavenToolsConstants;
import com.arvindand.mcp.maven.config.Context7Properties;
import com.arvindand.mcp.maven.model.BulkCheckResult;
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
import com.arvindand.mcp.maven.pom.PluginDependencyDeclaration;
import com.arvindand.mcp.maven.pom.PropertyOverride;
import com.arvindand.mcp.maven.pom.Source;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
  private static final String MAVEN_CENTRAL_ERROR = "Maven Central error: ";
  private static final String INVALID_MAVEN_COORDINATE_FORMAT = "Invalid Maven coordinate format: ";
  private static final String SUCCESS_STATUS = "success";
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
      return ToolResponse.Success.of(result);
    } catch (IllegalArgumentException e) {
      return ToolResponse.Error.of(
          McpError.invalidInput(INVALID_MAVEN_COORDINATE_FORMAT + e.getMessage(), Map.of()));
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
   * Mill).
   *
   * @param dependency the dependency coordinate (groupId:artifactId)
   * @param stabilityFilter controls version filtering: ALL, STABLE_ONLY, or PREFER_STABLE (default)
   * @return JSON response with latest versions by type
   */
  @SuppressWarnings("java:S100") // MCP tool method naming
  @Tool(
      description =
          "Single dependency. Returns newest versions by type (stable/rc/beta/alpha/milestone). Set"
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
          return buildVersionsByType(coordinate, allVersions, filter);
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
          "Bulk lookup (NO versions in input). Returns latest versions by type for each"
              + " dependency. Use when you have a list of dependencies without versions and need to"
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

          return processBatchWithBackpressure(
              depList.stream().distinct().toList(), dep -> processVersionCheck(dep, filter));
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
              + " suggests upgrades (major/minor/patch). Includes CVE vulnerability scanning."
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

          List<VersionComparison.DependencyComparisonResult> results =
              processBatchWithBackpressure(
                  depList.stream().distinct().toList(),
                  dep -> compareDependencyVersion(dep, filter, securityAssessments));

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
            throw new MavenCentralException(
                "No versions found for " + coordinate.toCoordinateString());
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
              + " scanning and license compliance. Use this instead of check_multiple_dependencies"
              + " when you need security/license info or overall project health assessment.")
  public ToolResponse analyze_project_health(
      @ToolParam(
              description =
                  "Comma or newline separated list of Maven dependency coordinates in format"
                      + " 'groupId:artifactId' (NO versions). Example:"
                      + " 'org.springframework:spring-core,junit:junit'")
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
                  "Stability filter: ALL (any version), STABLE_ONLY (production-ready only), or"
                      + " PREFER_STABLE (prioritize stable). Default: PREFER_STABLE",
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

          final Map<String, SecurityAssessment> securityByDependency =
              scanSecurity ? scanLatestDependencySecurity(depList) : Map.of();
          final Map<String, List<LicenseInfo>> licensesByDependency =
              scanLicenses ? scanLatestDependencyLicenses(depList) : Map.of();

          // Analyze each dependency for age and patterns
          List<ProjectHealthAnalysis.DependencyHealthAnalysis> dependencyAnalyses =
              processBatchWithBackpressure(
                  depList.stream().distinct().toList(),
                  dep ->
                      analyzeDependencyHealthWithSecurityAndLicenses(
                          dep,
                          maxAgeInDays,
                          scanSecurity,
                          securityByDependency,
                          scanLicenses,
                          licensesByDependency));

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
    return com.arvindand.mcp.maven.util.BoundedBatch.map(
        items,
        processor,
        batchSemaphore,
        java.time.Duration.ofSeconds(MavenToolsConstants.DEFAULT_BATCH_TIMEOUT_SECONDS));
  }

  private ProjectHealthAnalysis.DependencyHealthAnalysis
      analyzeDependencyHealthWithSecurityAndLicenses(
          String dependency,
          Integer maxAgeInDays,
          boolean scanSecurity,
          Map<String, SecurityAssessment> securityByDependency,
          boolean scanLicenses,
          Map<String, List<LicenseInfo>> licensesByDependency) {
    try {
      MavenCoordinate coordinate = MavenCoordinateParser.parse(dependency);
      String gaKey = gaKey(coordinate);

      List<MavenArtifact> versions =
          mavenCentralService.getRecentVersionsWithAccurateTimestamps(coordinate, 10);
      if (versions.isEmpty()) {
        return ProjectHealthAnalysis.DependencyHealthAnalysis.notFound(dependency);
      }

      MavenArtifact latestVersion = versions.get(0);
      DependencyAgeAnalysis ageAnalysis =
          DependencyAgeAnalysis.fromTimestamp(
              coordinate.toCoordinateString(), latestVersion.version(), latestVersion.timestamp());

      String maintenanceStatus = determineMaintenanceStatus(versions);
      int freshnessScore = calculateFreshnessScore(ageAnalysis, maxAgeInDays);
      int maintenanceScore = calculateMaintenanceScore(maintenanceStatus);

      SecurityAssessment security =
          resolveSecurityAssessment(scanSecurity, gaKey, securityByDependency);
      List<LicenseInfo> licenses = resolveLicenses(scanLicenses, gaKey, licensesByDependency);

      int healthScore =
          (scanSecurity || scanLicenses)
              ? calculateHealthScoreWithSecurityAndLicense(
                  freshnessScore, maintenanceScore, security, licenses)
              : calculateSimpleHealthScore(ageAnalysis, maintenanceStatus, maxAgeInDays);

      if (scanSecurity || scanLicenses) {
        return ProjectHealthAnalysis.DependencyHealthAnalysis.successWithSecurityAndLicenses(
            dependency,
            latestVersion.version(),
            ageAnalysis.ageClassification().getName(),
            ageAnalysis.daysSinceLastRelease(),
            healthScore,
            maintenanceStatus,
            security,
            licenses,
            context7Properties.enabled());
      }

      return ProjectHealthAnalysis.DependencyHealthAnalysis.success(
          dependency,
          latestVersion.version(),
          ageAnalysis.ageClassification().getName(),
          ageAnalysis.daysSinceLastRelease(),
          healthScore,
          maintenanceStatus,
          context7Properties.enabled());
    } catch (RuntimeException e) {
      return ProjectHealthAnalysis.DependencyHealthAnalysis.error(dependency, e.getMessage());
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

  private static String gaKey(MavenCoordinate coordinate) {
    return coordinate.groupId() + ":" + coordinate.artifactId();
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

  private Map<String, SecurityAssessment> scanLatestDependencySecurity(List<String> dependencies) {
    // Resolve latest versions and scan those versions
    List<MavenCoordinate> coordinatesWithVersions = new ArrayList<>();

    for (String dep : dependencies) {
      try {
        MavenCoordinate coord = MavenCoordinateParser.parse(dep);
        String latestVersion = mavenCentralService.getLatestVersion(coord);
        if (latestVersion != null) {
          coordinatesWithVersions.add(
              MavenCoordinate.of(coord.groupId(), coord.artifactId(), latestVersion));
        }
      } catch (Exception e) {
        logger.debug("Could not resolve version for {}: {}", dep, e.getMessage());
      }
    }

    if (coordinatesWithVersions.isEmpty()) {
      return Map.of();
    }

    Map<String, SecurityAssessment> versionKeyed =
        vulnerabilityService.scanBulk(coordinatesWithVersions);
    Map<String, SecurityAssessment> byGa = new LinkedHashMap<>();

    for (MavenCoordinate c : coordinatesWithVersions) {
      String ga = gaKey(c);
      SecurityAssessment assessment = versionKeyed.get(c.toCoordinateString());
      if (assessment != null) {
        byGa.put(ga, assessment);
      }
    }

    return Map.copyOf(byGa);
  }

  private Map<String, List<LicenseInfo>> scanLatestDependencyLicenses(List<String> dependencies) {
    Map<String, List<LicenseInfo>> byGa = new LinkedHashMap<>();

    for (String dep : dependencies) {
      try {
        MavenCoordinate coord = MavenCoordinateParser.parse(dep);
        String ga = gaKey(coord);
        String latestVersion = mavenCentralService.getLatestVersion(coord);
        if (latestVersion == null) {
          byGa.put(ga, List.of());
          continue;
        }

        MavenCoordinate coordWithVersion =
            MavenCoordinate.of(coord.groupId(), coord.artifactId(), latestVersion);
        byGa.put(ga, mavenCentralService.getLicenses(coordWithVersion));
      } catch (Exception e) {
        logger.debug("Could not scan license for {}: {}", dep, e.getMessage());
      }
    }

    return Map.copyOf(byGa);
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

  private BulkCheckResult processVersionCheck(String dep, StabilityFilter filter) {
    return switch (filter) {
      case STABLE_ONLY -> processStableVersionCheck(dep);
      case ALL, PREFER_STABLE -> processComprehensiveVersionCheck(dep);
    };
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
      String dep,
      StabilityFilter stabilityFilter,
      Map<String, SecurityAssessment> securityAssessments) {
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
            coordinate.toCoordinateString());
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
            sameMajorStableFallback);
      }

      return VersionComparison.DependencyComparisonResult.success(
          coordinate.toCoordinateString(),
          currentVersion,
          latestVersion,
          latestType,
          updateType,
          updateAvailable,
          context7Properties.enabled(),
          sameMajorStableFallback);
    } catch (RuntimeException e) {
      return VersionComparison.DependencyComparisonResult.error(dep, e.getMessage());
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
    } catch (RuntimeException e) {
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
        counts.getOrDefault(MAJOR_UPDATE_TYPE, 0L).intValue(),
        counts.getOrDefault(MINOR_UPDATE_TYPE, 0L).intValue(),
        counts.getOrDefault(PATCH_UPDATE_TYPE, 0L).intValue(),
        counts.getOrDefault(NO_UPDATE_TYPE, 0L).intValue());
  }

  private String getLatestStableVersion(MavenCoordinate coordinate) throws MavenCentralException {
    return getLatestCompatibleVersion(coordinate, true);
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
      return new ProjectHealthAnalysis(
          "unknown",
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

    return new ProjectHealthAnalysis(
        overallHealth,
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
              + " resolved dependencies, directly editable root dependency-management and"
              + " build/plugin dependency declarations, propertyOverrides (root properties"
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
      logger.error(UNEXPECTED_ERROR, e);
      return ToolResponse.Error.of(UNEXPECTED_ERROR + ": " + e.getMessage());
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
   * @return JSON response wrapping {@link PomUpgradeRecommendation}
   */
  @SuppressWarnings("java:S100") // MCP tool method naming
  @Tool(
      description =
          "POM-aware upgrade recommender. Takes raw pom.xml content and returns (1)"
              + " deterministicActions — mechanical version/property edits a non-LLM agent can"
              + " apply (explicit_bump, bom_bump, managed_decl_bump, plugin_dep_bump); actions"
              + " for vulnerable versions carry a security object with targetStatus; (2)"
              + " needsAttention — vulnerable (known CVEs, first), major_available (alongside the"
              + " same-major action when one exists), property_override (a root property pinning a"
              + " parent-managed version, e.g. snakeyaml.version),"
              + " conflict and explicit_override entries needing review, each with the Maven"
              + " Central latest; plus securitySummary. Mode MINOR_PATCH (default) keeps majors in"
              + " needsAttention; ALL makes them deterministic. Use for 'recommend upgrades for my"
              + " pom.xml' or automated dependency-update workflows.")
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
          @Nullable Boolean includeSecurityScan) {
    try {
      EffectivePomResult resolved =
          (sideloadedPoms == null || sideloadedPoms.isEmpty())
              ? pomResolver.resolve(pomXml)
              : pomResolver.resolve(pomXml, sideloadedPoms);
      UpgradeMode effectiveMode = mode != null ? mode : UpgradeMode.MINOR_PATCH;
      boolean scan = includeSecurityScan == null || includeSecurityScan;
      return ToolResponse.Success.of(buildPomUpgradeRecommendation(resolved, effectiveMode, scan));
    } catch (IllegalArgumentException e) {
      return ToolResponse.Error.of("Invalid POM input: " + e.getMessage());
    } catch (Exception e) {
      logger.error(UNEXPECTED_ERROR, e);
      return ToolResponse.Error.of(UNEXPECTED_ERROR + ": " + e.getMessage());
    }
  }

  private PomUpgradeRecommendation buildPomUpgradeRecommendation(
      EffectivePomResult resolved, UpgradeMode mode, boolean includeSecurityScan) {
    List<UpgradeAction> actions = new ArrayList<>();
    List<NeedsAttention> attention = new ArrayList<>();

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
      classifyBomCandidate(bom, mode, actions, attention);
    }

    // Direct non-import dependency-management declarations are independent user-owned knobs. They
    // remain actionable even when the platform POM has no normal <dependencies> usage of its own.
    for (ManagedDeclaration declaration : resolved.rootManagedDeclarations()) {
      classifyManagedDeclarationCandidate(declaration, mode, actions, attention);
    }

    for (PluginDependencyDeclaration declaration : resolved.rootPluginDependencyDeclarations()) {
      classifyPluginDependencyCandidate(declaration, mode, actions, attention);
    }

    // Second pass: per-dep classification. MANAGED deps whose managedBy is user-controllable are
    // covered by the BOM pass above; transitively-managed deps are silently skipped (their
    // upgrade lives behind a user-controllable knob already classified).
    for (EffectiveDependency dep : resolved.dependencies()) {
      classifyDependencyCandidate(dep, mode, actions, attention);
    }

    // Property pins are never mechanical: a pin exists for a reason.
    for (PropertyOverride override : resolved.propertyOverrides()) {
      toPropertyOverrideAttention(override).ifPresent(attention::add);
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
      return new PomUpgradeRecommendation(actions, attention, warnings);
    }
    SecurityOutcome security = applySecurityScan(resolved, actions);
    if (security.summary().unknown() > 0) {
      warnings.add(
          "Security scan unavailable for " + security.summary().unknown() + " dependencies");
    }
    List<NeedsAttention> ordered = new ArrayList<>(security.vulnerable());
    ordered.addAll(attention);
    return new PomUpgradeRecommendation(security.actions(), ordered, warnings, security.summary());
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
      EffectivePomResult resolved, List<UpgradeAction> actions) {
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
      if (statusOf(current) != SecurityAssessment.Status.VULNERABLE) {
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
    Set<String> seen = new java.util.HashSet<>();
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
              safeGetLatestStable(coordinate)));
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
  private Optional<NeedsAttention> toPropertyOverrideAttention(PropertyOverride override) {
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
        safeGetLatestStable(
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
   * bom_bump} action or a {@link NeedsAttention.MajorAvailable major_available} entry. Network
   * failures and missing-version cases drop silently — those would already surface as warnings on
   * the upstream resolution.
   */
  private void classifyBomCandidate(
      MavenCoordinate bom,
      UpgradeMode mode,
      List<UpgradeAction> actions,
      List<NeedsAttention> attention) {
    try {
      if (bom.version() == null) {
        return;
      }
      classifyUpgrade(
          bom,
          bom.version(),
          getLatestStableVersion(bom),
          mode,
          Source.MANAGED.name(),
          bom.toCoordinateString(),
          (target, updateType) ->
              UpgradeAction.bomBump(
                  bom.groupId(), bom.artifactId(), bom.version(), target, updateType),
          actions,
          attention);
    } catch (MavenCentralException _) {
      // upstream resolver already warned about unreachable parents; nothing to add here.
    }
  }

  /** Classifies a direct root dependency-management declaration with a known edit target. */
  private void classifyManagedDeclarationCandidate(
      ManagedDeclaration declaration,
      UpgradeMode mode,
      List<UpgradeAction> actions,
      List<NeedsAttention> attention) {
    MavenCoordinate coordinate =
        MavenCoordinate.of(declaration.groupId(), declaration.artifactId(), declaration.version());
    classifyUpgrade(
        coordinate,
        declaration.version(),
        safeGetLatestStable(coordinate),
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
      PluginDependencyDeclaration declaration,
      UpgradeMode mode,
      List<UpgradeAction> actions,
      List<NeedsAttention> attention) {
    MavenCoordinate coordinate =
        MavenCoordinate.of(declaration.groupId(), declaration.artifactId(), declaration.version());
    classifyUpgrade(
        coordinate,
        declaration.version(),
        safeGetLatestStable(coordinate),
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

  /**
   * Classifies a single declared dependency: surfaces conflicts and explicit overrides into {@code
   * needsAttention} regardless of upgrade availability; for EXPLICIT deps emits a {@link
   * UpgradeAction explicit_bump} (plus a {@code major_available} entry when a newer major exists in
   * {@code MINOR_PATCH} mode); MANAGED deps without conflicts are covered by the upstream BOM bump
   * pass and do not produce per-dep actions here.
   */
  private void classifyDependencyCandidate(
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
    String latestOnCentral = safeGetLatestStable(coordinate);

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
   * mode promotes the major itself to an action.
   */
  private void classifyUpgrade(
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
    if (MAJOR_UPDATE_TYPE.equals(updateType) && mode == UpgradeMode.MINOR_PATCH) {
      String sameMajor = findCurrentMajorLatest(coordinate, current);
      attention.add(
          new NeedsAttention.MajorAvailable(
              coordinate.groupId(),
              coordinate.artifactId(),
              current,
              sameMajor != null ? sameMajor : current,
              latest,
              majorSource,
              majorManagedBy));
      if (sameMajor != null && versionComparator.compare(current, sameMajor) < 0) {
        String sameMajorType = versionComparator.determineUpdateType(current, sameMajor);
        if (MINOR_UPDATE_TYPE.equals(sameMajorType) || PATCH_UPDATE_TYPE.equals(sameMajorType)) {
          actions.add(actionFactory.apply(sameMajor, sameMajorType));
        }
      }
    } else if (List.of(MAJOR_UPDATE_TYPE, MINOR_UPDATE_TYPE, PATCH_UPDATE_TYPE)
        .contains(updateType)) {
      actions.add(actionFactory.apply(latest, updateType));
    }
  }

  /** Wraps {@link #getLatestStableVersion} and swallows transient lookup failures. */
  private String safeGetLatestStable(MavenCoordinate coord) {
    try {
      return getLatestStableVersion(coord);
    } catch (MavenCentralException _) {
      return null;
    }
  }

  /**
   * Finds the latest stable version sharing the same major as {@code current}, or null if no newer
   * same-major stable version exists.
   */
  private String findCurrentMajorLatest(MavenCoordinate coordinate, String current) {
    Integer currentMajor = extractMajorVersion(current);
    if (currentMajor == null) {
      return null;
    }
    try {
      return mavenCentralService.getAllVersions(coordinate).stream()
          .filter(versionComparator::isStableVersion)
          .filter(candidate -> versionComparator.preservesReleaseVariant(current, candidate))
          .filter(candidate -> currentMajor.equals(extractMajorVersion(candidate)))
          .filter(candidate -> versionComparator.compare(current, candidate) <= 0)
          .findFirst()
          .orElse(null);
    } catch (MavenCentralException _) {
      return null;
    }
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
