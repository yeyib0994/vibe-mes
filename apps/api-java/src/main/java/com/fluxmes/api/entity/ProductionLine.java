package com.fluxmes.api.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import lombok.Data;

/** D2 · 产线主数据（多产线维度切换与 KPI 分组依据）。 */
@Data
@TableName("production_line")
public class ProductionLine {
  @TableId
  private String code;        // LINE-1
  private String name;
  private String workshop;
  private BigDecimal capacityT;  // 日设计产能（吨）
  private Boolean enabled;
  /** G4 · 所属厂区（SITE-01 / SITE-02）。 */
  private String siteCode;
}
