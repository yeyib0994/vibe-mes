# 多厂区 · 功能规范（spec）

- **模块**：multi-site（Phase G4 已落地，本文档为**回填**规范）
- **对应页面**：`fluxmes/src/components/dashboard-controls.tsx`（SiteSwitcher）、`pages/Dashboard.jsx`
- **依赖**：`specs/constitution.md`、`production-cockpit`、`batch-management`、`auth`
- **版本**：v1.0 · 2026-09-14
- **实现状态**：✅ 已实现并通过 Phase H 端到端验证（40/40）；
  表见 `db/schema-p3.sql`，代码见 `dashboard/DashboardController`、`batch/BatchService`、`common/CurrentUser`

## 1. 概述（Overview）

食品企业多基地生产是常态（同一集团下城东工厂、滨海工厂各自独立排产与质量放行）。
本模块为系统引入**厂区（Site）维度**：厂区作为主数据，产线、批次、CCP 点、用户均归属厂区，
驾驶舱与批次列表支持按厂区切换与过滤，JWT 携带用户归属厂区以支持集团账号跨厂区查看。

当前实现定位为 **Phase 1：维度打通与过滤**。尚未实现的是**强制数据隔离**
（即服务端按 JWT 中的厂区自动收敛查询结果）——见 §5 边界与 P2 待办。

## 2. 用户场景（User Scenarios）

- **US1 · 厂区切换**：管理员在驾驶舱顶部切换「城东工厂 / 滨海工厂」，KPI、在产批次、
  日产量与 OEE 全部按所选厂区重算。
- **US2 · 集团视图**：集团账号（`site` 为空）不选择厂区时看到全集团汇总数据。
- **US3 · 产线归属**：滨海工厂新增「柠檬酸发酵四线」后，切到滨海工厂可见 LINE-4，
  切到城东工厂不可见。
- **US4 · 批次归属**：新建批次时默认归属当前用户所属厂区（集团账号默认 SITE-01），
  批次列表可按厂区过滤。
- **US5 · 厂区主数据**：管理员查看厂区列表，含各厂区产线数与批次数。
- **US6 · CCP 点归属**：各厂区的 HACCP 关键控制点各自维护，互不干扰。

## 3. 功能需求（Functional Requirements，EARS）

### 3.1 厂区主数据

- **FR-1** THE SYSTEM SHALL 维护厂区主数据：编码（`SITE-xx`）、名称、地址、启用标志。
- **FR-2** WHEN 查询厂区列表，THE SYSTEM SHALL 仅返回启用中的厂区，
  并附带该厂区的产线数与批次数。
- **FR-3** WHEN 停用某厂区，THE SYSTEM SHALL 保留其历史数据（软停，不物理删除）。

### 3.2 归属关系

- **FR-4** THE SYSTEM SHALL 为下列实体维护厂区归属：产线（`production_line.site_code`）、
  批次（`batch.site`）、用户（`app_user.site_code`）、CCP 点（`ccp_point.site_code`）。
- **FR-5** WHEN 新建批次且未显式指定厂区，THE SYSTEM SHALL 取当前用户归属厂区；
  用户无归属（集团账号）时取默认厂区 `SITE-01`。
- **FR-6** WHEN 已存在数据未设置厂区（历史迁移），THE SYSTEM SHALL 在迁移脚本中回落到 `SITE-01`。

### 3.3 查询维度

- **FR-7** WHEN 查询驾驶舱 KPI、在产批次、日产量、OEE、班报，
  THE SYSTEM SHALL 支持 `?site=` 参数按厂区过滤；不传则跨厂区汇总。
- **FR-8** WHEN 查询批次列表，THE SYSTEM SHALL 支持 `?site=` 过滤，并与其他过滤条件（状态/工序/产品/关键字/产线）叠加生效。
- **FR-9** WHEN 查询产线列表，THE SYSTEM SHALL 支持 `?site=` 过滤，且返回每条产线的归属厂区。
- **FR-10** WHEN 用户认证成功，THE SYSTEM SHALL 在 JWT 中携带 `site` 声明：
  值为用户归属厂区；为空表示集团账号，可跨厂区查看。

## 4. 非功能需求（Non-Functional Requirements）

- **NFR-1** 厂区维度查询须走索引（`idx_batch_site`、`idx_user_site`、`production_line.site_code`）。
- **NFR-2** 新增厂区的配置成本 ≤ 1 条 INSERT，无需改代码。
- **NFR-3** 厂区主数据变更留痕（C3）。

## 5. 边界与约束（Boundaries & Constraints）

- **当前未实现强制数据隔离**：`?site=` 由调用方显式传入，服务端不校验该厂区是否在
  当前用户的可见范围内。集团账号与单厂区账号的差异仅体现在**默认值**上。
  这意味着单厂区账号仍可通过手传参数看到其他厂区数据——**生产启用前必须补齐**（见 tasks T7）。
- 本模块**不做**跨厂区调拨、多厂区库存与合并报表（ERP 范畴）。
- 本模块**不做**多时区：全部厂区统一使用 `Asia/Shanghai`（与 `DashboardController.ZONE` 一致）。

## 6. 成功标准（Success Criteria）

| # | 验收项 | 判定方式 |
| --- | --- | --- |
| SC-1 | 厂区主数据可见 | `/api/dashboard/sites` 返回启用厂区及各厂区产线数、批次数 |
| SC-2 | 驾驶舱按厂区切换 | `?site=SITE-02` 时 KPI 与在产批次仅含滨海工厂数据 |
| SC-3 | 产线归属正确 | 切换厂区后可见产线集合不同（LINE-4 仅属 SITE-02） |
| SC-4 | 批次归属默认 | 单厂区账号建批次自动带出其厂区；集团账号默认 SITE-01 |
| SC-5 | 批次过滤生效 | `?site=` 与其他过滤条件叠加结果正确 |
| SC-6 | 班报按厂区 | 班报含 `site` 字段并仅统计该厂区数据 |
| SC-7 | JWT 携带厂区 | 登录响应/令牌含 `site` 声明；集团账号为空串 |
| SC-8 | 历史迁移完整 | 迁移后既有批次、用户、CCP 点均有厂区归属，无 NULL |

## 7. 依赖与集成点

| 依赖 | 说明 |
| --- | --- |
| `auth` | JWT 新增 `site` 声明；`CurrentUser.site()` 提供上下文 |
| `production-cockpit` | 驾驶舱 KPI、在产批次、日产量、OEE、班报支持厂区维度 |
| `batch-management` | 批次归属与过滤；建单默认值 |
| `foodsafety`（HACCP） | CCP 点归属厂区 |
| `production-execution`（规划） | 工单继承批次厂区；OEE 聚合支持厂区维度 |
| `quality-regtech`（规划） | CAPA 与内审记录归属厂区 |
