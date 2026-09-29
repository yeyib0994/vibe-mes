package com.fluxmes.api.audit;

import static com.fluxmes.api.common.ApiSupport.map;
import static com.fluxmes.api.common.ApiSupport.nowIso;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fluxmes.api.common.CurrentUser;
import com.fluxmes.api.common.Roles;
import com.fluxmes.api.entity.AuditLog;
import com.fluxmes.api.mapper.AuditLogMapper;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 审计查询端点（C3）：仅管理员可查，支持按实体类型/ID 与操作人过滤。 */
@RestController
@RequestMapping("/api/audit")
public class AuditController {

  private final AuditLogMapper mapper;

  public AuditController(AuditLogMapper mapper) {
    this.mapper = mapper;
  }

  /** GET /api/audit?entityType=&entityId=&actor=&page=1&size=50 */
  @GetMapping
  public Map<String, Object> list(@RequestParam(required = false) String entityType,
      @RequestParam(required = false) String entityId,
      @RequestParam(required = false) String actor,
      @RequestParam(defaultValue = "1") long page,
      @RequestParam(defaultValue = "50") long size) {
    if (!CurrentUser.hasRole(Roles.ADMIN)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅管理员可查询审计日志");
    }
    Page<AuditLog> p = mapper.selectPage(new Page<>(page, Math.min(size, 50)),
        Wrappers.<AuditLog>lambdaQuery()
            .eq(entityType != null && !entityType.isBlank(), AuditLog::getEntityType, entityType)
            .eq(entityId != null && !entityId.isBlank(), AuditLog::getEntityId, entityId)
            .eq(actor != null && !actor.isBlank(), AuditLog::getActor, actor)
            .orderByDesc(AuditLog::getId));
    List<Map<String, Object>> items = p.getRecords().stream()
        .map(a -> map(
            "id", a.getId(),
            "actor", a.getActor(),
            "action", a.getAction(),
            "entityType", a.getEntityType(),
            "entityId", a.getEntityId(),
            "before", a.getBeforeData(),
            "after", a.getAfterData(),
            "createdAt", a.getCreatedAt() == null ? null : a.getCreatedAt().toString()))
        .toList();
    return map("generatedAt", nowIso(), "total", p.getTotal(), "items", items);
  }
}
