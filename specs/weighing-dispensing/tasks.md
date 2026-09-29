# 配料称量与容差 · 任务清单（tasks）

- **模块**：weighing-dispensing
- **依赖**：`spec.md`、`plan.md`
- **版本**：v1.0 · 2026-09-14
- 图例：✅ 已完成 · ◐ 部分完成 · ⬜ 待办；优先级 P0/P1/P2

## P0 · 数据模型与容差判定

- ✅ **T1** 建表 `weighing_task` / `weighing_item`（schema-p3.sql，幂等）
  - 验收：启动自动建表；索引建立
- ✅ **T2** 实体 + Mapper（`WeighingTask` / `WeighingItem`，`@Mapper` 注解）
  - 验收：`mvn compile` 通过
- ✅ **T3** `POST /api/weighing/tasks` 创建任务（目标量 > 0、容差 0~50%、默认 1%）
  - 验收：非法目标量与容差返回 400；批次不存在 404
- ✅ **T4** `POST /api/weighing/tasks/{id}/weigh` 容差判定：`BigDecimal` 计算偏差百分比，
  输出 PASS / OVER / UNDER
  - 验收：目标 1250 / 实测 1300 → `+4.000%` `OVER`
- ✅ **T5** 累计达标自动 `DONE`
  - 验收：累计量 ≥ 目标量时状态变更

## P1 · 超差闭环与门禁

- ✅ **T6** 超差联动：偏差单 + major 报警 + 任务 `BLOCKED`
  - 验收：一次超差同时产生三者，明细记录 `deviationId` 与 `alarmId`
- ✅ **T7** `POST /api/weighing/items/{id}/review` 超差复核（QC+，`BATCH_REVIEW` 资质）
  - 验收：非 QC 返回 403；称量人本人复核返回 409
- ✅ **T8** 复核后自动解除阻断（全部超差已复核 → DONE / OPEN）
  - 验收：仍有未复核超差时维持 `BLOCKED`
- ✅ **T9** 资质门禁：称量需 `WEIGHING` 资质 + 有效健康证
  - 验收：缺证账号返回 403
- ✅ **T10** 放行批次只读：禁止新建任务与补录称量（409）
  - 验收：`released=true` 批次相关写入均被拒
- ✅ **T11** `GET /api/weighing/summary` 合规看板
  - 验收：合格率、OVER/UNDER 数、待复核数与明细一致
- ✅ **T12** 前端 `Weighing.jsx`：任务列表、称量录入、超差复核、看板
  - 验收：角色受限按钮正确禁用

## P2 · 增强（待办）

- ⬜ **T13** 目标量从 `recipe_step` 设定值自动带出（消除人工填写与配方脱节，R2）
  - 验收：按配方步骤创建任务时自动填充目标量与默认容差
- ⬜ **T14** 任务号生成改数据库序列，解决并发冲突（R4）
  - 验收：并发创建 10 个任务编号不重复
- ⬜ **T15** 衡器直连：OPC-UA / 串口采集自动填充实测值与衡器编号（R1）
  - 验收：采集数据自动写入 `weighing_item`

## 现状说明

- Phase G3 实现，Phase H 端到端验证通过（`scripts/verify-phase4.mjs`，40/40）。
- 演示路径：以工艺员建任务 → 操作员称量（PASS）→ 构造超差 → QC 复核解除阻断。
