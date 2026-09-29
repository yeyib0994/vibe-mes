# 追溯管理 · 技术计划（plan）

- **模块**：traceability
- **依赖**：`spec.md`、`specs/constitution.md`
- **版本**：v1.0 · 2026-09-14

## 1. 架构与数据模型

| 表 | 用途 | 关键字段 |
| --- | --- | --- |
| `material_lot` | 原料批号主数据（新增） | lot_no(PK)、material、supplier、received_at、qty、unit、status |
| `batch_material` | 批次—原料消耗关系（新增） | id、batch_id、lot_no、qty、unit、charged_at、stage |
| `batch_genealogy` | 父子批次关系（新增） | id、parent_batch_id、child_batch_id、relation(投入/产出)、qty |
| `trace_query_log` | 追溯查询审计（新增） | id、actor、query_type(forward/backward)、key、result_count、created_at |

现有可复用：

- `batch.material_lots`（JSON 原料批号数组）——本期作为兼容字段保留，新增 `batch_material` 结构化表承载数量与工序，二者以种子同步。
- `audit_log` ——追溯查询审计（FR-7）可复用，但为便于统计单列 `trace_query_log`。

## 2. 关键决策

| # | 决策 | 理由 |
| --- | --- | --- |
| D1 | 谱系关系独立成表而非遍历 JSON | 逆向追溯与影响面分析需要可索引的关系查询（NFR-2） |
| D2 | 正向用「批次关系 + 物料消耗」两表拼装，不做图数据库 | 链路深度有限（≤ 5 层），关系型足够；避免引入 Neo4j 等新栈 |
| D3 | 追溯查询独立审计表 | 监管核查需要「谁查过什么」的专项统计 |
| D4 | 完整性校验在服务层做规则表驱动 | 缺失项清单可配置，随法规变化调整（C2/C5） |

## 3. 集成点

- `TraceController`：`GET /api/trace/{batchId}`（正向链路）、`GET /api/trace/backward?lotNo=`（逆向）、`GET /api/trace/{batchId}/completeness`（完整性）。
- 新增：`GET /api/trace/impact?lotNo=`（影响面 + 导出）、`POST /api/trace/export`（报表导出，写审计）。
- 横向关联：节点上聚合 `alarm`（按设备 + 时间窗）与 `deviation`（按 batch_id）。

## 4. 合规与安全设计

- **C2 批次档案完整性**：追溯视图强制校验必填字段，缺失即标记（FR-8）。
- **C3 审计**：追溯查询与导出写审计；导出含生成时间 + 操作人水印。
- **C4 RBAC**：查询=登录即可；导出与影响面=质检员+（涉及召回决策）。
- **C6 留存**：谱系与日志留存 ≥ 产品保质期 + 1 年。

## 5. 风险

| # | 风险 | 缓解 |
| --- | --- | --- |
| R1 | `batch.material_lots` 与 `batch_material` 双写不一致 | 以 `batch_material` 为准，JSON 字段只读兼容；种子阶段一次性同步 |
| R2 | 深链路递归性能 | 限制展开深度（默认 5 层）+ 关系表索引 |
| R3 | 导出报表体积大 | 分页导出 + 异步生成，前端轮询状态 |
