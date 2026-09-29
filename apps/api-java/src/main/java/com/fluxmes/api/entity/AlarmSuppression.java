package com.fluxmes.api.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Data;

/**
 * 报警抑制规则（D1）：同一报警源在窗口期内重复触发时只计数不入库，避免报警洪水（alarm flood）。
 * 匹配逻辑：source 精确（可空=通配） AND content 包含（可空=通配） AND level 精确（可空=通配）。
 */
@Data
@TableName("alarm_suppression")
public class AlarmSuppression {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String name;
  private String source;
  private String content;
  private String level;
  private Integer windowMinutes;
  private Boolean enabled;
  private String reason;
  private String createdBy;
  private OffsetDateTime createdAt;
}
