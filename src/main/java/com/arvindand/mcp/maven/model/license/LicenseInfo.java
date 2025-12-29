package com.arvindand.mcp.maven.model.license;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.Set;

/**
 * License information extracted from Maven POM.
 *
 * @param name license name (e.g., "Apache License, Version 2.0")
 * @param url license URL
 * @param category license category classification
 * @author Arvind Menon
 * @since 2.0.0
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LicenseInfo(String name, String url, LicenseCategory category) {

  /** License category classification for compliance purposes. */
  public enum LicenseCategory {
    PERMISSIVE,
    WEAK_COPYLEFT,
    STRONG_COPYLEFT,
    PROPRIETARY,
    UNKNOWN
  }

  private static final Set<String> PERMISSIVE_PATTERNS =
      Set.of("apache", "mit", "bsd", "isc", "unlicense", "cc0", "wtfpl", "zlib", "public domain");

  private static final Set<String> WEAK_COPYLEFT_PATTERNS =
      Set.of(
          "lgpl",
          "lesser general public",
          "library general public",
          "mpl",
          "mozilla",
          "epl",
          "eclipse",
          "cddl",
          "common development and distribution");

  private static final Set<String> STRONG_COPYLEFT_PATTERNS =
      Set.of("gpl", "general public license", "agpl", "affero");

  /**
   * Categorize license based on name pattern matching.
   *
   * @param licenseName license name to categorize
   * @return license category
   */
  public static LicenseCategory categorize(String licenseName) {
    if (licenseName == null || licenseName.isBlank()) {
      return LicenseCategory.UNKNOWN;
    }

    String lower = licenseName.toLowerCase();

    // Check permissive first
    for (String pattern : PERMISSIVE_PATTERNS) {
      if (lower.contains(pattern)) {
        return LicenseCategory.PERMISSIVE;
      }
    }

    // Check weak copyleft BEFORE strong copyleft (LGPL before GPL)
    for (String pattern : WEAK_COPYLEFT_PATTERNS) {
      if (lower.contains(pattern)) {
        return LicenseCategory.WEAK_COPYLEFT;
      }
    }

    // Check strong copyleft last
    for (String pattern : STRONG_COPYLEFT_PATTERNS) {
      if (lower.contains(pattern)) {
        return LicenseCategory.STRONG_COPYLEFT;
      }
    }

    return LicenseCategory.UNKNOWN;
  }

  /**
   * Factory from POM license element.
   *
   * @param name license name
   * @param url license URL
   * @return LicenseInfo with auto-categorization
   */
  public static LicenseInfo fromPom(String name, String url) {
    return new LicenseInfo(name, url, categorize(name));
  }

  /** Check if license is permissive. */
  public boolean isPermissive() {
    return category == LicenseCategory.PERMISSIVE;
  }

  /** Check if license requires source disclosure (any copyleft). */
  public boolean isCopyleft() {
    return category == LicenseCategory.WEAK_COPYLEFT || category == LicenseCategory.STRONG_COPYLEFT;
  }
}
