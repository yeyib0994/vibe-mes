# 生产驾驶舱 · 技术计划（plan）

- **模块**：production-cockpit
- **对应 spec**：`specs/production-cockpit/spec.md`
- **版本**：v1.0 · 2026-08-26

## 1. 架构概览

```
Dashboard (src/pages/Dashboard.jsx)
   ├─ KpiCard            # 产量/合格率/OEE/活跃报警 四类卡片
   ├─ AreaChart          # 生产趋势（Recharts + ReferenceLine 计划线）
   ├─ EquipmentList      # 罐区设备状态（Dot + 参数）
   ├─ RunningBatches     # 在制批次（Progress + ETA）
   └─ RecentAlarms       # 最新报警（跳转 onNavigate('alarms')）
        │
        ▼  Phase 1 起经服务层
   src/api/dashboard.js  # getCockpit(siteId) -> CockpitDTO
        │
        ▼
   Backend /aggregations  # 产量、合格率、OEE 计算（口径唯一来源）
   SCADA Adapter         # 设备状态与参数
```

**关键决策**：聚合计算（产量达成、合格率、OEE）放在**后端聚合服务**，前端只渲染；保证驾驶舱、批次、质量多页面口径一致，并满足 constitution 的「C2 口径可追溯」。

## 2. 数据模型

```ts
// Phase 1 数据契约（替换 src/data/mes.js）
interface CockpitDTO {
  siteId: string
  generatedAt: string            // ISO，用于时效校验
  kpis: {
    dailyOutput: { actual: number; plan: number; progressPct: number; vsSchedulePct: number }
    batchPassRate: { value: number; target: number }
    oee: { value: number; availability: number; performance: number; quality: number }
    activeAlarms: { total: number; critical: number; major: number; minor: number }
  }
  productionTrend: { t: string; out: number }[]      // 叠加 planRate 参考线
  planRate: number
  equipment: { code: string; name: string; status: 'running'|'cleaning'|'alarm'|'idle'; params: string }[]
  runningBatches: { id: string; product: string; stage: string; equipment: string; progress: number; eta: string }[]
  recentAlarms: AlarmSummary[]                        // 见 alarm-center 模块
}
```

- 设备状态枚举与 `src/data/mes.js` 的 `equipment[].status` 对齐（running/cleaning/alarm/idle），状态机由后端维护。
- `generatedAt` 供前端实现 spec FR-8 的时效降级。

## 3. 关键技术决策

- **Mock 适配器**：Phase 0 保留 `src/data/mes.js`，新增 `src/api/dashboard.js` 暴露 `getCockpit()`，内部返回 Mock；Phase 1 仅替换实现，页面无需改动（符合 constitution P6 渐进演进）。
- **实时刷新**：引入 TanStack Query `useQuery({ refetchInterval: 30_000 })`；刷新时保持展开/滚动态（避免 `key` 变化导致的重挂载）。
- **主题令牌**：KPI 卡片沿用 `Card` + `card-pad`，配色走 `--seed-*` / 派生令牌，新增 `KpiCard` 不得写死颜色。
- **班报导出**：Phase 1 用 `window.print()` + 打印样式，或后端生成 PDF；班报内容须含生成人/时间并写审计（constitution P5）。

## 4. 集成点

- **SCADA / OPC-UA**：设备状态、过程参数、产量计数实时采集 → 聚合服务。
- **ERP**：日产量计划（`planRate`、日计划）来自生产工单。
- **alarm-center**：最近报警与活跃报警计数由报警服务供给。
- **batch-management**：在制批次来自批次服务。

## 5. 合规与安全设计

- KPI 口径（合格率/OEE）计算逻辑须在后端可审计、可复核，避免前端重算导致多端不一致（C2）。
- 班报生成动作记录审计日志（who/when/班次），保留 ≥ 3 年（C3）。
- 多产线维度切换受 RBAC 约束：仅被授权产线的数据可见（C4）。

## 6. 风险

- **R1 · 口径漂移**：多页面各自计算合格率 → 缓解：唯一聚合服务。
- **R2 · 实时性误判**：数据延迟被当作真实值 → 缓解：FR-8 时效降级 + 明确「最近更新」时间戳（原型已显示「最近更新 12 秒前」）。
- **R3 · 性能**：多产线聚合查询重 → 缓解：预聚合物化视图 / 缓存 30s。
