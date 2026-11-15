package com.arvindand.mcp.maven.pom;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Objects;

/**
 * A root-POM property that shadows a property of the same name defined by an ancestor, where an
 * ancestor uses that property as a {@code <version>}. This is the standard way of overriding a
 * managed version (for example {@code <snakeyaml.version>} under Spring Boot).
 *
 * @param propertyName the property name, for example {@code snakeyaml.version}
 * @param pinnedValue the value declared by the root POM (interpolated)
 * @param inheritedValue the value of the nearest ancestor definition, interpolated in that
 *     ancestor's context; null when it could not be resolved
 * @param definedIn the nearest ancestor that defines the property
 * @param affects {@code groupId:artifactId} coordinates whose version the property controls, capped
 *     at {@link #MAX_AFFECTS}
 * @param affectedCount total number of distinct coordinates controlled by the property
 * @author Arvind Menon
 * @since 3.3.0
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PropertyOverride(
    String propertyName,
    String pinnedValue,
    String inheritedValue,
    String definedIn,
    List<String> affects,
    int affectedCount) {

  /** Maximum number of coordinates listed in {@link #affects()}. */
  public static final int MAX_AFFECTS = 20;

  public PropertyOverride {
    Objects.requireNonNull(propertyName, "propertyName must not be null");
    Objects.requireNonNull(affects, "affects must not be null");
    affects = List.copyOf(affects);
  }
}
