package com.fluxmes.api.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Data;

/**
 * J1 · 设备实时工艺参数（equipment-management T12 / FR-3）。
 *
 * <p>外部系统（SCADA / OPC-UA）采集数据的唯一落点。此前该表只存在于
 * {@code specs/equipment-management/plan.md} 的设计里，未落库，实时参数由
 * FixtureStore 伪序列兜住；本表补齐后趋势序列改为真实查询。
 *
 * <p>注意：这张表增长快（采样周期 ≤30s、发酵罐 ≤10s），保留策略见 schema-p7.sql 注释。
 */
@Data
@TableName("equipment_metric")
public class EquipmentMetric {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String equipmentCode;   // F-102
  private String metricKey;       // temp / pressure / level / rpm
  private String metricName;      // 罐温 / 罐压 / 液位 / 搅拌转速
  private BigDecimal value;
  private String unit;            // ℃ / MPa / % / rpm
  private BigDecimal lowerLimit;
  private BigDecimal upperLimit;

  /** OPC-UA StatusCode 归一：GOOD / BAD / UNCERTAIN。 */
  private String quality;

  /** MOCK / OPCUA / MANUAL —— 数据来源标识，前端据此展示来源标签。 */
  private String source;

  /** 数据源时间戳（区别于入库时间 created_at）。 */
  private OffsetDateTime sampledAt;

  private OffsetDateTime createdAt;
}
