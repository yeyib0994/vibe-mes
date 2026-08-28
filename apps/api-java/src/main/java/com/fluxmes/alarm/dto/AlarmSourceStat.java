package com.fluxmes.alarm.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "高频报警源统计（spec FR-6）")
public record AlarmSourceStat(
        @Schema(description = "来源设备编码") String source,
        @Schema(description = "近 N 日触发次数") long count) {
}
