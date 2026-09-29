# 多厂区 · 任务清单（tasks）

- **模块**：multi-site
- **依赖**：`spec.md`、`plan.md`
- **版本**：v1.0 · 2026-09-14
- 图例：✅ 已完成 · ◐ 部分完成 · ⬜ 待办；优先级 P0/P1/P2

## P0 · 主数据与归属

- ✅ **T1** 建表 `site`（schema-p3.sql，幂等）+ 2 条种子厂区
  - 验收：启动自动建表；种子就位
- ✅ **T2** 归属列迁移：`production_line.site_code` / `batch.site` / `app_user.site_code` /
  `ccp_point.site_code`，历史数据回填 `SITE-01`
  - 验收：迁移后无 NULL；索引建立
- ✅ **T3** 实体 + Mapper（`Site`，`@Mapper` 注解）
  - 验收：`mvn compile` 通过
- ✅ **T4** JWT 增加 `site` claim，`CurrentUser.site()` 提供上下文
  - 验收：登录后令牌含 site；集团账号为空串

## P1 · 查询维度与前端

- ✅ **T5** 查询参数支持：驾驶舱 KPI / 在产批次 / 日产量 / OEE / 班报 / 批次列表 / 产线列表
  - 验收：`?site=SITE-02` 仅返回滨海工厂数据；不传则跨厂区
- ✅ **T6** 前端 SiteSwitcher（`dashboard-controls.tsx`）+ Dashboard 联动
  - 验收：切换厂区后 KPI 与批次刷新；产线切换器选项随之变化
- ✅ **T7** 建单默认值：取 `CurrentUser.site()`，空则 `SITE-01`
  - 验收：单厂区账号建批次自动带出归属厂区

## P2 · 强制隔离（待办，上线前必须完成）

- ⬜ **T8** 服务端强制数据隔离：单厂区账号的查询自动收敛到其厂区，忽略/拒绝越权 `?site=`（R1）
  - 验收：单厂区账号传他厂区 `?site=` 返回 403 或自动收敛；集团账号不受限
- ⬜ **T9** 厂区变更后令牌刷新：提供刷新端点或缩短有效期，避免旧 claim 长期有效（R3）
  - 验收：改厂区后 5 分钟内生效
- ⬜ **T10** 厂区主数据管理 UI：增删改停 + 归属批量调整（管理员）
  - 验收：停用厂区后不出现在切换器，历史数据仍可查
- ⬜ **T11** 跨厂区对比视图：多厂区 KPI 并排对比
  - 验收：同一指标可横比 SITE-01 / SITE-02

## 现状说明

- Phase G4 实现，Phase H 端到端验证通过（`scripts/verify-phase4.mjs`，40/40）。
- **已知缺口**：仅实现过滤，未实现强制隔离（spec §5）。生产启用前须完成 T8。
