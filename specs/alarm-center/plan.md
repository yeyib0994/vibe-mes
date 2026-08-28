# 报警中心 · 技术计划（plan）

- **模块**：alarm-center
- **对应 spec**：`specs/alarm-center/spec.md`
- **版本**：v1.0 · 2026-08-26

## 1. 架构概览

```
Alarms (src/pages/Alarms.jsx)
   ├─ AlarmStat ×4           # 今日报警/活跃/平均响应/确认率
   ├─ AlarmTrend (BarChart)  # 按级别堆叠
   ├─ AlarmSources           # 高频源排行
   └─ AlarmTable + ack()     # 列表 + 确认（原型本地 state）
        │
        ▼  Phase 1 起
   src/api/alarms.js
     listAlarms(filter) / ackAlarm(id, by) / getTrend(range) / getTopSources(range)
        │
        ▼
   Alarm Service（状态机 + SLA + 升级）
   SCADA / OPC-UA Adapter（实时报警流）
   WebSocket / SSE（推送）
```

## 2. 数据模型

```ts
// Phase 1 契约
type AlarmLevel = 'critical' | 'major' | 'minor'
type AlarmStatus = 'unacked' | 'acked' | 'recovered' | 'closed'

interface Alarm {
  id: string                 // A-YYMMDD-NNN
  time: string               // ISO 触发时间
  level: AlarmLevel
  source: string             // 设备编码，如 E-501
  content: string
  value: string
  threshold: string
  status: AlarmStatus
  ackBy?: string
  ackAt?: string
  recoveredAt?: string
  relatedBatch?: string      // 关联批次（若有）
  audit: AuditEntry[]        // 确认/恢复记录（C3）
}

interface AlarmTrendPoint { t: string; critical: number; major: number; minor: number }
interface AlarmSourceStat { source: string; count: number }
```

- `status` 状态机在后端维护，前端 `ack()` 仅调用 `ackAlarm(id, by)`；原型 `STATUS_MAP` 的 unacked/acked/recovered 保持一致。
- `relatedBatch` 由 SCADA 事件附带上下文（如某设备正在运行的批次）。

## 3. 关键技术决策

- **实时流**：SCADA/OPC-UA 报警经适配器转为 `Alarm` 事件，通过 WebSocket/SSE 推前端；列表用乐观更新（确认即本地置 acked，再落库）。
- **Mock 适配器**：`src/api/alarms.js` Phase 0 返回 `mes.js` 的 `alarmData/alarmTrend/alarmTopSources`，`ack` 仅改本地；Phase 1 改为 HTTP + 推送。
- **SLA 计时**：后端对 `unacked` 起算响应计时；critical 超时触发升级（FR-9），升级策略可配置。
- **确认审计**：`ackAlarm` 落 `audit`（确认人/时间/前后状态），满足 C3；恢复同理。
- **计数一致性**：侧栏 badge 与顶栏铃铛均读 `unacked` 实时计数（FR-7），来源唯一。

## 4. 集成点

- **SCADA / OPC-UA**：报警实时来源；设备状态联动。
- **batch-management**：`relatedBatch` 关联；设备异常可影响批次状态。
- **quality-management**：SPC 失控可同步生成工艺报警（major/critical）。
- **equipment-monitor（规划）**：报警 source 跳转设备详情。

## 5. 合规与安全设计

- **C3 审计**：确认/恢复写审计（FR-10），不可篡改。
- **C4 权限**：确认动作限值班长/工艺员角色。
- **C6 留存**：报警记录保留 ≥ 3 年（FR-12），按设备/级别/时间可检索；存储分层（热/冷）。
- **强提示**：critical 未确认须强提示（脉冲+可选声光），不依赖颜色（NFR-3）。

## 6. 风险

- **R1 · 报警风暴**：单设备抖动产生大量报警 → 缓解：报警抑制/闪避（deadband + 持续时间）策略。
- **R2 · 确认丢失**：前端乐观更新失败 → 缓解：失败回滚并提示。
- **R3 · SLA 误计**：恢复后仍未确认计时 → 缓解：状态机守卫，恢复即停 SLA 计时。
- **R4 · 存储膨胀**：3 年报警量大 → 缓解：时序/冷存储分层，聚合检索。
