package com.fluxmes.api.alarm;

import com.fluxmes.api.alarm.AlarmService.AckResult;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 报警中心端点（契约 = fluxmes/src/api/alarms.ts）。 */
@RestController
@RequestMapping("/api/alarms")
public class AlarmController {

  private final AlarmService service;
  private final AlarmSlaPolicyService slaPolicy;

  public AlarmController(AlarmService service, AlarmSlaPolicyService slaPolicy) {
    this.service = service;
    this.slaPolicy = slaPolicy;
  }

  /* ---- G1 · SLA 政策可配置 ---- */

  /** GET /api/alarms/sla/policy —— 当前各级别响应时限（0 = 豁免考核）。 */
  @GetMapping("/sla/policy")
  public Object slaPolicy() {
    return slaPolicy.list();
  }

  /** PUT /api/alarms/sla/policy/{level} —— 调整响应时限（值班长及以上）。 */
  @PutMapping("/sla/policy/{level}")
  public Map<String, Object> updateSlaPolicy(@PathVariable String level,
      @RequestBody(required = false) Map<String, Object> body) {
    Map<String, Object> b = body == null ? Map.of() : body;
    Integer minutes = null;
    if (b.get("minutes") != null && !String.valueOf(b.get("minutes")).isBlank()) {
      minutes = Integer.parseInt(String.valueOf(b.get("minutes")).trim());
    }
    Boolean enabled = b.get("enabled") == null ? null : Boolean.valueOf(String.valueOf(b.get("enabled")));
    String note = b.get("note") == null ? null : String.valueOf(b.get("note"));
    return slaPolicy.update(level, minutes, enabled, note);
  }

  @GetMapping
  public Map<String, Object> list() {
    return service.list();
  }

  @GetMapping("/unacked-count")
  public Map<String, Object> unackedCount() {
    return service.unackedCount();
  }

  @GetMapping("/stats")
  public Map<String, Object> stats() {
    return service.stats();
  }

  @GetMapping("/trend")
  public Object trend(@RequestParam(defaultValue = "8") int hours) {
    return service.trend(hours);
  }

  @GetMapping("/top-sources")
  public Object topSources(@RequestParam(defaultValue = "7") int days,
      @RequestParam(defaultValue = "5") int limit) {
    return service.topSources(days, limit);
  }

  @PostMapping("/{id}/ack")
  public Map<String, Object> ack(@PathVariable String id,
      @RequestBody(required = false) Map<String, Object> body) {
    String operator = body == null ? null : String.valueOf(body.getOrDefault("operator", ""));
    AckResult result = service.ack(id, operator);
    if (result.alarm() == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "报警不存在: " + id);
    return result.alarm();
  }

  @PostMapping("/{id}/recover")
  public Map<String, Object> recover(@PathVariable String id,
      @RequestParam(name = "auto", defaultValue = "false") boolean auto,
      @RequestBody(required = false) Map<String, Object> body) {
    String operator = body == null ? null : String.valueOf(body.getOrDefault("operator", ""));
    AckResult result = service.recover(id, operator, auto);
    if (result.alarm() == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "报警不存在: " + id);
    return result.alarm();
  }

  /* ---- D1 · 抑制规则 ---- */

  /** GET /api/alarms/suppressions —— 抑制规则列表（防报警洪水）。 */
  @GetMapping("/suppressions")
  public Object listSuppressions() {
    return service.listSuppressions();
  }

  /** POST /api/alarms/suppressions —— 新建抑制规则（值班长+）。 */
  @PostMapping("/suppressions")
  public Map<String, Object> createSuppression(@RequestBody(required = false) Map<String, Object> body) {
    return service.createSuppression(body == null ? Map.of() : body);
  }

  /** DELETE /api/alarms/suppressions/{id} —— 删除抑制规则（值班长+）。 */
  @DeleteMapping("/suppressions/{id}")
  public Map<String, Object> deleteSuppression(@PathVariable Long id) {
    return service.deleteSuppression(id);
  }

  /** POST /api/alarms/sla/sweep —— 手动触发一次 SLA 巡检（值班长+，便于演示与联调）。 */
  @PostMapping("/sla/sweep")
  public Map<String, Object> sweepSla() {
    service.sweepSla();
    return Map.of("swept", true, "at", java.time.Instant.now().toString());
  }

  /** SSE 事件流：新报警/确认/恢复/升级即推送 event:name=alarm（前端 EventSource 订阅）。 */
  @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream() {
    return service.stream();
  }
}
