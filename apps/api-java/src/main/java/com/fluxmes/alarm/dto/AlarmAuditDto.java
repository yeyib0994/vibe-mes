package com.fluxmes.alarm.dto;

import com.fluxmes.alarm.domain.AlarmStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "报警审计条目（who/when/what/before/after，constitution C3）")
public record AlarmAuditDto(
        @Schema(description = "动作：ACK / RECOVER / CLOSE / CREATE") String action,
        AlarmStatus fromStatus,
        AlarmStatus toStatus,
        String operator,
        OffsetDateTime occurredAt,
        String note) {
}
