package com.arvindand.mcp.maven.pom;

import com.arvindand.mcp.maven.model.MavenCoordinate;
import java.util.Objects;

/**
 * A losing candidate from {@code <dependencyManagement>} arbitration: another parent POM or
 * imported BOM that would have managed the same coordinate, but lost to a closer ancestor (or to a
 * BOM declared earlier in the same {@code <dependencyManagement>} block).
 *
 * <p>Surfaced on {@link EffectiveDependency#conflicts()} for any dependency whose managed version
 * came from a source that had to outcompete other candidates — typically because the importing POM
 * pulls in multiple BOMs that disagree on a shared transitive (e.g., Spring Boot + Spring Cloud +
 * Jackson BOM all managing {@code jackson-databind}). The resolver does not try to reason about
 * which candidate is "right"; it just reports them so the caller (or an LLM) can decide whether to
 * pin the version explicitly.
 *
 * @param version the version this candidate would have supplied (already interpolated)
 * @param managedBy the BOM or parent coordinate the candidate came from
 * @author Arvind Menon
 * @since 3.0.0
 */
public record ManagedAlternative(String version, MavenCoordinate managedBy) {

  public ManagedAlternative {
    if (version == null || version.isBlank()) {
      throw new IllegalArgumentException("version must not be blank");
    }
    Objects.requireNonNull(managedBy, "managedBy must not be null");
  }
}
