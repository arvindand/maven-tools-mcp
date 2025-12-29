package com.arvindand.mcp.maven.model;

import com.arvindand.mcp.maven.model.license.LicenseFindings;
import com.arvindand.mcp.maven.model.license.LicenseInfo;
import com.arvindand.mcp.maven.model.security.SecurityAssessment;
import com.arvindand.mcp.maven.model.security.SecurityFindings;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Comprehensive health analysis for multiple dependencies in a project.
 *
 * @param analysisDate ISO formatted date when analysis was performed
 * @param dependencyCount total number of dependencies analyzed
 * @param successfulAnalysis count of successfully analyzed dependencies
 * @param failedAnalysis count of failed analyses
 * @param ageDistribution breakdown of dependencies by age classification
 * @param dependencies individual analysis for each dependency
 * @param recommendations prioritized list of recommendations
 * @param securityFindings aggregate security findings (null if scanning disabled)
 * @param licenseFindings aggregate license findings (null if scanning disabled)
 * @author Arvind Menon
 * @since 1.1.0
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ProjectHealthAnalysis(
    String analysisDate,
    int dependencyCount,
    int successfulAnalysis,
    int failedAnalysis,
    AgeDistribution ageDistribution,
    List<DependencyHealthAnalysis> dependencies,
    List<String> recommendations,
    SecurityFindings securityFindings,
    LicenseFindings licenseFindings) {

  /** Constructor without security/license findings for backward compatibility. */
  public ProjectHealthAnalysis(
      String analysisDate,
      int dependencyCount,
      int successfulAnalysis,
      int failedAnalysis,
      AgeDistribution ageDistribution,
      List<DependencyHealthAnalysis> dependencies,
      List<String> recommendations) {
    this(
        analysisDate,
        dependencyCount,
        successfulAnalysis,
        failedAnalysis,
        ageDistribution,
        dependencies,
        recommendations,
        null,
        null);
  }

  /** Distribution of dependencies by age classification. */
  public record AgeDistribution(int fresh, int current, int aging, int stale) {}

  /** Health analysis for a single dependency. */
  public record DependencyHealthAnalysis(
      String dependency,
      String status,
      String latestVersion,
      String ageClassification,
      long daysSinceRelease,
      int healthScore,
      String maintenanceLevel,
      Optional<SecurityAssessment> security,
      Optional<List<LicenseInfo>> licenses,
      Optional<Context7Guidance> context7Guidance,
      Optional<String> error) {

    private static final String STATUS_SUCCESS = "success";
    private static final String AGE_AGING = "aging";
    private static final String AGE_STALE = "stale";

    /** Creates a successful analysis result. */
    public static DependencyHealthAnalysis success(
        String dependency,
        String latestVersion,
        String ageClassification,
        long daysSinceRelease,
        int healthScore,
        String maintenanceLevel,
        boolean context7Enabled) {
      // Add Context7 guidance for aging/stale dependencies when Context7 is enabled
      Optional<Context7Guidance> guidance =
          (context7Enabled
                  && (AGE_AGING.equals(ageClassification) || AGE_STALE.equals(ageClassification)))
              ? Optional.of(Context7Guidance.forModernization(dependency, ageClassification))
              : Optional.empty();

      return new DependencyHealthAnalysis(
          dependency,
          STATUS_SUCCESS,
          latestVersion,
          ageClassification,
          daysSinceRelease,
          healthScore,
          maintenanceLevel,
          Optional.empty(),
          Optional.empty(),
          guidance,
          Optional.empty());
    }

    /** Creates a successful analysis result with security assessment. */
    public static DependencyHealthAnalysis successWithSecurity(
        String dependency,
        String latestVersion,
        String ageClassification,
        long daysSinceRelease,
        int healthScore,
        String maintenanceLevel,
        SecurityAssessment security,
        boolean context7Enabled) {
      Optional<Context7Guidance> guidance =
          (context7Enabled
                  && (AGE_AGING.equals(ageClassification) || AGE_STALE.equals(ageClassification)))
              ? Optional.of(Context7Guidance.forModernization(dependency, ageClassification))
              : Optional.empty();

      return new DependencyHealthAnalysis(
          dependency,
          STATUS_SUCCESS,
          latestVersion,
          ageClassification,
          daysSinceRelease,
          healthScore,
          maintenanceLevel,
          Optional.ofNullable(security),
          Optional.empty(),
          guidance,
          Optional.empty());
    }

    /** Creates a successful analysis result with security and license information. */
    public static DependencyHealthAnalysis successWithSecurityAndLicenses(
        String dependency,
        String latestVersion,
        String ageClassification,
        long daysSinceRelease,
        int healthScore,
        String maintenanceLevel,
        SecurityAssessment security,
        List<LicenseInfo> licenses,
        boolean context7Enabled) {
      Optional<Context7Guidance> guidance =
          (context7Enabled
                  && (AGE_AGING.equals(ageClassification) || AGE_STALE.equals(ageClassification)))
              ? Optional.of(Context7Guidance.forModernization(dependency, ageClassification))
              : Optional.empty();

      return new DependencyHealthAnalysis(
          dependency,
          STATUS_SUCCESS,
          latestVersion,
          ageClassification,
          daysSinceRelease,
          healthScore,
          maintenanceLevel,
          Optional.ofNullable(security),
          Optional.ofNullable(licenses),
          guidance,
          Optional.empty());
    }

    /** Creates an error result. */
    public static DependencyHealthAnalysis error(String dependency, String error) {
      return new DependencyHealthAnalysis(
          dependency,
          "error",
          null,
          null,
          0,
          0,
          null,
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.of(error));
    }

    /** Creates a not found result. */
    public static DependencyHealthAnalysis notFound(String dependency) {
      return new DependencyHealthAnalysis(
          dependency,
          "not_found",
          null,
          null,
          0,
          0,
          null,
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.of("Dependency not found in Maven Central"));
    }

    /** Builder for cleaner construction when many optional fields are involved. */
    public static Builder builder() {
      return new Builder();
    }

    public static final class Builder {
      private String dependency;
      private String status = STATUS_SUCCESS;
      private String latestVersion;
      private String ageClassification;
      private long daysSinceRelease;
      private int healthScore;
      private String maintenanceLevel;
      private SecurityAssessment security;
      private List<LicenseInfo> licenses;
      private Context7Guidance context7Guidance;
      private String error;

      public Builder dependency(String dependency) {
        this.dependency = dependency;
        return this;
      }

      public Builder status(String status) {
        this.status = status;
        return this;
      }

      public Builder latestVersion(String latestVersion) {
        this.latestVersion = latestVersion;
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

      public Builder context7Guidance(Context7Guidance context7Guidance) {
        this.context7Guidance = context7Guidance;
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
            latestVersion,
            ageClassification,
            daysSinceRelease,
            healthScore,
            maintenanceLevel,
            Optional.ofNullable(security),
            Optional.ofNullable(licenses),
            Optional.ofNullable(context7Guidance),
            Optional.ofNullable(error));
      }
    }
  }
}
