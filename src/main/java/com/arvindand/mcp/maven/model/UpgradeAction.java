package com.arvindand.mcp.maven.model;

import com.arvindand.mcp.maven.model.security.SecurityAssessment;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * A mechanical upgrade action a non-LLM agent can apply by editing the POM directly.
 *
 * @param kind {@code "explicit_bump"} (edit the declared dep's {@code <version>}), {@code
 *     "bom_bump"} (edit a user-controllable BOM coordinate), {@code "managed_decl_bump"} (edit a
 *     direct root dependency-management declaration), {@code "plugin_dep_bump"} (edit a direct
 *     build/plugin dependency), or {@code "plugin_bump"} (edit a build plugin's own version)
 * @param groupId target groupId
 * @param artifactId target artifactId
 * @param current the currently-resolved version
 * @param target the version to bump to
 * @param updateType {@code "minor"} or {@code "patch"}; {@code "major"} only appears when the
 *     caller passed {@link UpgradeMode#ALL}
 * @param editTarget {@code "literal_version"} or {@code "property"} when the resolver can identify
 *     the exact edit site; null for legacy action kinds whose raw declaration is not surfaced
 * @param propertyName backing property to edit when {@code editTarget} is {@code "property"}
 * @param declaredIn logical POM location containing the declaration, such as {@code
 *     "dependency_management"}
 * @param ownerGroupId groupId of the plugin that owns a plugin dependency action
 * @param ownerArtifactId artifactId of the plugin that owns a plugin dependency action
 * @param security security context, present only when {@code current} has known vulnerabilities
 * @param heldBackFrom newest version skipped by the {@code minReleaseAgeDays} cooldown when {@code
 *     target} is an older eligible version; absent otherwise
 * @author Arvind Menon
 * @since 3.0.0
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UpgradeAction(
    String kind,
    String groupId,
    String artifactId,
    String current,
    String target,
    String updateType,
    String editTarget,
    String propertyName,
    String declaredIn,
    String ownerGroupId,
    String ownerArtifactId,
    Security security,
    String heldBackFrom) {

  /**
   * Security context of an action whose current version is vulnerable.
   *
   * @param maxSeverity highest severity among the current version's findings
   * @param vulnerabilityCount number of findings for the current version
   * @param vulnerabilityIds critical and high severity vulnerability ids
   * @param targetStatus whether {@code target} is {@code OK}, {@code VULNERABLE} or {@code UNKNOWN}
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Security(
      SecurityAssessment.Severity maxSeverity,
      int vulnerabilityCount,
      List<String> vulnerabilityIds,
      SecurityAssessment.Status targetStatus) {}

  public static final String KIND_EXPLICIT_BUMP = "explicit_bump";
  public static final String KIND_BOM_BUMP = "bom_bump";
  public static final String KIND_MANAGED_DECL_BUMP = "managed_decl_bump";
  public static final String KIND_PLUGIN_DEP_BUMP = "plugin_dep_bump";
  public static final String KIND_PLUGIN_BUMP = "plugin_bump";

  /** Backwards-compatible constructor for actions without security or cooldown context. */
  public UpgradeAction(
      String kind,
      String groupId,
      String artifactId,
      String current,
      String target,
      String updateType,
      String editTarget,
      String propertyName,
      String declaredIn,
      String ownerGroupId,
      String ownerArtifactId) {
    this(
        kind,
        groupId,
        artifactId,
        current,
        target,
        updateType,
        editTarget,
        propertyName,
        declaredIn,
        ownerGroupId,
        ownerArtifactId,
        null,
        null);
  }

  /** Returns a copy of this action carrying the given security context. */
  public UpgradeAction withSecurity(Security security) {
    return new UpgradeAction(
        kind,
        groupId,
        artifactId,
        current,
        target,
        updateType,
        editTarget,
        propertyName,
        declaredIn,
        ownerGroupId,
        ownerArtifactId,
        security,
        heldBackFrom);
  }

  /** Returns a copy of this action recording the newest version the cooldown held back. */
  public UpgradeAction withHeldBackFrom(String heldBackFrom) {
    return new UpgradeAction(
        kind,
        groupId,
        artifactId,
        current,
        target,
        updateType,
        editTarget,
        propertyName,
        declaredIn,
        ownerGroupId,
        ownerArtifactId,
        security,
        heldBackFrom);
  }

  /** Backwards-compatible constructor for action kinds without edit metadata. */
  public UpgradeAction(
      String kind,
      String groupId,
      String artifactId,
      String current,
      String target,
      String updateType) {
    this(kind, groupId, artifactId, current, target, updateType, null, null, null, null, null);
  }

  public static UpgradeAction explicitBump(
      String groupId, String artifactId, String current, String target, String updateType) {
    return new UpgradeAction(KIND_EXPLICIT_BUMP, groupId, artifactId, current, target, updateType);
  }

  public static UpgradeAction bomBump(
      String groupId, String artifactId, String current, String target, String updateType) {
    return new UpgradeAction(KIND_BOM_BUMP, groupId, artifactId, current, target, updateType);
  }

  public static UpgradeAction managedDeclarationBump(
      String groupId,
      String artifactId,
      String current,
      String target,
      String updateType,
      String editTarget,
      String propertyName) {
    return new UpgradeAction(
        KIND_MANAGED_DECL_BUMP,
        groupId,
        artifactId,
        current,
        target,
        updateType,
        editTarget,
        propertyName,
        "dependency_management",
        null,
        null);
  }

  public static UpgradeAction pluginDependencyBump(
      String groupId,
      String artifactId,
      String current,
      String target,
      String updateType,
      String editTarget,
      String propertyName,
      String declaredIn,
      String ownerGroupId,
      String ownerArtifactId) {
    return new UpgradeAction(
        KIND_PLUGIN_DEP_BUMP,
        groupId,
        artifactId,
        current,
        target,
        updateType,
        editTarget,
        propertyName,
        declaredIn,
        ownerGroupId,
        ownerArtifactId);
  }

  public static UpgradeAction pluginBump(
      String groupId,
      String artifactId,
      String current,
      String target,
      String updateType,
      String editTarget,
      String propertyName,
      String declaredIn) {
    return new UpgradeAction(
        KIND_PLUGIN_BUMP,
        groupId,
        artifactId,
        current,
        target,
        updateType,
        editTarget,
        propertyName,
        declaredIn,
        null,
        null);
  }
}
