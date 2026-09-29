# 质量体系 RegTech · 功能规范（spec）

- **模块**：quality-regtech（Phase I 新增 · 落地优先级第二位）
- **对应页面**：拟新增 `fluxmes/src/pages/Capa.jsx`、`Audit.jsx`；质量页内嵌签名条
- **依赖**：`specs/constitution.md`、`quality-management`、`batch-management`、`audit` 服务
- **版本**：v1.0 · 2026-09-14
- **状态**：待实现。本模块补齐「偏差 → CAPA → 验证 → 关闭」的闭环与合规证据链

## 1. 概述（Overview）

质量管理已有偏差单（`deviation`），但偏差单的状态机里 CAPA 只是一个**文本字段**：

```java
private String capa;   // Deviation.java —— 无责任人、无期限、无验证、无有效性评价
```

关闭偏差只需值班长调用 `/api/deviations/{id}/transition` 推进到 `closed`，中间没有
「措施是否完成、谁验证、验证结论是什么」的结构化证据。食品企业内审 / 客户审核 / 监管检查时，
这条链是必查项（GMP、FSSC 22000、BRCGS 均要求 CAPA 有效性与记录留痕）。

同时，系统现有关键动作（批次放行、偏差关闭、配方生效、eBR 复核）只有**审计日志**（系统记录 who/when），
缺少**电子签名**（人主动声明「我批准/我复核」并绑定记录内容哈希），不满足
21 CFR Part 11 / EU GMP Annex 11 对电子记录与电子签名的基本要求。

本模块分三条线：**CAPA 闭环**、**内审管理**、**电子签名**。

## 2. 用户场景（User Scenarios）

- **US1 · 偏差转 CAPA**：质检员对偏差 `DEV-260914-002`（SPC 判异）建 CAPA，拆成 3 条行动项
  （纠正：隔离当批；纠正措施：校准加料秤；预防措施：修订点检频次），分别指定责任人与期限。
- **US2 · CAPA 逾期预警**：临近期限未完成的 CAPA 在质量页与驾驶舱以「逾期 / 临期」标记提示，
  管理员可按责任人汇总。
- **US3 · 有效性验证**：责任人完成行动项并上传证据后，质量负责人验证并给出结论
  （有效 / 无效退回），无效则 CAPA 回到 `in_progress` 并留痕。
- **US4 · 内审发现项转 CAPA**：内审员在年度内审中登记发现项「清场记录缺双人签字」（major），
  一键生成 CAPA，责任人默认为该车间主管。
- **US5 · 电子签名放行**：QC 放行批次时弹出签名条，需输入密码并声明签名含义「批准放行」，
  签名记录绑定该批次档案的当前哈希；事后若档案被改，验证接口能检出哈希不匹配。
- **US6 · 监管检查导出**：导出某批次的签名清单（签名人、角色、时间、含义、记录版本、哈希），
  作为审计证据包的一部分。
- **US7 · 签名尝试防护**：连续 5 次密码校验失败，账号签名能力锁定 15 分钟并写审计。

## 3. 功能需求（Functional Requirements，EARS）

### 3.1 CAPA 闭环

- **FR-1** WHEN 用户基于偏差 / 内审发现项 / 报警 / 手工来源创建 CAPA，THE SYSTEM SHALL 生成
  `CAPA-<yyMMdd>-<seq>`，并区分类型：纠正 `CORRECTION`（处置现有不合格）、
  纠正措施 `CORRECTIVE`（消除已发生原因）、预防措施 `PREVENTIVE`（消除潜在原因）。
- **FR-2** WHEN 创建 CAPA，THE SYSTEM SHALL 要求填写根本原因（根因分析，不得为空）与至少一条行动项。
- **FR-3** WHEN CAPA 状态流转（`open → in_progress → pending_verify → verified → closed`，
  或 `rejected` 退回 `in_progress`），THE SYSTEM SHALL 校验流转合法性并写审计（C3）。
- **FR-4** IF CAPA 存在未完成的行动项，THE SYSTEM SHALL 拒绝流转至 `pending_verify`（400 + 未完成清单）。
- **FR-5** WHEN CAPA 距到期日 ≤ 3 天或已逾期，THE SYSTEM SHALL 在列表与驾驶舱标记「临期 / 逾期」，
  并向责任人生成提醒（major 及以上级别同时产生报警）。
- **FR-6** WHEN 质量负责人验证 CAPA，THE SYSTEM SHALL 记录验证方式（文件审查 / 现场确认 / 数据复核）、
  有效性结论与说明；结论为「无效」时退回并保留退回原因。
- **FR-7** IF 某偏差存在未关闭的 CAPA，THE SYSTEM SHALL 阻断该偏差关闭与关联批次放行（409 + CAPA 编号）。
- **FR-8** WHEN CAPA 关闭，THE SYSTEM SHALL 要求电子签名（含义 = 批准），与 FR-20 联动。

### 3.2 内审管理

- **FR-9** WHEN 用户创建内审计划，THE SYSTEM SHALL 记录审核编号、类型（体系 / 过程 / 产品 / GMP 自查）、
  范围、审核组、计划开始与结束日期。
- **FR-10** WHEN 登记发现项，THE SYSTEM SHALL 要求分级：`critical` / `major` / `minor` / `observation`，
  并关联条款（如 FSSC 22000 条款号或 GMP 附录条款）。
- **FR-11** WHEN `critical` 或 `major` 发现项被登记，THE SYSTEM SHALL 提示（并可一键）生成 CAPA，
  默认责任人为被审核区域负责人。
- **FR-12** WHEN 内审关闭，THE SYSTEM SHALL 校验全部 `critical/major` 发现项均已有关联 CAPA，
  否则拒绝关闭（409）。
