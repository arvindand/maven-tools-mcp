package com.arvindand.mcp.maven.model;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Version information for a Maven dependency with type-safe version classification.
 *
 * @param version the version string
 * @param type the version type classification
 * @author Arvind Menon
 * @since 0.1.0
 */
public record VersionInfo(String version, VersionType type) {

  /** Type-safe enumeration of Maven version types. */
  public enum VersionType {
    STABLE("stable"),
    RC("rc"),
    BETA("beta"),
    ALPHA("alpha"),
    MILESTONE("milestone");

    private final String displayName;

    VersionType(String displayName) {
      this.displayName = displayName;
    }

    @JsonValue
    public String getDisplayName() {
      return displayName;
    }

    /** Parse version type from string, defaulting to STABLE for unknown types. */
    public static VersionType fromString(String type) {
      if (type == null) return STABLE;
      return switch (type.toLowerCase()) {
        case "rc" -> RC;
        case "beta" -> BETA;
        case "alpha" -> ALPHA;
        case "milestone" -> MILESTONE;
        default -> STABLE;
      };
    }
  }
}
