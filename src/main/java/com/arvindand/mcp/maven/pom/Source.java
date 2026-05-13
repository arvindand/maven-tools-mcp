package com.arvindand.mcp.maven.pom;

/**
 * Classifies where the effective version of a declared dependency came from.
 *
 * <ul>
 *   <li>{@link #EXPLICIT} - the POM declared the version directly with no entry in any
 *       reachable {@code <dependencyManagement>}.
 *   <li>{@link #MANAGED} - the POM did not declare a version; the version was inherited
 *       from a parent POM or a BOM import.
 *   <li>{@link #EXPLICIT_OVERRIDE} - the POM declared an explicit version, but a reachable
 *       {@code <dependencyManagement>} entry also covers the same coordinate. The explicit
 *       value wins, but the override is worth surfacing to the user.
 * </ul>
 */
public enum Source {
  EXPLICIT,
  MANAGED,
  EXPLICIT_OVERRIDE
}
