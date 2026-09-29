package com.fluxmes.api.regtech;

import com.fluxmes.api.common.CurrentUser;
import com.fluxmes.api.common.Roles;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内审管理端点（契约见 specs/quality-regtech/plan.md §3）。
 *
 * <p>RBAC（C4）：查询=登录；创建 / 流转 / 关闭内审=管理员；登记发现项=管理员或质检员；
 * 发现项转 CAPA=质检员+。
 */
@RestController
@RequestMapping("/api/internal-audit")
public class AuditProgramController {

  private final AuditProgramService service;

  public AuditProgramController(AuditProgramService service) {
    this.service = service;
  }

  @GetMapping
  public Map<String, Object> list(@RequestParam(required = false) Integer year,
      @RequestParam(required = false) String site) {
    return service.list(year, site);
  }

  /** FR-13 · 年度发现项分布与 CAPA 转化率。 */
  @GetMapping("/summary")
  public Map<String, Object> summary(@RequestParam(required = false) Integer year) {
    return service.summary(year);
  }

  @PostMapping
  public Map<String, Object> create(@RequestBody(required = false) Map<String, Object> body) {
    requireRole(Roles.ADMIN, "创建内审计划需要管理员角色");
    return service.create(body);
  }

  @GetMapping("/{id}")
  public Map<String, Object> detail(@PathVariable String id) {
    return service.detail(id);
  }

  @PostMapping("/{id}/transition")
  public Map<String, Object> transition(@PathVariable String id,
      @RequestBody(required = false) Map<String, Object> body) {
    requireRole(Roles.ADMIN, "推进内审状态需要管理员角色");
    Map<String, Object> b = body == null ? Map.of() : body;
    Object target = b.get("target") != null ? b.get("target") : b.get("status");
    return service.transition(id, target == null ? null : String.valueOf(target),
        b.get("comment") == null ? null : String.valueOf(b.get("comment")));
  }

  /** FR-10 · 登记发现项（分级 + 条款关联）。 */
  @PostMapping("/{id}/findings")
  public Map<String, Object> addFinding(@PathVariable String id,
      @RequestBody(required = false) Map<String, Object> body) {
    requireRole(Roles.QC, "登记内审发现项需要质检员及以上角色");
    return service.addFinding(id, body);
  }

  /** FR-11 · 发现项一键转 CAPA。 */
  @PostMapping("/findings/{findingId}/to-capa")
  public Map<String, Object> findingToCapa(@PathVariable Long findingId,
      @RequestBody(required = false) Map<String, Object> body) {
    requireRole(Roles.QC, "发现项转 CAPA 需要质检员及以上角色");
    return service.findingToCapa(findingId, body);
  }

  /** FR-12 · 关闭内审（critical / major 未生成 CAPA 则 409）。 */
  @PostMapping("/{id}/close")
  public Map<String, Object> close(@PathVariable String id,
      @RequestBody(required = false) Map<String, Object> body) {
    requireRole(Roles.ADMIN, "关闭内审需要管理员角色");
    Map<String, Object> b = body == null ? Map.of() : body;
    return service.close(id, b.get("comment") == null ? null : String.valueOf(b.get("comment")));
  }

  private static void requireRole(String role, String message) {
    RegtechWeb.requireRole(CurrentUser.role(), role, message);
  }
}
