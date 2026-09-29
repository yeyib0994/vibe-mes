package com.fluxmes.api.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Data;

/**
 * G3 · 配料称量任务：按配方目标量 + 允许容差（±百分比）称重，
 * 累计量达标后任务关闭；任何一次超差即联动偏差单与报警。
 */
@Data
@TableName("weighing_task")
public class WeighingTask {
  @TableId
  private String id;            // WT-260914-001
  private String batchId;
  private String materialCode;
  private String materialName;
  private BigDecimal targetQty;
  private String unit;
  private BigDecimal tolerancePct;
  private BigDecimal totalWeighed;
  private String status;        // OPEN / DONE / BLOCKED
  private String createdBy;
  private OffsetDateTime createdAt;
}
