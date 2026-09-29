package com.fluxmes.api.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import lombok.Data;

/**
 * H1 · 设备主数据（主键为设备编号，天然可读且与批次档案引用一致）。
 * 实时工艺参数与趋势序列仍由 FixtureStore 提供（plan D3：真实采集接入后再迁时序库）。
 */
@Data
@TableName("equipment")
public class Equipment {
  @TableId
  private String code;              // F-101
  private String name;
  private String model;
  private String category;          // FERMENTER / MIXING / EVAPORATOR / CRYSTALLIZER / DRYER / PACKING
  private String location;
  private String siteCode;          // G4 厂区
  private String line;
  private String vol;
  private String status;            // RUNNING / IDLE / CLEANING / ALARM / MAINTENANCE / STOPPED
  private String criticality;       // A / B / C
  private BigDecimal runtimeHours;
  private BigDecimal mtbfHours;
  private LocalDate lastMaintenanceAt;
  private Integer maintenanceCycleDays;
  private LocalDate nextMaintenanceAt;
  private String calibrationItem;
  private LocalDate calibrationDueAt;
  private Boolean enabled;
  private OffsetDateTime updatedAt;
}
