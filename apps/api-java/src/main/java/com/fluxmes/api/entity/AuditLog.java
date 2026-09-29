package com.fluxmes.api.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Data;

/** 审计日志（C3：who / when / action / entity / before / after，保留 ≥3 年）。 */
@Data
@TableName("audit_log")
public class AuditLog {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String actor;
  private String action;       // alarm.ack / batch.release / deviation.close ...
  private String entityType;   // alarm / batch / deviation / qc / spc_limit
  private String entityId;
  private String beforeData;   // JSON
  private String afterData;    // JSON
  private OffsetDateTime createdAt;
}
