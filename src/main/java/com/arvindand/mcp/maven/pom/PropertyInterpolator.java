package com.arvindand.mcp.maven.pom;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Substitutes Maven-style {@code ${name}} placeholders against a property map.
 *
 * <p>Supports chained placeholders ({@code ${a}} → {@code ${b}} → {@code final}) up to a fixed
 * depth (10 passes) to guarantee termination on cyclic property definitions. Unknown
 * placeholders are left unchanged — the resolver surfaces them as warnings, not errors.
 *
 * <p>Edge cases deliberately not supported in Phase 6a: {@code ${project.version}},
 * {@code ${project.parent.version}}, {@code ${revision}}, environment variables. These are
 * future work; for now they appear as literal unresolved placeholders in the output.
 */
final class PropertyInterpolator {

  private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");
  private static final int MAX_PASSES = 10;

  private PropertyInterpolator() {}

  static String interpolate(String input, Map<String, String> properties) {
    if (input == null) {
      return null;
    }
    Objects.requireNonNull(properties, "properties must not be null");
    String current = input;
    for (int pass = 0; pass < MAX_PASSES; pass++) {
      Matcher matcher = PLACEHOLDER.matcher(current);
      if (!matcher.find()) {
        return current;
      }
      StringBuilder replaced = new StringBuilder();
      matcher.reset();
      boolean anySubstitution = false;
      while (matcher.find()) {
        String key = matcher.group(1);
        String value = properties.get(key);
        if (value != null) {
          matcher.appendReplacement(replaced, Matcher.quoteReplacement(value));
          anySubstitution = true;
        } else {
          matcher.appendReplacement(replaced, Matcher.quoteReplacement(matcher.group(0)));
        }
      }
      matcher.appendTail(replaced);
      String next = replaced.toString();
      // `next.equals(current)` is defensive: catches the pathological case where a property
      // maps to its own placeholder literal (e.g. a -> "${a}") and `anySubstitution` is true
      // but no real progress was made. MAX_PASSES would still bound it, but exiting now is
      // cleaner.
      if (!anySubstitution || next.equals(current)) {
        return next;
      }
      current = next;
    }
    return current;
  }
}
