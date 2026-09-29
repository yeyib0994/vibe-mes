package com.fluxmes.api.weighing;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * G3 · 配料称量与容差校验端点。
 * 称量登记须持「配料称量」岗位资质（G2 门禁），超差自动建偏差单与报警。
 */
@RestController
@RequestMapping("/api/weighing")
public class WeighingController {

  private final WeighingService service;

  public WeighingController(WeighingService service) {
    this.service = service;
  }

  /** GET /api/weighing/tasks —— 称量任务列表（?batchId=&status=OPEN|BLOCKED|DONE）。 */
  @GetMapping("/tasks")
  public Object tasks(@RequestParam(required = false) String batchId,
      @RequestParam(required = false) String status) {
    return service.tasks(batchId, status);
  }

  /** POST /api/weighing/tasks —— 创建称量任务（目标量 + 容差百分比）。 */
  @PostMapping("/tasks")
  public Map<String, Object> createTask(@RequestBody(required = false) Map<String, Object> body) {
    return service.createTask(body == null ? Map.of() : body);
  }

  /** GET /api/weighing/tasks/{id}/items —— 任务称量明细。 */
  @GetMapping("/tasks/{id}/items")
  public Object itemsOf(@PathVariable String id) {
    return service.itemsOf(id);
  }

  /** POST /api/weighing/tasks/{id}/weigh —— 登记实际称量并做容差判定。 */
  @PostMapping("/tasks/{id}/weigh")
  public Map<String, Object> weigh(@PathVariable String id,
      @RequestBody(required = false) Map<String, Object> body) {
    return service.weigh(id, body == null ? Map.of() : body);
  }

  /** POST /api/weighing/items/{id}/review —— 超差复核（质检员及以上，非称量人本人）。 */
  @PostMapping("/items/{id}/review")
  public Map<String, Object> review(@PathVariable Long id) {
    return service.review(id);
  }

  /** GET /api/weighing/summary —— 称量合规看板（合格率 / 超差 / 待复核）。 */
  @GetMapping("/summary")
  public Map<String, Object> summary() {
    return service.summary();
  }
}
