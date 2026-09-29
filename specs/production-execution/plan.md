# 生产执行 · 技术计划（plan）

- **模块**：production-execution
- **依赖**：`spec.md`、`specs/constitution.md`
- **版本**：v1.0 · 2026-09-14
- **落地阶段**：Phase I（优先）

## 1. 架构与数据模型

新增 `resources/db/schema-p5.sql`（幂等，随应用启动执行）：

| 表 | 用途 | 关键字段 |
| --- | --- | --- |
| `work_order` | 工单主数据 | id(PK `WO-yyMMdd-seq`)、batch_id、line、site、product、recipe_code、recipe_version、plan_qty、unit、plan_start、plan_end、shift、status、plan_minutes、created_by、released_at、finished_at、closed_at、remark |
| `work_order_assignment` | 派工（含撤销留痕） | id、order_id、username、display_name、role_in_order(OPERATOR/REVIEWER)、capability、assigned_by、assigned_at、revoked_by、revoked_at |
| `step_report` | 工序报工 | id、order_id、batch_id、step_no、step_name、equipment、username、reviewer、started_at、finished_at、duration_min、input_qty、good_qty、scrap_qty、std_minutes、critical、created_at、remark |
| `downtime_event` | 停机事件（带原因码） | id、order_id(nullable)、equipment、line、site、shift、reason_code、planned、started_at、ended_at、duration_min、description、source(EVENT/MANUAL)、created_by、created_at |
| `downtime_reason` | 停机原因码字典 | code(PK)、name、category(PLANNED/UNPLANNED)、alarm_threshold_min、enabled、sort_no |
| `oee_rollup` | OEE 预汇总（按 范围×日×班次） | id、scope_type(LINE/EQUIPMENT)、scope_key、stat_date、shift、site、planned_minutes、unplanned_stop_minutes、run_minutes、std_minutes、good_qty、scrap_qty、availability、performance、quality、oee、data_sufficient、missing(JSON)、computed_at |

唯一约束：`oee_rollup(scope_type, scope_key, stat_date, shift)`，重算走 upsert。

沿用既有分层约定：全部为可变业务数据 → PostgreSQL；无时序库依赖。

### 派生关系

```
batch (1) ──< work_order (N) ──< work_order_assignment
                             ──< step_report   ──> batch_step (回写 status / actual)
                             ──< downtime_event ──> equipment_event (时间窗合并)
oee_rollup  ← 聚合 step_report + downtime_event
```

## 2. 关键决策

| # | 决策 | 理由 |
| --- | --- | --- |
| D1 | 工单与批次为 **N:1**，工单继承配方版本快照 | 发酵/精制可拆单；版本快照避免批次换版导致执行记录漂移（C2） |
| D2 | 批次 `progress` **只由报工推导**，关闭其他写入入口 | 消除双写不一致（现状 progress 手工维护，与产量无因果关系） |
| D3 | 计划/非计划停机分类挂在**原因码**上（`downtime_reason.category`） | 换型与 CIP 属食品行业计划性损失，计入可用率会误导改善方向 |
| D4 | OEE 走 `oee_rollup` 预汇总 + 实时查询双轨 | 驾驶舱频繁查询不能每次全表扫报工；写入后 30s 失效保证新鲜度 |
| D5 | **移除全部硬编码兜底**（92.1 / 96.5 / 33.4 等） | 兜底值让 OEE 从未真实过（曾出现 105%）；数据不足时返回 null + 缺失说明 |
| D6 | 停机事件与 `equipment_event` **时间窗合并**而非同时累加 | 同一段停机被两处记录会重复扣减可用率 |
| D7 | 派工资质校验复用 `personnel-certification` 的 `capability_dict` | 不重建第二套资质模型，保持单一事实源 |

## 3. 集成点与端点设计

包路径：`com.fluxmes.api.execution`（`ExecutionController` / `ExecutionService` / `OeeService`）。
前缀 `/api/execution`，权限沿用 `Roles`（工艺员 / 值班长 / 质检员 / 管理员）。

| 端点 | 说明 | 权限 |
| --- | --- | --- |
| `GET /api/execution/orders` | 工单列表（?batchId=&line=&site=&status=&shift=&from=&to=） | 登录 |
| `POST /api/execution/orders` | 建工单（继承批次快照） | 值班长+ |
| `GET /api/execution/orders/{id}` | 详情：派工 + 报工 + 停机聚合 | 登录 |
| `POST /api/execution/orders/{id}/release` | CREATED → RELEASED | 值班长+ |
| `POST /api/execution/orders/{id}/start` · `/finish` · `/close` | RUNNING / FINISHED / CLOSED | 工艺员+ / 值班长+ |
| `POST /api/execution/orders/{id}/dispatch` | 派工（资质门禁 FR-6/7/8） | 值班长+ |
| `DELETE /api/execution/orders/{id}/dispatch/{aid}` | 撤销派工（软删） | 值班长+ |
| `GET·POST /api/execution/orders/{id}/reports` | 报工列表 / 提交报工 | 提交需工艺员+ |
| `GET·POST /api/execution/downtime` | 停机列表 / 录入 | 录入需工艺员+ |
| `GET /api/execution/downtime/reasons` | 原因码字典 | 登录 |
| `GET /api/execution/oee` | 真实 OEE（?site=&line=&equipment=&shift=&from=&to=） | 登录 |
| `POST /api/execution/oee/recompute` | 手动重算某日汇总 | 值班长+ |
| `GET /api/execution/oee/pareto` | 停机原因帕累托 | 登录 |

**契约变更**：`GET /api/dashboard/cockpit` 的 `oee` 字段改用本模块聚合，
新增 `dataSufficient` 与 `missing` 字段；前端须处理 `null`（显示「数据不足」而非 0）。

## 4. 合规与安全设计

- **C2 批次档案完整性**：已放行批次禁止报工（FR-14）；工单继承配方版本快照，历史不漂移。
- **C3 审计**：工单状态流转、派工/撤销、报工、停机录入全部写 `audit_log`（who/when/前后值）。
- **C4 RBAC**：报工=工艺员+；派工与工单释放=值班长+；关闭工单=管理员；查询=登录即可。
- **C6 数据保留**：报工与停机 ≥ 3 年，与 `equipment_event` 同一归档策略。
- **食品行业法定**：派工与报工强制健康证在有效期内（复用 G2 门禁），缺证 403 不降级为警告。

## 5. 风险

| # | 风险 | 缓解 |
| --- | --- | --- |
| R1 | 移除硬编码兜底后驾驶舱 OEE 空白 | 种子数据为近 14 天补录报工与停机；前端显示「数据不足」而非 0 |
| R2 | `progress` 改由报工推导，历史批次进度失真 | 迁移脚本按现有 `progress` 反推补录工单与报工，标记 `source=BACKFILL` |
| R3 | 停机事件与设备状态事件重复扣减 | 时间窗合并（D6），响应标注 `mergedFrom` |
| R4 | `oee_rollup` 缓存与实时写入不一致 | 写入后 30s 失效；提供 `/recompute` 手动对齐 |
| R5 | 单位混乱（kg vs 吨）复发（曾致 OEE 105%） | 统一以 **kg** 存储，`unit` 字段显式；换算只发生在展示层 |
| R6 | 关键工序双人复核被绕过 | 服务端强制 `reviewer != username`，与 eBR 同口径 |
