package com.fluxmes.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * FluxMES 后端入口（Phase 1 初步实现）。
 * 数据源为 classpath:fixtures/mes.json（由 apps/api-java/scripts/export-fixtures.mjs
 * 从前端示例数据 fluxmes/src/data/mes.js 导出），接口契约与 fluxmes/src/api/*.ts 对齐。
 */
@SpringBootApplication
public class Application {
  public static void main(String[] args) {
    SpringApplication.run(Application.class, args);
  }
}
