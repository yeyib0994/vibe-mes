package com.fluxmes.api.recipe;

import com.fluxmes.api.regtech.RegtechWeb;
import jakarta.servlet.http.HttpServletRequest;
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
 * 配方管理端点（契约 = fluxmes/src/api/recipes.ts）。
 *
 * H2 变更：版本受控由 recipe_version / recipe_step 承载，列表仍兼容既有前端字段；
 * 生命周期动作（草稿 / 提交 / 审批 / 生效）与影响面分析为本期新增。
 */
@RestController
@RequestMapping("/api/recipes")
public class RecipeController {

  private final RecipeService service;

  public RecipeController(RecipeService service) {
    this.service = service;
  }

  /** GET /api/recipes —— 配方台账（版本与 params 取自 PG，展示字段回落 fixture）。 */
  @GetMapping
  public Map<String, Object> list() {
    return service.list();
  }

  /** GET /api/recipes/{code}/versions —— 全部版本倒序（NFR-2）。 */
  @GetMapping("/{code}/versions")
  public List<Map<String, Object>> versions(@PathVariable String code) {
    return service.versions(code);
  }

  /** GET /api/recipes/{code}/versions/{version} —— 单版本详情 + 工序参数。 */
  @GetMapping("/{code}/versions/{version}")
  public Map<String, Object> versionDetail(@PathVariable String code, @PathVariable String version) {
    return service.detail(code, version);
  }

  /** GET /api/recipes/{code}/history —— 版本历史（T4，兼容前端抽屉契约）。 */
  @GetMapping("/{code}/history")
  public List<Map<String, Object>> history(@PathVariable String code) {
    return service.history(code);
  }

  /** GET /api/recipes/{code}/steps —— 指定版本的工序参数与容差（FR-3）。 */
  @GetMapping("/{code}/steps")
  public List<Map<String, Object>> steps(@PathVariable String code,
      @RequestParam(required = false) String version) {
    return service.steps(code, version);
  }

  /** GET /api/recipes/{code}/impact —— 变更影响分析（FR-7，QC+）。 */
  @GetMapping("/{code}/impact")
  public Map<String, Object> impact(@PathVariable String code,
      @RequestParam(required = false) String fromVersion) {
    return service.impact(code, fromVersion);
  }

  /** POST /api/recipes/{code}/draft —— 基于版本创建草稿（FR-4，工艺员+）。 */
  @PostMapping("/{code}/draft")
  public Map<String, Object> draft(@PathVariable String code,
      @RequestBody(required = false) Map<String, Object> body) {
    return service.createDraft(code, body == null ? Map.of() : body);
  }

  /** POST /api/recipes/{code}/versions/{version}/submit —— 提交审批（FR-5）。 */
  @PostMapping("/{code}/versions/{version}/submit")
  public Map<String, Object> submit(@PathVariable String code, @PathVariable String version) {
    return service.submit(code, version);
  }

  /** POST /api/recipes/{code}/versions/{version}/approve —— 审批（FR-6，管理员；通过即生效需签名）。 */
  @PostMapping("/{code}/versions/{version}/approve")
  public Map<String, Object> approve(@PathVariable String code, @PathVariable String version,
      @RequestBody(required = false) Map<String, Object> body, HttpServletRequest req) {
    return service.approve(code, version, body == null ? Map.of() : body,
        RegtechWeb.clientIp(req), req.getHeader("User-Agent"));
  }

  /** POST /api/recipes/{code}/versions/{version}/activate —— 生效切换（NFR-3 原子操作，管理员；FR-21 需签名）。 */
  @PostMapping("/{code}/versions/{version}/activate")
  public Map<String, Object> activate(@PathVariable String code, @PathVariable String version,
      @RequestBody(required = false) Map<String, Object> body, HttpServletRequest req) {
    Map<String, Object> b = body == null ? Map.of() : body;
    String comment = b.get("comment") == null ? "" : String.valueOf(b.get("comment"));
    return service.activate(code, version, comment,
        RegtechWeb.signatureBody(b), RegtechWeb.clientIp(req), req.getHeader("User-Agent"));
  }
}
