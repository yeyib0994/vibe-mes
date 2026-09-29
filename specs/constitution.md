# FluxMES 项目章程（Constitution）

> 本文件是 FluxMES 规范驱动开发的**根约束**。所有功能规范（spec）、技术计划（plan）与任务（tasks）均不得违反本章内容。修改本文件需经项目负责人评审并记录版本。

- **项目**：FluxMES · 流程制造执行系统（Food-grade Process MES）
- **适用行业**：食品 / 食品添加剂流程制造（示例产线：柠檬酸发酵 — 清禾生物 · 一车间）
- **当前阶段**：Phase H 已完成（PostgreSQL 持久化 + 食品行业合规 + 设备/配方/追溯三模块落地）；
  Phase I 规划中（生产执行深化 → 质量体系 RegTech）
- **文档版本**：v1.1 · 2026-09-14（v1.0 · 2026-08-26）
- **工作流**：Spec Kit（constitution → specify → plan → tasks → implement → analyze）

---

## 1. 项目背景

FluxMES 面向**流程型食品制造企业的制造执行层（MES）**，覆盖从配料、发酵/反应、精制、结晶、干燥到包装的完整批次生产链路。原型以「柠檬酸系列 + 食品级柠檬酸钠」产线为示例数据，验证了一套深色主题、高密度信息呈现、面向工艺员与值班长的生产监控界面。

当前交付物（`src/`，约 1500 行）是一个 React + Vite + Tailwind 的纯前端高保真原型，数据全部来自 `src/data/mes.js` 的静态示例。本章程的目的是将这套界面从「原型」升级为「可落地、合规、可审计的工业级 MES」的方法论基石。

## 2. 目标（Goals）

1. 为食品流程制造提供**批次级**的实时监控、执行与追溯能力。
2. 在单一界面内收敛「生产驾驶舱 / 批次管理 / 质量管理 / 报警中心」四类核心场景。
3. 满足食品行业合规基线（HACCP、GMP、批次可追溯、审计追踪、数据保留）。
4. 以**规范驱动（Spec-Kit）**方式演进，使需求、设计、任务可追踪、可复核。
5. 保持原型的视觉与交互优点（深色高密度、种子令牌换肤），并补齐浅色主题与可访问性。

## 3. 非目标（Non-Goals）

1. **不做**设备 PLC / DCS 控制逻辑本身（控制归 SCADA/PCS，MES 仅采集与下发指令）。
2. **不做**企业资源计划（ERP）的财务、采购、销售模块（通过集成而非自建）。
3. **不做**实验室信息管理系统（LIMS）的仪器直连（通过接口集成，检验结果回流 MES）。
4. 原型阶段**不强制**后端服务与数据库落地（Phase 0 允许 Mock），但 Phase 1 起必须接入。
5. **不绑定**单一云厂商；部署形态应支持私有化（食品企业常见合规要求）。

## 4. 设计原则（Design Principles）

- **P1 · 批次是第一公民**：所有页面、记录、报警、质量数据都必须能关联到具体「批次号」，以支持正向/逆向追溯。
- **P2 · 信息密度优先，但不牺牲可读性**：沿用原型的高密度卡片 + 等宽数字（`tabular-nums`）+ 技术标签风格；关键阈值、状态必须视觉可辨。
- **P3 · 深色为默认，浅色需可用**：种子令牌（`--seed-*`）+ 派生令牌（`color-mix`）结构必须保留，换肤只需改种子值；浅色主题须通过对比度校验。
- **P4 · 实时数据须标注时效**：任何采集数据都要显示「最近更新」时间戳与采集周期（原型为 30s），超期须明显降级提示。
- **P5 · 关键操作不可抵赖**：批次放行、报警确认、偏差关闭等动作必须留审计日志（操作人、时间、前后值）。
- **P6 · 渐进式演进**：先保原型交互，再接数据，再接设备/质量/报警实时流，最后补全配方、追溯、设备监控。

## 5. 技术栈约束（Tech Constraints）

### 前端（沿用并演进原型）
- **框架**：React 18 + Vite 5（继续沿用，不升级破坏性版本）。
- **样式**：Tailwind CSS 3；设计令牌保留「种子层 + 派生层」（`src/index.css` 的 `:root`）。
- **组件**：shadcn/ui 风格自研实现（`src/components/ui.jsx`），新增组件须沿用同一套 `cn()` + `Badge/Card/Button/Progress/Tabs/Input/Dot` 原语。
- **图表**：Recharts（驾驶舱/质量/报警的 Area/Line/Bar/Composed 图保持一致的 `AXIS_TICK`/`ChartTip` 约定，见 `src/components/charts.jsx`）。
- **图标**：lucide-react。
- **状态管理**：原型用 `useState`/`useMemo` 局部状态；Phase 1 起引入服务端数据层（建议 TanStack Query）与轻量全局态（Zustand 或 Context），**禁止**在组件内硬编码业务数据。

### 数据层（Phase 1 起强制）
- **Mock 隔离**：所有示例数据从 `src/data/mes.js` 抽出，改为通过 `src/api/*` 服务层访问，原型可继续用 Mock 适配器。
- **持久化**：关系数据（批次、配方、检验、报警、用户）入 PostgreSQL；时序/过程数据（罐温、pH、产量趋势）入时序库（TDengine / InfluxDB）。
- **实时**：设备与报警流通过 WebSocket / SSE 推送，刷新周期 ≤ 30s，超时进入降级态。

