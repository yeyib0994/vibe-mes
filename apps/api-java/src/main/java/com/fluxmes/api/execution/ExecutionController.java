package com.fluxmes.api.execution;

import com.fluxmes.api.common.CurrentUser;
import com.fluxmes.api.common.Roles;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Phase I · 生产执行端点（契约见 specs/production-execution/plan.md §3）。
 *
 * <p>RBAC（C4）：查询=登录；建单/派工/撤销派工/完工=值班长+；开工/报工/停机录入=工艺员+；
 * 关闭工单=管理员。
 */
@RestController
@RequestMapping("/api/execution")
public class ExecutionController {

  private final ExecutionService service;
  private final OeeService oee;

  public ExecutionController(ExecutionService service, OeeService oee) {
    this.service = service;
    this.oee = oee;
  }

  /* ---------------- 工单 ---------------- */

  @GetMapping("/orders")
  public Map<String, Object> orders(@RequestParam(required = false) String batchId,
      @RequestParam(required = false) String line,
      @RequestParam(required = false) String site,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String shift,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) Long page,
      @RequestParam(required = false) Long size) {
    return service.listOrders(batchId, line, site, status, shift, from, to, page, size);
  }

  @PostMapping("/orders")
  public Map<String, Object> createOrder(@RequestBody(required = false) Map<String, Object> body) {
    requireRole(Roles.SUPERVISOR, "新建工单需要值班长及以上角色");
    return service.createOrder(body == null ? Map.of() : body);
  }

  @GetMapping("/orders/{id}")
  public Map<String, Object> order(@PathVariable String id) {
    return service.orderDetail(id);
  }

  /** POST /api/execution/orders/{id}/release —— CREATED → RELEASED（值班长+）。 */
  @PostMapping("/orders/{id}/release")
  public Map<String, Object> release(@PathVariable String id) {
    requireRole(Roles.SUPERVISOR, "下达工单需要值班长及以上角色");
    return service.transition(id, "RELEASED");
  }

  /** POST /api/execution/orders/{id}/start —— RELEASED → RUNNING（工艺员+）。 */
  @PostMapping("/orders/{id}/start")
  public Map<String, Object> start(@PathVariable String id) {
    requireRole(Roles.OPERATOR, "开工需要工艺员及以上角色");
    return service.transition(id, "RUNNING");
  }

  /** POST /api/execution/orders/{id}/finish —— RUNNING → FINISHED（值班长+）。 */
  @PostMapping("/orders/{id}/finish")
  public Map<String, Object> finish(@PathVariable String id) {
    requireRole(Roles.SUPERVISOR, "完工需要值班长及以上角色");
    return service.transition(id, "FINISHED");
  }

  /** POST /api/execution/orders/{id}/close —— FINISHED → CLOSED（管理员）。 */
  @PostMapping("/orders/{id}/close")
  public Map<String, Object> close(@PathVariable String id) {
    requireRole(Roles.ADMIN, "关闭工单需要管理员角色");
    return service.transition(id, "CLOSED");
  }

  /* ---------------- 派工 ---------------- */

  @PostMapping("/orders/{id}/dispatch")
  public Map<String, Object> dispatch(@PathVariable String id,
      @RequestBody(required = false) Map<String, Object> body) {
    requireRole(Roles.SUPERVISOR, "派工需要值班长及以上角色");
    return service.dispatch(id, body == null ? Map.of() : body);
  }

  @DeleteMapping("/orders/{id}/dispatch/{aid}")
  public Map<String, Object> revoke(@PathVariable String id, @PathVariable Long aid,
      @RequestParam(required = false) String reason) {
    requireRole(Roles.SUPERVISOR, "撤销派工需要值班长及以上角色");
    return service.revokeDispatch(id, aid, reason);
  }

  /* ---------------- 报工 ---------------- */

  @GetMapping("/orders/{id}/reports")
  public Object reports(@PathVariable String id) {
    return service.reportsOfPublic(id);
  }

  @PostMapping("/orders/{id}/reports")
  public Map<String, Object> report(@PathVariable String id,
      @RequestBody(required = false) Map<String, Object> body) {
    requireRole(Roles.OPERATOR, "工序报工需要工艺员及以上角色");
    return service.report(id, body == null ? Map.of() : body);
  }

  /* ---------------- 停机 ---------------- */

  @GetMapping("/downtime")
  public Object downtime(@RequestParam(required = false) String equipment,
      @RequestParam(required = false) String orderId,
      @RequestParam(required = false) String site,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to) {
    return service.downtimeList(equipment, orderId, site, from, to);
  }

  @PostMapping("/downtime")
  public Map<String, Object> recordDowntime(
      @RequestBody(required = false) Map<String, Object> body) {
    requireRole(Roles.OPERATOR, "停机录入需要工艺员及以上角色");
    return service.recordDowntime(body == null ? Map.of() : body);
  }

  @GetMapping("/downtime/reasons")
  public Object reasons() {
    return service.reasonDict();
  }

  /* ---------------- OEE ---------------- */

  /** GET /api/execution/oee —— 真实 OEE（?site=&line=&equipment=&shift=&from=&to=）。 */
  @GetMapping("/oee")
  public Map<String, Object> oee(@RequestParam(required = false) String site,
      @RequestParam(required = false) String line,
      @RequestParam(required = false) String equipment,
      @RequestParam(required = false) String shift,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to) {
    return oee.compute(site, line, equipment, shift, from, to);
  }

  /** POST /api/execution/oee/recompute —— 重算某日汇总（值班长+）。 */
  @PostMapping("/oee/recompute")
  public Map<String, Object> recompute(@RequestParam(required = false) String date,
      @RequestParam(required = false) String site,
      @RequestParam(required = false) String line) {
    requireRole(Roles.SUPERVISOR, "重算 OEE 需要值班长及以上角色");
    return oee.recompute(date, site, line);
  }

  /** GET /api/execution/oee/pareto —— 停机原因帕累托。 */
  @GetMapping("/oee/pareto")
  public Map<String, Object> pareto(@RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) String site,
      @RequestParam(required = false) String line) {
    return oee.pareto(from, to, site, line);
  }

  private static void requireRole(String role, String message) {
    if (!CurrentUser.hasRole(role)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, message);
    }
  }
}
