package com.arvindand.mcp.maven.pom;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class PropertyInterpolatorTest {

  @Test
  void returnsInputUnchangedWhenNoPlaceholder() {
    assertThat(PropertyInterpolator.interpolate("2.19.2", Map.of())).isEqualTo("2.19.2");
  }

  @Test
  void substitutesSinglePlaceholder() {
    assertThat(PropertyInterpolator.interpolate("${jackson.version}", Map.of("jackson.version", "2.19.2")))
        .isEqualTo("2.19.2");
  }

  @Test
  void resolvesChainedPlaceholders() {
    var props = Map.of("a", "${b}", "b", "${c}", "c", "final");
    assertThat(PropertyInterpolator.interpolate("${a}", props)).isEqualTo("final");
  }

  @Test
  void leavesUnknownPlaceholderUntouched() {
    assertThat(PropertyInterpolator.interpolate("${missing}", Map.of())).isEqualTo("${missing}");
  }

  @Test
  void substitutesMultiplePlaceholdersInOneString() {
    var props = Map.of("a", "1", "b", "2");
    assertThat(PropertyInterpolator.interpolate("${a}.${b}", props)).isEqualTo("1.2");
  }

  @Test
  void capsRecursionAtTenPasses() {
    // a cycle: a -> b -> a -> ...
    var props = Map.of("a", "${b}", "b", "${a}");
    // After 10 passes we stop. The string may still contain ${a} or ${b}.
    String result = PropertyInterpolator.interpolate("${a}", props);
    assertThat(result).matches("\\$\\{[ab]}");
  }

  @Test
  void returnsNullForNullInput() {
    assertThat(PropertyInterpolator.interpolate(null, Map.of())).isNull();
  }
}
