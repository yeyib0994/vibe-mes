package com.fluxmes.api.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Data;

/**
 * Phase I · 停机事件：`planned` 由原因码 category 推导（FR-17），
 * 与 equipment_event 时间窗合并去重（FR-18）。
 */
@Data
@TableName("downtime_event")
public class DowntimeEvent {
  @TableId
  private Long id;
  private String orderId;
  private String equipment;
  private String line;
  private String site;
  private String shift;
  private String reasonCode;
  private Boolean planned;
  private OffsetDateTime startedAt;
  private OffsetDateTime endedAt;
  private BigDecimal durationMin;
  private String description;
  private String source;          // MANUAL / EVENT
  private String mergedFrom;      // 合并来源说明（FR-18）
  private String createdBy;
  private OffsetDateTime createdAt;
}
