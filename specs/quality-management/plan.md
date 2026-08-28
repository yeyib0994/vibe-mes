# 质量管理 · 技术计划（plan）

- **模块**：quality-management
- **对应 spec**：`specs/quality-management/spec.md`
- **版本**：v1.0 · 2026-08-26

## 1. 架构概览

```
Quality (src/pages/Quality.jsx)
   ├─ MiniStat ×4            # 成品一次合格率/检验完成/SPC失控/客户投诉
   ├─ SpcChart (LineChart)   # UCL/CL/LCL + 失控点 SpcDot
   ├─ ParetoChart (Composed) # 不合格项 Bar + 累计 Line
   └─ QcTaskTable            # 检验任务
        │
        ▼  Phase 1 起
   src/api/quality.js
     getSpc(product, char) / getPareto(range) / listQcTasks(filter)
     releaseBatch(id) / createDeviation(src) / getCoa(batchId)
        │
        ▼
   Quality Service（判异引擎 + 偏差状态机）
   LIMS Adapter（检验数据回流）
   Batch Service（放行状态联动）
```

## 2. 数据模型

```ts
// Phase 1 契约
interface SpcPoint { t: string; v: number; ooc: boolean; ruleHits?: string[] }
interface SpcLimit { ucl: number; cl: number; lcl: number; source: string } // 来自过程能力
interface ParetoItem { name: string; n: number; cum: number }

type QcType = '成品检验' | '过程巡检' | '首件检验'
type QcStatus = 'done' | 'review' | 'progress'
interface QcTask {
  id: string; type: QcType; batch: string; item: string
  sample: number; pass: number; inspector: string; status: QcStatus
  aql?: number                 // 接收质量限
}

interface Deviation {          // 偏差单 DEV-xxxx
  id: string; source: string; triggeredAt: string
  status: 'open'|'investigating'|'capa'|'closed'
  rootCause?: string; capa?: string; closedBy?: string; closedAt?: string
  audit: AuditEntry[]
}

interface COA {                // 检验报告/合格证
  no: string; batch: string; standard: string; standardVersion: string
  items: { name: string; result: number; spec: string; verdict: 'pass'|'fail' }[]
  issuedBy: string; issuedAt: string
}
```

- `SpcLimit` 由配置/过程能力计算，替换原型 `SPC_UCL/CL/LCL` 常量。
- 判异引擎为纯函数 `detectRules(points, limit): ruleHits[]`，便于单元测试（NFR-2）。

## 3. 关键技术决策

- **判异引擎独立**：Western Electric 8 规则（如 1 点超 3σ、连续 9 点同侧、连续 6 点单调等）实现为纯函数，UI 仅展示命中规则；原型只做了「超 UCL」单点判断，需扩展。
- **Mock 适配器**：`src/api/quality.js` Phase 0 返回 `mes.js` 的 `spcData/pareto/qcTasks`，Phase 1 替换。
- **偏差与放行解耦**：偏差（DEV）状态机独立；放行（FR-8）依赖「检验合格 + 偏差关闭」，由 Batch/Quality 协同。
- **图表一致性**：SPC 用 `LineChart` + 自定义 `SpcDot`（失控点加大描边），帕累托用 `ComposedChart`（Bar + Line），沿用 `charts.jsx` 的 `AXIS_TICK`/`ChartTip`。

## 4. 集成点

- **LIMS**：检验任务与结果回流（`qcTasks` 来源）；COA 可由 LIMS 出具，MES 编排。
- **batch-management**：放行状态联动；COA 号回写批次档案 `meta.coaNo`。
- **alarm-center**：SPC 失控可同步生成工艺报警（严重/重要）。
- **recipe-management（规划）**：检验项目与标准来自配方/产品规格。

## 5. 合规与安全设计

- **C5 标准标注**：所有判定显示标准及版本（GB 1886.25—2016），版本变更写审计。
- **C3 审计**：放行、复核、偏差关闭写审计（FR-11）。
- **C2 档案**：COA 字段完整回流批次，作为放行依据可追溯。
- **数据完整性**：SPC 原始样本不可篡改，判异结论可复核。

## 6. 风险

- **R1 · 控制限失准**：硬编码导致误判 → 缓解：`SpcLimit` 配置化、来源可追溯。
- **R2 · 误放行**：状态未闭环即放行 → 缓解：放行守卫（检验合格 ∧ 偏差关闭）。
- **R3 · 判异规则误报**：规则集过严 → 缓解：规则可配置、命中可人工确认。
