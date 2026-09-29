# 人员资质与健康证 · 任务清单（tasks）

- **模块**：personnel-certification
- **依赖**：`spec.md`、`plan.md`
- **版本**：v1.0 · 2026-09-14
- 图例：✅ 已完成 · ◐ 部分完成 · ⬜ 待办；优先级 P0/P1/P2

## P0 · 数据模型与台账

- ✅ **T1** 建表 `person_certificate` / `capability_dict`（schema-p3.sql，幂等）
  - 验收：启动自动建表；6 条能力项种子就位
- ✅ **T2** 实体 + Mapper（`PersonCertificate` / `CapabilityDict`，`@Mapper` 注解）
  - 验收：`mvn compile` 通过
- ✅ **T3** `GET /api/personnel/certificates` 台账查询（按人/类型过滤，有效期升序）
  - 验收：过滤与排序正确
- ✅ **T4** `POST /api/personnel/certificates` 登记（校验用户存在、类型合法、必填项）
  - 验收：不存在用户 404；非法类型 400；权限不足 403

## P1 · 到期与门禁

- ✅ **T5** `sweepExpired()` 幂等巡检：过期置 `EXPIRED`，恢复有效回置 `VALID`
  - 验收：过期证书被自动标记；重复执行无副作用
- ✅ **T6** `GET /api/personnel/alerts` 到期预警（已过期 + 临期，窗口可配）
  - 验收：返回 `overdueDays` / `daysLeft`，`?days=` 生效
- ✅ **T7** `PersonnelService#requireCapability` 门禁：能力项 + 健康证双重校验
  - 验收：缺证返回 403 且说明缺失内容
- ✅ **T8** 门禁接入 7 处（CCP 记录 / 清场×2 / eBR 复核 ×2 / 放行 / 称量 / 超差复核）
  - 验收：健康证过期账号 `temp` 提交 CCP 返回 403
- ✅ **T9** 渐进启用：`capabilityEnforced` / `healthCertEnforced`
  - 验收：无持证人的能力项不阻断业务
- ✅ **T10** 前端 `Personnel.jsx`：台账、预警、能力项、发吊销（角色受限）
  - 验收：值班长可见发证入口；工艺员仅可读

## P2 · 增强（待办）

- ⬜ **T11** `LAB_TEST`（理化检验）能力项接入：质检任务创建 / 结果录入时校验
  - 验收：无理化检验资质账号录入检验结果返回 403
- ⬜ **T12** 证书扫描件与到期提醒推送（对接文档存储与通知）
  - 验收：临期证书可推送至责任人与管理员
- ⬜ **T13** 资质合规导出（按人 / 按能力项，Markdown / CSV）
  - 验收：导出内容与页面一致，含有效期与状态

## 现状说明

- Phase G2 实现，Phase H 端到端验证通过（`scripts/verify-phase4.mjs`，40/40）。
- 演示账号 `temp/temp123` 的健康证已过期，用于演示门禁拦截（生产环境种子须清理）。
