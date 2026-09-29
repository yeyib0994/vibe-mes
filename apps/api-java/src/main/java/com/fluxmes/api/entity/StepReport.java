package com.fluxmes.api.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Data;

/**
 * Phase I · 工序报工：批次的进度与产量由报工事实驱动（plan D2），
 * 关键工序须双人复核（reviewer != username）。
 */
@Data
@TableName("step_report")
public class StepReport {
  @TableId
  private Long id;
  private String orderId;
  private String batchId;
  private Integer stepNo;
  private String stepName;
  private String equipment;
  private String username;
  private String reviewer;
  private OffsetDateTime reviewedAt;
  private OffsetDateTime startedAt;
  private OffsetDateTime finishedAt;
  private BigDecimal durationMin;
  private BigDecimal inputQty;
  private BigDecimal goodQty;
  private BigDecimal scrapQty;
  private BigDecimal stdMinutes;    // 标准工时（性能率分子）
  private Boolean critical;
  private String source;            // MANUAL / BACKFILL
  private String remark;
  private OffsetDateTime createdAt;
}
