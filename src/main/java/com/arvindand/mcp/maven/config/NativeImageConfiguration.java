package com.arvindand.mcp.maven.config;

import com.arvindand.mcp.maven.model.BulkCheckResult;
import com.arvindand.mcp.maven.model.Context7Guidance;
import com.arvindand.mcp.maven.model.DependencyAge;
import com.arvindand.mcp.maven.model.DependencyAgeAnalysis;
import com.arvindand.mcp.maven.model.DependencyInfo;
import com.arvindand.mcp.maven.model.MavenArtifact;
import com.arvindand.mcp.maven.model.MavenCoordinate;
import com.arvindand.mcp.maven.model.MavenMetadata;
import com.arvindand.mcp.maven.model.ProjectHealthAnalysis;
import com.arvindand.mcp.maven.model.ReleasePatternAnalysis;
import com.arvindand.mcp.maven.model.ToolResponse;
import com.arvindand.mcp.maven.model.VersionComparison;
import com.arvindand.mcp.maven.model.VersionInfo;
import com.arvindand.mcp.maven.model.VersionTimelineAnalysis;
import com.arvindand.mcp.maven.model.VersionsByType;
import com.arvindand.mcp.maven.model.license.LicenseFindings;
import com.arvindand.mcp.maven.model.license.LicenseInfo;
import com.arvindand.mcp.maven.model.security.SecurityAssessment;
import com.arvindand.mcp.maven.model.security.SecurityFindings;
import com.arvindand.mcp.maven.model.security.SecuritySummary;
import com.arvindand.mcp.maven.model.security.VulnerabilityInfo;
import com.arvindand.mcp.maven.service.VulnerabilityService;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

/**
 * Native image configuration for reflection hints. This configuration ensures that record classes
 * and their methods are accessible in native images, particularly for SpEL expression evaluation in
 * caching annotations and Jackson JSON serialization.
 *
 * @author Arvind Menon
 * @since 0.1.0
 */
@Configuration(proxyBeanMethods = false)
@ImportRuntimeHints(NativeImageConfiguration.MavenRecordHints.class)
public class NativeImageConfiguration {

  private NativeImageConfiguration() {
    // Prevent instantiation
  }

