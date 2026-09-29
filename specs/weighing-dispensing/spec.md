# 配料称量与容差 · 功能规范（spec）

- **模块**：weighing-dispensing（Phase G3 已落地，本文档为**回填**规范）
- **对应页面**：`fluxmes/src/pages/Weighing.jsx`
- **依赖**：`specs/constitution.md`、`batch-management`、`personnel-certification`、`alarm-center`
- **版本**：v1.0 · 2026-09-14
- **实现状态**：✅ 已实现并通过 Phase H 端到端验证（40/40）；
  代码见 `weighing/WeighingService.java` / `WeighingController.java`，表见 `db/schema-p3.sql`

## 1. 概述（Overview）

食品配料的称量偏差直接决定配方一致性与成品合规——**过敏原与食品添加剂限量均按配方比例管控**，
称量超差不是「数据不好看」，而是可能触发整批不合格的合规事件。

本模块把「目标量 ± 容差百分比」从纸面 SOP 变成**服务端强制校验**：
每次称量实时计算偏差百分比，超差即自动生成偏差单与 major 报警、阻断称量任务，
并强制由**另一名**具备批记录复核资质的人员复核后方可继续，且已放行批次禁止补录。

## 2. 用户场景（User Scenarios）

- **US1 · 建立称量任务**：工艺员为批次 `B-260914-003` 建立称量任务：柠檬酸一水合物，
  目标 1250 kg，允许偏差 ±1%，单位 kg。
- **US2 · 正常称量**：操作员分三次称量（500 / 480 / 270 kg），每次偏差均在容差内，
  累计达到目标量后任务自动置 `DONE`。
- **US3 · 超差拦截**：第三次称量实测 1300 kg（偏差 +4%，超出 ±1%），系统判定 `OVER`，
  自动生成偏差单 `DEV-…-xxx` 与 major 报警，任务置 `BLOCKED`，无法继续称量。
- **US4 · 超差复核**：质检员（非称量人本人）复核该超差记录并给出处置意见，
  全部超差记录复核完毕后任务恢复 `OPEN`（累计不足）或 `DONE`（累计达标）。
- **US5 · 资质拦截**：无称量资质或健康证过期的账号提交称量，返回 403。
- **US6 · 放行后防篡改**：批次已放行后尝试补录称量数据，返回 409。
- **US7 · 合规看板**：管理员查看称量合格率、超差次数（OVER/UNDER 分列）与待复核数量。

## 3. 功能需求（Functional Requirements，EARS）

### 3.1 称量任务

- **FR-1** WHEN 用户创建称量任务，THE SYSTEM SHALL 生成任务号 `WT-<yyMMdd>-<seq>`，
  并校验批次存在（404）、目标量 > 0（400）、容差在 0%~50%（400，默认 1.0%）。
- **FR-2** IF 目标批次已放行，THE SYSTEM SHALL 拒绝创建称量任务（409）。
- **FR-3** WHEN 查询任务列表，THE SYSTEM SHALL 支持按批次与状态过滤，按创建时间倒序。

### 3.2 称量登记与容差判定

- **FR-4** WHEN 提交一次称量，THE SYSTEM SHALL 计算偏差百分比
  `devPct =（实际 − 目标）/ 目标 × 100`（保留 3 位小数），并判定：
  `|devPct| ≤ 容差` → `PASS`；`devPct > 容差` → `OVER`；`devPct < −容差` → `UNDER`。
- **FR-5** WHEN 称量提交，THE SYSTEM SHALL 校验提交人持有效 `WEIGHING` 资质与健康证（403 拦截）。
- **FR-6** WHEN 判定为 `PASS`，THE SYSTEM SHALL 累加 `total_weighed`；
  累计达到目标量时任务置 `DONE`。
- **FR-7** WHEN 判定为 `OVER` / `UNDER`，THE SYSTEM SHALL 在同一业务流中：
  ① 创建偏差单（`source = weighing`，描述含物料、目标、实测、偏差与允许范围）；
  ② 触发 major 级报警；③ 将任务置 `BLOCKED`；④ 记录偏差单号与报警号到称量明细。
