package com.fluxmes.api.integration.dto;

import com.fluxmes.api.integration.DataSourceTag;
import java.time.OffsetDateTime;

/**
 * 一次设备工艺参数读数（Port 层的通用货币）。
 *
 * @param equipmentCode 设备编号，如 F-101
 * @param metricKey     指标键，如 temp / ph / do / rpm
 * @param metricName    指标展示名，如 罐温
 * @param value         读数
 * @param unit          单位，如 ℃ / % / rpm
 * @param lowerLimit    工艺下限
 * @param upperLimit    工艺上限
 * @param quality       质量码（OPC-UA StatusCode 归一）：GOOD / BAD / UNCERTAIN
 * @param dataSource    {@link DataSourceTag} 之一，前端据此展示来源标签
 * @param sampledAt     数据源时间戳（非入库时间）
 */
public record MetricSample(
    String equipmentCode,
    String metricKey,
    String metricName,
    Double value,
    String unit,
    Double lowerLimit,
    Double upperLimit,
    String quality,
    String dataSource,
    OffsetDateTime sampledAt) {

  public static final String QUALITY_GOOD = "GOOD";
  public static final String QUALITY_BAD = "BAD";
  public static final String QUALITY_UNCERTAIN = "UNCERTAIN";

  public static MetricSample of(String code, String key, String name, Double value, String unit,
      Double lo, Double hi, String dataSource) {
    return new MetricSample(code, key, name, value, unit, lo, hi, QUALITY_GOOD, dataSource,
        OffsetDateTime.now());
  }

  /** 越限判定（含质量码不可信的情况——BAD 一律视为不可用于判定）。 */
  public boolean outOfControl() {
    if (!QUALITY_GOOD.equals(quality) || value == null) return false;
    return (upperLimit != null && value > upperLimit) || (lowerLimit != null && value < lowerLimit);
  }
}
