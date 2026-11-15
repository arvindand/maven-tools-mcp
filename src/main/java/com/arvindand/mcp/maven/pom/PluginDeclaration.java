package com.arvindand.mcp.maven.pom;

import java.util.Objects;

/**
 * A build plugin declared directly in the input POM's {@code <build><plugins>} or {@code
 * <build><pluginManagement><plugins>} whose own version can be edited in that same POM.
 *
 * @param groupId resolved plugin groupId ({@code org.apache.maven.plugins} when not declared)
 * @param artifactId resolved plugin artifactId
 * @param version resolved current plugin version
 * @param editTarget {@code "literal_version"} or {@code "property"}
 * @param propertyName root-POM property to edit for a property target; null for a literal version
 * @param declaredIn logical build location, {@link #BUILD_PLUGINS} or {@link #PLUGIN_MANAGEMENT}
 * @author Arvind Menon
 * @since 3.3.0
 */
public record PluginDeclaration(
    String groupId,
    String artifactId,
    String version,
    String editTarget,
    String propertyName,
    String declaredIn) {

  public static final String DEFAULT_GROUP_ID = "org.apache.maven.plugins";
  public static final String BUILD_PLUGINS = "build.plugins.plugin";
  public static final String PLUGIN_MANAGEMENT = "build.pluginManagement.plugins.plugin";

  public PluginDeclaration {
    Objects.requireNonNull(groupId, "groupId must not be null");
    Objects.requireNonNull(artifactId, "artifactId must not be null");
    Objects.requireNonNull(version, "version must not be null");
    Objects.requireNonNull(editTarget, "editTarget must not be null");
    Objects.requireNonNull(declaredIn, "declaredIn must not be null");
    if (ManagedDeclaration.EDIT_TARGET_PROPERTY.equals(editTarget)) {
      Objects.requireNonNull(propertyName, "propertyName must not be null for a property edit");
    }
  }

  public static PluginDeclaration literal(
      String groupId, String artifactId, String version, String declaredIn) {
    return new PluginDeclaration(
        groupId,
        artifactId,
        version,
        ManagedDeclaration.EDIT_TARGET_LITERAL_VERSION,
        null,
        declaredIn);
  }

  public static PluginDeclaration property(
      String groupId, String artifactId, String version, String propertyName, String declaredIn) {
    return new PluginDeclaration(
        groupId,
        artifactId,
        version,
        ManagedDeclaration.EDIT_TARGET_PROPERTY,
        propertyName,
        declaredIn);
  }
}
