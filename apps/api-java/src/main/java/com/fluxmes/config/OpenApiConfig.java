package com.fluxmes.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI fluxMesOpenApi() {
        return new OpenAPI().info(new Info()
                .title("FluxMES · 报警中心 API")
                .version("v1.0")
                .description("工艺与设备报警统一接入 · 分级响应 · 确认审计 · 趋势与高频源。数据保留 ≥ 3 年（C6）。")
                .license(new License().name("Internal")));
    }
}
