# 配料称量与容差 · 技术计划（plan）

- **模块**：weighing-dispensing
- **依赖**：`spec.md`、`specs/constitution.md`
- **版本**：v1.0 · 2026-09-14
- **实现状态**：✅ 已实现（Phase G3），本文档为回填

## 1. 架构与数据模型

表位于 `resources/db/schema-p3.sql`：

| 表 | 用途 | 关键字段 |
| --- | --- | --- |
| `weighing_task` | 称量任务 | id(PK `WT-yyMMdd-nnn`)、batch_id、material_code、material_name、target_qty、unit、tolerance_pct、total_weighed、status(OPEN/DONE/BLOCKED)、created_by、created_at |
| `weighing_item` | 称量明细 | id、task_id、seq、actual_qty、deviation_pct、result(PASS/OVER/UNDER)、deviation_id、alarm_id、operator、reviewer、reviewed_at、equipment、weighed_at、remark |

索引：`idx_wtask_batch(batch_id)`、`idx_witem_task(task_id)`。

## 2. 关键决策

| # | 决策 | 理由 |
| --- | --- | --- |
| D1 | 容差**存任务级**而非全局配置 | 不同物料（主料 vs 微量添加剂）容差差异大；历史称量不受后续调参影响 |
| D2 | 偏差用 `BigDecimal` 计算，保留 6 位中间精度 | 避免浮点误差导致临界值（恰好 ±1.000%）误判 |
| D3 | 超差**同步**创建偏差单与报警（同一业务流） | 不依赖后续巡检，保证「超差必留痕」，且偏差单天然阻断放行 |
| D4 | 任务 `BLOCKED` 由超差触发、由复核解除 | 阻断粒度在任务级（而非批次级），避免一处超差卡死整批 |
| D5 | 复核人不得为称量人本人，在**服务端**强制 | 双人复核是食品/制药行业基本要求，前端校验可绕过 |
| D6 | 放行批次禁止补录（409） | C2 批次档案完整性：放行后档案只读 |
| D7 | 称量不直连衡器，人工录入 + 衡器编号 | 设备集成（OPC-UA / 串口）属 Phase 2，先保证业务闭环 |

## 3. 集成点与端点

包路径：`com.fluxmes.api.weighing`。

| 端点 | 说明 | 权限 |
| --- | --- | --- |
| `GET /api/weighing/tasks` | 任务列表（?batchId=&status=） | 登录 |
| `POST /api/weighing/tasks` | 创建任务 | 登录（批次需未放行） |
| `GET /api/weighing/tasks/{id}/items` | 称量明细 | 登录 |
| `POST /api/weighing/tasks/{id}/weigh` | 提交称量（容差判定） | 称量资质 + 健康证 |
| `POST /api/weighing/items/{id}/review` | 超差复核 | QC+ 且 `BATCH_REVIEW` 资质 |
| `GET /api/weighing/summary` | 合规看板 | 登录 |

**下游联动**：

- `DeviationMapper`：超差写偏差单（`source = weighing`），未关闭偏差阻断批次放行。
- `AlarmService.raise(...)`：超差触发 `major` 报警，进入 SLA 计时。
- `AuditService.record(...)`：四类动作全量审计。
- `PersonnelService.requireCapability(...)`：称量 `WEIGHING`、复核 `BATCH_REVIEW`。

## 4. 合规与安全设计

- **C2 批次档案完整性**：放行后禁止新建称量任务与补录数据（409）。
- **C3 审计追踪**：任务创建、称量、超差、复核全量留痕。
- **C4 RBAC**：称量=称量资质持有人；复核=QC 及以上；查询=登录。
- **C5 依据标注**：偏差单描述含「目标 / 实测 / 偏差 / 允许范围」，可直接作为调查输入。
- **C6 数据保留**：称量明细留存 ≥ 3 年。

## 5. 风险

| # | 风险 | 缓解 |
| --- | --- | --- |
| R1 | 人工录入导致数据不真实 | 保留 `equipment`（衡器编号）字段，Phase 2 接衡器直连后自动填充 |
| R2 | 目标量人工填写，与配方脱节 | P2 待办：从 `recipe_step` 设定值自动带出（见 tasks T11） |
| R3 | 超差报警风暴（连续多次超差） | 报警按任务 + 偏差单聚合展示；SLA 策略可配置（`alarm_sla_policy`） |
| R4 | 任务号并发冲突（`selectCount + 1`） | 单实例演示可接受；生产需改序列或数据库自增（见 tasks T12） |