- **FR-8** IF 任务状态为 `DONE` 或所属批次已放行，THE SYSTEM SHALL 拒绝继续称量（409）。
- **FR-9** WHEN 查询称量明细，THE SYSTEM SHALL 按序号升序返回，含偏差百分比、判定结果、
  称量人、复核人、衡器编号与时间。

### 3.3 超差复核

- **FR-10** WHEN 复核超差记录，THE SYSTEM SHALL 要求复核人为质检员及以上（403），
  且持有有效 `BATCH_REVIEW` 资质。
- **FR-11** IF 复核人等于称量人本人，THE SYSTEM SHALL 拒绝（409，双人复核要求）。
- **FR-12** WHEN 该任务下全部超差记录均已复核，THE SYSTEM SHALL 解除 `BLOCKED`：
  累计量达标置 `DONE`，否则回到 `OPEN`；仍有未复核超差记录则维持 `BLOCKED`。

### 3.4 审计与统计

- **FR-13** WHEN 称量、超差、复核发生，THE SYSTEM SHALL 写审计日志，
  动作分别为 `weighing.weigh` / `weighing.out_of_tolerance` / `weighing.review`，
  以及 `weighing.task.create`。
- **FR-14** WHEN 查询合规看板，THE SYSTEM SHALL 返回总数、合格数、OVER 数、UNDER 数、待复核数与合格率。

## 4. 非功能需求（Non-Functional Requirements）

- **NFR-1** 金额/重量计算统一用 `BigDecimal`，除法保留 6 位中间精度、结果 3 位，舍入 `HALF_UP`，禁止 double 直接比较。
- **NFR-2** 称量写入须在事务内完成（明细 + 任务状态 + 偏差单 + 报警），任一失败整体回滚。
- **NFR-3** 称量数据留存 ≥ 3 年（C6）。
- **NFR-4** 容差参数存储于任务级（而非全局），历史称量不受后续容差调整影响。

## 5. 边界与约束（Boundaries & Constraints）

- 本模块**不做**衡器直连与自动去皮（对接电子秤属设备集成，Phase 2 OPC-UA / 串口采集范畴）；
  当前由人工录入实测值，`equipment` 字段记录所用衡器编号。
- 本模块**不替代**实验室检验：称量容差针对配料工序，成品指标判定归质量管理。
- 一个称量任务对应一个物料；多物料配料需建立多个任务（不做任务内多物料行）。
- 复核仅解除阻断，**不修改**称量数据本身；数据更正须新称量记录 + 备注说明。

## 6. 成功标准（Success Criteria）

| # | 验收项 | 判定方式 |
| --- | --- | --- |
| SC-1 | 任务创建校验 | 目标量 ≤ 0 返回 400；容差 > 50% 返回 400；批次不存在 404 |
| SC-2 | 偏差计算正确 | 目标 1250、实测 1300 → `+4.000%`、`OVER`；实测 1200 → `-4.000%`、`UNDER` |
| SC-3 | 容差内放行 | 偏差 ±1% 内 → `PASS`，累计达标后置 `DONE` |
| SC-4 | 超差联动 | 超差同时产生偏差单 + major 报警 + 任务 `BLOCKED` |
| SC-5 | 资质门禁 | 无称量资质 / 健康证过期账号返回 403 |
| SC-6 | 双人复核 | 称量人本人复核返回 409；QC 他人复核通过 |
| SC-7 | 阻断解除 | 全部超差复核后，达标 → `DONE`，未达标 → `OPEN` |
| SC-8 | 放行只读 | 已放行批次新建任务或补录称量返回 409 |
| SC-9 | 审计可查 | 三类动作在 `/api/audit` 可查到记录 |
| SC-10 | 看板准确 | 合格率 = 合格数 / 总数，与明细一致 |

## 7. 依赖与集成点

| 依赖 | 说明 |
| --- | --- |
| `batch-management` | 任务归属批次；放行状态决定是否只读（FR-2 / FR-8） |
| `personnel-certification` | 称量需 `WEIGHING` 资质、复核需 `BATCH_REVIEW` 资质，均需有效健康证 |
| `quality-management` | 超差生成偏差单（阻断关联批次放行）；复核人角色为 QC+ |
| `alarm-center` | 超差触发 major 报警，计入 SLA 计时 |
| `recipe-management` | 目标量应来源于配方步骤的设定值（当前为人工填写，见 P2 待办） |
