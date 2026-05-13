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
    // Cycle: a -> b -> a -> ...
    // With MAX_PASSES = 10 starting from "${a}", each pass alternates the value;
    // termination value is deterministic given the cap. Either ${a} or ${b} is
    // acceptable — the contract is "we stop, not infinite loop". We use isIn to
    // tolerate future tweaks to MAX_PASSES without rewriting this test.
    var props = java.util.Map.of("a", "${b}", "b", "${a}");
    String result = PropertyInterpolator.interpolate("${a}", props);
    assertThat(result).isIn("${a}", "${b}");
  }

  @Test
  void returnsNullForNullInput() {
    assertThat(PropertyInterpolator.interpolate(null, Map.of())).isNull();
  }
}
