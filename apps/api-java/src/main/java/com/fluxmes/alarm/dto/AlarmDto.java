package com.fluxmes.alarm.dto;

import com.fluxmes.alarm.domain.Alarm;
import com.fluxmes.alarm.domain.AlarmLevel;
import com.fluxmes.alarm.domain.AlarmStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 报警对外契约（plan 第 2 节）。前端 {@code src/api/alarms.js} 与 {@code Alarms.jsx} 直接消费该结构。
 */
@Schema(description = "报警记录")
public record AlarmDto(
        @Schema(description = "业务报警编号 A-YYMMDD-NNN") String id,
        @Schema(description = "触发时间 ISO-8601") OffsetDateTime triggeredAt,
        @Schema(description = "展示用 HH:mm（本地时区）") String time,
        AlarmLevel level,
        @Schema(description = "来源设备编码") String source,
        String content,
        @Schema(description = "触发值") String value,
        @Schema(description = "阈值") String threshold,
        AlarmStatus status,
        String ackBy,
        OffsetDateTime ackAt,
        OffsetDateTime recoveredAt,
        @Schema(description = "关联批次（若有）") String relatedBatch,
        List<AlarmAuditDto> audit) {

    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    public static AlarmDto from(Alarm a, ZoneId zone) {
        return new AlarmDto(
                a.getId(),
                a.getTriggeredAt(),
                a.getTriggeredAt().atZoneSameInstant(zone).format(HHMM),
                a.getLevel(),
                a.getSource(),
                a.getContent(),
                a.getValue(),
                a.getThreshold(),
                a.getStatus(),
                a.getAckBy(),
                a.getAckAt(),
                a.getRecoveredAt(),
                a.getRelatedBatch(),
                a.getAudit().stream()
                        .map(x -> new AlarmAuditDto(
                                x.getAction(), x.getFromStatus(), x.getToStatus(),
                                x.getOperator(), x.getOccurredAt(), x.getNote()))
                        .toList());
    }
}
