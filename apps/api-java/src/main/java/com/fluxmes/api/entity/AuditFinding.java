package com.fluxmes.api.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Data;

/** 内审发现项（FR-10 四级分级 + 标准条款号；FR-11 转 CAPA 后回写 capaId）。 */
@Data
@TableName("audit_finding")
public class AuditFinding {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String auditId;
  private Integer seq;
  private String clause;        // FSSC 22000 8.9.5 / GMP 附录1 §4.3
  private String severity;      // CRITICAL / MAJOR / MINOR / OBSERVATION
  private String description;
  private String area;
  private String owner;
  private String capaId;
  private String createdBy;
  private OffsetDateTime createdAt;
}
