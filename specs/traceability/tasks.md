# 追溯管理 · 任务清单（tasks）

- **模块**：traceability
- **依赖**：`spec.md`、`plan.md`
- **版本**：v1.0 · 2026-09-14
- 图例：✅ 已完成 · ◐ 部分完成 · ⬜ 待办

## P0 · 谱系数据落地

- ✅ **T1** 建表 `material_lot` / `batch_material` / `batch_genealogy` / `trace_query_log`（幂等 + 关系索引）
  - 验收：重复启动不报错；关系查询走索引
- ✅ **T2** 实体 + Mapper，字段与 plan 数据模型一致
  - 验收：`mvn compile` 通过
- ✅ **T3** 种子导入：从 `batch.material_lots` 解析出 `batch_material`，生成示例 `material_lot` 与父子关系
  - 验收：关系表行数与批次 JSON 中的原料条目一致
- ✅ **T4** `GET /api/trace/{batchId}` 改读关系表组装正向链路，契约兼容前端现有节点结构
  - 验收：前端追溯链渲染与内存模式一致

## P1 · 逆向与影响面

- ✅ **T5** `GET /api/trace/backward?lotNo=`：列出消耗该原料的成品批次与投用数量
  - 验收：与投料记录核对一致
- ✅ **T6** `GET /api/trace/impact?lotNo=`：汇总受影响成品批次、流向与放行状态（QC+）
  - 验收：数量/状态正确，≤ 500 批次响应 < 5s
- ✅ **T7** 完整性校验 `GET /api/trace/{batchId}/completeness`：缺原料/设备/操作人即标记并列出缺失项
  - 验收：构造缺失批次可见标记与缺失清单
- ✅ **T8** 追溯查询审计：查询与导出写 `trace_query_log` + `audit_log`
  - 验收：`/api/audit` 与追溯日志均可查到记录

## P2 · 关联与导出

- ✅ **T9** 节点横向关联：聚合链路节点的报警与偏差单，支持下钻
- ✅ **T10** 追溯报表导出（PDF/Excel，含生成时间与操作人），大结果异步生成
- ✅ **T11** 前端：逆向追溯入口、影响面清单、完整性标记与导出按钮（角色受限）
- ⬜ **T12** 扫码入口（托盘码 → 批次追溯视图，Phase 2）

## 现状说明

- Phase 0/1 已实现：单批次正向追溯链可视化（原料 → 工序 → 成品节点与流转关系，`Trace.jsx` + `TraceController`），数据源为 fixture 示例链。
- 本期新增：谱系结构化、逆向追溯、影响面分析、完整性校验与报表导出（T1–T10）。
