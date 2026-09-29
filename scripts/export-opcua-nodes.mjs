#!/usr/bin/env node
/**
 * 从 api-java 的 fixture（mes.json → equipmentSpec）导出 OPC-UA 地址空间目录。
 *
 * 为什么要导出而不是让模拟器直接读 mes.json：
 *   模拟器是**独立进程**，不应依赖 MES 应用的资源目录；而地址空间本质上是
 *   「SCADA 侧的组态」，应当是被生成、被 review 的产物，而不是运行时猜出来的。
 *   导出成 Java 常量后，节点清单在 code review 里是可见的。
 *
 * 用法：node scripts/export-opcua-nodes.mjs
 * 产出：tools/ext-simulator/src/main/java/com/fluxmes/sim/NodeCatalog.java
 */
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const src = resolve(root, 'apps/api-java/src/main/resources/fixtures/mes.json')
const out = resolve(root, 'tools/ext-simulator/src/main/java/com/fluxmes/sim/NodeCatalog.java')
const outMock = resolve(root, 'tools/ext-simulator/src/main/java/com/fluxmes/sim/ExtMockData.java')

const data = JSON.parse(readFileSync(src, 'utf8'))
const spec = data.equipmentSpec || []

if (spec.length === 0) {
  console.error('equipmentSpec 为空，导出中止')
  process.exit(1)
}

const javaStr = (s) => `"${String(s ?? '').replace(/\\/g, '\\\\').replace(/"/g, '\\"')}"`
const javaNum = (n) => {
  const v = Number(n)
  return Number.isFinite(v) ? String(v) : '0'
}

const lines = []
let count = 0
for (const e of spec) {
  for (const p of e.params || []) {
    count += 1
    lines.push(
      `        new Metric(${javaStr(e.code)}, ${javaStr(e.name)}, ${javaStr(p.key)}, ` +
        `${javaStr(p.name)}, ${javaStr(p.unit)}, ${javaNum(p.lo)}, ${javaNum(p.hi)}, ` +
        `${javaNum(p.seed)}, ${javaNum(p.amp)}, ${javaNum(p.value)})`
    )
  }
}
// Java 不允许尾随逗号，用 join 拼接而非逐行加逗号
const metricsBlock = lines.join(',\n')

const content = `package com.fluxmes.sim;

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
 * 当前导出 ${spec.length} 台设备 / ${count} 个工艺参数指标。
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
${metricsBlock}
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
`

mkdirSync(dirname(out), { recursive: true })
writeFileSync(out, content, 'utf8')
console.log(`已导出 ${spec.length} 台设备 / ${count} 个指标 → ${out}`)

/* ---------------------------------------------------------------------------
 * 第二份产物：LIMS / ERP 的 mock 数据（ExtMockData）
 * 同样来自 fixture，保证 mock 外部系统返回的批次号/检验项与 MES 内数据对得上——
 * 否则演示时会看到"MES 里根本没有这个批次"这种假到没法看的结果。
 * ------------------------------------------------------------------------- */

const jstr = (s) => `"${String(s ?? '').replace(/\\/g, '\\\\').replace(/"/g, '\\"')}"`

const qcRows = (data.qcTasks || [])
  .filter((t) => t && t.id && t.batch)
  .map((t) => {
    const pass = Number(t.pass) || 0
    const sample = Number(t.sample) || 0
    const allPass = sample > 0 && pass >= sample
    return (
      `        new LabResultRow(${jstr(t.id)}, ${jstr(t.batch)}, ${jstr(t.item || '成品检验')}, ` +
      `${jstr(t.inspector)}, ${allPass ? 'true' : 'false'})`
    )
  })

const erpRows = (data.runningBatches || [])
  .slice(0, 3)
  .map(
    (b) =>
      `        new WorkOrderRow(${jstr('ERP-WO-' + String(b.id).replace('B-', ''))}, ` +
      `${jstr(b.product)}, ${jstr(b.recipe)}, ${jstr('SITE-01')})`
  )

const labBlock = qcRows.join(',\n')
const woBlock = erpRows.join(',\n')

const mockContent = `package com.fluxmes.sim;

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
 * 当前导出 ${qcRows.length} 条检验结果 / ${erpRows.length} 张 ERP 工单。
 */
public final class ExtMockData {

  /** LIMS 待回流的检验结果行。 */
  public record LabResultRow(String taskNo, String batchId, String item, String inspector,
      boolean pass) {}

  /** ERP 待下发的生产工单行。 */
  public record WorkOrderRow(String orderNo, String product, String recipeCode, String siteCode) {}

  public static final List<LabResultRow> LAB_RESULTS = List.of(
${labBlock}
  );

  public static final List<WorkOrderRow> WORK_ORDERS = List.of(
${woBlock}
  );

  private ExtMockData() {}
}
`

writeFileSync(outMock, mockContent, 'utf8')
console.log(
  `已导出 ${qcRows.length} 条检验结果 / ${erpRows.length} 张 ERP 工单 → ${outMock}`
)
