package com.fluxmes.api.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import lombok.Data;

/**
 * 批次实体（C2 批次档案完整性）。
 * 复合字段（stages / meta / params / materialLots）以 JSON 字符串存 TEXT，应用层解析，
 * 避免 jsonb 与 TypeHandler 的复杂度。
 */
@Data
@TableName("batch")
public class Batch {
  @TableId
  private String id;              // B-260826-007
  private String product;
  private String recipe;
  private String recipeVersion;
  private String equipment;
  private String stage;           // 当前工序
  private String status;          // running / waiting / done / abnormal
  private String params;          // JSON
  private Integer progress;
  private String startedAt;       // 展示用日期/时间
  private String endedAt;
  private String stages;          // JSON: [["配料","done"], ...]
  private String meta;            // JSON
  private String operator;
  private BigDecimal planYield;
  private String materialLots;    // JSON
  private String coaNo;
  private String warehouseBin;
  private String deviationNo;
  private Boolean released;       // 放行锁定：TRUE 后只读
  private String line;            // D2 · 所属产线（LINE-1 / LINE-2 / LINE-3）
  private LocalDate productionDate; // F3 · 生产日期（效期起算点）
  private Integer shelfLifeDays;    // F3 · 保质期天数
  private LocalDate expiryDate;     // F3 · 到期日 = 生产日期 + 保质期
  private String site;              // G4 · 所属厂区（SITE-01 / SITE-02）
  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;
}
