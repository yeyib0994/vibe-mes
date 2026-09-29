package com.fluxmes.api.quality;

import com.fluxmes.api.common.CurrentUser;
import com.fluxmes.api.common.Roles;
import com.fluxmes.api.regtech.RegtechWeb;
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
 * 质量管理端点（契约 = fluxmes/src/api/quality.ts，向后兼容并新增判异/放行/偏差端点）。
 * RBAC（C4）：放行需质检员及以上；偏差关闭需值班长及以上。
 */
@RestController
@RequestMapping("/api/quality")
public class QualityController {

  private final QualityService service;

  public QualityController(QualityService service) {
    this.service = service;
  }

  @GetMapping
  public Map<String, Object> overview() {
    return service.overview();
  }

  @GetMapping("/spc")
  public List<Map<String, Object>> spc() {
    return service.overview() == null ? List.of() : List.of();
  }

  /** GET /api/quality/spc-limit?product= —— 控制限配置（T2）。 */
  @GetMapping("/spc-limit")
  public Map<String, Object> spcLimit(@RequestParam(required = false) String product) {
    return service.spcLimit(product);
  }

  /** GET /api/quality/spc/detect —— Western Electric 判异结果（T3）。 */
  @GetMapping("/spc/detect")
  public Map<String, Object> detect() {
    return service.detect();
  }

  @GetMapping("/pareto")
  public List<Map<String, Object>> pareto() {
    return service.pareto();
  }

  @GetMapping("/tasks")
  public List<Map<String, Object>> tasks() {
    return service.qcTasks();
  }

  /** GET /api/quality/deviations —— 偏差单列表（T8）。 */
  @GetMapping("/deviations")
  public List<Map<String, Object>> deviations() {
    return service.listDeviations();
  }

  /**
   * POST /api/quality/deviations/{id}/transition —— 偏差状态逐级推进。
   * Phase I：关闭需电子签名（FR-21），且须关联 CAPA 全部关闭（FR-7）。
   */
  @PostMapping("/deviations/{id}/transition")
  public Map<String, Object> transition(@PathVariable String id,
      @RequestBody Map<String, Object> body, HttpServletRequest req) {
    Map<String, Object> b = body == null ? Map.of() : body;
    return service.transitionDeviation(id,
        str(b.get("target")), str(b.get("rootCause")), str(b.get("capa")),
        RegtechWeb.signatureBody(b), RegtechWeb.clientIp(req), req.getHeader("User-Agent"));
  }

  /**
   * POST /api/quality/release —— 成品放行 + 生成 COA（T7）。
   * Phase I：受控动作，需电子签名（含义 APPROVED / 质检员+，FR-21）；
   * 关联 CAPA 未关闭时阻断（FR-7）。
   */
  @PostMapping("/release")
  public Map<String, Object> release(@RequestBody Map<String, Object> body, HttpServletRequest req) {
    if (!CurrentUser.hasRole(Roles.QC)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "批次放行需要质检员及以上角色");
    }
    String batchId = str(body == null ? null : body.get("batchId"));
    if (batchId == null || batchId.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少 batchId");
    }
    return service.release(batchId, RegtechWeb.signatureBody(body),
        RegtechWeb.clientIp(req), req.getHeader("User-Agent"));
  }

  private static String str(Object v) {
    if (v == null) return null;
    String s = String.valueOf(v).trim();
    return s.isEmpty() ? null : s;
  }
}
