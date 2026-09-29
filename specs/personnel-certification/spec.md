# 人员资质与健康证 · 功能规范（spec）

- **模块**：personnel-certification（Phase G2 已落地，本文档为**回填**规范）
- **对应页面**：`fluxmes/src/pages/Personnel.jsx`
- **依赖**：`specs/constitution.md`、`auth`、`batch-management`、`quality-management`
- **版本**：v1.0 · 2026-09-14
- **实现状态**：✅ 已实现并通过 Phase H 端到端验证（40/40）；实现代码见
  `personnel/PersonnelService.java` / `PersonnelController.java`，表见 `db/schema-p3.sql`

## 1. 概述（Overview）

《食品安全法》第四十五条要求食品生产经营者建立并执行从业人员健康管理制度，
从事接触直接入口食品工作的从业人员须**每年健康检查、取得健康证明后方可上岗**；
HACCP / FSSC 22000 体系另要求 CCP 监控、配料称量、成品放行等关键岗位人员具备相应资质并定期复审。

本模块建立**人员证书台账 + 能力项字典 + 关键动作门禁**三件套：
证照到期自动预警，关键操作前强制校验「岗位资质 + 有效健康证」，缺证即 403 拒绝，
使「持证上岗」从制度文本变成系统强制约束。

## 2. 用户场景（User Scenarios）

- **US1 · 台账登记**：人事/值班长登记张伟的「食品从业人员健康证」，发证日期 2025-11-20，
  有效期至 2026-11-19，证书编号与发证机构一并录入。
- **US2 · 资质发证**：登记李娜的「化验员资格证」，能力项 = 理化检验 `LAB_TEST`。
- **US3 · 到期预警**：系统在健康证到期前 30 天开始预警；到期后自动标记 `EXPIRED` 并拦截其全部关键操作。
- **US4 · 门禁拦截**：工艺员 `temp`（健康证已过期）尝试提交 CCP 监控记录，系统返回 403
  「当前用户无有效健康证（食品从业人员须持年度体检健康证上岗）」。
- **US5 · 证书吊销**：员工离职或资质被撤销时，值班长吊销证书并填写原因，吊销记录永久保留。
- **US6 · 合规看板**：管理员查看资质合规概览：证书总数、健康证数、岗位资质数、失效数，
  以及每个能力项的持证人数与是否已纳入强制管控。

## 3. 功能需求（Functional Requirements，EARS）

### 3.1 证书台账

- **FR-1** THE SYSTEM SHALL 支持登记两类证书：健康证 `HEALTH` 与岗位资质 `QUALIFICATION`。
- **FR-2** WHEN 登记证书，THE SYSTEM SHALL 校验：用户存在、`certType` 合法、
  `certName` 与 `username` 非空，否则返回 400 / 404。
- **FR-3** WHEN 登记岗位资质，THE SYSTEM SHALL 记录其对应的能力项 `capability`
  （取值来自 `capability_dict`）。
- **FR-4** WHEN 查询台账，THE SYSTEM SHALL 支持按人员与证书类型过滤，并按有效期升序排列（最快到期的在前）。
- **FR-5** WHEN 证书被吊销，THE SYSTEM SHALL 置 `status = REVOKED` 并记录吊销原因，**不得**物理删除。
- **FR-6** 登记与吊销操作的权限为**值班长及以上**（C4）。

### 3.2 到期管理

- **FR-7** WHEN 系统查询到期预警或合规概览，THE SYSTEM SHALL 先执行过期巡检
  （`sweepExpired()`），将已过期证书标记 `EXPIRED`，并对恢复有效的证书回置 `VALID`。
- **FR-8** WHEN 查询到期预警，THE SYSTEM SHALL 输出已过期清单（含 `overdueDays`）与
  即将到期清单（默认 30 天窗口，含 `daysLeft`）。
- **FR-9** 证书有效性判定规则：状态为 `REVOKED` 或 `EXPIRED` 视为无效；
  未过期且未吊销视为有效；`valid_until` 为空视为长期有效。

### 3.3 关键动作门禁

