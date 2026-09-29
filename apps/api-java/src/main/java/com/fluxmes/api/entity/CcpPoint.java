package com.fluxmes.api.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import lombok.Data;

/** F1 · HACCP 关键控制点定义（关键限值 CL、监控频次、纠偏措施）。 */
@Data
@TableName("ccp_point")
public class CcpPoint {
  @TableId
  private String code;              // CCP-01
  private String name;
  private String line;
  private String stepName;
  private String hazard;            // 显著危害
  private String hazardType;        // BIOLOGICAL / CHEMICAL / PHYSICAL / ALLERGEN
  private String controlMeasure;
  private BigDecimal clMin;         // 关键限值下限
  private BigDecimal clMax;         // 关键限值上限
  private String unit;
  private String monitorFreq;
  private String correctiveAction;
  private String responsibleRole;
  private Boolean enabled;
  /** G4 · 所属厂区。 */
  private String siteCode;
}
