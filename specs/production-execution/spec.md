# 生产执行 · 功能规范（spec）

- **模块**：production-execution（Phase I 新增 · 落地优先级第一位）
- **对应页面**：拟新增 `fluxmes/src/pages/Execution.jsx`（工单看板 / 报工 / 停机录入）
- **依赖**：`specs/constitution.md`、`batch-management`、`equipment-management`、`recipe-management`、`personnel-certification`
- **版本**：v1.0 · 2026-09-14
- **状态**：待实现。本模块解决「OEE 是算出来的假数字」这一核心缺陷

## 1. 概述（Overview）

批次管理当前只有**批次状态机**（running → done / abnormal）与一个手工维护的 `progress` 字段，
缺少「谁、在什么设备、按什么工单、做了多少、停了多久、为什么停」的执行层证据链。
直接的后果是 OEE 三个因子全部是**替代口径**：

| 因子 | 当前实现（`DashboardController#oee` / `EquipmentService#oee`） | 问题 |
| --- | --- | --- |
| 可用率 | 驾驶舱用「非 abnormal 批次占比」；设备页用近 7 天停机时长 ÷ 168h | 与「计划生产工时」无关；周末与非计划时段被计入分母，虚高 |
| 性能率 | 当日折算产量 ÷ 产线设计产能 | 用产量比产能，不是「实际节拍 vs 理论节拍」，且产量本身由 `progress` 折算 |
| 良品率 | 质检任务通过率 | 与当班投入产出无关，跨批次串味 |

生产执行模块引入**工单（Work Order）→ 派工（Dispatch）→ 报工（Step Report）→ 停机（Downtime）**
四层执行记录，使 OEE 三因子有可审计的原始数据，并让批次进度由报工事实驱动而非手工填写。

## 2. 用户场景（User Scenarios）

- **US1 · 值班长排产派工**：王强把工单 `WO-260915-001`（柠檬酸发酵 · 120t）派给发酵岗三人，系统校验三人
  均持有效健康证与 CCP 监控资质，否则拒绝派工并提示缺证人员。
- **US2 · 工艺员工序报工**：张伟在发酵工序报工「实产 41.2t · 合格 41.0t · 废次品 0.2t · 耗时 6.5h」，
  批次进度与累计产量自动前进，无需手工改 `progress`。
- **US3 · 停机原因录入**：C-601 结晶罐搅拌故障停机 2.3 小时，值班长录入原因码 `MECH`（机械故障）与说明，
  该时长计入 OEE 可用率的**计划外停机**分子。
- **US4 · 真实 OEE 复盘**：月末复盘时，产线 OEE 68.4% = 可用率 84.1% × 性能率 87.2% × 良品率 93.3%，
  每一项都能下钻到工单、停机事件与报工记录。
- **US5 · 停机帕累托**：驾驶舱展示近 30 天停机原因帕累托（机械故障 38% → 换型 24% → 待料 15%），
  定位最大损失源。
- **US6 · 工时与人员绩效**：按人员汇总报工工时与合格产量，作为班组核算依据（只读统计，不做薪酬）。

## 3. 功能需求（Functional Requirements，EARS）

### 3.1 工单

- **FR-1** WHEN 用户基于某批次创建工单，THE SYSTEM SHALL 生成工单号 `WO-<yyMMdd>-<seq>`，
  并继承批次的产线 / 厂区 / 产品 / 配方版本快照，工单创建后配方版本不可随批次变更而漂移。
- **FR-2** WHEN 工单创建，THE SYSTEM SHALL 要求指定计划数量、计划开工与计划完工时间、班次（DAY/NIGHT）与产线。
- **FR-3** IF 计划数量 ≤ 0 或计划完工早于开工，THE SYSTEM SHALL 返回 400 并给出字段级错误。
- **FR-4** WHEN 工单状态流转（CREATED → RELEASED → RUNNING → FINISHED / CLOSED），
  THE SYSTEM SHALL 校验流转合法性（不可回退至 CREATED、FINISHED 后不可再报工）并写审计（C3）。