- **FR-10** WHEN 用户执行关键动作，THE SYSTEM SHALL 校验两项：
  ① 该动作对应能力项的有效资质；② 有效健康证。任一项缺失即返回 403 并说明缺失内容。
- **FR-11** IF 某能力项在系统中**尚无任何持证人**，THE SYSTEM SHALL 不启用该能力项的强制校验
  （`capabilityEnforced = false`）；健康证同理（`healthCertEnforced`）。
  ——避免未配置资质数据的环境整体不可用（渐进启用策略）。
- **FR-12** THE SYSTEM SHALL 在下列关键动作前施加门禁：

  | 动作 | 能力项 | 代码位置 |
  | --- | --- | --- |
  | CCP 监控值记录 | `CCP_MONITOR` | `FoodSafetyService:145` |
  | 清场执行与确认 | `SANITATION` | `FoodSafetyService:256 / 291` |
  | 电子批记录工序复核 | `BATCH_REVIEW` | `BatchRecordService:139`、`FoodSafetyService:204` |
  | 成品放行 | `RELEASE` | `QualityService:175` |
  | 配料称量提交 | `WEIGHING` | `WeighingService:126` |
  | 称量超差复核 | `BATCH_REVIEW` | `WeighingService:196` |

- **FR-13** WHEN 调用方为非 HTTP 上下文（定时任务、种子导入、匿名），
  THE SYSTEM SHALL 跳过门禁，避免基础设施任务被阻断。
- **FR-14** THE SYSTEM SHALL 提供能力项字典查询，返回每个能力项的持证人数与是否已强制管控。

## 4. 非功能需求（Non-Functional Requirements）

- **NFR-1** 门禁校验 P95 < 50ms（按 `username + cert_type + capability` 索引查询，不扫全表）。
- **NFR-2** 过期巡检为幂等操作，可在任意查询路径重复触发而不产生副作用。
- **NFR-3** 证书数据留存 ≥ 3 年（C6），包含已吊销与已过期记录。
- **NFR-4** 巡检异常（数据库连接失败等）不得阻断主流程，须降级为警告日志。

## 5. 边界与约束（Boundaries & Constraints）

- 本模块**不做**人力资源主数据管理（入职/离职/组织架构），仅管理证书与能力项。
- 本模块**不做**体检预约、证书扫描件存储（后续可对接文档管理）。
- 能力项字典为系统级配置，新增能力项需同步在业务动作中接入 `requireCapability`，否则不生效。
- 已定义但**尚未接入**门禁的能力项：`LAB_TEST`（理化检验，当前无对应受控动作）。

## 6. 成功标准（Success Criteria）

| # | 验收项 | 判定方式 |
| --- | --- | --- |
| SC-1 | 台账登记与过滤 | 按人/类型过滤结果正确，按有效期升序 |
| SC-2 | 非法输入拦截 | 不存在的用户 404；非法 certType 400 |
| SC-3 | 权限控制 | 工艺员登记证书返回 403 |
| SC-4 | 到期预警 | 已过期含 `overdueDays`，临期含 `daysLeft`，窗口参数生效 |
| SC-5 | 自动巡检 | 过期证书被标记 `EXPIRED`，无需人工干预 |
| SC-6 | 健康证门禁 | 健康证过期账号提交 CCP 记录返回 403 |
| SC-7 | 能力项门禁 | 无称量资质账号提交称量返回 403 |
| SC-8 | 渐进启用 | 无任何持证人的能力项不阻断业务 |
| SC-9 | 吊销留痕 | 吊销后记录仍在且含原因，持证人立即失去门禁资格 |
| SC-10 | 非 HTTP 上下文跳过 | 种子导入与定时任务不受门禁影响 |

## 7. 依赖与集成点

| 依赖 | 说明 |
| --- | --- |
| `auth` | `CurrentUser` 提供当前用户名与角色；`Roles` 提供权限判断 |
| `quality-management` | 放行受 `RELEASE` 门禁 |
| `foodsafety`（HACCP/eBR/清场） | CCP 记录、清场、批记录复核门禁 |
| `weighing-dispensing` | 称量与超差复核门禁 |
| `production-execution`（规划） | 派工与报工复用本门禁（见该模块 FR-6/FR-7） |
