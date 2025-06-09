package com.arvindand.mcp.maven.config;

import com.arvindand.mcp.maven.model.BulkCheckResult;
import com.arvindand.mcp.maven.model.DependencyExistsResponse;
import com.arvindand.mcp.maven.model.DetailedVersionInfo;
import com.arvindand.mcp.maven.model.MavenCoordinate;
import com.arvindand.mcp.maven.model.MavenSearchResponse;
import com.arvindand.mcp.maven.model.VersionComparisonResponse;
import com.arvindand.mcp.maven.model.VersionInfo;
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
      registerRecordClass(hints, DetailedVersionInfo.class);
      registerRecordClass(hints, DependencyExistsResponse.class);
      registerRecordClass(hints, VersionComparisonResponse.class);
      registerRecordClass(hints, VersionComparisonResponse.DependencyComparisonResult.class);
      registerRecordClass(hints, VersionComparisonResponse.UpdateSummary.class);
      registerRecordClass(hints, VersionInfo.class);

      // Register MavenSearchResponse and its nested records for Jackson deserialization
      registerRecordClass(hints, MavenSearchResponse.class);
      registerRecordClass(hints, MavenSearchResponse.ResponseData.class);
      registerRecordClass(hints, MavenSearchResponse.MavenArtifact.class);

      // Register VersionComparator record for version parsing
      registerRecordClass(
          hints, com.arvindand.mcp.maven.util.VersionComparator.VersionComponents.class);

      // Register enum classes for Jackson serialization and reflection access
      registerEnumClass(hints, VersionInfo.VersionType.class);
      registerEnumClass(hints, BulkCheckResult.Status.class);
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
