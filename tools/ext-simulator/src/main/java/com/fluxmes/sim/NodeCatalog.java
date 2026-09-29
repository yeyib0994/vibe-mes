package com.fluxmes.sim;

import java.util.List;

/**
 * OPC-UA 地址空间目录（**自动生成，请勿手改**）。
 *
 * 生成命令：{@code node scripts/export-opcua-nodes.mjs}
 * 数据来源：{@code apps/api-java/src/main/resources/fixtures/mes.json} 的 {@code equipmentSpec}
 *
 * 作用：模拟器按本清单在 OPC-UA 上建节点，MES 侧的 OPC-UA 客户端浏览同一地址空间取数。
 * 双方共用一份来源，避免"模拟器节点名和适配器期望的节点名对不上"这类低级错位。
 *
 * 当前导出 8 台设备 / 24 个工艺参数指标。
 */
public final class NodeCatalog {

  /** 一个工艺参数指标（对应 OPC-UA 上的一个 Double 变量节点）。 */
  public record Metric(
      String equipmentCode,
      String equipmentName,
      String key,
      String metricName,
      String unit,
      double lowerLimit,
      double upperLimit,
      double seed,
      double amplitude,
      double liveValue) {}

  /** 根节点 BrowseName / NodeId 的字符串标识。 */
  public static final String ROOT_NAME = "FluxMES";

  /** 指标节点 BrowseName 的分隔符：{@code <code>.<key>}，如 {@code F-101.temp}。 */
  public static final String SEPARATOR = ".";

  public static final List<Metric> METRICS = List.of(
        new Metric("F-101", "发酵罐 #1", "temp", "罐温", "°C", 35, 38, 36.8, 0.35, 36.8),
        new Metric("F-101", "发酵罐 #1", "ph", "pH", "", 1.9, 2.3, 2.05, 0.05, 2.05),
        new Metric("F-101", "发酵罐 #1", "do", "溶氧 DO", "%", 25, 60, 34, 2.4, 34),
        new Metric("F-101", "发酵罐 #1", "rpm", "搅拌转速", "rpm", 150, 220, 180, 3, 180),
        new Metric("F-102", "发酵罐 #2", "temp", "罐温", "°C", 35, 38, 37.2, 0.4, 37.2),
        new Metric("F-102", "发酵罐 #2", "ph", "pH", "", 1.9, 2.3, 1.98, 0.06, 1.98),
        new Metric("F-102", "发酵罐 #2", "do", "溶氧 DO", "%", 25, 60, 18, 1.8, 18),
        new Metric("F-102", "发酵罐 #2", "rpm", "搅拌转速", "rpm", 150, 220, 182, 3, 182),
        new Metric("F-103", "发酵罐 #3", "temp", "清洗温度", "°C", 80, 88, 85, 0.5, 85),
        new Metric("F-103", "发酵罐 #3", "flow", "碱液流量", "m³/h", 1.5, 3, 2.1, 0.08, 2.1),
        new Metric("F-103", "发酵罐 #3", "cond", "电导率", "mS/cm", 30, 60, 42, 1.5, 42),
        new Metric("M-201", "配料罐 #1", "temp", "料温", "°C", 42, 48, 45, 0.4, 45),
        new Metric("M-201", "配料罐 #1", "rpm", "搅拌转速", "rpm", 50, 80, 62, 2.2, 62),
        new Metric("M-201", "配料罐 #1", "cur", "搅拌电流", "A", 25, 40, 33.2, 1.4, 33.2),
        new Metric("E-501", "MVR 浓缩器", "press", "加热蒸汽压力", "MPa", 0.3, 0.6, 0.62, 0.015, 0.62),
        new Metric("E-501", "MVR 浓缩器", "vac", "真空度", "MPa", -0.06, -0.04, -0.052, 0.001, -0.052),
        new Metric("E-501", "MVR 浓缩器", "temp", "浓缩温度", "°C", 65, 72, 68.5, 0.5, 68.5),
        new Metric("C-601", "结晶罐", "temp", "结晶温度", "°C", 26, 31, 28.5, 0.3, 28.5),
        new Metric("C-601", "结晶罐", "rpm", "搅拌转速", "rpm", 35, 55, 42, 1.5, 42),
        new Metric("C-601", "结晶罐", "flow", "冷却水流量", "m³/h", 16, 24, 15.8, 0.4, 15.8),
        new Metric("D-701", "流化床干燥机", "temp", "进风温度", "°C", 75, 82, 78.2, 0.6, 78.2),
        new Metric("D-701", "流化床干燥机", "tout", "出风温度", "°C", 58, 63, 61.4, 0.8, 61.4),
        new Metric("D-701", "流化床干燥机", "water", "物料水分", "%", 0, 0.5, 0.42, 0.02, 0.42),
        new Metric("F-104", "发酵罐 #4", "temp", "罐温", "°C", 20, 30, 24.6, 0.2, 24.6)
  );

  /** 元数据节点后缀（值节点之外的伴随节点）。 */
  public static final String META_UNIT = "unit";
  public static final String META_LO = "lo";
  public static final String META_HI = "hi";
  public static final String META_NAME = "name";
  public static final List<String> META_SUFFIXES =
      List.of(META_UNIT, META_LO, META_HI, META_NAME);

  private NodeCatalog() {}
}
