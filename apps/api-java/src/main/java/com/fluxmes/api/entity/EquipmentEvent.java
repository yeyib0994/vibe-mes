package com.fluxmes.api.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Data;

/** H1 · 设备状态事件流水（FR-5）：状态变更留痕，并为 OEE 可用率提供停机时长证据。 */
@Data
@TableName("equipment_event")
public class EquipmentEvent {
  @TableId
  private Long id;
  private String equipmentCode;
  private String fromStatus;
  private String toStatus;
  private String reason;
  private String operator;
  private OffsetDateTime startedAt;
  private OffsetDateTime endedAt;
}
