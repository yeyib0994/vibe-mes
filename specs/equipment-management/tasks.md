# 设备管理 · 任务清单（tasks）

- **模块**：equipment-management
- **依赖**：`spec.md`、`plan.md`
- **版本**：v1.0 · 2026-09-14
- 图例：✅ 已完成 · ◐ 部分完成 · ⬜ 待办；优先级 P0/P1/P2

## P0 · 数据持久化与主数据

- ✅ **T1** 建表 `equipment` / `equipment_event` / `maintenance_order`（schema.sql，幂等 `IF NOT EXISTS`）
  - 验收：应用启动自动建表；重复启动不报错
- ✅ **T2** 实体 + Mapper（MyBatis-Plus），`Equipment` 实体字段与 plan 数据模型一致
  - 验收：`mvn compile` 通过
- ✅ **T3** 种子导入：mes.json 设备台账 → `equipment`，状态分布覆盖运行/报警/清洗/待机
  - 验收：表行数与 fixture 一致，二次启动不重复插入
- ✅ **T4** `GET /api/equipment` 改读 PG（保留状态筛选与关键字检索），契约兼容前端现有字段
  - 验收：筛选/检索结果与内存模式一致

## P1 · 状态机与维护

- ✅ **T5** `POST /api/equipment/{code}/status`：状态变更写 `equipment_event` + 审计（C3）
  - 验收：任一变更可在 `/api/audit` 查到 who/when/前后值
- ✅ **T6** 保养到期提示：`next_maintenance_at` 逾期设备在台账与驾驶舱标记
  - 验收：构造逾期数据可见标记
- ✅ **T7** 维护工单：创建/完成，完成时更新 `runtime_hours` 与下次保养日期
  - 验收：完成后下次保养日期按周期顺延
- ✅ **T8** 校准有效期阻断：`calibration_due_at` 超期设备不可被新批次引用，且其检验数据不参与放行
  - 验收：以超期设备建批次返回 409；放行接口返回明确错误

## P2 · 增强

- ✅ **T9** 设备详情关联视图：报警、在制批次、最近维护记录聚合
- ✅ **T10** OEE 三因子：可用率取自 `equipment_event` 停机时长，性能率与良品率取批次与质检
- ✅ **T11** 前端设备详情页 + 状态变更与工单操作（角色受限）
- ⬜ **T12** 实时参数采集接入 OPC-UA/Modbus（Phase 2，替换模拟器）

## 现状说明

- Phase 0/1 已实现：设备台账表格、状态筛选与关键字检索、罐区实时监控卡（`Equipment.jsx` + `EquipmentController`），数据源为 `FixtureStore` 只读 fixture。
- 本期新增：持久化、状态机、维护工单与校准合规阻断（T1–T8）。
