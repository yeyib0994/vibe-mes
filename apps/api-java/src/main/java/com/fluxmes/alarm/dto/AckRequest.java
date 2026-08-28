package com.fluxmes.alarm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * 确认请求体。{@code operator} 由后端 RBAC 解析当前值班人员；前端原型阶段可显式传入（C4）。
 */
@Schema(description = "报警确认请求")
public record AckRequest(
        @Schema(description = "确认人（受 RBAC 约束：值班长/工艺员）")
        @Size(max = 64) String operator) {
}
