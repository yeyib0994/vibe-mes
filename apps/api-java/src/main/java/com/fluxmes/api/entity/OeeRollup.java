package com.fluxmes.api.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import lombok.Data;

/**
 * Phase I · OEE 预汇总：按「范围 × 日期 × 班次」聚合（plan D4）。
 * 数据不足时 data_sufficient=false 且各因子为 NULL —— 禁止硬编码兜底（plan D5 / FR-21）。
 */
@Data
@TableName("oee_rollup")
public class OeeRollup {
  @TableId
  private Long id;
  private String scopeType;           // LINE / EQUIPMENT
  private String scopeKey;
  private LocalDate statDate;
  private String shift;               // ALL / DAY / NIGHT
  private String site;
  private BigDecimal plannedMinutes;
  private BigDecimal unplannedStopMinutes;
  private BigDecimal runMinutes;
  private BigDecimal stdMinutes;
  private BigDecimal goodQty;
  private BigDecimal scrapQty;
  private BigDecimal availability;
  private BigDecimal performance;
  private BigDecimal quality;
  private BigDecimal oee;
  private Boolean dataSufficient;
  private String missing;
  private OffsetDateTime computedAt;
}
