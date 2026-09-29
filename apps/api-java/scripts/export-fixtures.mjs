// 把前端示例数据（fluxmes/src/data/mes.js）导出为后端 fixture JSON。
// 用法：node apps/api-java/scripts/export-fixtures.mjs
// 数据源变更（新增字段/模块）后重跑一次即可，后端无需手工同步。
import { writeFileSync, mkdirSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const repoRoot = resolve(here, '..', '..', '..')
const dataMod = resolve(repoRoot, 'fluxmes', 'src', 'data', 'mes.js')
const outDir = resolve(here, '..', 'src', 'main', 'resources', 'fixtures')

const m = await import(pathToFileURL(dataMod).href)

const fixture = {
  // 驾驶舱
  planRate: m.PLAN_RATE,
  productionTrend: m.productionTrend,
  tankEquipment: m.equipment,
  runningBatches: m.runningBatches,
  // 批次
  batches: m.batches,
  // 质量
  spcData: m.spcData,
  pareto: m.pareto,
  qcTasks: m.qcTasks,
  // 报警（含驾驶舱引用的最新报警）
  alarms: m.alarmData,
  alarmTrend: m.alarmTrend,
  alarmTopSources: m.alarmTopSources,
  recentAlarms: m.alarmData.slice(0, 4),
  // 设备
  equipmentSpec: m.equipmentSpec,
  // 配方
  recipes: m.recipes,
  // 追溯
  traceChains: m.traceChains,
  traceableBatches: m.traceableBatches,
}

mkdirSync(outDir, { recursive: true })
const out = resolve(outDir, 'mes.json')
writeFileSync(out, JSON.stringify(fixture, null, 2), 'utf8')
console.log(`fixture written: ${out} (${Object.keys(fixture).length} sections)`)
