package com.fluxmes.api.regtech;

import com.fluxmes.api.common.CurrentUser;
import com.fluxmes.api.common.Roles;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * CAPA 闭环端点（契约见 specs/quality-regtech/plan.md §3）。
 *
 * <p>RBAC（C4）：查询=登录；创建 / 流转 / 修订 / 加行动项=质检员+；
 * 有效性验证与关闭=管理员（与 signature_policy 的角色要求叠加校验）。
 */
@RestController
@RequestMapping("/api/capa")
public class CapaController {

  private final CapaService service;
  private final AuditProgramService audits;

  public CapaController(CapaService service, AuditProgramService audits) {
    this.service = service;
    this.audits = audits;
  }

  @GetMapping
  public Map<String, Object> list(@RequestParam(required = false) String status,
      @RequestParam(required = false) String owner,
      @RequestParam(required = false) String site,
      @RequestParam(required = false) Boolean overdue) {
    return service.list(status, owner, site, overdue);
  }

  /** FR-5 · 临期 / 逾期清单（含按责任人汇总与报警联动）。 */
  @GetMapping("/overdue")
  public Map<String, Object> overdue() {
    return service.overdue();
  }

  /** 驾驶舱 / 质量页指标卡（T22）。 */
  @GetMapping("/metrics")
  public Map<String, Object> metrics() {
    Map<String, Object> c = service.list(null, null, null, null);
    Map<String, Object> a = audits.summary(null);
    Map<String, Object> m = new java.util.LinkedHashMap<>();
    m.put("open", c.get("open"));
    m.put("total", c.get("total"));
    m.put("overdue", c.get("overdue"));
    m.put("dueSoon", c.get("dueSoon"));
    m.put("dueSoonDays", c.get("dueSoonDays"));
    m.put("auditCount", a.get("auditCount"));
    m.put("findingCount", a.get("findingCount"));
    m.put("openMajorFindings", a.get("openMajorFindings"));
    m.put("capaConversionRate", a.get("capaConversionRate"));
    m.put("bySeverity", a.get("bySeverity"));
    return m;
  }

  @PostMapping
  public Map<String, Object> create(@RequestBody(required = false) Map<String, Object> body) {
    requireRole(Roles.QC, "创建 CAPA 需要质检员及以上角色");
    return service.create(body == null ? Map.of() : body);
  }

  @GetMapping("/{id}")
  public Map<String, Object> detail(@PathVariable String id) {
    return service.detail(id);
  }

  /** FR-3 / FR-4 · 状态流转（open→in_progress→pending_verify；rejected→in_progress）。 */
  @PostMapping("/{id}/transition")
  public Map<String, Object> transition(@PathVariable String id,
      @RequestBody(required = false) Map<String, Object> body) {
    requireRole(Roles.QC, "CAPA 流转需要质检员及以上角色");
    Map<String, Object> b = body == null ? Map.of() : body;
    Object target = b.get("target") != null ? b.get("target") : b.get("status");
    return service.transition(id, target == null ? null : String.valueOf(target),
        b.get("comment") == null ? null : String.valueOf(b.get("comment")));
  }

  /** FR-18 · 内容修订（修订号 +1 并作废既有签名，需重新签名）。 */
  @PostMapping("/{id}/update")
  public Map<String, Object> update(@PathVariable String id,
      @RequestBody(required = false) Map<String, Object> body) {
    requireRole(Roles.QC, "修订 CAPA 需要质检员及以上角色");
    return service.update(id, body);
  }

  @PostMapping("/{id}/tasks")
  public Map<String, Object> addTask(@PathVariable String id,
      @RequestBody(required = false) Map<String, Object> body) {
    requireRole(Roles.QC, "新增行动项需要质检员及以上角色");
    return service.addTask(id, body == null ? Map.of() : body);
  }

  /** 行动项完成：责任人或质检员+（服务层判定）。 */
  @PostMapping("/tasks/{taskId}/done")
  public Map<String, Object> doneTask(@PathVariable Long taskId,
      @RequestBody(required = false) Map<String, Object> body) {
    return service.doneTask(taskId, body == null ? Map.of() : body);
  }

  /** FR-6 + FR-8 · 有效性验证（需电子签名 CAPA_VERIFY / VERIFIED / 管理员）。 */
  @PostMapping("/{id}/verify")
  public Map<String, Object> verify(@PathVariable String id,
      @RequestBody(required = false) Map<String, Object> body, HttpServletRequest req) {
    requireRole(Roles.ADMIN, "CAPA 有效性验证需要管理员角色");
    return service.verify(id, body, RegtechWeb.clientIp(req), req.getHeader("User-Agent"));
  }

  /** FR-8 · 关闭（需电子签名 CAPA_CLOSE / APPROVED / 管理员）。 */
  @PostMapping("/{id}/close")
  public Map<String, Object> close(@PathVariable String id,
      @RequestBody(required = false) Map<String, Object> body, HttpServletRequest req) {
    requireRole(Roles.ADMIN, "关闭 CAPA 需要管理员角色");
    return service.close(id, body, RegtechWeb.clientIp(req), req.getHeader("User-Agent"));
  }

  /* ---------------- helpers ---------------- */

  private static void requireRole(String role, String message) {
    RegtechWeb.requireRole(CurrentUser.role(), role, message);
  }
}