  /**
   * Runtime hints registrar for Maven record classes to ensure proper reflection access in native
   * images.
   */
  static class MavenRecordHints implements RuntimeHintsRegistrar {
    @Override
    public void registerHints(@NonNull RuntimeHints hints, @Nullable ClassLoader classLoader) {
      // Register all record classes with comprehensive reflection access
      registerRecordClass(hints, MavenCoordinate.class);
      registerRecordClass(hints, BulkCheckResult.class);
      registerRecordClass(hints, DependencyInfo.class);
      registerRecordClass(hints, VersionComparison.class);
      registerRecordClass(hints, VersionComparison.DependencyComparisonResult.class);
      registerRecordClass(hints, VersionComparison.UpdateSummary.class);
      registerRecordClass(hints, VersionInfo.class);

      // Register MavenArtifact for timestamp analysis compatibility
      registerRecordClass(hints, MavenArtifact.class);

      // Register MavenMetadata and its nested records for XML deserialization (v1.4.0)
      registerRecordClass(hints, MavenMetadata.class);
      registerRecordClass(hints, MavenMetadata.VersioningInfo.class);
      registerRecordClass(hints, MavenMetadata.VersionList.class);
      registerRecordClass(hints, MavenMetadata.SnapshotInfo.class);

      // Register VersionComparator record for version parsing
      registerRecordClass(
          hints, com.arvindand.mcp.maven.util.VersionComparator.VersionComponents.class);

      // Register new analytical intelligence record classes (v1.1.0)
      registerRecordClass(hints, DependencyAgeAnalysis.class);
      registerRecordClass(hints, ReleasePatternAnalysis.class);
      registerRecordClass(hints, ReleasePatternAnalysis.ReleaseInfo.class);
      registerRecordClass(hints, VersionTimelineAnalysis.class);
      registerRecordClass(hints, VersionTimelineAnalysis.TimelineEntry.class);
      registerRecordClass(hints, VersionTimelineAnalysis.VelocityTrend.class);
      registerRecordClass(hints, VersionTimelineAnalysis.StabilityPattern.class);
      registerRecordClass(hints, VersionTimelineAnalysis.RecentActivity.class);

      // Register Context7 integration record classes (v1.2.0)
      registerRecordClass(hints, Context7Guidance.class);
      registerRecordClass(hints, DependencyAge.class);

      // Register structured response record classes (v1.2.0)
      registerRecordClass(hints, ProjectHealthAnalysis.class);
      registerRecordClass(hints, ProjectHealthAnalysis.AgeDistribution.class);
      registerRecordClass(hints, ProjectHealthAnalysis.DependencyHealthAnalysis.class);
      registerRecordClass(hints, VersionsByType.class);

      // Register security + license enrichment records (v2.0.0+)
      registerRecordClass(hints, SecurityAssessment.class);
      registerRecordClass(hints, SecuritySummary.class);
      registerRecordClass(hints, SecurityFindings.class);
      registerRecordClass(hints, LicenseInfo.class);
      registerRecordClass(hints, LicenseFindings.class);
      registerRecordClass(hints, VulnerabilityInfo.class);

      // Register OSV API DTOs for native image JSON serialization (v2.0.0)
      registerRecordClass(hints, VulnerabilityService.OsvRequest.class);
      registerRecordClass(hints, VulnerabilityService.OsvPackage.class);
      registerRecordClass(hints, VulnerabilityService.OsvResponse.class);
      registerRecordClass(hints, VulnerabilityService.OsvVulnerability.class);
      registerRecordClass(hints, VulnerabilityService.OsvSeverity.class);
      registerRecordClass(hints, VulnerabilityService.OsvAffected.class);
      registerRecordClass(hints, VulnerabilityService.OsvRange.class);
      registerRecordClass(hints, VulnerabilityService.OsvEvent.class);
      registerRecordClass(hints, VulnerabilityService.OsvReference.class);

      registerRecordClass(hints, ToolResponse.class);
      registerRecordClass(hints, ToolResponse.Success.class);
      registerRecordClass(hints, ToolResponse.Error.class);

      // Register configuration properties record classes
      registerRecordClass(hints, MavenCentralProperties.class);
      registerRecordClass(hints, Context7Properties.class);

      // Register enum classes for Jackson serialization and reflection access
      registerEnumClass(hints, VersionInfo.VersionType.class);
      registerEnumClass(hints, BulkCheckResult.Status.class);

      // Register security + license enum classes (v2.0.0+)
      registerEnumClass(hints, SecurityAssessment.Status.class);
      registerEnumClass(hints, SecurityAssessment.Severity.class);
      registerEnumClass(hints, LicenseInfo.LicenseCategory.class);

      // Register new analytical intelligence enum classes (v1.1.0)
      registerEnumClass(hints, DependencyAgeAnalysis.AgeClassification.class);
      registerEnumClass(hints, ReleasePatternAnalysis.MaintenanceLevel.class);
      registerEnumClass(hints, ReleasePatternAnalysis.ReleaseConsistency.class);
      registerEnumClass(hints, VersionTimelineAnalysis.TimelineEntry.ReleaseGap.class);
      registerEnumClass(hints, VersionTimelineAnalysis.VelocityTrend.TrendDirection.class);
      registerEnumClass(hints, VersionTimelineAnalysis.RecentActivity.ActivityLevel.class);
    }

    /**
     * Register a record class with all necessary reflection access for Jackson serialization and
     * SpEL expression evaluation.
     */
    private void registerRecordClass(RuntimeHints hints, Class<?> recordClass) {
      hints
          .reflection()
          .registerType(
              recordClass,
              typeHint ->
                  typeHint.withMembers(
                      MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
                      MemberCategory.INVOKE_PUBLIC_METHODS,
                      MemberCategory.DECLARED_FIELDS,
                      MemberCategory.PUBLIC_FIELDS,
                      MemberCategory.INTROSPECT_PUBLIC_CONSTRUCTORS,
                      MemberCategory.INTROSPECT_PUBLIC_METHODS,
                      MemberCategory.INTROSPECT_DECLARED_METHODS));
    }

    /**
     * Register an enum class with reflection access for Jackson serialization and native image
     * compatibility.
     */
    private void registerEnumClass(RuntimeHints hints, Class<?> enumClass) {
      hints
          .reflection()
          .registerType(
              enumClass,
              typeHint ->
                  typeHint.withMembers(
                      MemberCategory.PUBLIC_FIELDS,
                      MemberCategory.INVOKE_PUBLIC_METHODS,
                      MemberCategory.INTROSPECT_PUBLIC_METHODS,
                      MemberCategory.INTROSPECT_DECLARED_METHODS));
    }
  }
}
