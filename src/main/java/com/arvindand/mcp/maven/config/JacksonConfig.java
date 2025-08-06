package com.arvindand.mcp.maven.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Jackson configuration for handling JDK8 types like Optional and Java 8 time types.
 *
 * @author Arvind Menon
 * @since 1.2.0
 */
@Configuration(proxyBeanMethods = false)
public class JacksonConfig {

  /**
   * Configures ObjectMapper with JDK8 and JSR310 module support for Optional and time types.
   *
   * @return configured ObjectMapper
   */
  @Bean
  @Primary
  public ObjectMapper objectMapper() {
    ObjectMapper mapper = new ObjectMapper();
    mapper.registerModule(new Jdk8Module());
    mapper.registerModule(new JavaTimeModule());
    return mapper;
  }
}