- **FR-5** WHEN 工单完工，THE SYSTEM SHALL 汇总其下全部报工的合格数量，并与批次 `plan_yield` 比对，
  偏差超过 ±5% 时提示值班长确认（不阻断）。

### 3.2 派工与资质门禁

- **FR-6** WHEN 值班长向工单派工，THE SYSTEM SHALL 逐人校验：账号启用、健康证在有效期内、
  具备该工序要求的能力项（复用 `personnel-certification` 的 `capability_dict`）。
- **FR-7** IF 被派人员缺证或健康证过期，THE SYSTEM SHALL 返回 403 并列出具体人员与缺失项，
  **不得**以警告放行（食品行业法定持证上岗）。
- **FR-8** WHEN 同一人员在同一时段被派至两个工单，THE SYSTEM SHALL 提示冲突（409 或显式确认标志），由值班长决策。
- **FR-9** WHEN 派工记录被撤销，THE SYSTEM SHALL 保留撤销记录而非物理删除（C3）。

### 3.3 工序报工

- **FR-10** WHEN 工艺员对某工序报工，THE SYSTEM SHALL 记录工单、工序号、设备、报工人、起止时间、
  投入量、合格量、废次品量与备注。
- **FR-11** WHEN 报工提交，THE SYSTEM SHALL 回写 `batch_step` 的实际参数与状态（RUNNING → DONE），
  并按「累计合格量 ÷ 计划量」推进批次 `progress`。
- **FR-12** IF 报工数量使累计合格量超过计划量的 110%，THE SYSTEM SHALL 返回 400 要求复核，防止误填。
- **FR-13** WHEN 报工涉及关键工序（配方步骤标记 `critical`），THE SYSTEM SHALL 要求复核人字段，
  且复核人不得为报工人本人（与 eBR 双人复核一致，C2）。
- **FR-14** WHEN 已放行批次（C2 `released = TRUE`）被尝试报工，THE SYSTEM SHALL 返回 409，批次档案只读。

### 3.4 停机与原因码

- **FR-15** WHEN 设备发生非计划停机，THE SYSTEM SHALL 支持录入停机事件：设备、起止时间、原因码、说明。
- **FR-16** THE SYSTEM SHALL 提供停机原因码字典，至少覆盖：机械故障 `MECH`、电气故障 `ELEC`、
  换型 `CHANGEOVER`、清洗清场 `CLEANING`、待料 `MATERIAL`、质量问题 `QUALITY`、公用工程 `UTILITY`、
  无订单 `NO_ORDER`、其他 `OTHER`；原因码可维护但不可物理删除。
- **FR-17** WHEN 计算可用率，THE SYSTEM SHALL 仅将 `MECH / ELEC / MATERIAL / QUALITY / UTILITY / OTHER`
  计入计划外停机；`CHANGEOVER` 与 `CLEANING` 计入计划停机（食品行业换型与 CIP 属计划性损失）。
- **FR-18** WHEN 停机事件与设备状态事件（`equipment_event`）时间窗重叠，THE SYSTEM SHALL 以停机事件为准，
  并在响应中标注合并来源，避免双重扣分。
- **FR-19** WHEN 停机时长超过原因码阈值（如 `MECH` ≥ 2h），THE SYSTEM SHALL 自动生成 major 级报警。

### 3.5 OEE 重算

- **FR-20** WHEN 查询 OEE，THE SYSTEM SHALL 按下列口径计算，且每个因子附带数据充足性标记：
  - 可用率 =（计划生产工时 − 计划外停机工时）÷ 计划生产工时
  - 性能率 = 标准工时（报工合格量 × 单位标准工时）÷ 实际生产工时
  - 良品率 = 合格量 ÷（合格量 + 废次品量）
  - OEE = 可用率 × 性能率 × 良品率
