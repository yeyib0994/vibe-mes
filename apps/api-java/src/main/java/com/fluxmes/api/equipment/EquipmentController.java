package com.fluxmes.api.equipment;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 设备监控端点（契约 = fluxmes/src/api/equipment.ts）。
 *
 * H1 变更：台账主数据由 PostgreSQL 承载（equipment / equipment_event / maintenance_order），
 * 实时参数趋势仍由 FixtureStore 提供并在服务层缝合，前端契约保持不变。
 */
@RestController
@RequestMapping("/api/equipment")
public class EquipmentController {

  private final EquipmentService service;

  public EquipmentController(EquipmentService service) {
    this.service = service;
  }

  /** GET /api/equipment —— 集群总览：状态以 PG 为准，实时参数与 OEE 三因子由 fixture 提供。 */
  @GetMapping
  public Map<String, Object> fleet(@RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword) {
    return service.fleet(status, keyword);
  }

  /** GET /api/equipment/{code} —— 单设备详情：趋势 + 报警 + 在制批次 + 维护与校准 + 状态事件。 */
  @GetMapping("/{code}")
  public Map<String, Object> detail(@PathVariable String code) {
    return service.detail(code);
  }

  /** GET /api/equipment/alerts —— 保养到期（默认 7 天内）与校准超期清单。 */
  @GetMapping("/alerts")
  public Map<String, Object> alerts(@RequestParam(defaultValue = "7") int withinDays) {
    return service.alerts(withinDays);
  }

  /** GET /api/equipment/available —— 批次建单可用设备（启用 + 校准有效，FR-10）。 */
  @GetMapping("/available")
  public List<Map<String, Object>> available(@RequestParam(required = false) String line) {
    return service.available(line);
  }

  /** GET /api/equipment/maintenance-orders —— 维护工单列表。 */
  @GetMapping("/maintenance-orders")
  public List<Map<String, Object>> orders(@RequestParam(required = false) String equipmentCode,
      @RequestParam(required = false) String status) {
    return service.maintenanceOrders(equipmentCode, status);
  }

  /** POST /api/equipment/maintenance-orders —— 值班长及以上创建维护工单（FR-6）。 */
  @PostMapping("/maintenance-orders")
  public Map<String, Object> createOrder(@RequestBody Map<String, Object> body) {
    return service.createOrder(body);
  }

  /** POST /api/equipment/maintenance-orders/{id}/done —— 完成工单，顺延保养与校准周期（FR-7）。 */
  @PostMapping("/maintenance-orders/{id}/done")
  public Map<String, Object> completeOrder(@PathVariable String id,
      @RequestBody(required = false) Map<String, Object> body) {
    return service.completeOrder(id, body == null ? Map.of() : body);
  }

  /** POST /api/equipment/{code}/status —— 状态变更，写事件流水与审计（FR-5）。 */
  @PostMapping("/{code}/status")
  public Map<String, Object> changeStatus(@PathVariable String code,
      @RequestBody Map<String, Object> body) {
    if (body == null || body.get("toStatus") == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少 toStatus");
    }
    return service.changeStatus(code, body);
  }

  /** GET /api/equipment/{code}/oee —— OEE 三因子分解（可用率取自状态事件停机时长，FR-9）。 */
  @GetMapping("/{code}/oee")
  public Map<String, Object> oee(@PathVariable String code) {
    return service.oee(code);
  }
}
