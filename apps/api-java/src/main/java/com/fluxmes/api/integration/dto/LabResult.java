package com.fluxmes.api.integration.dto;

import java.time.OffsetDateTime;

/**
 * LIMS 检验结果回流的单条记录。
 *
 * <p>对应 MES 侧的 {@code qc_task} 与 {@code batch.coa_no}。章程 §3 非目标 3 明确
 * MES **不做仪器直连**，检验数据由 LIMS 通过接口回流，故这里是"结果"而非"原始谱图"。
 *
 * @param taskNo      检验任务号（LIMS 侧主键，用于幂等去重）
 * @param batchId     关联批次号
 * @param item        检验项，如「柠檬酸含量」
 * @param value       实测值
 * @param unit        单位
 * @param standard    依据标准，如 GB 1886.25—2016（C5 依据标注）
 * @param conclusion  PASS / FAIL / PENDING
 * @param coaNo       COA 编号（结论 PASS 时由 LIMS 出具）
 * @param inspector   检验员
 * @param reportedAt  报告时间
 */
public record LabResult(
    String taskNo,
    String batchId,
    String item,
    Double value,
    String unit,
    String standard,
    String conclusion,
    String coaNo,
    String inspector,
    OffsetDateTime reportedAt) {

  public static final String PASS = "PASS";
  public static final String FAIL = "FAIL";
  public static final String PENDING = "PENDING";
}
