# 配方管理 · 技术计划（plan）

- **模块**：recipe-management
- **依赖**：`spec.md`、`specs/constitution.md`
- **版本**：v1.0 · 2026-09-14

## 1. 架构与数据模型

| 表 | 用途 | 关键字段 |
| --- | --- | --- |
| `recipe`（已存在） | 配方主数据（当前生效视图） | code(PK)、name、product、version、status、used_batches、yield_rate、updated_by、updated_at |
| `recipe_version` | 版本受控表（新增） | id、recipe_code、version、status(draft/pending/effective/obsolete)、effective_at、obsolete_at、created_by、approved_by、change_note |
| `recipe_step` | 工序与参数（新增） | id、recipe_code、version、seq、stage、equipment_category、param_key、target、lower、upper、unit |
| `recipe_change` | 变更影响记录（新增） | id、recipe_code、from_version、to_version、affected_batches(JSON)、review_required |

现状：`recipe` 表已在 Phase 1 建立并由 `DatabaseSeeder` 从 mes.json 种子导入；`stages`/`params`/`history` 以 TEXT 存 JSON（沿用既有约定，避免 jsonb + TypeHandler 复杂度）。本期新增的三张表沿用同样约定。

## 2. 关键决策

| # | 决策 | 理由 |
| --- | --- | --- |
| D1 | 主表 `recipe` 保留为**当前生效视图**，`recipe_version` 承载版本受控 | 兼容既有查询与前端契约，避免一次性大改 |
| D2 | 版本状态流转用**显式状态机 + 服务层校验** | 生效唯一性（NFR-3）必须在服务端强制 |
| D3 | 参数容差用结构化表 `recipe_step` 而非 JSON | 容差校验与下发需要可索引、可比较的数值列 |
| D4 | 批次引用版本快照，不做联表实时解析 | 保证历史批次档案不可被配方升级污染（C2） |

## 3. 集成点

- `RecipeController`：`GET /api/recipes`（列表 + 在用批次数）、`GET /api/recipes/{code}/history`（版本历史）。
- 新增：`POST /api/recipes/{code}/draft`（基于版本建草稿）、`POST /api/recipes/{code}/submit`、`/approve`、`POST /api/recipes/{code}/activate?at=`（生效切换，原子事务）。
- 批次建单校验配方版本存在且为生效态；放行校验批次配方版本与检验标准一致。

## 4. 合规与安全设计

- **C2 数据完整性**：生效版本不可编辑；批次持有版本快照。
- **C3 审计**：草稿创建/提交/审批/生效全部写 `audit_log`（含参数 diff）。
- **C4 RBAC**：查看=登录即可；草稿与提交=工艺员+；审批与生效=管理员（或指定的技术负责人角色）。
- **C5 执行标准**：配方版本与检验标准版本关联，配方变更触发重评标记。

## 5. 风险

| # | 风险 | 缓解 |
| --- | --- | --- |
| R1 | 生效切换并发导致双生效 | 数据库唯一约束「同配方仅一个 effective」+ 事务内先置 obsolete 再置 effective |
| R2 | 参数结构迁移影响既有种子数据 | 种子导入时同步写入 `recipe_step`；缺失参数容差按 JSON 兜底 |
| R3 | 变更影响分析漏批次 | 影响面扫描以「批次.recipe_version = 旧版本且状态非 done」为条件 |
