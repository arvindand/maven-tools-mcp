package com.arvindand.mcp.maven.pom;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Substitutes Maven-style {@code ${name}} placeholders against a property map.
 *
 * <p>Supports chained placeholders ({@code ${a}} → {@code ${b}} → {@code final}) up to a fixed
 * depth and allocation budgets to guarantee termination on cyclic property definitions. Unknown
 * placeholders are left unchanged — the resolver surfaces them as warnings, not errors.
 *
 * <p>This interpolator is intentionally dumb about Maven semantics — it does plain string
 * substitution. The resolver pre-seeds well-known {@code project.*} properties (so {@code
 * ${project.version}} resolves) before invoking this class; placeholders the resolver does not
 * pre-seed (e.g., {@code ${revision}}, environment variables) appear as literal unresolved
 * placeholders in the output.
 *
 * @author Arvind Menon
 * @since 3.0.0
 */
final class PropertyInterpolator {

  private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");

  private PropertyInterpolator() {}

  static String interpolate(String input, Map<String, String> properties) {
    if (input == null) {
      return null;
    }
    Objects.requireNonNull(properties, "properties must not be null");
    return expand(input, properties, new java.util.HashSet<>(), 0, 16_384);
  }

  /** Expands with explicit cycle, depth and allocation bounds before appending each fragment. */
  static String expand(
      String input,
      Map<String, String> properties,
      java.util.Set<String> visiting,
      int depth,
      int limit) {
    return expand(input, properties::get, visiting, depth, limit);
  }

  /** Applies the same allocation budget to values obtained from Maven's own value sources. */
  static String expand(
      String input,
      java.util.function.UnaryOperator<String> lookup,
      java.util.Set<String> visiting,
      int depth,
      int limit) {
    if (input.length() > limit || depth > 32) {
      throw new IllegalArgumentException(
          "POM property expansion exceeds the permitted size or depth");
    }
    Matcher matcher = PLACEHOLDER.matcher(input);
    StringBuilder result = new StringBuilder();
    int position = 0;
    while (matcher.find()) {
      String key = matcher.group(1);
      String value = lookup.apply(key);
      String replacement = matcher.group();
      if (value != null && visiting.add(key)) {
        replacement = expand(value, lookup, visiting, depth + 1, limit);
        visiting.remove(key);
      }
      append(result, input.substring(position, matcher.start()), limit);
      append(result, replacement, limit);
      position = matcher.end();
    }
    append(result, input.substring(position), limit);
    return result.toString();
  }

  private static void append(StringBuilder output, String value, int limit) {
    if (value.length() > limit - output.length()) {
      throw new IllegalArgumentException("POM property expansion exceeds the permitted size");
    }
    output.append(value);
  }
}
