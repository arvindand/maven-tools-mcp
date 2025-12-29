package com.arvindand.mcp.maven.model.license;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.ArrayList;
import java.util.List;

/**
 * Aggregated license findings for project health analysis.
 *
 * @param permissiveCount number of dependencies with permissive licenses
 * @param copyleftCount number of dependencies with copyleft licenses
 * @param unknownCount number of dependencies with unknown licenses
 * @param flaggedDependencies dependencies that may need license review
 * @param missingLicenses dependencies with no license information
 * @author Arvind Menon
 * @since 2.0.0
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LicenseFindings(
    int permissiveCount,
    int copyleftCount,
    int unknownCount,
    List<String> flaggedDependencies,
    List<String> missingLicenses) {

  /** Create a builder for LicenseFindings. */
  public static Builder builder() {
    return new Builder();
  }

  /** Create empty findings for when no license scanning was performed. */
  public static LicenseFindings empty() {
    return new LicenseFindings(0, 0, 0, List.of(), List.of());
  }

  /** Builder for constructing LicenseFindings. */
  public static final class Builder {
    private int permissive;
    private int copyleft;
    private int unknown;
    private final List<String> flagged = new ArrayList<>();
    private final List<String> missing = new ArrayList<>();

    /** Add a permissive license finding. */
    public Builder addPermissive() {
      permissive++;
      return this;
    }

    /**
     * Add a copyleft license finding.
     *
     * @param dependency dependency identifier
     * @param licenseName license name for flagging
     */
    public Builder addCopyleft(String dependency, String licenseName) {
      copyleft++;
      flagged.add(dependency + " (" + licenseName + ")");
      return this;
    }

    /** Add an unknown license finding. */
    public Builder addUnknown(String dependency) {
      unknown++;
      missing.add(dependency);
      return this;
    }

    /** Build the final LicenseFindings. */
    public LicenseFindings build() {
      return new LicenseFindings(
          permissive, copyleft, unknown, List.copyOf(flagged), List.copyOf(missing));
    }
  }

  /** Check if any licenses may need review (copyleft or unknown). */
  public boolean needsReview() {
    return copyleftCount > 0 || unknownCount > 0;
  }
}
