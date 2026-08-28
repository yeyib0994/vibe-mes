package com.fluxmes.alarm.web;

import com.fluxmes.alarm.dto.AckRequest;
import com.fluxmes.alarm.dto.AlarmDto;
import com.fluxmes.alarm.dto.AlarmEvent;
import com.fluxmes.alarm.dto.AlarmListResponse;
import com.fluxmes.alarm.dto.AlarmSourceStat;
import com.fluxmes.alarm.dto.AlarmStats;
import com.fluxmes.alarm.dto.AlarmTrendPoint;
import com.fluxmes.alarm.service.AlarmNotifier;
import com.fluxmes.alarm.service.AlarmService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Tag(name = "报警中心", description = "报警列表 / 确认 / 恢复 / 趋势 / 高频源 / 统计 / 实时推送")
@RestController
@RequestMapping("/api/alarms")
public class AlarmController {

    private final AlarmService service;
    private final AlarmNotifier notifier;

    public AlarmController(AlarmService service, AlarmNotifier notifier) {
        this.service = service;
        this.notifier = notifier;
    }

    @Operation(summary = "报警列表", description = "返回全部报警（按触发时间倒序）、生成时间与未确认计数")
    @GetMapping
    public AlarmListResponse list() {
        return service.list();
    }

    @Operation(summary = "未确认实时计数", description = "侧栏 badge 与顶栏铃铛的唯一计数来源（FR-7）")
    @GetMapping("/unacked-count")
    public Map<String, Long> unackedCount() {
        return Map.of("unacked", service.unackedCount());
    }

    @Operation(summary = "确认报警", description = "状态机 unacked→acked，记录确认人/时间并写审计（FR-3/FR-10）")
    @PostMapping("/{id}/ack")
    public AlarmDto ack(
            @PathVariable String id,
            @Parameter(description = "确认人") @Valid @RequestBody(required = false) AckRequest request) {
        String operator = request != null && request.operator() != null && !request.operator().isBlank()
                ? request.operator()
                : "未知操作员";
        AlarmDto dto = service.ack(id, operator);
        notifier.publish(AlarmEvent.acked(dto));
        return dto;
    }

    @Operation(summary = "恢复报警", description = "状态机 →recovered，记录恢复时间并写审计；auto=true 为系统自动恢复（FR-4）")
    @PostMapping("/{id}/recover")
    public AlarmDto recover(
            @PathVariable String id,
            @Parameter(description = "是否系统自动恢复") @RequestParam(defaultValue = "false") boolean auto,
            @Parameter(description = "恢复操作人") @Valid @RequestBody(required = false) AckRequest request) {
        String operator = request != null && request.operator() != null && !request.operator().isBlank()
                ? request.operator()
                : "未知操作员";
        AlarmDto dto = service.recover(id, operator, auto);
        notifier.publish(AlarmEvent.recovered(dto));
        return dto;
    }

    @Operation(summary = "报警趋势", description = "最近 hours 小时按级别堆叠（FR-5）")
    @GetMapping("/trend")
    public List<AlarmTrendPoint> trend(
            @RequestParam(defaultValue = "8") @Parameter(description = "回溯小时数") int hours) {
        return service.trend(hours);
    }

    @Operation(summary = "高频报警源", description = "近 days 日按触发次数降序（FR-6）")
    @GetMapping("/top-sources")
    public List<AlarmSourceStat> topSources(
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(defaultValue = "5") int limit) {
        return service.topSources(days, limit);
    }

    @Operation(summary = "报警 KPI 统计")
    @GetMapping("/stats")
    public AlarmStats stats() {
        return service.stats();
    }

    @Operation(summary = "实时报警流", description = "SSE 长连接，推送 created/acked/recovered 事件（NFR-1）")
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return notifier.subscribe();
    }
}
