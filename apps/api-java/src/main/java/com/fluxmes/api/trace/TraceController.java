package com.fluxmes.api.trace;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 追溯查询端点（契约 = fluxmes/src/api/trace.ts）。
 *
 * H3 变更：链路由真实业务数据（batch_input / material_lot / batch_step / batch_genealogy）
 * 组装，替代此前的 fixture 硬编码示例链；新增逆向追溯、影响面、完整性校验、
 * 查询审计与报表导出。
 */
@RestController
@RequestMapping("/api/trace")
public class TraceController {

  private final TraceService service;

  public TraceController(TraceService service) {
    this.service = service;
  }

  /** GET /api/trace/targets —— 可追溯成品批次候选。 */
  @GetMapping("/targets")
  public Map<String, Object> targets() {
    return Map.of("generatedAt", java.time.Instant.now().toString(),
        "batches", service.targets());
  }

  /** GET /api/trace/chain/{batchId} —— 正向 / 逆向链路 + 横向关联 + 完整性。 */
  @GetMapping("/chain/{batchId}")
  public Map<String, Object> chain(@PathVariable String batchId) {
    return service.chain(batchId);
  }

  /** GET /api/trace/backward?lotNo= —— 逆向追溯：消耗该原料的成品批次（FR-3）。 */
  @GetMapping("/backward")
  public Map<String, Object> backward(@RequestParam String lotNo) {
    return service.backward(lotNo);
  }

  /** GET /api/trace/impact?lotNo= —— 影响面分析（FR-4）。 */
  @GetMapping("/impact")
  public Map<String, Object> impact(@RequestParam String lotNo) {
    return service.impact(lotNo);
  }

  /** GET /api/trace/{batchId}/completeness —— 档案完整性与缺失项（FR-8）。 */
  @GetMapping("/{batchId}/completeness")
  public Map<String, Object> completeness(@PathVariable String batchId) {
    return service.completeness(batchId);
  }

  /** GET /api/trace/{batchId}/export —— 追溯报表导出（FR-6，含生成人与时间水印）。 */
  @GetMapping("/{batchId}/export")
  public Map<String, Object> exportReport(@PathVariable String batchId) {
    return service.exportReport(batchId);
  }

  /** GET /api/trace/logs —— 追溯查询审计流水（FR-7）。 */
  @GetMapping("/logs")
  public List<Map<String, Object>> logs(@RequestParam(defaultValue = "50") int limit) {
    return service.queryLogs(limit);
  }

  /** POST /api/trace/genealogy —— 登记中间品流转关系。 */
  @PostMapping("/genealogy")
  public Map<String, Object> link(@RequestBody Map<String, Object> body) {
    return service.link(body);
  }
}
