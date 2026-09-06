package com.arvindand.mcp.maven.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.arvindand.mcp.maven.model.McpError;
import com.arvindand.mcp.maven.model.ToolResponse;
import com.arvindand.mcp.maven.pom.ManagedDeclaration;
import com.arvindand.mcp.maven.pom.PluginDependencyDeclaration;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.PropertyNamingStrategies;

/**
 * Guards the native-image reflection hints. The key regression this protects against: Jackson 3
 * instantiates the {@code @JsonNaming} strategy class reflectively when serializing tool responses,
 * so {@link PropertyNamingStrategies.SnakeCaseStrategy}'s constructor must be reachable in the
 * native image. Without the hint, tool calls fail at runtime in the native image with "no default
 * (no arg) constructor" even though the JVM works fine.
 *
 * @author Arvind Menon
 */
class NativeImageConfigurationTest {

  private RuntimeHints register() {
    RuntimeHints hints = new RuntimeHints();
    new NativeImageConfiguration.MavenRecordHints()
        .registerHints(hints, getClass().getClassLoader());
    return hints;
  }

  @Test
  void registersNestedErrorRecordForSerialization() {
    assertThat(
            RuntimeHintsPredicates.reflection()
                .onType(McpError.class)
                .withMemberCategories(
                    MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
                    MemberCategory.INVOKE_PUBLIC_METHODS))
        .accepts(register());
  }

  @Test
  void registersFactoriesActuallySelectedByConfiguredCaches() {
    CacheManager manager = new CacheConfig().cacheManager();
    RuntimeHints hints = register();
    for (String name : manager.getCacheNames()) {
      Cache cache = manager.getCache(name);
      assertThat(cache).isNotNull();
      Object implementation = ReflectionTestUtils.getField(cache.getNativeCache(), "cache");
      assertThat(implementation).isNotNull();
      Object nodeFactory = ReflectionTestUtils.getField(implementation, "nodeFactory");
      assertThat(nodeFactory).isNotNull();
      for (Object factory : List.of(implementation, nodeFactory)) {
        assertThat(
                RuntimeHintsPredicates.reflection()
                    .onType(factory.getClass())
                    .withMemberCategories(
                        MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                        MemberCategory.ACCESS_DECLARED_FIELDS))
            .as("%s factory %s", name, factory.getClass().getName())
            .accepts(hints);
      }
    }
  }

  @Test
  void registersExceptionTypesNamedInResilienceConfiguration() throws ClassNotFoundException {
    YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
    yaml.setResources(new ClassPathResource("application.yaml"));
    Properties properties = yaml.getObject();
    assertThat(properties).isNotNull();
    List<String> exceptionKeys =
        properties.stringPropertyNames().stream()
            .filter(key -> key.startsWith("resilience4j.") && key.contains("-exceptions["))
            .toList();
    assertThat(exceptionKeys).isNotEmpty();
    RuntimeHints hints = register();
    for (String key : exceptionKeys) {
      Class<?> exceptionType = Class.forName(properties.getProperty(key));
      assertThat(RuntimeHintsPredicates.reflection().onType(exceptionType)).as(key).accepts(hints);
    }
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

  @Test
  void registersManagedDeclarationForReflection() {
    assertThat(RuntimeHintsPredicates.reflection().onType(ManagedDeclaration.class))
        .accepts(register());
  }

  @Test
  void registersPluginDependencyDeclarationForReflection() {
    assertThat(RuntimeHintsPredicates.reflection().onType(PluginDependencyDeclaration.class))
        .accepts(register());
  }
}
