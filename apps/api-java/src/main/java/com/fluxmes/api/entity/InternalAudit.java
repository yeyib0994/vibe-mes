package com.fluxmes.api.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import lombok.Data;

/** 内审计划（FR-9；状态机 planned → in_progress → reported → closed）。 */
@Data
@TableName("internal_audit")
public class InternalAudit {
  @TableId
  private String id;            // AUDIT-2026-01
  private String title;
  private String auditType;     // SYSTEM / PROCESS / PRODUCT / GMP_SELF
  private String scope;
  private String lead;
  private String team;          // JSON 数组（TEXT 约定）
  private LocalDate planStart;
  private LocalDate planEnd;
  private String status;
  private String reportNote;
  private String closedBy;
  private OffsetDateTime closedAt;
  private String site;
  private String createdBy;
  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;
}
