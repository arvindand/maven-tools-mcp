package com.arvindand.mcp.maven.config;

import static org.junit.jupiter.api.Assertions.assertFalse;

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
  void testContext7PropertiesDisabledByDefault() {
    System.out.println("Context7Properties.enabled = " + context7Properties.enabled());
    assertFalse(context7Properties.enabled(), "Context7 should be disabled by default");
  }
}
