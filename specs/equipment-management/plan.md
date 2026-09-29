# 设备管理 · 技术计划（plan）

- **模块**：equipment-management
- **依赖**：`spec.md`、`specs/constitution.md`
- **版本**：v1.0 · 2026-09-14

## 1. 架构与数据模型

| 表 | 用途 | 关键字段 |
| --- | --- | --- |
| `equipment` | 设备主数据 | code(PK)、name、model、location、category、status、runtime_hours、next_maintenance_at、calibration_due_at、enabled |
| `equipment_event` | 状态事件流水 | id、equipment_code、from_status、to_status、reason、started_at、ended_at、operator |
| `maintenance_order` | 维护工单 | id、equipment_code、type(保养/维修)、status(open/done)、plan_date、done_at、operator、remark |
| `equipment_metric` | 实时工艺参数 | id、equipment_code、metric_key、value、unit、sampled_at |

沿用 Phase 1 数据分层约定：

- **可变业务数据**（主数据、事件、工单）→ PostgreSQL；
- **只读展示数据**（罐区设备卡、趋势序列）→ `FixtureStore`（mes.json），Phase 2 接真实采集后改为时序查询。

## 2. 关键决策

| # | 决策 | 理由 |
| --- | --- | --- |
| D1 | 设备状态用**事件流水**而非仅当前字段 | OEE 可用率需要停机时长证据；审计要求状态变更留痕（C3） |
| D2 | 主数据变更写**版本快照**，批次引用快照 | 避免设备改名导致历史批次档案失真（C2） |
| D3 | 实时参数先走 `FixtureStore`，不引入时序库 | 控制栈复杂度；Phase 2 数据量上来再评估 TimescaleDB |
| D4 | 校准有效期阻断在**服务层校验**而非仅前端 | 合规约束必须在服务端强制（C5） |

## 3. 集成点

- `EquipmentController`：`GET /api/equipment`（台账 + 筛选）、`GET /api/equipment/{code}`（详情 + 关联报警/批次/维护记录）。
- 新增：`POST /api/equipment/{code}/status`（状态变更，写事件 + 审计）、`POST /api/equipment/maintenance-orders`（工单）。
- 报警归属：`alarm.source` 以设备编号开头，设备详情按前缀聚合。

## 4. 合规与安全设计

- **C2 批次档案完整性**：批次引用的设备须为启用且校准在有效期内状态。
- **C3 审计**：状态变更、工单创建/完成、主数据修改全部写 `audit_log`。
- **C5 执行标准**：校准有效期作为检验数据可用性的前置条件。
- **C4 RBAC**：查看=登录即可；状态变更/工单=工艺员+；主数据维护=管理员。

## 5. 风险

| # | 风险 | 缓解 |
| --- | --- | --- |
| R1 | 采集数据缺失导致 OEE 失真 | 三因子均设兜底口径与「数据不足」标记，不伪造数值 |
| R2 | 事件流水表增长快 | 按月分区 + 保留 ≥3 年后归档（C6） |
| R3 | 设备改名影响历史追溯 | 主数据版本快照（D2） |
