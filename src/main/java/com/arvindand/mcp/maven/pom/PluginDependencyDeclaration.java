package com.arvindand.mcp.maven.pom;

import java.util.Objects;

/**
 * A dependency declared directly under a build plugin in the input POM whose version can be edited
 * in that same POM.
 *
 * @param groupId resolved dependency groupId
 * @param artifactId resolved dependency artifactId
 * @param version resolved current dependency version
 * @param editTarget {@code "literal_version"} or {@code "property"}
 * @param propertyName root-POM property to edit for a property target; null for a literal version
 * @param pluginGroupId resolved owner plugin groupId
 * @param pluginArtifactId resolved owner plugin artifactId
 * @param declaredIn logical build location containing the dependency
 * @author Arvind Menon
 * @since 3.2.0
 */
public record PluginDependencyDeclaration(
    String groupId,
    String artifactId,
    String version,
    String editTarget,
    String propertyName,
    String pluginGroupId,
    String pluginArtifactId,
    String declaredIn) {

  public static final String BUILD_PLUGINS = "build.plugins.plugin.dependencies";
  public static final String PLUGIN_MANAGEMENT =
      "build.pluginManagement.plugins.plugin.dependencies";

  public PluginDependencyDeclaration {
    Objects.requireNonNull(groupId, "groupId must not be null");
    Objects.requireNonNull(artifactId, "artifactId must not be null");
    Objects.requireNonNull(version, "version must not be null");
    Objects.requireNonNull(editTarget, "editTarget must not be null");
    Objects.requireNonNull(pluginGroupId, "pluginGroupId must not be null");
    Objects.requireNonNull(pluginArtifactId, "pluginArtifactId must not be null");
    Objects.requireNonNull(declaredIn, "declaredIn must not be null");
    if (ManagedDeclaration.EDIT_TARGET_PROPERTY.equals(editTarget)) {
      Objects.requireNonNull(propertyName, "propertyName must not be null for a property edit");
    }
  }

  public static PluginDependencyDeclaration literal(
      String groupId,
      String artifactId,
      String version,
      String pluginGroupId,
      String pluginArtifactId,
      String declaredIn) {
    return new PluginDependencyDeclaration(
        groupId,
        artifactId,
        version,
        ManagedDeclaration.EDIT_TARGET_LITERAL_VERSION,
        null,
        pluginGroupId,
        pluginArtifactId,
        declaredIn);
  }

  public static PluginDependencyDeclaration property(
      String groupId,
      String artifactId,
      String version,
      String propertyName,
      String pluginGroupId,
      String pluginArtifactId,
      String declaredIn) {
    return new PluginDependencyDeclaration(
        groupId,
        artifactId,
        version,
        ManagedDeclaration.EDIT_TARGET_PROPERTY,
        propertyName,
        pluginGroupId,
        pluginArtifactId,
        declaredIn);
  }
}
