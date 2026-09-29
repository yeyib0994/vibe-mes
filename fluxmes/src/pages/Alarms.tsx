import type { ReactNode } from 'react'
import { useMemo, useState } from 'react'
import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { ShieldCheck } from 'lucide-react'
import { Badge, Button, Card, Tabs, type BadgeTone } from '../components/ui'
import { PageHeader } from '../components/layout'
import { AXIS_TICK, ChartTip } from '../components/charts'
import { alarmLevelMap, alarmTopSources, alarmTrend } from '../data/mes'
import { useAlarms, useAckAlarm, useRecoverAlarm, useAlarmTrend, useAlarmTopSources, useAlarmStats, type AlarmItem } from '../api/alarms'
import { SuppressionPanel } from '../components/alarm-suppression'
import StalenessBadge from '../components/StalenessBadge'

const STATUS_MAP: Record<string, { label: string; tone: BadgeTone }> = {
  unacked: { label: '未确认', tone: 'danger' },
  acked: { label: '已确认', tone: 'warn' },
  recovered: { label: '已恢复', tone: 'muted' },
}

function fmtWhen(a: AlarmItem) {
  if (!a.triggeredAt) return `08-26 ${a.time}`
  const d = new Date(a.triggeredAt)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${a.time ?? `${p(d.getHours())}:${p(d.getMinutes())}`}`
}

function AlarmStat({
  label,
  value,
  unit,
  badge,
}: {
  label: ReactNode
  value: ReactNode
  unit?: ReactNode
  badge?: ReactNode
  [key: string]: unknown
}) {
  return (
    <Card className="card-pad" data-component="alarm-kpi" data-qoder-id="qel-alarm-kpi-fcec10f1" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-alarm-kpi-fcec10f1&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;AlarmStat&quot;,&quot;elementRole&quot;:&quot;alarm-kpi&quot;,&quot;loc&quot;:{&quot;line&quot;:17,&quot;column&quot;:5}}">
      <div className="flex items-center justify-between" data-qoder-id="qel-flex-d276474e" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-flex-d276474e&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;AlarmStat&quot;,&quot;elementRole&quot;:&quot;flex&quot;,&quot;loc&quot;:{&quot;line&quot;:18,&quot;column&quot;:7}}">
        <span className="label-tech" data-qoder-id="qel-label-tech-6bd3d37e" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-label-tech-6bd3d37e&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;AlarmStat&quot;,&quot;elementRole&quot;:&quot;label-tech&quot;,&quot;loc&quot;:{&quot;line&quot;:19,&quot;column&quot;:9}}">{label}</span>
        {badge}
      </div>
      <div className="mt-2.5 flex items-baseline gap-1.5" data-qoder-id="qel-mt-2-5-15ac998f" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-mt-2-5-15ac998f&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;AlarmStat&quot;,&quot;elementRole&quot;:&quot;mt-2-5&quot;,&quot;loc&quot;:{&quot;line&quot;:22,&quot;column&quot;:7}}">
        <span className="text-[26px] font-semibold leading-none tracking-tight" data-qoder-id="qel-text-26px-a6353eaf" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-text-26px-a6353eaf&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;AlarmStat&quot;,&quot;elementRole&quot;:&quot;text-26px&quot;,&quot;loc&quot;:{&quot;line&quot;:23,&quot;column&quot;:9}}">{value}</span>
        {unit && <span className="text-[13px] text-muted-foreground" data-qoder-id="qel-text-13px-4552befa" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-text-13px-4552befa&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;AlarmStat&quot;,&quot;elementRole&quot;:&quot;text-13px&quot;,&quot;loc&quot;:{&quot;line&quot;:24,&quot;column&quot;:18}}">{unit}</span>}
      </div>
    </Card>
  )
}

export default function Alarms() {
  const { data: alarmsRaw } = useAlarms()
  const ack = useAckAlarm()
  const recover = useRecoverAlarm()
  const { data: trendRaw } = useAlarmTrend(8)
  const { data: sourcesRaw } = useAlarmTopSources(7, 5)
  const { data: statsRaw } = useAlarmStats()
  const [tab, setTab] = useState('all')

  const alarms: AlarmItem[] = alarmsRaw?.alarms ?? []
  const trend = trendRaw ?? alarmTrend
  const sources = sourcesRaw ?? alarmTopSources
  const stats = statsRaw ?? null

  const counts = useMemo(() => {
    const c: Record<string, number> = { all: alarms.length, unacked: 0, acked: 0, recovered: 0 }
    for (const a of alarms) c[a.status] = (c[a.status] ?? 0) + 1
    return c
  }, [alarms])

  const tabs = [
    { key: 'all', label: '全部', count: counts.all },
    { key: 'unacked', label: '未确认', count: counts.unacked },
    { key: 'acked', label: '已确认', count: counts.acked },
    { key: 'recovered', label: '已恢复', count: counts.recovered },
  ]

  const filtered = alarms.filter((a) => tab === 'all' || a.status === tab)
  const maxSource = sources.length ? Math.max(...sources.map((s) => s.count ?? 0)) : 1

  return (
    <div className="mx-auto max-w-[1600px] p-6" data-component="page-alarms" data-qoder-id="qel-page-alarms-8211c23b" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-page-alarms-8211c23b&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;Alarms&quot;,&quot;elementRole&quot;:&quot;page-alarms&quot;,&quot;loc&quot;:{&quot;line&quot;:55,&quot;column&quot;:5}}">
      <PageHeader
        title="报警中心"
        desc="工艺与设备报警统一接入 · 分级响应 · 报警数据保留 3 年"
        actions={<ShieldCheck className="h-4 w-4 text-faint" />}
       data-qoder-id="qel-pageheader-c716cad5" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-pageheader-c716cad5&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;Alarms&quot;,&quot;elementRole&quot;:&quot;pageheader&quot;,&quot;loc&quot;:{&quot;line&quot;:56,&quot;column&quot;:7}}"/>
      <StalenessBadge generatedAt={alarmsRaw?.generatedAt} />

      <div className="grid grid-cols-2 gap-4 xl:grid-cols-4" data-qoder-id="qel-grid-2c594361" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-grid-2c594361&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;Alarms&quot;,&quot;elementRole&quot;:&quot;grid&quot;,&quot;loc&quot;:{&quot;line&quot;:62,&quot;column&quot;:7}}">
        <AlarmStat label="今日报警" value={stats ? stats.todayTotal : '17'} unit="条" badge={<Badge tone="warn" dot={false}>活跃 {stats ? stats.active : '—'}</Badge>}  data-qoder-id="qel-alarmstat-27018e6e" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-alarmstat-27018e6e&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;Alarms&quot;,&quot;elementRole&quot;:&quot;alarmstat&quot;,&quot;loc&quot;:{&quot;line&quot;:63,&quot;column&quot;:9}}"/>
        <AlarmStat label="活跃报警" value={stats ? stats.active : '3'} unit="条" badge={<Badge tone="danger" pulse={stats ? (stats.unackedCritical ?? 0) > 0 : true}>含严重 {stats ? stats.unackedCritical : '—'} · 超时 {stats ? stats.overdueUnacked : '—'}</Badge>}  data-qoder-id="qel-alarmstat-28019001" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-alarmstat-28019001&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;Alarms&quot;,&quot;elementRole&quot;:&quot;alarmstat&quot;,&quot;loc&quot;:{&quot;line&quot;:64,&quot;column&quot;:9}}"/>
        <AlarmStat label="平均响应时长" value={stats ? (stats.avgResponseMinutes ?? '—') : '4.2'} unit="min" badge={<Badge tone="accent" dot={false}>目标 ≤ 5 min</Badge>}  data-qoder-id="qel-alarmstat-25018b48" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-alarmstat-25018b48&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;Alarms&quot;,&quot;elementRole&quot;:&quot;alarmstat&quot;,&quot;loc&quot;:{&quot;line&quot;:65,&quot;column&quot;:9}}"/>
        <AlarmStat label="本班确认率" value={stats ? stats.ackRatePercent : '82'} unit="%"  data-qoder-id="qel-alarmstat-26018cdb" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-alarmstat-26018cdb&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;Alarms&quot;,&quot;elementRole&quot;:&quot;alarmstat&quot;,&quot;loc&quot;:{&quot;line&quot;:66,&quot;column&quot;:9}}"/>
      </div>

      <div className="mt-4 grid grid-cols-1 gap-4 xl:grid-cols-3" data-qoder-id="qel-mt-4-9b2074ff" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-mt-4-9b2074ff&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;Alarms&quot;,&quot;elementRole&quot;:&quot;mt-4&quot;,&quot;loc&quot;:{&quot;line&quot;:69,&quot;column&quot;:7}}">
        <Card className="card-pad xl:col-span-2" data-component="alarm-trend" data-qoder-id="qel-alarm-trend-cb73013c" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-alarm-trend-cb73013c&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;Alarms&quot;,&quot;elementRole&quot;:&quot;alarm-trend&quot;,&quot;loc&quot;:{&quot;line&quot;:70,&quot;column&quot;:9}}">
          <div className="mb-3 flex items-center justify-between" data-qoder-id="qel-mb-3-ef87dd54" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-mb-3-ef87dd54&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;Alarms&quot;,&quot;elementRole&quot;:&quot;mb-3&quot;,&quot;loc&quot;:{&quot;line&quot;:71,&quot;column&quot;:11}}">
            <div data-qoder-id="qel-div-33f12e4b" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-div-33f12e4b&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;Alarms&quot;,&quot;elementRole&quot;:&quot;div&quot;,&quot;loc&quot;:{&quot;line&quot;:72,&quot;column&quot;:13}}">
              <div className="text-[15px] font-semibold tracking-tight" data-qoder-id="qel-text-15px-d219c7a7" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-text-15px-d219c7a7&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;Alarms&quot;,&quot;elementRole&quot;:&quot;text-15px&quot;,&quot;loc&quot;:{&quot;line&quot;:73,&quot;column&quot;:15}}">报警趋势</div>
              <div className="mt-0.5 text-xs text-muted-foreground" data-qoder-id="qel-mt-0-5-da429d2e" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-mt-0-5-da429d2e&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;Alarms&quot;,&quot;elementRole&quot;:&quot;mt-0-5&quot;,&quot;loc&quot;:{&quot;line&quot;:74,&quot;column&quot;:15}}">最近 8 小时 · 按级别统计</div>
            </div>
            <div className="flex items-center gap-3 text-xs text-muted-foreground" data-qoder-id="qel-flex-7b328551" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-flex-7b328551&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;Alarms&quot;,&quot;elementRole&quot;:&quot;flex&quot;,&quot;loc&quot;:{&quot;line&quot;:76,&quot;column&quot;:13}}">
              <span className="flex items-center gap-1.5" data-qoder-id="qel-flex-bd97213b" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-flex-bd97213b&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;Alarms&quot;,&quot;elementRole&quot;:&quot;flex&quot;,&quot;loc&quot;:{&quot;line&quot;:77,&quot;column&quot;:15}}"><span className="h-2 w-2 rounded-sm bg-danger"  data-qoder-id="qel-h-2-0de9f424" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-h-2-0de9f424&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;Alarms&quot;,&quot;elementRole&quot;:&quot;h-2&quot;,&quot;loc&quot;:{&quot;line&quot;:77,&quot;column&quot;:59}}"/> 严重</span>
              <span className="flex items-center gap-1.5" data-qoder-id="qel-flex-bf972461" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-flex-bf972461&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;Alarms&quot;,&quot;elementRole&quot;:&quot;flex&quot;,&quot;loc&quot;:{&quot;line&quot;:78,&quot;column&quot;:15}}"><span className="h-2 w-2 rounded-sm bg-warn"  data-qoder-id="qel-h-2-0be9f0fe" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-h-2-0be9f0fe&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;Alarms&quot;,&quot;elementRole&quot;:&quot;h-2&quot;,&quot;loc&quot;:{&quot;line&quot;:78,&quot;column&quot;:59}}"/> 重要</span>
              <span className="flex items-center gap-1.5" data-qoder-id="qel-flex-c1972787" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-flex-c1972787&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;Alarms&quot;,&quot;elementRole&quot;:&quot;flex&quot;,&quot;loc&quot;:{&quot;line&quot;:79,&quot;column&quot;:15}}"><span className="h-2 w-2 rounded-sm bg-faint"  data-qoder-id="qel-h-2-09e9edd8" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-h-2-09e9edd8&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;Alarms&quot;,&quot;elementRole&quot;:&quot;h-2&quot;,&quot;loc&quot;:{&quot;line&quot;:79,&quot;column&quot;:59}}"/> 一般</span>
            </div>
          </div>
          <div className="h-[220px]" data-qoder-id="qel-h-220px-fcff8fe6" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-h-220px-fcff8fe6&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;Alarms&quot;,&quot;elementRole&quot;:&quot;h-220px&quot;,&quot;loc&quot;:{&quot;line&quot;:82,&quot;column&quot;:11}}">
            <ResponsiveContainer width="100%" height="100%" data-qoder-id="qel-responsivecontainer-94932c85" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-responsivecontainer-94932c85&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;Alarms&quot;,&quot;elementRole&quot;:&quot;responsivecontainer&quot;,&quot;loc&quot;:{&quot;line&quot;:83,&quot;column&quot;:13}}">
              <BarChart data={trend} margin={{ top: 8, right: 8, left: -22, bottom: 0 }} data-qoder-id="qel-barchart-c52cb518" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-barchart-c52cb518&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;Alarms&quot;,&quot;elementRole&quot;:&quot;barchart&quot;,&quot;loc&quot;:{&quot;line&quot;:84,&quot;column&quot;:15}}">
                <CartesianGrid stroke="var(--chart-grid)" vertical={false}  data-qoder-id="qel-cartesiangrid-74c98ec8" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-cartesiangrid-74c98ec8&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;Alarms&quot;,&quot;elementRole&quot;:&quot;cartesiangrid&quot;,&quot;loc&quot;:{&quot;line&quot;:85,&quot;column&quot;:17}}"/>
                <XAxis dataKey="t" tick={AXIS_TICK} tickLine={false} axisLine={false}  data-qoder-id="qel-xaxis-390c3467" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-xaxis-390c3467&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;Alarms&quot;,&quot;elementRole&quot;:&quot;xaxis&quot;,&quot;loc&quot;:{&quot;line&quot;:86,&quot;column&quot;:17}}"/>
                <YAxis tick={AXIS_TICK} tickLine={false} axisLine={false} allowDecimals={false}  data-qoder-id="qel-yaxis-063df7b2" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-yaxis-063df7b2&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;Alarms&quot;,&quot;elementRole&quot;:&quot;yaxis&quot;,&quot;loc&quot;:{&quot;line&quot;:87,&quot;column&quot;:17}}"/>
                <Tooltip content={<ChartTip />} cursor={{ fill: 'var(--muted)' }}  data-qoder-id="qel-tooltip-2ae70cd9" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-tooltip-2ae70cd9&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;Alarms&quot;,&quot;elementRole&quot;:&quot;tooltip&quot;,&quot;loc&quot;:{&quot;line&quot;:88,&quot;column&quot;:17}}"/>
                <Bar dataKey="critical" name="严重" stackId="a" fill="var(--danger)" barSize={18}  data-qoder-id="qel-bar-92eafe18" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-bar-92eafe18&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;Alarms&quot;,&quot;elementRole&quot;:&quot;bar&quot;,&quot;loc&quot;:{&quot;line&quot;:89,&quot;column&quot;:17}}"/>
                <Bar dataKey="major" name="重要" stackId="a" fill="var(--warn)" barSize={18}  data-qoder-id="qel-bar-93eaffab" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-bar-93eaffab&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;Alarms&quot;,&quot;elementRole&quot;:&quot;bar&quot;,&quot;loc&quot;:{&quot;line&quot;:90,&quot;column&quot;:17}}"/>
                <Bar dataKey="minor" name="一般" stackId="a" fill="var(--faint)" radius={[3, 3, 0, 0]} barSize={18}  data-qoder-id="qel-bar-94eb013e" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-bar-94eb013e&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;Alarms&quot;,&quot;elementRole&quot;:&quot;bar&quot;,&quot;loc&quot;:{&quot;line&quot;:91,&quot;column&quot;:17}}"/>
              </BarChart>
            </ResponsiveContainer>
          </div>
        </Card>

        <Card className="card-pad" data-component="alarm-sources" data-qoder-id="qel-alarm-sources-39021393" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-alarm-sources-39021393&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;Alarms&quot;,&quot;elementRole&quot;:&quot;alarm-sources&quot;,&quot;loc&quot;:{&quot;line&quot;:97,&quot;column&quot;:9}}">
          <div className="mb-3" data-qoder-id="qel-mb-3-e98356b4" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-mb-3-e98356b4&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;Alarms&quot;,&quot;elementRole&quot;:&quot;mb-3&quot;,&quot;loc&quot;:{&quot;line&quot;:98,&quot;column&quot;:11}}">
            <div className="text-[15px] font-semibold tracking-tight" data-qoder-id="qel-text-15px-551485b2" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-text-15px-551485b2&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;Alarms&quot;,&quot;elementRole&quot;:&quot;text-15px&quot;,&quot;loc&quot;:{&quot;line&quot;:99,&quot;column&quot;:13}}">高频报警源</div>
            <div className="mt-0.5 text-xs text-muted-foreground" data-qoder-id="qel-mt-0-5-654a33c4" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-mt-0-5-654a33c4&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;Alarms&quot;,&quot;elementRole&quot;:&quot;mt-0-5&quot;,&quot;loc&quot;:{&quot;line&quot;:100,&quot;column&quot;:13}}">近 7 日 · 按触发次数</div>
          </div>
          <div className="space-y-3" data-qoder-id="qel-space-y-3-7653caa5" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-space-y-3-7653caa5&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;Alarms&quot;,&quot;elementRole&quot;:&quot;space-y-3&quot;,&quot;loc&quot;:{&quot;line&quot;:102,&quot;column&quot;:11}}">
            {sources.map((s) => (
              <div key={s.source} data-qoder-id="qel-div-b8fd389d" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-div-b8fd389d&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;Alarms&quot;,&quot;elementRole&quot;:&quot;div&quot;,&quot;loc&quot;:{&quot;line&quot;:104,&quot;column&quot;:15}}">
                <div className="mb-1 flex items-center justify-between text-xs" data-qoder-id="qel-mb-1-ffa15548" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-mb-1-ffa15548&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;Alarms&quot;,&quot;elementRole&quot;:&quot;mb-1&quot;,&quot;loc&quot;:{&quot;line&quot;:105,&quot;column&quot;:17}}">
                  <span className="num text-muted-foreground" data-qoder-id="qel-num-cb8c19cd" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-num-cb8c19cd&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;Alarms&quot;,&quot;elementRole&quot;:&quot;num&quot;,&quot;loc&quot;:{&quot;line&quot;:106,&quot;column&quot;:19}}">{s.source}</span>
                  <span className="num font-medium" data-qoder-id="qel-num-ca8c183a" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-num-ca8c183a&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;Alarms&quot;,&quot;elementRole&quot;:&quot;num&quot;,&quot;loc&quot;:{&quot;line&quot;:107,&quot;column&quot;:19}}">{s.count}</span>
                </div>
                <div className="h-1.5 overflow-hidden rounded-full bg-muted" data-qoder-id="qel-h-1-5-15727c90" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-h-1-5-15727c90&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;Alarms&quot;,&quot;elementRole&quot;:&quot;h-1-5&quot;,&quot;loc&quot;:{&quot;line&quot;:109,&quot;column&quot;:17}}">
                  <div className="h-full rounded-full bg-primary" style={{ width: `${(s.count / maxSource) * 100}%` }}  data-qoder-id="qel-h-full-fd98f575" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-h-full-fd98f575&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;Alarms&quot;,&quot;elementRole&quot;:&quot;h-full&quot;,&quot;loc&quot;:{&quot;line&quot;:110,&quot;column&quot;:19}}"/>
                </div>
              </div>
            ))}
          </div>
          <div className="mt-4 rounded-md bg-muted px-3 py-2 text-[11px] leading-relaxed text-muted-foreground" data-qoder-id="qel-mt-4-212803b6" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-mt-4-212803b6&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;Alarms&quot;,&quot;elementRole&quot;:&quot;mt-4&quot;,&quot;loc&quot;:{&quot;line&quot;:115,&quot;column&quot;:11}}">
            {sources[0]
              ? `${sources[0].source} 近 7 日报警 ${sources[0].count} 次，建议优先排查该设备（如蒸汽减压阀 / 真空机组 / 变送器信号）。`
              : '暂无高频报警源'}
          </div>
        </Card>
      </div>

      <Card className="mt-4" data-component="alarm-list" data-qoder-id="qel-alarm-list-94e18416" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-alarm-list-94e18416&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;Alarms&quot;,&quot;elementRole&quot;:&quot;alarm-list&quot;,&quot;loc&quot;:{&quot;line&quot;:121,&quot;column&quot;:7}}">
        <div className="px-4 pt-3" data-qoder-id="qel-px-4-af887078" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-px-4-af887078&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;Alarms&quot;,&quot;elementRole&quot;:&quot;px-4&quot;,&quot;loc&quot;:{&quot;line&quot;:122,&quot;column&quot;:9}}">
          <Tabs items={tabs} active={tab} onChange={setTab}  data-qoder-id="qel-tabs-7c749bbd" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-tabs-7c749bbd&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;Alarms&quot;,&quot;elementRole&quot;:&quot;tabs&quot;,&quot;loc&quot;:{&quot;line&quot;:123,&quot;column&quot;:11}}"/>
        </div>
        <div className="overflow-x-auto" data-qoder-id="qel-overflow-x-auto-f1960452" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-overflow-x-auto-f1960452&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;Alarms&quot;,&quot;elementRole&quot;:&quot;overflow-x-auto&quot;,&quot;loc&quot;:{&quot;line&quot;:125,&quot;column&quot;:9}}">
          <table className="w-full text-[13px]" data-qoder-id="qel-w-full-816089aa" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-w-full-816089aa&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;Alarms&quot;,&quot;elementRole&quot;:&quot;w-full&quot;,&quot;loc&quot;:{&quot;line&quot;:126,&quot;column&quot;:11}}">
            <thead data-qoder-id="qel-thead-9451f8f2" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-thead-9451f8f2&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;Alarms&quot;,&quot;elementRole&quot;:&quot;thead&quot;,&quot;loc&quot;:{&quot;line&quot;:127,&quot;column&quot;:13}}">
              <tr className="border-y border-border text-left text-xs text-muted-foreground" data-qoder-id="qel-border-y-89fac333" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-border-y-89fac333&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;Alarms&quot;,&quot;elementRole&quot;:&quot;border-y&quot;,&quot;loc&quot;:{&quot;line&quot;:128,&quot;column&quot;:15}}">
                <th className="px-4 py-2.5 font-medium" data-qoder-id="qel-px-4-8de426e3" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-px-4-8de426e3&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;Alarms&quot;,&quot;elementRole&quot;:&quot;px-4&quot;,&quot;loc&quot;:{&quot;line&quot;:129,&quot;column&quot;:17}}">时间</th>
                <th className="px-3 py-2.5 font-medium" data-qoder-id="qel-px-3-d65bd7e3" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-px-3-d65bd7e3&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;Alarms&quot;,&quot;elementRole&quot;:&quot;px-3&quot;,&quot;loc&quot;:{&quot;line&quot;:130,&quot;column&quot;:17}}">报警编号</th>
                <th className="px-3 py-2.5 font-medium" data-qoder-id="qel-px-3-e35bec5a" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-px-3-e35bec5a&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;Alarms&quot;,&quot;elementRole&quot;:&quot;px-3&quot;,&quot;loc&quot;:{&quot;line&quot;:131,&quot;column&quot;:17}}">级别</th>
                <th className="px-3 py-2.5 font-medium" data-qoder-id="qel-px-3-e45beded" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-px-3-e45beded&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;Alarms&quot;,&quot;elementRole&quot;:&quot;px-3&quot;,&quot;loc&quot;:{&quot;line&quot;:132,&quot;column&quot;:17}}">来源设备</th>
                <th className="px-3 py-2.5 font-medium" data-qoder-id="qel-px-3-5558ce39" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-px-3-5558ce39&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;Alarms&quot;,&quot;elementRole&quot;:&quot;px-3&quot;,&quot;loc&quot;:{&quot;line&quot;:133,&quot;column&quot;:17}}">报警内容</th>
                <th className="px-3 py-2.5 font-medium" data-qoder-id="qel-px-3-5458cca6" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-px-3-5458cca6&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;Alarms&quot;,&quot;elementRole&quot;:&quot;px-3&quot;,&quot;loc&quot;:{&quot;line&quot;:134,&quot;column&quot;:17}}">触发值 / 阈值</th>
                <th className="px-3 py-2.5 font-medium" data-qoder-id="qel-px-3-5358cb13" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-px-3-5358cb13&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;Alarms&quot;,&quot;elementRole&quot;:&quot;px-3&quot;,&quot;loc&quot;:{&quot;line&quot;:135,&quot;column&quot;:17}}">状态</th>
                <th className="px-4 py-2.5 font-medium" data-qoder-id="qel-px-4-98e676cb" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-px-4-98e676cb&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;Alarms&quot;,&quot;elementRole&quot;:&quot;px-4&quot;,&quot;loc&quot;:{&quot;line&quot;:136,&quot;column&quot;:17}}">操作</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border" data-qoder-id="qel-divide-y-f9ce2ae6" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-divide-y-f9ce2ae6&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;Alarms&quot;,&quot;elementRole&quot;:&quot;divide-y&quot;,&quot;loc&quot;:{&quot;line&quot;:139,&quot;column&quot;:13}}">
              {filtered.map((a) => {
                const lv = (alarmLevelMap as Record<string, { label?: string; tone?: string }>)[a.level ?? '']
                const st = STATUS_MAP[a.status] ?? { label: a.status, tone: 'muted' as BadgeTone }
                return (
                  <tr key={a.id} className="transition-colors hover:bg-muted" data-qoder-id="qel-transition-colors-cdda1528" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-transition-colors-cdda1528&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;Alarms&quot;,&quot;elementRole&quot;:&quot;transition-colors&quot;,&quot;loc&quot;:{&quot;line&quot;:144,&quot;column&quot;:19}}">
                    <td className="num px-4 py-3 text-xs text-muted-foreground" data-qoder-id="qel-num-23c2e6c9" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-num-23c2e6c9&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;Alarms&quot;,&quot;elementRole&quot;:&quot;num&quot;,&quot;loc&quot;:{&quot;line&quot;:145,&quot;column&quot;:21}}">{fmtWhen(a)}</td>
                    <td className="num px-3 py-3 text-primary" data-qoder-id="qel-num-22c2e536" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-num-22c2e536&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;Alarms&quot;,&quot;elementRole&quot;:&quot;num&quot;,&quot;loc&quot;:{&quot;line&quot;:146,&quot;column&quot;:21}}">{a.id}</td>
                    <td className="px-3 py-3" data-qoder-id="qel-px-3-cd866ab5" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-px-3-cd866ab5&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;Alarms&quot;,&quot;elementRole&quot;:&quot;px-3&quot;,&quot;loc&quot;:{&quot;line&quot;:147,&quot;column&quot;:21}}">
                      <Badge tone={(lv?.tone ?? 'muted') as BadgeTone} pulse={a.level === 'critical' && a.status === 'unacked'} data-qoder-id="qel-badge-29f1ee12" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-badge-29f1ee12&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;Alarms&quot;,&quot;elementRole&quot;:&quot;badge&quot;,&quot;loc&quot;:{&quot;line&quot;:148,&quot;column&quot;:23}}">{lv?.label ?? a.level}</Badge>
                    </td>
                    <td className="num px-3 py-3 text-muted-foreground" data-qoder-id="qel-num-9fbfd866" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-num-9fbfd866&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;Alarms&quot;,&quot;elementRole&quot;:&quot;num&quot;,&quot;loc&quot;:{&quot;line&quot;:150,&quot;column&quot;:21}}">{a.source}</td>
                    <td className="px-3 py-3" data-qoder-id="qel-px-3-d08430d7" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-px-3-d08430d7&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;Alarms&quot;,&quot;elementRole&quot;:&quot;px-3&quot;,&quot;loc&quot;:{&quot;line&quot;:151,&quot;column&quot;:21}}">{a.content}</td>
                    <td className="num px-3 py-3 text-xs text-muted-foreground" data-qoder-id="qel-num-9dbfd540" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-num-9dbfd540&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;Alarms&quot;,&quot;elementRole&quot;:&quot;num&quot;,&quot;loc&quot;:{&quot;line&quot;:152,&quot;column&quot;:21}}">
                      {a.value} <span className="text-faint" data-qoder-id="qel-text-faint-68bd257d" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-text-faint-68bd257d&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;Alarms&quot;,&quot;elementRole&quot;:&quot;text-faint&quot;,&quot;loc&quot;:{&quot;line&quot;:153,&quot;column&quot;:33}}">/ {a.threshold}</span>
                    </td>
                    <td className="px-3 py-3" data-qoder-id="qel-px-3-cb8428f8" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-px-3-cb8428f8&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;Alarms&quot;,&quot;elementRole&quot;:&quot;px-3&quot;,&quot;loc&quot;:{&quot;line&quot;:155,&quot;column&quot;:21}}">
                      <div className="flex items-center gap-1.5">
                        <Badge tone={a.status === 'unacked' && a.overdue ? 'danger' : st.tone} pulse={a.status === 'unacked' && a.overdue}>
                          {a.status === 'unacked' && a.overdue ? '超时未确认' : st.label}
                        </Badge>
                        {a.escalated && (
                          <span className="num inline-flex items-center rounded bg-danger-soft px-1.5 py-0.5 text-[10px] font-medium text-danger">已升级 · 值班长</span>
                        )}
                      </div>
                    </td>
                    <td className="px-4 py-3" data-qoder-id="qel-px-4-0e913d65" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-px-4-0e913d65&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;Alarms&quot;,&quot;elementRole&quot;:&quot;px-4&quot;,&quot;loc&quot;:{&quot;line&quot;:158,&quot;column&quot;:21}}">
                      {a.status === 'unacked' ? (
                        <Button size="sm" variant="primary" onClick={() => ack.mutate(a.id)}>确认</Button>
                      ) : a.status === 'acked' ? (
                        <Button size="sm" variant="outline" onClick={() => recover.mutate({ id: a.id, auto: false })}>恢复</Button>
                      ) : (
                        <span className="text-xs text-faint">{a.status === 'recovered' ? '已恢复' : a.ackBy ? `${a.ackBy} 已确认` : '系统自动恢复'}</span>
                      )}
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
          {filtered.length === 0 && (
            <div className="px-4 py-10 text-center text-[13px] text-muted-foreground" data-qoder-id="qel-px-4-b48cf585" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-px-4-b48cf585&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Alarms.jsx&quot;,&quot;componentName&quot;:&quot;Alarms&quot;,&quot;elementRole&quot;:&quot;px-4&quot;,&quot;loc&quot;:{&quot;line&quot;:171,&quot;column&quot;:13}}">当前筛选下没有报警记录</div>
          )}
        </div>
      </Card>

      {/* D1 · SLA 升级与抑制策略 */}
      <SuppressionPanel />
    </div>
  )
}
