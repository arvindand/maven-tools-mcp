package com.arvindand.mcp.maven.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.arvindand.mcp.maven.model.ToolResponse;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import tools.jackson.databind.PropertyNamingStrategies;

/**
 * Guards the native-image reflection hints. The key regression this protects against: Jackson 3
 * instantiates the {@code @JsonNaming} strategy class reflectively when serializing tool responses,
 * so {@link PropertyNamingStrategies.SnakeCaseStrategy}'s constructor must be reachable in the
 * native image. Without the hint, tool calls fail at runtime in the native image with "no default
 * (no arg) constructor" even though the JVM works fine.
 */
class NativeImageConfigurationTest {

  private RuntimeHints register() {
    RuntimeHints hints = new RuntimeHints();
    new NativeImageConfiguration.MavenRecordHints()
        .registerHints(hints, getClass().getClassLoader());
    return hints;
  }

  @Test
  void registersSnakeCaseStrategyConstructorForNativeReflection() {
    assertThat(
            RuntimeHintsPredicates.reflection()
                .onType(PropertyNamingStrategies.SnakeCaseStrategy.class)
                .withMemberCategory(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS))
        .accepts(register());
  }

  @Test
  void registersToolResponseSuccessForReflection() {
    assertThat(RuntimeHintsPredicates.reflection().onType(ToolResponse.Success.class))
        .accepts(register());
  }
}
