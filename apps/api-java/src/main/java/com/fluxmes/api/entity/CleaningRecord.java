package com.fluxmes.api.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Data;

/** F1 · 清场与卫生记录（CIP / 换型 / 过敏原 / 深度清洁），QA 确认后作为开工前置条件。 */
@Data
@TableName("cleaning_record")
public class CleaningRecord {
  @TableId
  private String id;                // CLN-260914-001
  private String line;
  private String equipmentCode;
  private String type;              // ROUTINE / CHANGEOVER / ALLERGEN / DEEP
  private String method;
  private String allergenFrom;
  private String allergenTo;
  private OffsetDateTime startedAt;
  private OffsetDateTime finishedAt;
  private String executedBy;
  private String verifiedBy;
  private OffsetDateTime verifiedAt;
  private String result;            // PENDING / PASS / FAIL
  private String swabResult;
  private OffsetDateTime validUntil;
  private String nextBatchId;
  private String remark;
  private OffsetDateTime createdAt;
}