- **FR-13** WHEN 查询内审，THE SYSTEM SHALL 支持按年度统计发现项分布与 CAPA 转化率。

### 3.3 电子签名（21 CFR Part 11 / GMP 附录对齐）

- **FR-14** WHEN 对受控动作签名（批次放行、偏差关闭、CAPA 关闭与验证、配方生效、eBR 工序复核），
  THE SYSTEM SHALL 要求二次身份确认（密码）并声明**签名含义**：
  `AUTHORED`（编制）/ `REVIEWED`（复核）/ `APPROVED`（批准）/ `VERIFIED`（验证）/ `WITNESSED`（见证）。
- **FR-15** WHEN 签名提交，THE SYSTEM SHALL 记录：签名人、当时角色、服务器时间、签名含义、
  记录类型、记录 ID、记录版本号、**记录内容 SHA-256 哈希**、客户端 IP 与 User-Agent。
- **FR-16** THE SYSTEM SHALL 禁止共享账号签名：签名人必须是已启用且具备对应角色的实名账号，
  系统账号（如 service / integration）不得签名。
- **FR-17** WHEN 签名校验连续失败 5 次，THE SYSTEM SHALL 锁定该账号签名能力 15 分钟并写审计（C3）。
- **FR-18** WHEN 已签名记录被修改，THE SYSTEM SHALL 使原签名失效（新增版本 + 需重新签名），
  历史签名不可删除、不可修改。
- **FR-19** WHEN 验证签名，THE SYSTEM SHALL 重算记录当前哈希并与签名时哈希比对，
  不一致时返回 `tampered = true`（篡改检测）。
- **FR-20** WHEN 展示签名清单，THE SYSTEM SHALL 提供可导出的清单
  （签名人、角色、时间、含义、记录类型/ID/版本、哈希），供监管检查提交。
- **FR-21** IF 受控动作要求签名但该记录无有效签名，THE SYSTEM SHALL 拒绝该动作完成（409），
  并说明所需签名含义与角色。

## 4. 非功能需求（Non-Functional Requirements）

- **NFR-1** 签名接口 P95 < 500ms（含 BCrypt 校验）；哈希计算对 ≤ 1MB 记录 < 100ms。
- **NFR-2** 签名记录只追加（append-only）：无 UPDATE / DELETE 接口，更正以「作废 + 新签」表达。
- **NFR-3** 签名、CAPA、内审数据留存 ≥ 3 年（C6），且导出格式稳定（不因前端改版失效）。
- **NFR-4** 密码校验失败计数须在服务端持久化（内存计数在多实例下失效），并与登录失败计数隔离。
- **NFR-5** 哈希算法与记录序列化规则须版本化（字段顺序固定、忽略易变字段如 `updated_at`），
  避免无害更新导致误报篡改。

## 5. 边界与约束（Boundaries & Constraints）

- 本模块**不替代审计日志**：审计是系统被动记录，签名是人的主动声明，两者并存。
- 本模块**不做**文件级数字证书 / PKI / 第三方 CA 时间戳（企业部署时由外部信任服务提供），
  但哈希与时间戳字段预留，便于后续对接。
- 本模块**不做**完整 QMS（文档控制、变更管理、供应商审计等），仅覆盖与 MES 执行直接相关的 CAPA 与内审。
- 内审发现项的条款库不内置完整标准全文，仅存条款号与标题引用。
- 电子签名适用范围以配置表为准，不硬编码在业务代码里（见 plan D4）。

## 6. 成功标准（Success Criteria）

| # | 验收项 | 判定方式 |
| --- | --- | --- |
| SC-1 | CAPA 来源与类型齐全 | 可从偏差 / 内审发现项 / 手工创建；三种类型可区分 |
| SC-2 | 行动项门禁 | 有未完成的行动项时无法流转到 `pending_verify`（400 + 清单） |
| SC-3 | 无效退回留痕 | 验证为「无效」退回后，`rejected` 与原因可查，行动项重开 |
| SC-4 | 放行阻断 | 偏差存在未关闭 CAPA 时，偏差关闭与批次放行返回 409 |
| SC-5 | 临期/逾期可见 | 构造到期数据，列表与驾驶舱出现临期/逾期标记并产生报警 |
| SC-6 | 内审关闭门禁 | 存在未生成 CAPA 的 major 发现项时，内审关闭返回 409 |
| SC-7 | 签名要素完整 | 签名记录含含义、角色、服务器时间、记录版本、哈希、IP/UA |
| SC-8 | 篡改可检出 | 修改已签名记录后，验证接口返回 `tampered = true` |
| SC-9 | 防共享账号 | service 账号签名返回 403 |
| SC-10 | 失败锁定 | 连续 5 次密码错误后签名返回 423（锁定），15 分钟后恢复 |
| SC-11 | 签名强制 | 未签名的受控动作无法完成（放行 / CAPA 关闭返回 409） |
| SC-12 | 清单可导出 | 导出内容与页面一致，含哈希与记录版本 |

## 7. 依赖与集成点

| 依赖 | 说明 |
| --- | --- |
| `quality-management` | 偏差单是 CAPA 主要来源；放行与偏差关闭受 CAPA 状态阻断（FR-7） |
| `batch-management` | 批次放行需电子签名（FR-21）；放行阻断联动 |
| `recipe-management` | 配方版本生效（activate）纳入签名范围 |
| `foodsafety`（eBR） | 关键工序复核签名，与既有双人复核衔接 |
| `alarm-center` | CAPA 逾期、major 发现项生成报警 |
| `audit`（审计服务） | 签名与审计互为佐证；签名不写审计，但签名行为本身可查 |
| `auth` | 密码二次校验复用现有 BCrypt 校验与 JWT 身份，不引入新认证库 |
