import type { ComponentType, ReactNode } from 'react'
import { useEffect, useMemo, useState } from 'react'
import {
  CartesianGrid,
  Line,
  LineChart,
  ReferenceLine,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import {
  Activity,
  CalendarClock,
  ChevronRight,
  Gauge,
  HardDrive,
  TriangleAlert,
  Wrench,
  Zap,
} from 'lucide-react'
import { Badge, Button, Card, Dot, type BadgeTone } from '../components/ui'
import { PageHeader } from '../components/layout'
import { ComplianceStrip, MaintenancePanel } from '../components/equipment-panel'
import { AXIS_TICK, ChartTip } from '../components/charts'
import { equipmentHealthMap } from '../data/mes'
import { useEquipmentDetail, useEquipmentFleet, type EquipmentItem } from '../api/equipment'
import StalenessBadge from '../components/StalenessBadge'
import { DataSourceBadge, IntegrationStrip } from '../components/integration-strip'
import { sourceMeta } from '../api/integration'
import { cn } from '../lib/utils'

/** 设备健康状态 → 点色 / 文字色。 */
type EquipStatusKey = 'running' | 'alarm' | 'cleaning' | 'idle' | 'standby'

const STATUS_DOT: Record<EquipStatusKey, BadgeTone> = {
  running: 'accent',
  alarm: 'danger',
  cleaning: 'warn',
  idle: 'muted',
  standby: 'primary',
}
const STATUS_TEXT: Record<EquipStatusKey, string> = {
  running: 'text-accent',
  alarm: 'text-danger',
  cleaning: 'text-warn',
  idle: 'text-muted-foreground',
  standby: '',
}

/** 设备健康映射（mes.js 导出，键为 good/watch/fault/idle）。 */
const HEALTH_MAP: Record<string, { label: string; tone: BadgeTone }> = equipmentHealthMap as unknown as Record<
  string,
  { label: string; tone: BadgeTone }
>
const healthOf = (h?: string) => HEALTH_MAP[h ?? ''] ?? { label: h ?? '—', tone: 'muted' as BadgeTone }
/** 页面展示用设备条目（api 层类型 + fixture 缝合出的展示字段）。 */
type EquipRow = EquipmentItem & {
  type?: string
  vol?: string
  batch?: string
  runHours?: number
  mtbf?: number
  /** 以下三项 fixture/后端均必带，页面直接解引用，故这里放宽为非可选。 */
  maint: { last?: string; next?: string; nextTask?: string; cycleDays?: number; [key: string]: unknown }
  metrics: { oee?: number; availability?: number; performance?: number; quality?: number; [key: string]: unknown }
  params: NonNullable<EquipmentItem['params']>
}
// 由健康度 + 是否维护推导状态文案，供列表点色。
function equipStatus(e: EquipRow): EquipStatusKey {
  if (e.health === 'fault') return 'alarm'
  if (e.health === 'idle') return 'idle'
  if (e.maint?.nextTask?.includes('CIP') || e.code === 'F-103') return 'cleaning'
  return 'running'
}

function StatCard({
  icon: Icon,
  label,
  value,
  unit,
  badge,
  sub,
}: {
  icon: ComponentType<{ className?: string }>
  label: ReactNode
  value: ReactNode
  unit?: ReactNode
  badge?: ReactNode
  sub?: ReactNode
  [key: string]: unknown
}) {
  return (
    <Card className="card-pad" data-component="equip-stat">
      <div className="flex items-center justify-between gap-2">
        <span className="label-tech flex items-center gap-1.5">
          <Icon className="h-3.5 w-3.5" /> {label}
        </span>
        {badge}
      </div>
      <div className="mt-2.5 flex items-baseline gap-1.5">
        <span className="text-[28px] font-semibold leading-none tracking-tight">{value}</span>
        {unit && <span className="text-[13px] text-muted-foreground">{unit}</span>}
      </div>
      {sub && <div className="num mt-2 text-[11px] text-faint">{sub}</div>}
    </Card>
  )
}

function ParamStateBadge({ state }: { state?: string; [key: string]: unknown }) {
  if (state === 'ooc') return <Badge tone="danger" dot={false}>越限</Badge>
  if (state === 'near') return <Badge tone="warn" dot={false}>接近限值</Badge>
  return <Badge tone="accent" dot={false}>正常</Badge>
}

export default function Equipment({ onNavigate }: { onNavigate?: (key: string) => void }) {
  const { data: fleet } = useEquipmentFleet()
  const equipment = (fleet?.equipment ?? []) as EquipRow[]
  const summary = fleet?.summary
  const [code, setCode] = useState('F-101')
  const [paramIdx, setParamIdx] = useState(0)
  const { data: detail } = useEquipmentDetail(code)

  useEffect(() => setParamIdx(0), [code])

  const selected = useMemo(() => equipment.find((e) => e.code === code) ?? equipment[0], [equipment, code])

  const trendParam = detail?.trend?.[paramIdx]

  return (
    <div className="mx-auto max-w-[1600px] p-6" data-component="page-equipment">
      <PageHeader
        title="设备监控"
        desc="罐区与关键设备实时采集 · OPC-UA 数据每 30 秒刷新 · 超期自动降级"
        actions={
          <>
            <Button variant="ghost" onClick={() => onNavigate?.('alarms')}>
              <TriangleAlert className="h-3.5 w-3.5" /> 报警中心
            </Button>
            <Button variant="primary">
              <Wrench className="h-3.5 w-3.5" /> 维护计划
            </Button>
          </>
        }
      />
      <StalenessBadge generatedAt={fleet?.generatedAt} />

      {/* 设备合规总览（H1 · FR-6 / FR-8） */}
      <div className="mt-4">
        <ComplianceStrip />
      </div>

      {/* 外部系统集成状态（Phase J · 回答「这些数据是谁给的」） */}
      <div className="mt-4">
        <IntegrationStrip />
      </div>

      {/* KPI 行 */}
      <div className="mt-4 grid grid-cols-2 gap-4 xl:grid-cols-4">
        <StatCard
          icon={Zap}
          label="运行设备"
          value={summary?.running ?? '—'}
          unit={`/ ${summary?.total ?? 8} 台`}
          badge={<Badge tone="accent" dot={false}>在线</Badge>}
          sub={`待机 ${summary?.idle ?? 0} · 清洗 1 · 故障 ${summary?.alarm ?? 0}`}
        />
        <StatCard
          icon={Gauge}
          label="平均 OEE"
          value={summary?.oeeAvg ?? '—'}
          unit="%"
          badge={<Badge tone="primary" dot={false}>目标 85</Badge>}
          sub="排除待机设备后的运行均值"
        />
        <StatCard
          icon={Activity}
          label="故障设备"
          value={summary?.alarm ?? '—'}
          unit="台"
          badge={<Badge tone="danger" pulse>E-501 报警中</Badge>}
          sub="MVR 浓缩器蒸汽压力高高"
        />
        <StatCard
          icon={CalendarClock}
          label="今日维护"
          value={summary?.maintToday ?? '—'}
          unit="项到期"
          badge={<Badge tone="warn" dot={false}>F-102 明日</Badge>}
          sub="预防性维护按运行小时数触发"
        />
      </div>

      <div className="mt-4 grid grid-cols-1 gap-4 xl:grid-cols-3">
        {/* 设备列表 */}
        <Card className="card-pad" data-component="equip-list">
          <div className="mb-2 flex items-center justify-between">
            <div className="text-[15px] font-semibold tracking-tight">设备清单</div>
            <HardDrive className="h-4 w-4 text-faint" />
          </div>
          <div>
            {equipment.map((e) => {
              const st = equipStatus(e)
              const isActive = e.code === code
              return (
                <button
                  key={e.code}
                  onClick={() => setCode(e.code)}
                  className={cn(
                    'flex w-full items-center gap-3 rounded-md border-b border-border px-2 py-2.5 text-left transition-colors last:border-0 hover:bg-muted',
                    isActive && 'bg-muted',
                  )}
                >
                  <Dot tone={STATUS_DOT[st]} pulse={st === 'alarm'} />
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-2">
                      <span className="num text-xs text-muted-foreground">{e.code}</span>
                      <span className="truncate text-[13px] font-medium">{e.name}</span>
                    </div>
                    <div className="num mt-0.5 truncate text-[11px] text-faint">
                      {(e.metrics.oee ?? 0) > 0 ? `OEE ${e.metrics.oee}%` : e.maint.nextTask}
                    </div>
                  </div>
                  <span className={cn('shrink-0 text-xs font-medium', STATUS_TEXT[st])}>
                    {healthOf(e.health).label}
                  </span>
                  <ChevronRight className={cn('h-3.5 w-3.5 shrink-0', isActive ? 'text-primary' : 'text-faint')} />
                </button>
              )
            })}
          </div>
        </Card>

        {/* 设备详情 */}
        <div className="space-y-4 xl:col-span-2">
          {selected && (
            <>
              <Card className="card-pad" data-component="equip-detail-head">
                <div className="flex flex-wrap items-start justify-between gap-3">
                  <div>
                    <div className="flex items-center gap-2.5">
                      <span className="text-[17px] font-semibold tracking-tight">{selected.name}</span>
                      <Badge tone={healthOf(selected.health).tone} pulse={selected.health === 'fault'} dot={false}>
                        {healthOf(selected.health).label}
                      </Badge>
                    </div>
                    <div className="num mt-1 text-xs text-muted-foreground">
                      {selected.code} · {selected.type} · 有效容积 {selected.vol}
                    </div>
                  </div>
                  {selected.batch && selected.batch !== '—' ? (
                    <Button variant="outline" size="sm" onClick={() => onNavigate?.('batches')}>
                      关联批次 {selected.batch}
                      <ChevronRight className="h-3.5 w-3.5" />
                    </Button>
                  ) : (
                    <Badge tone="muted" dot={false}>当前无在制批次</Badge>
                  )}
                </div>

                <div className="mt-4 grid grid-cols-2 gap-3 sm:grid-cols-4">
                  <Metric label="OEE" value={`${selected.metrics.oee}%`} />
                  <Metric label="时间开动" value={`${selected.metrics.availability}%`} />
                  <Metric label="性能开动" value={`${selected.metrics.performance}%`} />
                  <Metric label="合格品率" value={`${selected.metrics.quality}%`} />
                </div>
                <div className="num mt-3 flex flex-wrap gap-x-6 gap-y-1 border-t border-border pt-3 text-[11px] text-faint">
                  <span>累计运行 {(selected.runHours ?? 0).toLocaleString()} h</span>
                  <span>平均无故障 MTBF {selected.mtbf} h</span>
                  <span>上次维护 {selected.maint.last}</span>
                  <span className="text-warn">下次 {selected.maint.next} · {selected.maint.nextTask}</span>
                </div>
              </Card>

              {/* 实时趋势 */}
              <Card className="card-pad" data-component="equip-trend">
                <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
                  <div>
                    <div className="flex items-center gap-2">
                      <span className="text-[15px] font-semibold tracking-tight">实时参数趋势</span>
                      <DataSourceBadge dataSource={detail?.dataSource} />
                    </div>
                    <div className="mt-0.5 text-xs text-muted-foreground">
                      近 12 小时 · 每 15 分钟一桶（桶内取最后一条）· 控制线为工艺限值
                    </div>
                  </div>
                  <div className="flex flex-wrap gap-1">
                    {(detail?.trend ?? []).map((p, i) => (
                      <button
                        key={p.name}
                        onClick={() => setParamIdx(i)}
                        className={cn(
                          'rounded-md border px-2.5 py-1 text-xs font-medium transition-colors',
                          i === paramIdx
                            ? 'border-primary bg-primary-soft text-primary'
                            : 'border-border text-muted-foreground hover:bg-muted',
                        )}
                      >
                        {p.name}
                      </button>
                    ))}
                  </div>
                </div>
                <div className="h-[240px]">
                  {trendParam && trendParam.series?.length ? (
                    <ResponsiveContainer width="100%" height="100%">
                      <LineChart data={trendParam.series} margin={{ top: 8, right: 10, left: -14, bottom: 0 }}>
                        <CartesianGrid stroke="var(--chart-grid)" vertical={false} />
                        <XAxis dataKey="t" tick={AXIS_TICK} tickLine={false} axisLine={false} minTickGap={28} />
                        <YAxis
                          domain={['auto', 'auto']}
                          tick={AXIS_TICK}
                          tickLine={false}
                          axisLine={false}
                          width={56}
                        />
                        <Tooltip content={<ChartTip unit={` ${trendParam.unit}`} />} cursor={{ stroke: 'var(--border-strong)' }} />
                        <ReferenceLine y={trendParam.hi} stroke="var(--warn)" strokeDasharray="4 4" label={{ value: '上限', position: 'insideTopRight', fill: 'var(--warn)', fontSize: 10 }} />
                        <ReferenceLine y={trendParam.lo} stroke="var(--warn)" strokeDasharray="4 4" label={{ value: '下限', position: 'insideBottomRight', fill: 'var(--warn)', fontSize: 10 }} />
                        <Line type="monotone" dataKey="v" name={trendParam.name} stroke="var(--seed-primary)" strokeWidth={2} dot={false} isAnimationActive={false} />
                      </LineChart>
                    </ResponsiveContainer>
                  ) : detail && (detail.trend ?? []).length === 0 ? (
                    // 后端读 equipment_metric 为空——不是「加载中」，是真的没有历史采样。
                    // 此前这里用前端伪曲线填充，掩盖了采集链路没通的问题。
                    <div className="flex h-full flex-col items-center justify-center gap-1 text-xs text-faint">
                      <span>暂无趋势数据：equipment_metric 中还没有该设备近 12 小时的采样</span>
                      <span>请确认采集链路（SCADA 模式 / 采集周期）或点击上方「立即采集」</span>
                    </div>
                  ) : (
                    <div className="flex h-full items-center justify-center text-xs text-faint">加载趋势…</div>
                  )}
                </div>
              </Card>

              {/* 监控参数 + 关联报警 */}
              <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
                <Card className="card-pad" data-component="equip-params">
                  <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
                    <div className="text-[15px] font-semibold tracking-tight">监控参数</div>
                    <DataSourceBadge dataSource={selected.paramsDataSource ?? detail?.dataSource} />
                  </div>
                  <div className="overflow-x-auto">
                    <table className="w-full text-[13px]">
                      <thead>
                        <tr className="border-y border-border text-left text-xs text-muted-foreground">
                          <th className="py-2 pr-2 font-medium">参数</th>
                          <th className="py-2 pr-2 text-right font-medium">实时</th>
                          <th className="py-2 pr-2 font-medium">限值</th>
                          <th className="py-2 pr-2 font-medium">状态</th>
                          <th className="py-2 font-medium">来源</th>
                        </tr>
                      </thead>
                      <tbody className="divide-y divide-border">
                        {selected.params.map((p) => (
                          <tr key={p.name}>
                            <td className="py-2.5 pr-2">{p.name}</td>
                            <td className={cn('num py-2.5 pr-2 text-right font-medium', p.state === 'ooc' ? 'text-danger' : p.state === 'near' ? 'text-warn' : 'text-foreground')}>
                              {p.value}{p.unit && <span className="ml-0.5 text-[11px] text-faint">{p.unit}</span>}
                            </td>
                            <td className="num py-2.5 pr-2 text-xs text-muted-foreground">
                              {p.lo} ~ {p.hi}{p.unit && ` ${p.unit}`}
                            </td>
                            <td className="py-2.5 pr-2"><ParamStateBadge state={p.state} /></td>
                            <td className="py-2.5 text-[11px] text-faint">
                              {sourceMeta(p.dataSource).label}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                </Card>

                <Card className="card-pad" data-component="equip-alarms">
                  <div className="mb-2 flex items-center justify-between">
                    <div className="text-[15px] font-semibold tracking-tight">关联报警</div>
                    <Badge tone={detail?.alarms?.length ? 'danger' : 'muted'} dot={false}>
                      {detail?.alarms?.length ?? 0} 条
                    </Badge>
                  </div>
                  {detail?.alarms?.length ? (
                    <div>
                      {detail.alarms.map((a) => (
                        <div key={a.id} className="border-b border-border py-2.5 last:border-0">
                          <div className="flex items-center gap-2">
                            <Dot tone={a.level === 'critical' ? 'danger' : a.level === 'major' ? 'warn' : 'muted'} pulse={a.status === 'unacked'} />
                            <span className="truncate text-[13px] font-medium">{a.content}</span>
                            <span className="num ml-auto shrink-0 text-[11px] text-faint">{a.time}</span>
                          </div>
                          <div className="num mt-1 pl-4 text-[11px] text-muted-foreground">
                            {a.value}（阈值 {a.threshold}）· {a.status === 'unacked' ? '未确认' : a.status === 'acked' ? `已确认 ${a.ackBy ?? ''}` : '已恢复'}
                          </div>
                        </div>
                      ))}
                    </div>
                  ) : (
                    <div className="flex h-[120px] flex-col items-center justify-center gap-1.5 text-center text-xs text-faint">
                      <Activity className="h-5 w-5" />
                      该设备近 24 小时无报警记录
                    </div>
                  )}
                </Card>
              </div>

              {/* H1 · 设备保全：保养 / 校准 / 工单 / 状态 / OEE */}
              <MaintenancePanel code={code} />
            </>
          )}
        </div>
      </div>
    </div>
  )
}

function Metric({ label, value }: { label: ReactNode; value: ReactNode; [key: string]: unknown }) {
  return (
    <div className="rounded-md bg-muted px-3 py-2">
      <div className="label-tech">{label}</div>
      <div className="num mt-1 text-[17px] font-semibold tracking-tight">{value}</div>
    </div>
  )
}
