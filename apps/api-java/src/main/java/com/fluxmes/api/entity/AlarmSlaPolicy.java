package com.fluxmes.api.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Data;

/**
 * G1 · 报警响应 SLA 政策：按级别配置响应时限（分钟），运行时可改（值班长及以上）。
 * 此前为 {@code AlarmService} 内的代码常量，无法随管理要求调整。
 */
@Data
@TableName("alarm_sla_policy")
public class AlarmSlaPolicy {
  @TableId
  private String level;        // critical / major / minor（主键）
  private Integer minutes;     // 响应时限
  private Boolean enabled;     // 停用后回落默认时限
  private String note;
  private String updatedBy;
  private OffsetDateTime updatedAt;
}
