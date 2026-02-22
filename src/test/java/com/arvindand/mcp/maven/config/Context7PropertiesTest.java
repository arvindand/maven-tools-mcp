package com.arvindand.mcp.maven.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Test to verify Context7Properties binding.
 *
 * @author Arvind Menon
 * @since 1.2.0
 */
@SpringBootTest
@ActiveProfiles("test")
class Context7PropertiesTest {

  @Autowired private Context7Properties context7Properties;

  @Test
  void testContext7PropertiesEnabledByDefault() {
    System.out.println("Context7Properties.enabled = " + context7Properties.enabled());
    assertTrue(context7Properties.enabled(), "Context7 should be enabled by default");
  }

  @Test
  void testHasApiKeyFalseWhenNullOrBlank() {
    assertFalse(
        new Context7Properties(true, null).hasApiKey(), "null should be treated as no API key");
    assertFalse(
        new Context7Properties(true, "").hasApiKey(),
        "empty string should be treated as no API key");
    assertFalse(
        new Context7Properties(true, "   ").hasApiKey(),
        "whitespace-only string should be treated as no API key");
  }

  @Test
  void testHasApiKeyTrueWhenTextPresent() {
    assertTrue(
        new Context7Properties(true, "abc123").hasApiKey(), "non-empty text should be an API key");
  }
}
