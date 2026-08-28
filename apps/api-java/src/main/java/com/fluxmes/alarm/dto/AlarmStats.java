package com.fluxmes.alarm.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "报警中心 KPI 统计")
public record AlarmStats(
        @Schema(description = "今日报警总数") long todayTotal,
        @Schema(description = "活跃报警（未确认 + 已确认未恢复）") long active,
        @Schema(description = "未确认报警") long unacked,
        @Schema(description = "未确认严重报警") long unackedCritical,
        @Schema(description = "平均响应时长（分钟，触发→确认）") Double avgResponseMinutes,
        @Schema(description = "本班确认率（%）") Integer ackRatePercent) {
}
