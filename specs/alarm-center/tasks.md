# 报警中心 · 任务清单（tasks）

- **模块**：alarm-center
- **对应 plan**：`specs/alarm-center/plan.md`
- **优先级**：P0 = Phase 1 必须；P1 = 重要；P2 = 增强
- **图例**：✅ 已原型实现

## 任务

### T1 · 报警数据服务化（P0）
- 目标：建立 `src/api/alarms.js` 的 `listAlarms/ackAlarm/getTrend/getTopSources`，Phase 0 返回 Mock。
- 验收：Alarms 页面经服务取数；`ack` 调用 `ackAlarm`；Mock 与真实可切换。

### T2 · 未确认实时计数（P0）
- 目标：侧栏 badge 与顶栏铃铛读 `unacked` 实时计数（替换硬编码 3）。
- 验收：确认一条后计数递减且多处一致。

### T3 · 确认持久化 + 审计（P0）
- 目标：实现 FR-3/FR-10，`ackAlarm` 落库并写审计（确认人/时间/前后状态）。
- 验收：刷新后确认状态保留；审计表含记录；乐观更新失败回滚（R2）。

### T4 · 恢复状态机（P1）
- 目标：实现 FR-4，alarm 进入 recovered 并记录恢复时间；自动/人工均可。
- 验收：恢复后状态正确；SLA 计时停止（R3）。

### T5 · SCADA/OPC-UA 接入（P1）
- 目标：实现实时报警流适配器，报警 ≤ 刷新周期可见（NFR-1）。
- 验收：模拟 SCADA 事件推送，前端列表实时出现新报警。

### T6 · 响应 SLA 与升级（P1）
- 目标：实现 FR-8/FR-9，unacked 起算 SLA；critical 超时触发升级通知。
- 验收：critical 超时可配置阈值后触发升级；平均响应时长 KPI 准确。

### T7 · 报警抑制/闪避（P2）
- 目标：缓解报警风暴（R1），deadband + 持续时间策略。
- 验收：抖动信号不产生重复报警；策略可配置。

### T8 · 关联设备/批次跳转（P1）
- 目标：实现 FR-11，报警 source/relatedBatch 可跳转设备监控/批次详情。
- 验收：点击来源跳转对应模块；relatedBatch 存在时可跳转。

### T9 · 留存与检索（P1）
- 目标：实现 FR-12，报警留存 ≥ 3 年，按设备/级别/时间检索。
- 验收：历史报警可检索；冷/热存储分层生效。

### T11 · SLA 响应时限可配置化（P1 · G1 已实现）
- 目标：实现 FR-13~FR-17，把写死的 5/15/30 分钟改为运行时可读写的 `alarm_sla_policy`。
- 实现：`schema-p3.sql` 建表 + 种子；`AlarmSlaPolicyService`（`effectiveMinutes` / `list` / `update`）；
  `AlarmService.slaFor()` 读表、`defaultSla()` 兜底；建报警写 `sla_minutes` 快照；
  `GET/PUT /api/alarms/sla/policy`、`POST /api/alarms/sla/sweep`。
- 验收：修改为 10 分钟后新报警按 10 分钟判逾期，历史报警仍按原时限；`minutes=0` 或停用后该级别不计逾期；
  越界值（0< 或 >1440）返回 400；统计响应含 `slaPolicy`。

### T10 · 强提示与可访问性（P1）
- 目标：critical 未确认强提示（脉冲+可选声光），不依赖颜色（NFR-3）。
- 验收：critical 未确认有多重提示；通过对比度/非颜色校验。

## 进度总览
- Phase 0 已实现：FR-1~FR-7 静态展示、FR-3 前端确认。
- Phase 1 关键路径：T1→T2→T3（确认/审计）→T4（恢复）→T5（接入）→T6（SLA）→T8/T9（关联/留存）。
- Phase 1 已完成：T1~T6、T8~T9、T11（SLA 策略化，G1）；T7 抑制规则已实现（D1 抑制表）。
- Phase 2：T5 真实 SCADA 接入（现为模拟器）、T7 策略深化、T10 深化。
