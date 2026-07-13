package com.arvindand.mcp.maven.pom;

import java.util.Objects;

/**
 * A dependency declared directly in the input POM's {@code <dependencyManagement>} block whose
 * version can be edited in that same POM.
 *
 * @param groupId resolved groupId
 * @param artifactId resolved artifactId
 * @param version resolved current version
 * @param editTarget {@code "literal_version"} when the declaration owns a concrete {@code
 *     <version>}, or {@code "property"} when it references a property declared by the input POM
 * @param propertyName the root-POM property to edit when {@code editTarget} is {@code "property"};
 *     null for a literal version
 * @author Arvind Menon
 * @since 3.2.0
 */
public record ManagedDeclaration(
    String groupId, String artifactId, String version, String editTarget, String propertyName) {

  public static final String EDIT_TARGET_LITERAL_VERSION = "literal_version";
  public static final String EDIT_TARGET_PROPERTY = "property";

  public ManagedDeclaration {
    Objects.requireNonNull(groupId, "groupId must not be null");
    Objects.requireNonNull(artifactId, "artifactId must not be null");
    Objects.requireNonNull(version, "version must not be null");
    Objects.requireNonNull(editTarget, "editTarget must not be null");
    if (EDIT_TARGET_PROPERTY.equals(editTarget)) {
      Objects.requireNonNull(propertyName, "propertyName must not be null for a property edit");
    }
  }

  public static ManagedDeclaration literal(String groupId, String artifactId, String version) {
    return new ManagedDeclaration(groupId, artifactId, version, EDIT_TARGET_LITERAL_VERSION, null);
  }

  public static ManagedDeclaration property(
      String groupId, String artifactId, String version, String propertyName) {
    return new ManagedDeclaration(groupId, artifactId, version, EDIT_TARGET_PROPERTY, propertyName);
  }
}
