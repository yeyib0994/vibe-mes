package com.fluxmes.api.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Data;

/** 报警（状态机 unacked→acked→recovered；留存 ≥3 年，C6；SLA 超时自动升级，D1）。 */
@Data
@TableName("alarm")
public class Alarm {
  @TableId
  private String id;
  private String time;              // 展示时间 HH:mm
  private String level;            // critical / major / minor
  private String source;
  private String content;
  private String value;
  private String threshold;
  private String status;           // unacked / acked / recovered
  private String ackBy;
  private OffsetDateTime ackedAt;
  private OffsetDateTime recoveredAt;
  private String recoveredBy;
  private Integer respondedMinutes;
  private OffsetDateTime createdAt;

  /* ---- D1 · SLA 与抑制 ---- */
  private Integer slaMinutes;      // 响应时限（分钟），按级别：critical 5 / major 15 / minor 30
  private Boolean escalated;       // 是否已升级（超时未确认）
  private OffsetDateTime escalatedAt;
  private String escalationNote;   // 升级说明（如「超时 12 分钟未确认，升级至值班长」）
  private String suppressionKey;   // 抑制键（source|content 归一化），用于规则匹配

  /* ---- J2 · 数据来源标识 ---- */
  /** MOCK（模拟器造的演示报警）/ OPCUA / MANUAL / null（历史数据与业务规则触发）。 */
  private String dataSource;
}
