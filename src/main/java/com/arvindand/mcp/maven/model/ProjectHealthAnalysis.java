package com.arvindand.mcp.maven.model;

import com.arvindand.mcp.maven.model.license.LicenseFindings;
import com.arvindand.mcp.maven.model.license.LicenseInfo;
import com.arvindand.mcp.maven.model.security.SecurityAssessment;
import com.arvindand.mcp.maven.model.security.SecurityFindings;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * Comprehensive health analysis for multiple dependencies in a project.
 *
 * @param analysisDate ISO-8601 instant at which the analysis was performed
 * @param overallHealth overall health label (excellent, good, fair, poor, unknown)
 * @param averageHealthScore average per-dependency health score rounded to an integer, over
 *     successfully analysed dependencies (null when none succeeded)
 * @param dependencyCount total number of dependencies analyzed
 * @param successfulAnalysis count of successfully analyzed dependencies
 * @param failedAnalysis count of failed analyses
 * @param ageDistribution breakdown of dependencies by age classification
 * @param dependencies individual analysis for each dependency
 * @param recommendations prioritized list of recommendations
 * @param securityFindings aggregate security findings (null if scanning disabled)
 * @param licenseFindings aggregate license findings (null if scanning disabled)
 * @param context7Guidance orchestration template for the per-dependency {@code context7Query}
 *     hints, emitted once (empty when Context7 is disabled or no dependency has a hint)
 * @author Arvind Menon
 * @since 1.1.0
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ProjectHealthAnalysis(
    String analysisDate,
    String overallHealth,
    Integer averageHealthScore,
    int dependencyCount,
    int successfulAnalysis,
    int failedAnalysis,
    AgeDistribution ageDistribution,
    List<DependencyHealthAnalysis> dependencies,
    List<String> recommendations,
    SecurityFindings securityFindings,
    LicenseFindings licenseFindings,
    Optional<Context7Guidance> context7Guidance) {

  /** Creates an analysis stamped with the current time and a derived Context7 template. */
  public static ProjectHealthAnalysis of(
      String overallHealth,
      Integer averageHealthScore,
      int dependencyCount,
      int successfulAnalysis,
      int failedAnalysis,
      AgeDistribution ageDistribution,
      List<DependencyHealthAnalysis> dependencies,
      List<String> recommendations,
      SecurityFindings securityFindings,
      LicenseFindings licenseFindings) {
    return new ProjectHealthAnalysis(
        Instant.now().toString(),
        overallHealth,
        averageHealthScore,
        dependencyCount,
        successfulAnalysis,
        failedAnalysis,
        ageDistribution,
        dependencies,
        recommendations,
        securityFindings,
        licenseFindings,
        dependencies.stream().anyMatch(dep -> dep.context7Query().isPresent())
            ? Optional.of(Context7Guidance.bulkTemplate())
            : Optional.empty());
  }

  /** Distribution of dependencies by age classification. */
  public record AgeDistribution(int fresh, int current, int aging, int stale) {}

  /**
   * Health analysis for a single dependency.
   *
   * @param dependency the dependency as supplied by the caller
   * @param status success, error, not_found or no_stable_version
   * @param currentVersion the version supplied by the caller (empty for unversioned input)
   * @param scannedVersion the exact version the security and license scans were run against
   * @param latestVersion newest version honouring the stability filter (and release variant)
   * @param updateAvailable whether latestVersion is newer than currentVersion (versioned input)
   * @param updateType major, minor, patch or none (versioned input)
   * @param currentVersionAgeDays days since the current version was published (best effort)
   * @param ageClassification age classification of latestVersion's release date
   * @param daysSinceRelease days since latestVersion was released
   * @param note additional clarification, for example a pre-release fallback
   * @param context7Query optional suggested Context7 query (see top-level context7_guidance)
   */
  public record DependencyHealthAnalysis(
      String dependency,
      String status,
      Optional<String> currentVersion,
      Optional<String> scannedVersion,
      String latestVersion,
      Optional<Boolean> updateAvailable,
      Optional<String> updateType,
      Optional<Long> currentVersionAgeDays,
      String ageClassification,
      long daysSinceRelease,
      int healthScore,
      String maintenanceLevel,
      Optional<SecurityAssessment> security,
      Optional<List<LicenseInfo>> licenses,
      Optional<String> note,
      Optional<String> context7Query,
      Optional<String> error) {

    private static final String STATUS_SUCCESS = "success";

    /** Creates an error result. */
    public static DependencyHealthAnalysis error(String dependency, String error) {
      return builder().dependency(dependency).status("error").error(error).build();
    }

    /** Creates a not found result. */
    public static DependencyHealthAnalysis notFound(String dependency) {
      return builder()
          .dependency(dependency)
          .status("not_found")
          .error("Dependency not found in Maven Central")
          .build();
    }

    /** Builder for cleaner construction when many optional fields are involved. */
    public static Builder builder() {
      return new Builder();
    }

    public static final class Builder {
      private String dependency;
      private String status = STATUS_SUCCESS;
      private String currentVersion;
      private String scannedVersion;
      private String latestVersion;
      private Boolean updateAvailable;
      private String updateType;
      private Long currentVersionAgeDays;
      private String ageClassification;
      private long daysSinceRelease;
      private int healthScore;
      private String maintenanceLevel;
      private SecurityAssessment security;
      private List<LicenseInfo> licenses;
      private String note;
      private String context7Query;
      private String error;

      public Builder dependency(String dependency) {
        this.dependency = dependency;
        return this;
      }

      public Builder status(String status) {
        this.status = status;
        return this;
      }

      public Builder currentVersion(String currentVersion) {
        this.currentVersion = currentVersion;
        return this;
      }

      public Builder scannedVersion(String scannedVersion) {
        this.scannedVersion = scannedVersion;
        return this;
      }

      public Builder latestVersion(String latestVersion) {
        this.latestVersion = latestVersion;
        return this;
      }

      public Builder updateAvailable(Boolean updateAvailable) {
        this.updateAvailable = updateAvailable;
        return this;
      }

      public Builder updateType(String updateType) {
        this.updateType = updateType;
        return this;
      }

      public Builder currentVersionAgeDays(Long currentVersionAgeDays) {
        this.currentVersionAgeDays = currentVersionAgeDays;
        return this;
      }

      public Builder ageClassification(String ageClassification) {
        this.ageClassification = ageClassification;
        return this;
      }

      public Builder daysSinceRelease(long daysSinceRelease) {
        this.daysSinceRelease = daysSinceRelease;
        return this;
      }

      public Builder healthScore(int healthScore) {
        this.healthScore = healthScore;
        return this;
      }

      public Builder maintenanceLevel(String maintenanceLevel) {
        this.maintenanceLevel = maintenanceLevel;
        return this;
      }

      public Builder security(SecurityAssessment security) {
        this.security = security;
        return this;
      }

      public Builder licenses(List<LicenseInfo> licenses) {
        this.licenses = licenses;
        return this;
      }

      public Builder note(String note) {
        this.note = note;
        return this;
      }

      public Builder context7Query(String context7Query) {
        this.context7Query = context7Query;
        return this;
      }

      public Builder error(String error) {
        this.error = error;
        return this;
      }

      public DependencyHealthAnalysis build() {
        Objects.requireNonNull(dependency, "dependency required");

        return new DependencyHealthAnalysis(
            dependency,
            status,
            Optional.ofNullable(currentVersion),
            Optional.ofNullable(scannedVersion),
            latestVersion,
            Optional.ofNullable(updateAvailable),
            Optional.ofNullable(updateType),
            Optional.ofNullable(currentVersionAgeDays),
            ageClassification,
            daysSinceRelease,
            healthScore,
            maintenanceLevel,
            Optional.ofNullable(security),
            Optional.ofNullable(licenses),
            Optional.ofNullable(note),
            Optional.ofNullable(context7Query),
            Optional.ofNullable(error));
      }
    }
  }
}
