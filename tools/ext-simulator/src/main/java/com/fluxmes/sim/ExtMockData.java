package com.fluxmes.sim;

import java.util.List;

/**
 * LIMS / ERP 的 mock 数据（**自动生成，请勿手改**）。
 *
 * 生成命令：{@code node scripts/export-opcua-nodes.mjs}
 * 数据来源：{@code apps/api-java/src/main/resources/fixtures/mes.json} 的
 *          {@code qcTasks}（→ LIMS 检验结果）与 {@code runningBatches}（→ ERP 工单）。
 *
 * 与 MES 内数据同源的意义：mock 外部系统返回的批次号、检验项必须与 MES 库里
 * 真实存在的批次对得上，否则演示时会看到「MES 里没有这个批次」这种一眼假的结果。
 *
 * 当前导出 5 条检验结果 / 3 张 ERP 工单。
 */
public final class ExtMockData {

  /** LIMS 待回流的检验结果行。 */
  public record LabResultRow(String taskNo, String batchId, String item, String inspector,
      boolean pass) {}

  /** ERP 待下发的生产工单行。 */
  public record WorkOrderRow(String orderNo, String product, String recipeCode, String siteCode) {}

  public static final List<LabResultRow> LAB_RESULTS = List.of(
        new LabResultRow("QC-260826-018", "B-260826-007", "柠檬酸含量 / 水分 / 色度", "林晓芸", true),
        new LabResultRow("QC-260826-017", "B-260826-014", "发酵液 pH / 溶氧 / 菌浓", "赵敏", true),
        new LabResultRow("QC-260826-016", "B-260826-011", "干燥水分 / 粒度分布", "林晓芸", false),
        new LabResultRow("QC-260826-015", "B-260826-009", "全项检验（8 项）", "赵敏", false),
        new LabResultRow("QC-260825-041", "B-260825-031", "柠檬酸含量 / 重金属", "林晓芸", true)
  );

  public static final List<WorkOrderRow> WORK_ORDERS = List.of(
        new WorkOrderRow("ERP-WO-260826-014", "一水柠檬酸", "", "SITE-01"),
        new WorkOrderRow("ERP-WO-260826-013", "食品级柠檬酸钠", "", "SITE-01"),
        new WorkOrderRow("ERP-WO-260826-011", "无水柠檬酸", "", "SITE-01")
  );

  private ExtMockData() {}
}
