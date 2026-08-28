package com.fluxmes.alarm.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "报警趋势点（按小时、按级别堆叠，spec FR-5）")
public record AlarmTrendPoint(
        @Schema(description = "小时标签 HH:00") String t,
        long critical,
        long major,
        long minor) {
}
