package com.fluxmes.api.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Data;

/** F3 · 电子批记录 eBR 工序（设定值 vs 实际值，双人复核）。 */
@Data
@TableName("batch_step")
public class BatchStep {
  @TableId
  private Long id;
  private String batchId;
  private Integer stepNo;
  private String stepName;
  private String status;            // PENDING / RUNNING / DONE / SKIPPED
  private String targetParams;      // JSON
  private String actualParams;      // JSON
  private String operator;
  private String reviewer;
  private OffsetDateTime startedAt;
  private OffsetDateTime finishedAt;
  private OffsetDateTime reviewedAt;
  private String remark;
}
