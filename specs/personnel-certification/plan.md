# 人员资质与健康证 · 技术计划（plan）

- **模块**：personnel-certification
- **依赖**：`spec.md`、`specs/constitution.md`
- **版本**：v1.0 · 2026-09-14
- **实现状态**：✅ 已实现（Phase G2），本文档为回填

## 1. 架构与数据模型

表位于 `resources/db/schema-p3.sql`：

| 表 | 用途 | 关键字段 |
| --- | --- | --- |
| `person_certificate` | 证书台账 | id、username、display_name、cert_type(HEALTH/QUALIFICATION)、cert_name、capability、cert_no、issued_by、issued_at、valid_until、status(VALID/EXPIRED/REVOKED)、remark、created_at |
| `capability_dict` | 能力项字典 | code(PK)、name、description、required_role |

索引：`idx_pcert_user(username)`、`idx_pcert_cap(capability)`、`idx_pcert_due(valid_until)`。

种子能力项 6 项：`WEIGHING`（配料称量）/`CCP_MONITOR`（CCP 监控）/`RELEASE`（成品放行）/
`BATCH_REVIEW`（批记录复核）/`SANITATION`（清场作业）/`LAB_TEST`（理化检验）。

## 2. 关键决策

| # | 决策 | 理由 |
| --- | --- | --- |
| D1 | 证书与用户**弱关联**（`username` 字符串，非外键） | 避免用户表变更影响证书历史；证书是独立合规证据 |
| D2 | 健康证与岗位资质**同表不同型**，用 `cert_type` 区分 | 两者字段高度重合；分开建表会使门禁查询复杂化 |
| D3 | 门禁采用**渐进启用**：能力项有人持证才强制 | 未配置资质数据的环境（含演示/种子不完整时）不应整体不可用 |
| D4 | 过期判定**不落定时任务**，改为查询路径上的幂等巡检 | 无调度器依赖（未引入 Quartz/Spring Scheduler），任何查询都会自愈 |
| D5 | 非 HTTP 上下文（种子/定时任务）跳过门禁 | `CurrentUser` 无身份时放行，避免基础设施被自己的门禁锁死 |
| D6 | 吊销用状态位而非物理删除 | 合规证据不可销毁（C3/C6） |

## 3. 集成点与端点

包路径：`com.fluxmes.api.personnel`。

| 端点 | 说明 | 权限 |
| --- | --- | --- |
| `GET /api/personnel/certificates` | 台账（?username=&certType=） | 登录 |
| `POST /api/personnel/certificates` | 登记/发证 | 值班长+ |
| `POST /api/personnel/certificates/{id}/revoke` | 吊销（含原因） | 值班长+ |
| `GET /api/personnel/capabilities` | 能力项字典（含持证人数与是否强制） | 登录 |
| `GET /api/personnel/alerts?days=30` | 到期预警（已过期 / 临期） | 登录 |
| `GET /api/personnel/summary` | 合规概览 | 登录 |
| `GET /api/personnel/{username}` | 个人资质档案 | 登录 |

**门禁接入方式**：业务服务注入 `PersonnelService` 后调用
`personnel.requireCapability(PersonnelService.CAP_XXX)`，失败抛
`ResponseStatusException(403, <缺失说明>)`。已在 7 处接入（见 spec FR-12）。

## 4. 合规与安全设计

- **C3 审计**：登记与吊销写 `audit_log`（who/when/前后值）。
- **C4 RBAC**：查询 = 登录；登记/吊销 = 值班长及以上。
- **C6 数据保留**：证书（含吊销与过期）留存 ≥ 3 年。
- **法定依据**：《食品安全法》第四十五条（健康证明）、HACCP 体系对关键岗位资质的要求。

## 5. 风险

| # | 风险 | 缓解 |
| --- | --- | --- |
| R1 | 渐进启用（D3）导致「配了一半」的门禁形同虚设 | `/capabilities` 与 `/summary` 显式暴露 `enforced` 标志，运维可自检 |
| R2 | 巡检放在查询路径上，大表下拖慢响应 | 索引 `idx_pcert_due` + 巡检异常降级为日志（NFR-4） |
| R3 | 用户改名后证书失联 | 证书冗余存 `display_name` 快照，改名不影响历史证据 |
| R4 | 门禁被新增业务动作遗漏 | 能力项字典为准入清单；新增动作须在 code review 中确认接入 |
