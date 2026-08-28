package com.fluxmes.alarm.dto;

import com.fluxmes.alarm.domain.AlarmLevel;
import com.fluxmes.alarm.domain.AlarmStatus;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 实时推送事件（SSE）。前端收到后失效并回查相关查询，实现「触发→可见 ≤ 刷新周期」（NFR-1）。
 */
@Schema(description = "报警实时事件")
public record AlarmEvent(
        @Schema(description = "事件类型：created / acked / recovered") String type,
        @Schema(description = "报警编号") String id,
        AlarmLevel level,
        AlarmStatus status) {

    public static AlarmEvent created(AlarmDto a) {
        return new AlarmEvent("created", a.id(), a.level(), a.status());
    }

    public static AlarmEvent acked(AlarmDto a) {
        return new AlarmEvent("acked", a.id(), a.level(), a.status());
    }

    public static AlarmEvent recovered(AlarmDto a) {
        return new AlarmEvent("recovered", a.id(), a.level(), a.status());
    }
}
