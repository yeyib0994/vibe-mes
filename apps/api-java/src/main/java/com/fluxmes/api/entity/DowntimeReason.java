package com.fluxmes.api.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * Phase I · 停机原因码字典：category 决定是否计入计划外停机
 * （CHANGEOVER / CLEANING / NO_ORDER 为计划性损失，FR-17）。
 */
@Data
@TableName("downtime_reason")
public class DowntimeReason {
  @TableId
  private String code;
  private String name;
  private String category;            // PLANNED / UNPLANNED
  private Integer alarmThresholdMin;  // ≥ 该时长自动报警（0 = 不报警）
  private Boolean enabled;
  private Integer sortNo;
  private String note;
}
