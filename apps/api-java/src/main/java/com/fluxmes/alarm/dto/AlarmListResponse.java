package com.fluxmes.alarm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 报警列表响应。{@code generatedAt} 用于前端时效降级（constitution P4 / spec NFR-1）；
 * {@code unackedCount} 保证侧栏 badge 与顶栏铃铛计数来源唯一（spec FR-7）。
 */
@Schema(description = "报警列表响应")
public record AlarmListResponse(
        OffsetDateTime generatedAt,
        @Schema(description = "未确认计数") long unackedCount,
        List<AlarmDto> alarms) {
}