### 后端与集成（建议，非强制绑定）
- 后端使用Java，集成最新版springboot；需提供 OpenAPI和前台交互。
- **必须集成**：SCADA/OPC-UA（设备采集）、LIMS（检验回流）、ERP（工单/物料）、WMS（入库）。
- 集成边界以「接口契约 + 适配器」隔离，避免因外部系统变更污染前端。

## 6. 食品行业合规约束（Compliance）

- **C1 · 可追溯性**：任意成品批次须能在 ≤ 1 次操作内正向追溯（原料→工序→设备→操作员→检验→入库）与逆向追溯（成品→原料批号）。
- **C2 · 批次档案完整性**：批次记录须含配方版本、原料批号、关键过程参数、操作员、检验结果、COA、入库位，且**不可在放行后篡改**（只读 + 审计）。
- **C3 · 审计追踪（Audit Trail）**：对放行、确认、关闭、删除等动作记录 `who / when / what / before / after`，保留 ≥ 3 年（与《食品安全法》追溯要求对齐）。
- **C4 · 权限分级（RBAC）**：工艺员、质检员、值班长、管理员权限分离；报警确认与批次放行须有相应角色。
- **C5 · 依据标注**：质量判定须标注执行标准（如柠檬酸含量依据 **GB 1886.25—2016**），标准版本变更须留痕。
- **C6 · 数据保留**：报警数据保留 ≥ 3 年（原型已声明），过程数据按合规与性能平衡保留。

## 7. 规范要求（Spec-Kit 纪律）

- 所有功能需求使用 **EARS 格式**：`[WHEN <触发>] [IF <条件>] THE SYSTEM SHALL <行为>`。
- 每个 `spec.md` 必须含：用户场景、功能需求、非功能需求、边界、成功标准（可验收）。
- 每个 `plan.md` 必须含：架构/数据模型、关键决策、集成点、合规与安全设计、风险。
- 每个 `tasks.md` 必须含：有序任务、优先级（P0/P1/P2）、每条任务的验收标准；现状已实现项标注「已原型实现」。
- 模块拆分：
  - 已 specify：`production-cockpit` / `batch-management` / `quality-management` / `alarm-center` /
    `equipment-management` / `recipe-management` / `traceability` / `personnel-certification` /
    `weighing-dispensing` / `multi-site`；
  - 规划中（Phase I）：`production-execution` / `quality-regtech`。
- **回填纪律**：任何先落地后补文档的能力，其 `spec.md` 须在文首标注「回填」，
  `plan.md` 标注实现状态，`tasks.md` 用 ✅/⬜ 区分已实现与待办，避免文档与代码脱节。

## 8. 质量要求（Quality Bar）

- **性能**：驾驶舱首屏 < 1.5s；实时卡片刷新 ≤ 30s；图表渲染无明显卡顿（数据量 ≤ 1k 点）。
- **可访问性**：关键信息对比度满足 WCAG 2.1 AA；状态不使用「仅颜色」传达（须配文字/图标，原型已用 Dot+文字，保持）。
- **一致性**：所有列表/表格沿用原型的 `Card` + `divide-y` + 等宽数字约定。
- **可观测**：前端错误与接口失败须有降级展示，不白屏。
- **可测试**：服务层与纯函数（如帕累托累计、SPC 判异、报警分级）须可单元测试。

## 9. 模块索引

| 模块 | 路径 | 状态 |
| --- | --- | --- |
| 项目章程 | `specs/constitution.md` | 本文件 |
| 生产驾驶舱 | `specs/production-cockpit/` | Phase 0 已实现 · Phase 1 持久化 + D2 多产线 / D3 班报 |
| 批次管理 | `specs/batch-management/` | Phase 0 已实现 · Phase 1 状态机 + 分页 + 放行锁定 |
| 质量管理 | `specs/quality-management/` | Phase 0 已实现 · Phase 1 控制限 + 判异 + COA 放行 |
| 报警中心 | `specs/alarm-center/` | Phase 0 已实现 · Phase 1 审计 + D1 SLA 升级/抑制 |
| 设备管理 | `specs/equipment-management/` | ✅ Phase H 已实现（T1–T11）；T12 OPC-UA 采集为 Phase 2 |
| 配方管理 | `specs/recipe-management/` | ✅ Phase H 已实现（版本受控 + 审批流 + 影响分析） |
| 追溯管理 | `specs/traceability/` | ✅ Phase H 已实现（复用 F2 表 + 谱系 + 查询审计） |
| 人员资质与健康证 | `specs/personnel-certification/` | ✅ Phase G2 已实现；本文档为回填（2026-09-14） |
| 配料称量与容差 | `specs/weighing-dispensing/` | ✅ Phase G3 已实现；本文档为回填（2026-09-14） |
| 多厂区 | `specs/multi-site/` | ◐ Phase G4 已实现过滤维度；强制数据隔离待办（T8） |
| 生产执行 | `specs/production-execution/` | ⬜ Phase I 待实现 · **优先**（工单/派工/报工/停机 → 真实 OEE） |
| 质量体系 RegTech | `specs/quality-regtech/` | ⬜ Phase I 待实现（CAPA 闭环 / 内审 / 电子签名） |

---

*本章程随项目演进更新；每次重大变更在文首记录版本与日期。*
