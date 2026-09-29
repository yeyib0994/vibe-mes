package com.fluxmes.api.regtech;

import com.fluxmes.api.common.CurrentUser;
import com.fluxmes.api.common.Roles;
import jakarta.servlet.http.HttpServletRequest;
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
 * 电子签名端点（契约见 specs/quality-regtech/plan.md §3）。
 *
 * <p>RBAC（C4）：签名=按 signature_policy.requiredRole 校验（服务层）；
 * 清单查询=登录；证据导出=管理员。
 * <b>无 UPDATE / DELETE 端点</b> —— 签名记录只追加（NFR-2）。
 */
@RestController
@RequestMapping("/api/signatures")
public class SignatureController {

  private final SignatureService service;

  public SignatureController(SignatureService service) {
    this.service = service;
  }

  /** FR-14 ~ FR-16 · 执行签名（含密码二次确认与含义声明）。 */
  @PostMapping
  public Map<String, Object> sign(@RequestBody(required = false) Map<String, Object> body,
      HttpServletRequest req) {
    Map<String, Object> b = body == null ? Map.of() : body;
    String action = str(b.get("action"));
    String recordId = str(b.get("recordId"));
    if (action == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "签名动作（action）必填，如 BATCH_RELEASE");
    }
    if (recordId == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "记录 ID（recordId）必填");
    }
    return SignatureService.toView(service.sign(
        action,
        str(b.get("recordType")),
        recordId,
        str(b.get("meaning")),
        str(b.get("password")),
        RegtechWeb.clientIp(req),
        req.getHeader("User-Agent")));
  }

  /** FR-20 / SC-12 · 签名清单。 */
  @GetMapping
  public Map<String, Object> list(@RequestParam(required = false) String recordType,
      @RequestParam(required = false) String recordId,
      @RequestParam(required = false) String signer,
      @RequestParam(required = false) String action) {
    List<Map<String, Object>> rows = service.list(recordType, recordId, signer, action);
    Map<String, Object> out = new java.util.LinkedHashMap<>();
    out.put("items", rows);
    out.put("total", rows.size());
    out.put("meanings", SignatureService.MEANING_LABEL);
    return out;
  }

  /** 签名适用范围配置（只读）。 */
  @GetMapping("/policy")
  public Map<String, Object> policies() {
    List<Map<String, Object>> rows = service.listPolicies();
    Map<String, Object> out = new java.util.LinkedHashMap<>();
    out.put("items", rows);
    out.put("total", rows.size());
    out.put("enableHint", "签名门禁默认开启；将某动作 enabled 置 false 可临时降级（plan D7）");
    return out;
  }

  /** FR-17 · 当前账号签名失败/锁定状态。 */
  @GetMapping("/me/attempts")
  public Map<String, Object> myAttempts() {
    return service.attemptState(CurrentUser.username());
  }

  /** FR-19 · 篡改检测：重算记录哈希并与签名哈希比对。 */
  @GetMapping("/{id}/verify")
  public Map<String, Object> verify(@PathVariable Long id) {
    return service.verify(id);
  }

  /** FR-20 · 签名证据包导出（服务端渲染 Markdown / JSON，R6）。 */
  @GetMapping("/export")
  public Map<String, Object> export(@RequestParam(required = false) String recordType,
      @RequestParam(required = false) String recordId,
      @RequestParam(defaultValue = "md") String format) {
    RegtechWeb.requireRole(CurrentUser.role(), Roles.ADMIN, "签名证据导出需要管理员角色");
    return service.export(recordType, recordId, format);
  }

  private static String str(Object v) {
    if (v == null) return null;
    String s = String.valueOf(v).trim();
    return s.isEmpty() ? null : s;
  }
}
