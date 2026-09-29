# 多厂区 · 技术计划（plan）

- **模块**：multi-site
- **依赖**：`spec.md`、`specs/constitution.md`
- **版本**：v1.0 · 2026-09-14
- **实现状态**：✅ Phase 1 已实现（Phase G4），本文档为回填

## 1. 架构与数据模型

表位于 `resources/db/schema-p3.sql`：

| 表 / 列 | 用途 | 关键字段 |
| --- | --- | --- |
| `site` | 厂区主数据 | code(PK)、name、address、enabled、created_at |
| `production_line.site_code` | 产线归属 | 新增列，回填 `SITE-01`；新增 LINE-4 属 `SITE-02` |
| `batch.site` | 批次归属 | 新增列 + `idx_batch_site`，回填 `SITE-01` |
| `app_user.site_code` | 用户归属（空 = 集团账号） | 新增列 + `idx_user_site`；`admin` 保持空 |
| `ccp_point.site_code` | CCP 点归属 | 新增列，回填 `SITE-01` |

种子厂区：`SITE-01` 清禾生物·城东工厂、`SITE-02` 清禾生物·滨海工厂。

## 2. 关键决策

| # | 决策 | 理由 |
| --- | --- | --- |
| D1 | 厂区维度用 **ALTER 加列**而非新建关联表 | 各实体与厂区均为 N:1，加列最简单且查询可直接走索引 |
| D2 | 厂区写进 **JWT claim** 而非每次查库 | 避免每个请求回查用户表；改厂区需重新登录生效（已知代价，见 R3） |
| D3 | **空 site = 集团账号**（可跨厂区） | 复用同一套权限模型，不为集团账号单开角色 |
| D4 | 缺省回落 `DEFAULT_SITE = SITE-01` | 保证历史数据与集团账号建单始终有归属，避免 NULL 维度 |
| D5 | **先维度打通，后强制隔离** | Phase 1 只做过滤参数与默认值；强制隔离涉及全量查询改造，单列 P2（tasks T7） |
| D6 | 厂区停用为软停（`enabled=false`） | 历史数据须保留（C6） |

## 3. 集成点与端点

| 端点 | 说明 | 权限 |
| --- | --- | --- |
| `GET /api/dashboard/sites` | 厂区主数据（含产线数、批次数） | 登录 |
| `GET /api/dashboard/lines?site=` | 产线列表按厂区过滤 | 登录 |
| `GET /api/dashboard/cockpit?site=` | 驾驶舱 KPI 按厂区 | 登录 |
| `GET /api/dashboard/shift-report?site=` | 班报按厂区 | 登录 |
| `GET /api/batches/lines?site=` | 产线（批次侧）按厂区 | 登录 |
| `GET /api/batches?site=` | 批次列表按厂区 | 登录 |

**兼容**：`GET /api/dashboard/cockpit/{siteId}` 保留旧契约，仅当路径变量以 `SITE-` 开头时才视为厂区。

**认证链路**：`JwtService.issue(userId, username, role, site)` → `AuthFilter` 解析 claims
→ `CurrentUser.Ctx(userId, username, role, site)` → `CurrentUser.site()` 供建单取默认值。

**前端**：`api/dashboard.ts` 增加 `sites()` 与 `site` 参数；`components/dashboard-controls.tsx`
的 SiteSwitcher 与产线切换器联动；`pages/Dashboard.jsx` 消费。

## 4. 合规与安全设计

- **C4 RBAC**：厂区是**数据范围**维度，不是权限维度；角色决定动作，厂区决定可见范围（隔离实现后）。
- **C3 审计**：厂区主数据变更留痕。
- **C6 数据保留**：停用厂区的数据继续保留 ≥ 3 年。
- **已知合规缺口**：单厂区账号可手传 `?site=` 越权查看他厂区数据（D5），
  上线前必须完成 tasks T7。

## 5. 风险

| # | 风险 | 缓解 |
| --- | --- | --- |
| R1 | 未强制数据隔离，存在越权查看风险 | P2 T7：服务端按 JWT `site` 收敛；集团账号除外 |
| R2 | 历史数据回填不完整导致 NULL 维度 | 迁移脚本统一回填 `SITE-01`；索引建立后校验无 NULL |
| R3 | 厂区写进 JWT，改厂区后旧令牌仍生效（最长 12h） | P2 T8：提供令牌刷新/退出机制或缩短有效期 |
| R4 | 新增实体忘记加厂区列 | 新建带业务归属的表时，将 `site_code` 列入评审清单 |
