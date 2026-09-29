# 报警中心 · 技术计划（plan）

- **模块**：alarm-center
- **对应 spec**：`specs/alarm-center/spec.md`
- **版本**：v1.1 · 2026-09-14（v1.0 2026-08-26；v1.1 增补 G1 SLA 策略可配置化）

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

**G1 新增 · SLA 策略表**（`resources/db/schema-p3.sql`）：

| 表 / 列 | 用途 | 关键字段 |
| --- | --- | --- |
| `alarm_sla_policy` | 按级别的响应时限配置 | level(PK critical/major/minor)、minutes、enabled、note、updated_by、updated_at |
| `alarm.sla_minutes` | 报警创建时的时限快照 | 审计口径，策略调整不追溯 |
| `alarm.escalated` / `escalated_at` / `escalation_note` | 升级标记与时间 | 巡检写入，SSE 推送 |

- `status` 状态机在后端维护，前端 `ack()` 仅调用 `ackAlarm(id, by)`；原型 `STATUS_MAP` 的 unacked/acked/recovered 保持一致。
- `relatedBatch` 由 SCADA 事件附带上下文（如某设备正在运行的批次）。

## 3. 关键技术决策

- **实时流**：SCADA/OPC-UA 报警经适配器转为 `Alarm` 事件，通过 WebSocket/SSE 推前端；列表用乐观更新（确认即本地置 acked，再落库）。
- **Mock 适配器**：`src/api/alarms.js` Phase 0 返回 `mes.js` 的 `alarmData/alarmTrend/alarmTopSources`，`ack` 仅改本地；Phase 1 改为 HTTP + 推送。
- **SLA 计时**：后端对 `unacked` 起算响应计时；critical 超时触发升级（FR-9），升级策略可配置。
- **G1 · SLA 时限配置优先，常量兜底**（D5）：`AlarmService.slaFor(level)` 读 `alarm_sla_policy`，
  表缺失/非法时回落 `defaultSla()`（5/15/30）；`enabled=false` 返回 0 = 豁免考核。
- **G1 · 时限快照到报警**（D6）：建报警时把 `slaFor(level)` 写入 `alarm.sla_minutes`，
  逾期判定用 `storedSla(alarm)` 而非当前策略，保证审计口径稳定、调参不追溯。
- **G1 · 巡检即升级**（D7）：`sweepSla()` 每 60s 扫描 `unacked && !escalated`，
  逾期的置 `escalated`、写审计 `alarm.escalate` 并 SSE 推送；`POST /api/alarms/sla/sweep` 可手动触发。
- **确认审计**：`ackAlarm` 落 `audit`（确认人/时间/前后状态），满足 C3；恢复同理。
- **计数一致性**：侧栏 badge 与顶栏铃铛均读 `unacked` 实时计数（FR-7），来源唯一。

## 4. 集成点

- **SCADA / OPC-UA**：报警实时来源；设备状态联动。
- **batch-management**：`relatedBatch` 关联；设备异常可影响批次状态。
- **quality-management**：SPC 失控可同步生成工艺报警（major/critical）。
- **equipment-monitor（规划）**：报警 source 跳转设备详情。

**G1 端点**（`AlarmController` / `AlarmSlaPolicyService`）：

| 端点 | 说明 | 权限 |
| --- | --- | --- |
| `GET /api/alarms/sla/policy` | 各级别响应时限（含 `effectiveMinutes`，0 = 豁免） | 登录 |
| `PUT /api/alarms/sla/policy/{level}` | 调整时限（1~1440）/ 启停 | 值班长+ |
| `POST /api/alarms/sla/sweep` | 手动触发 SLA 巡检 | 值班长+ |

统计响应体附 `slaPolicy: { critical, major, minor }`（FR-17）。

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
- **R5 · SLA 调参追溯**：放宽时限后历史逾期报警是否洗白 → 缓解：时限快照到报警（D6），
  判定用 `storedSla()`；策略变更本身写 `updated_by/updated_at` 与审计。
- **R6 · 豁免与 0 值歧义**：`minutes=0` 与 `enabled=false` 语义重合 → 缓解：统一为
  `effectiveMinutes()==0` 即豁免，`isOverdue()` 据此短路返回 false。
