# 生产执行 · 任务清单（tasks）

- **模块**：production-execution
- **依赖**：`spec.md`、`plan.md`
- **版本**：v1.1 · 2026-09-15（实现完成）
- 图例：✅ 已完成 · ◐ 部分完成 · ⬜ 待办；优先级 P0/P1/P2

## P0 · 工单主数据与派工

- ✅ **T1** 建表 `work_order` / `work_order_assignment` / `downtime_reason`（**已合并**到 `db/schema-p5.sql`，共 6 张表一次建完）
  - 验收：应用启动自动建表；重复启动不报错；`downtime_reason` 9 条原因码（MECH/ELEC/MATERIAL/QUALITY/UTILITY/OTHER 非计划，CHANGEOVER/CLEANING/NO_ORDER 计划）
- ✅ **T2** 实体 + Mapper（`WorkOrder` / `WorkOrderAssignment` / `DowntimeReason` / `StepReport` / `DowntimeEvent` / `OeeRollup`，均带 `@Mapper`）
  - 验收：`bash scripts/mvn.sh compile` 通过
- ✅ **T3** `POST /api/execution/orders`：继承批次产线/厂区/产品/配方版本快照，校验计划数量与时间区间（FR-1~FR-3）
  - 验收：`planQty<=0` 400；`planEnd<=planStart` 400；已放行批次 409；工单号 `WO-yyMMdd-NNN` 且循环判重
- ✅ **T4** 工单状态机 CREATED → RELEASED → RUNNING → FINISHED → CLOSED（FR-4），每次流转写审计
  - 验收：跳级（CREATED→RUNNING）409；重复下达 409；`/api/audit` 可查前后值；完工时校验合格量偏差 >5% 给出 `confirmNeeded` 提示（FR-5，不阻断）
- ✅ **T5** 派工 + 资质门禁（健康证 + 能力项），缺证 403 并列出缺失项；同时段冲突提示（FR-6~FR-8）
  - 验收：健康证过期账号 `temp` 派工 403；`confirmConflict=true` 可强制派工
- ✅ **T6** 派工撤销软删 + 审计（FR-9）
  - 验收：撤销后记录保留 `revokedBy/revokedAt/revokeReason`

## P1 · 报工与停机

- ✅ **T7** 建表 `step_report` / `downtime_event`（已含于 schema-p5.sql）
  - 验收：表与索引建立；`uq_sr_idem` 幂等唯一索引生效
- ✅ **T8** 报工写 `step_report` 并回写 `batch_step`（status=DONE + actualParams JSON + operator/reviewer）
  - 验收：报工后对应 eBR 工序状态与实际参数更新
- ✅ **T9** 批次 `progress` 由报工推导（D2）
  - 验收：报工后 `progress` = max(工序推推进度, 累计合格/计划×100)，上限 100；不再有手工改写入口
- ✅ **T10** 关键工序强制双人复核（FR-13）
  - 验收：自复核 400；`reviewer=qc` 通过；`critical` 可由请求显式声明或由工序名（关键/CCP/杀菌/灭菌）推断
- ✅ **T11** 已放行批次报工拦截（FR-14）
  - 验收：`released=true` 批次报工 409
- ✅ **T12** 停机录入 + 原因码 + 时间窗合并（FR-15~FR-18）
  - 验收：同设备重叠时段合并为一条（`merged=true`），不重复扣减；`CHANGEOVER` 判 `planned=true`
- ✅ **T13** 长时停机自动生成报警（FR-19）
  - 验收：`MECH` ≥ 120min 产生 major 报警，返回 `alarmId`

## P2 · OEE 重算与前端

- ✅ **T14** `OeeService` 三因子按 FR-20 口径 + `oee_rollup` upsert
  - 验收：可用率=(计划工时−计划外停机)/计划工时；性能率=标准工时/实际工时；良品率=合格/(合格+废次品)
- ✅ **T15** **移除硬编码兜底**（92.1 / 96.5 / 33.4 / `Math.min(120,…)`），数据不足返回 null（FR-21）
  - 验收：驾驶舱 `kpi.oee.source=execution`；无数据区间 `oee=null && dataSufficient=false`；设备页 OEE 回落 fixture 采集值的逻辑已删除
- ✅ **T16** `GET /api/execution/oee` + `/pareto` + `/recompute`，支持厂区/产线/设备/班次/日期区间
  - 验收：帕累托降序 + 累计百分比；`/recompute` 按产线写 `oee_rollup`
- ✅ **T17** 历史数据迁移：按现有批次反推补录工单与报工（`source=BACKFILL`）
  - 验收：`core/P5Seeder`（@Order 140）幂等，回填 12 张工单 + 报工 + 停机，二次启动跳过
- ✅ **T18** 前端 `Execution.jsx`：工单看板、派工、报工、停机录入、OEE 因子卡、停机帕累托
  - 验收：角色受限按钮正确禁用；OEE 数据不足显示「数据不足」而非 0
- ✅ **T19** 驾驶舱 OEE/日产量改造：真实口径 + 数据不足降级态
  - 验收：`Dashboard.jsx` 移除 `87.7` / `33.4` 前端兜底，改为 `—` + 提示
- ⬜ **T20** `scripts/verify-phase5.mjs` 端到端自检 + 回归 phase1/3/4
  - 验收：Phase I 自检全绿；前端单测 21/21；Phase 1/3/4 回归无回退

## 实现说明

- **新增文件**：`db/schema-p5.sql`、`entity/{WorkOrder,WorkOrderAssignment,StepReport,DowntimeEvent,DowntimeReason,OeeRollup}.java`、
  `mapper/*Mapper.java`（6 个）、`execution/{ExecutionController,ExecutionService,OeeService}.java`、
  `core/P5Seeder.java`、`fluxmes/src/api/execution.ts`、`fluxmes/src/pages/Execution.jsx`、`scripts/verify-phase5.mjs`
- **改动文件**：`application.yml`（schema-locations 加 p5）、`DashboardController`（OEE 换源 + 日产量去兜底）、
  `EquipmentService`（OEE 换源）、`Dashboard.jsx`（降级态）、`layout.tsx` / `App.tsx`（导航与路由）
- **未采用的替代方案**：未引入任何新三方库（哈希/调度/校验均为 JDK 内置），符合章程技术栈红线
