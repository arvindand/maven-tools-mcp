package com.arvindand.mcp.maven.model;

/**
 * A mechanical upgrade action a non-LLM agent can apply by editing the POM directly. Both kinds
 * collapse to "edit the {@code <version>} of this coordinate" — {@code kind} tells the agent
 * whether the coordinate refers to a dependency or a BOM.
 *
 * @param kind {@code "explicit_bump"} (edit the declared dep's {@code <version>}) or {@code
 *     "bom_bump"} (edit the BOM coordinate's {@code <version>}; covers every dep the BOM manages in
 *     one edit)
 * @param groupId target groupId
 * @param artifactId target artifactId
 * @param current the currently-resolved version
 * @param target the version to bump to
 * @param updateType {@code "minor"} or {@code "patch"}; {@code "major"} only appears when the
 *     caller passed {@link UpgradeMode#ALL}
 * @author Arvind Menon
 * @since 3.0.0
 */
public record UpgradeAction(
    String kind,
    String groupId,
    String artifactId,
    String current,
    String target,
    String updateType) {

  public static final String KIND_EXPLICIT_BUMP = "explicit_bump";
  public static final String KIND_BOM_BUMP = "bom_bump";

  public static UpgradeAction explicitBump(
      String groupId, String artifactId, String current, String target, String updateType) {
    return new UpgradeAction(KIND_EXPLICIT_BUMP, groupId, artifactId, current, target, updateType);
  }

  public static UpgradeAction bomBump(
      String groupId, String artifactId, String current, String target, String updateType) {
    return new UpgradeAction(KIND_BOM_BUMP, groupId, artifactId, current, target, updateType);
  }
}