- **FR-21** IF 某因子缺少原始数据（无报工 / 无停机记录 / 无标准工时），
  THE SYSTEM SHALL 在响应中置 `dataSufficient = false` 并说明缺失项，**不得**用兜底常量伪造数值
  （现状 `92.1 / 96.5 / 33.4` 等硬编码回落值须全部移除）。
- **FR-22** WHEN 查询 OEE，THE SYSTEM SHALL 支持按厂区 / 产线 / 设备 / 班次 / 日期区间聚合。
- **FR-23** WHEN 展示停机帕累托，THE SYSTEM SHALL 按原因码聚合停机时长、降序排列并给出累计百分比。

## 4. 非功能需求（Non-Functional Requirements）

- **NFR-1** 报工与停机录入接口 P95 < 300ms；OEE 聚合（≤ 31 天窗口）P95 < 800ms。
- **NFR-2** 报工与停机写入须幂等：同一工单 + 工序 + 报工人 + 起止时间重复提交返回既有记录，不产生双份。
- **NFR-3** 工单、派工、报工、停机数据留存 ≥ 3 年（C6），归档策略同 `equipment_event`。
- **NFR-4** OEE 聚合结果可缓存 5 分钟，但写入报工 / 停机后 30s 内失效。
- **NFR-5** 全部执行写入接口须在事务内完成，任一子表写入失败整体回滚。

## 5. 边界与约束（Boundaries & Constraints）

- 本模块不做**排产算法**（APS 范畴）：工单的计划时间与顺序由人填写，系统只校验合理性。
- 本模块不做**薪酬 / 绩效核算**：仅提供工时与产量统计，不做工资计算。
- 报工不替代质检：合格量指**过程**合格，最终放行仍由质量管理模块判定（C2 / C4）。
- 停机事件不替代设备状态机：设备状态仍由 `equipment-management` 管理，本模块仅补充**原因码**维度。
- 工单与批次为 N:1 关系：一个批次可拆多张工单（如发酵与精制分单），但单张工单只属一个批次。

## 6. 成功标准（Success Criteria）

| # | 验收项 | 判定方式 |
| --- | --- | --- |
| SC-1 | 工单可创建并继承批次快照 | 工单含产线/厂区/配方版本；批次后续改配方版本不影响已建工单 |
| SC-2 | 派工资质门禁 | 派给健康证过期人员返回 403，响应列出缺失项 |
| SC-3 | 报工驱动进度 | 报工后 `batch_step.status = DONE`，批次 `progress` 与累计合格量一致 |
| SC-4 | 放行批次只读 | 已放行批次报工返回 409 |
| SC-5 | OEE 口径正确 | 构造已知数据集，三因子与手工核算偏差 ≤ 0.5%，且无硬编码兜底 |
| SC-6 | 数据不足不伪造 | 无报工数据时 `dataSufficient = false`，因子为 `null` 而非常量 |
| SC-7 | 计划 vs 非计划停机区分 | `CHANGEOVER` 不计入计划外停机；`MECH` 计入 |
| SC-8 | 停机帕累托 | 各原因码时长降序 + 累计百分比，合计等于总停机时长 |
| SC-9 | 审计可追溯 | 工单状态流转、派工、报工、停机均在 `/api/audit` 可查到 who/when/前后值 |

## 7. 依赖与集成点

| 依赖 | 说明 |
| --- | --- |
| `batch-management` | 工单派生自批次；报工回写 `batch_step` 与 `batch.progress` |
| `equipment-management` | 报工与停机关联设备；设备状态事件与停机事件时间窗合并（FR-18） |
| `recipe-management` | 工单继承配方版本快照；关键工序标记来自 `recipe_step` |
| `personnel-certification` | 派工与报工的人员资质 / 健康证校验（FR-6 / FR-7） |
| `alarm-center` | 长时停机自动生成报警（FR-19） |
| `production-cockpit` | 驾驶舱 OEE 因子改读本模块聚合结果，移除替代口径 |
| `multi-site` | 全部查询支持厂区维度 |
