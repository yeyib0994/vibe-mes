package com.fluxmes.api.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Data;

/**
 * Phase I · 工单：批次为 N:1，继承批次产线/厂区/产品/配方版本快照（plan D1）。
 * 数量统一 kg（plan R5）；计划/实际工时单位分钟。
 */
@Data
@TableName("work_order")
public class WorkOrder {
  @TableId
  private String id;                // WO-260915-001
  private String batchId;
  private String line;
  private String site;
  private String product;
  private String recipeCode;
  private String recipeVersion;     // 快照：批次后续换版不影响已建工单
  private BigDecimal planQty;       // 计划数量（kg）
  private String unit;
  private Integer planMinutes;      // 计划生产工时（可用率分母）
  private OffsetDateTime planStart;
  private OffsetDateTime planEnd;
  private String shift;             // DAY / NIGHT
  private String status;            // CREATED / RELEASED / RUNNING / FINISHED / CLOSED
  private BigDecimal inputQty;      // 报工汇总
  private BigDecimal goodQty;
  private BigDecimal scrapQty;
  private Integer actualMinutes;    // 实际生产工时（性能率分母）
  private String source;            // MANUAL / BACKFILL
  private String createdBy;
  private OffsetDateTime releasedAt;
  private OffsetDateTime finishedAt;
  private OffsetDateTime closedAt;
  private String remark;
  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;
}
