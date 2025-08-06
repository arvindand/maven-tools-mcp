package com.arvindand.mcp.maven.model;

import java.util.Optional;

/**
 * Represents versions organized by their stability type for a dependency.
 *
 * @param dependency the dependency coordinate (groupId:artifactId)
 * @param latestStable latest stable version, if available
 * @param latestRc latest release candidate version, if available
 * @param latestBeta latest beta version, if available
 * @param latestAlpha latest alpha version, if available
 * @param latestMilestone latest milestone version, if available
 * @param totalVersions total number of versions found
 * @author Arvind Menon
 * @since 1.2.0
 */
public record VersionsByType(
    String dependency,
    Optional<VersionInfo> latestStable,
    Optional<VersionInfo> latestRc,
    Optional<VersionInfo> latestBeta,
    Optional<VersionInfo> latestAlpha,
    Optional<VersionInfo> latestMilestone,
    int totalVersions) {

  /** Creates a VersionsByType with primary version based on preference. */
  public static VersionsByType create(
      String dependency,
      Optional<VersionInfo> stable,
      Optional<VersionInfo> rc,
      Optional<VersionInfo> beta,
      Optional<VersionInfo> alpha,
      Optional<VersionInfo> milestone,
      int totalVersions) {
    return new VersionsByType(dependency, stable, rc, beta, alpha, milestone, totalVersions);
  }

  /** Gets the preferred version based on stability preference. */
  public Optional<VersionInfo> getPreferredVersion(boolean preferStable) {
    if (preferStable && latestStable.isPresent()) {
      return latestStable;
    }

    // Return first available version in order of stability
    return latestStable
        .or(() -> latestRc)
        .or(() -> latestBeta)
        .or(() -> latestAlpha)
        .or(() -> latestMilestone);
  }
}
