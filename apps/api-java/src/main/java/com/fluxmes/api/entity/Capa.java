package com.fluxmes.api.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import lombok.Data;

/**
 * CAPA（纠正与预防措施）主记录 —— 补齐「偏差 → CAPA → 验证 → 关闭」闭环。
 * 独立成表而非扩展 {@code deviation.capa} 文本字段（plan D1）：文本无法承载责任人 / 期限 / 验证 / 有效性。
 */
@Data
@TableName("capa")
public class Capa {
  @TableId
  private String id;                 // CAPA-260915-001
  private String sourceType;         // DEVIATION / AUDIT_FINDING / ALARM / MANUAL
  private String sourceId;
  private String type;               // CORRECTION / CORRECTIVE / PREVENTIVE
  private String title;
  private String description;
  private String rootCause;          // FR-2 根因必填
  private String owner;
  private LocalDate dueDate;
  private String severity;           // critical / major / minor
  private String status;             // open / in_progress / pending_verify / verified / closed / rejected
  private String verificationMethod; // DOC_REVIEW / SITE_CHECK / DATA_REVIEW
  private String effectiveness;      // EFFECTIVE / INEFFECTIVE
  private String verifyComment;
  private String rejectReason;
  private String verifiedBy;
  private OffsetDateTime verifiedAt;
  private String closedBy;
  private OffsetDateTime closedAt;
  private String site;
  /** 记录修订号，电子签名绑定（FR-15/FR-18）；内容实质变更时 +1。 */
  private Integer recordRevision;
  private String createdBy;
  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;
}
