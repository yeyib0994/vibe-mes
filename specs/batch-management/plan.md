# 批次管理 · 技术计划（plan）

- **模块**：batch-management
- **对应 spec**：`specs/batch-management/spec.md`
- **版本**：v1.0 · 2026-08-26

## 1. 架构概览

```
Batches (src/pages/Batches.jsx)
   ├─ Tabs(状态) + Search(关键字)        # 筛选/搜索（原型本地，Phase1 服务端）
   ├─ BatchTable -> FragmentRow          # 台账行
   └─ BatchDetail                        # 展开：StageFlow（工艺路线）+ 档案
        │
        ▼  Phase 1 起
   src/api/batches.js
     listBatches(filter) / getBatch(id) / createBatch(input) / importPlan(file)
        │
        ▼
   Backend /batches                      # 状态机 + 审计
   Recipe Service（配方版本，规划模块）
   Material/Lot Service（原料批号）
   WMS（入库位）
```

## 2. 数据模型

```ts
// Phase 1 契约（结构化替换 mes.js 的 batches[]）
type BatchStatus = 'running' | 'waiting' | 'done' | 'abnormal'
type StageState = 'done' | 'active' | 'fail' | 'todo'

interface Batch {
  id: string                     // B-YYMMDD-NNN
  product: string
  recipeId: string               // 关联配方（规划模块）
  recipeVersion: string          // 如 R-CA-07 v3.2
  equipment: string              // 当前/主设备编码
  stage: string                  // 当前工序名
  params: string                 // 当前关键参数摘要（实时）
  progress: number               // 0-100
  status: BatchStatus
  start: string                  // ISO 开工时间
  stages: { name: string; state: StageState; startedAt?: string; endedAt?: string }[]
  meta: {
    operator: string
    planYield: number
    actualYield?: number
    materialLots: string[]       // 原料批号，如 RM-20260823
    inoculumLot?: string         // 接种物批号
    coaNo?: string               // 检验报告号
    warehouseBin?: string        // 入库位
    deviationNo?: string         // 异常关联偏差单
  }
  audit: AuditEntry[]            // 状态变更记录（C3）
}

interface AuditEntry { who: string; at: string; action: string; before: any; after: any }
```

- `meta` 由原型 `meta: string[]` 升级为结构化对象，保留展示兼容（渲染时按字段拼装）。
- `stages[].state` 与 `progress` 由后端依据工艺路线实际推进计算，前端不臆造。

## 3. 关键技术决策

- **状态机落地**：在后端用显式状态机（如状态表 + 守卫）实现 FR-5/FR-10/FR-11；前端仅渲染状态与禁用态。
- **Mock 适配器**：`src/api/batches.js` 在 Phase 0 返回 `mes.js` 的 `batches`；Phase 1 替换为 HTTP。
- **展开态**：原型用 `expanded` 单项展开（`useState`）；Phase 1 若与服务端拉取明细，改用路由 `/batches/:id` 或按需 `getBatch`，避免一次性拉全量档案。
- **新建/导入**：表单组件复用 `ui.jsx` 的 `Input/Button/Card`；提交经 `createBatch`/`importPlan`，失败回显校验错误。

## 4. 集成点

- **recipe-management（规划）**：批次引用配方版本，工艺路线来源于配方。
- **quality-management**：`waiting` 状态由成品检验合格驱动；COA 号回流批次档案。
- **WMS（规划）**：`done` 时写入入库位（`warehouseBin`），联动库存。
- **traceability（规划）**：FR-12 正/逆向追溯依赖本模块的 `materialLots` 与工序记录。
- **ERP**：生产计划导入来源；物料可用性校验。

## 5. 合规与安全设计

- **C1 可追溯**：`materialLots` + 工序记录 + 设备 + 操作员构成正向链路；逆向由成品 `id` 反查原料批号。
- **C2 档案完整**：导出（FR-7）必须含配方版本、原料批号、关键参数、操作员、检验、COA、入库位。
- **C3 审计**：所有状态变更写 `audit`（FR-11）；已放行（`done`）批次进入只读。
- **C4 权限**：新建/导入需计划员角色；放行需质检/值班长角色。

## 6. 风险

- **R1 · 工艺路线漂移**：批次内联 stages 与配方不一致 → 缓解：stages 由配方版本生成，批次仅记录实际进度。
- **R2 · 异常批次误操作**：异常批次被误推 → 缓解：状态机守卫，abnormal 阻断推进。
- **R3 · 大台账性能**：全量渲染卡顿 → 缓解：服务端分页 + 虚拟滚动（数据量 > 500 时）。
