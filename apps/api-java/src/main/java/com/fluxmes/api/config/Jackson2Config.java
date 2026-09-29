package com.fluxmes.api.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Jackson 2 ObjectMapper 装配。
 *
 * Spring Boot 4 默认使用 Jackson 3（tools.jackson.*），自动装配的 ObjectMapper bean 是
 * Jackson 3 类型；而本项目既有代码（FixtureStore / AuditService / BatchService /
 * QualityService / 各 Seeder 等）注入的是 Jackson 2 的 com.fasterxml.jackson.databind.ObjectMapper。
 * 这里显式注册一个 Jackson 2 实例并标记 @Primary，保证两类依赖都能被满足。
 */
@Configuration
public class Jackson2Config {

  @Bean
  @Primary
  ObjectMapper jackson2ObjectMapper() {
    return new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
  }
}
