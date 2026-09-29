package com.fluxmes.api.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Data;

/** F1 · 环境与卫生监测（温湿度 / 压差 / 微生物 / ATP）。 */
@Data
@TableName("env_monitoring")
public class EnvMonitoring {
  @TableId
  private Long id;
  private String area;
  private String line;
  private String metric;            // TEMP / HUMIDITY / PRESSURE_DIFF / MICRO / ATP
  private BigDecimal value;
  private String unit;
  private BigDecimal limitMin;
  private BigDecimal limitMax;
  private String result;            // PASS / FAIL
  private String deviationId;
  private String sampledBy;
  private OffsetDateTime sampledAt;
  private String remark;
}
