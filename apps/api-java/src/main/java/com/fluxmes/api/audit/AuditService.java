package com.fluxmes.api.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxmes.api.common.ApiSupport;
import com.fluxmes.api.common.CurrentUser;
import com.fluxmes.api.entity.AuditLog;
import com.fluxmes.api.mapper.AuditLogMapper;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 审计服务（C3）：关键操作记录 who / when / action / entity / before / after。
 * 审计写入失败不阻断业务（仅告警），保证主流程可用性。
 */
@Service
public class AuditService {

  private static final Logger log = LoggerFactory.getLogger(AuditService.class);

  private final AuditLogMapper mapper;
  private final ObjectMapper json;

  public AuditService(AuditLogMapper mapper, ObjectMapper json) {
    this.mapper = mapper;
    this.json = json;
  }

  public void record(String action, String entityType, String entityId,
      Map<String, Object> before, Map<String, Object> after) {
    try {
      AuditLog entry = new AuditLog();
      entry.setActor(CurrentUser.username());
      entry.setAction(action);
      entry.setEntityType(entityType);
      entry.setEntityId(entityId);
      entry.setBeforeData(before == null ? null : json.writeValueAsString(before));
      entry.setAfterData(after == null ? null : json.writeValueAsString(after));
      mapper.insert(entry);
    } catch (Exception e) {
      log.warn("audit write failed: action={} entity={}/{} err={}",
          action, entityType, entityId, e.toString());
    }
  }

  public void record(String action, String entityType, String entityId) {
    record(action, entityType, entityId, null, null);
  }

  /** 变更字段前后值快照构造辅助。 */
  public static Map<String, Object> snapshot(Object... kv) {
    return ApiSupport.map(kv);
  }
}
