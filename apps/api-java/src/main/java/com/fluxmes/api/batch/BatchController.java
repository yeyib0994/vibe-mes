package com.fluxmes.api.batch;

import com.fluxmes.api.common.CurrentUser;
import com.fluxmes.api.common.Roles;
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
 * 批次管理端点（契约 = fluxmes/src/api/batches.ts，向后兼容并新增动作端点）。
 * RBAC（C4）：新建/推进/异常标记需工艺员及以上；查询登录即可。
 */
@RestController
@RequestMapping("/api/batches")
public class BatchController {

  private final BatchService service;

  public BatchController(BatchService service) {
    this.service = service;
  }

  /** D2 · 产线主数据（前端切换器数据源）；G4 支持 ?site= 过滤。 */
  @GetMapping("/lines")
  public Object lines(@RequestParam(required = false) String site) {
    return service.lines(site);
  }

  @GetMapping
  public Map<String, Object> list(@RequestParam(required = false) String status,
      @RequestParam(required = false) String stage,
      @RequestParam(required = false) String product,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String line,
      @RequestParam(required = false) String site,
      @RequestParam(required = false) Long page,
      @RequestParam(required = false) Long size) {
    return service.list(status, stage, product, keyword, line, page, size, site);
  }

  @GetMapping("/{id}")
  public Map<String, Object> detail(@PathVariable String id) {
    return service.detail(id);
  }

  /** POST /api/batches —— 新建（FR-8）：校验必填，生成批次号。 */
  @PostMapping
  public Map<String, Object> create(@RequestBody Map<String, Object> body) {
    requireRole(Roles.OPERATOR, "新建批次需要工艺员及以上角色");
    return service.create(body);
  }

  /** POST /api/batches/{id}/advance —— 工序推进（状态机 FR-5/T5）。 */
  @PostMapping("/{id}/advance")
  public Map<String, Object> advance(@PathVariable String id) {
    requireRole(Roles.OPERATOR, "推进工序需要工艺员及以上角色");
    return service.advance(id);
  }

  /** POST /api/batches/{id}/abnormal —— 标记异常并创建偏差单。 */
  @PostMapping("/{id}/abnormal")
  public Map<String, Object> abnormal(@PathVariable String id,
      @RequestBody(required = false) Map<String, String> body) {
    requireRole(Roles.OPERATOR, "标记异常需要工艺员及以上角色");
    return service.markAbnormal(id, body == null ? null : body.get("reason"));
  }

  private static void requireRole(String role, String message) {
    if (!CurrentUser.hasRole(role)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, message);
    }
  }
}
