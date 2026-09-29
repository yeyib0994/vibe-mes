import {
  Area,
  AreaChart,
  CartesianGrid,
  ReferenceLine,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import { useState } from 'react'
import { ChevronRight, RefreshCw, TriangleAlert } from 'lucide-react'
import { LineSwitcher, ShiftReportActions, SiteSwitcher } from '../components/dashboard-controls'
import { Badge, Button, Card, Dot, Progress } from '../components/ui'
import { PageHeader } from '../components/layout'
import { AXIS_TICK, ChartTip } from '../components/charts'
import { PLAN_RATE } from '../data/mes'
import { alarmLevelMap } from '../data/mes'
import { useCockpit } from '../api/dashboard'
import StalenessBadge from '../components/StalenessBadge'
import { cn } from '../lib/utils'

const EQUIP_DOT = { running: 'accent', alarm: 'danger', cleaning: 'warn', idle: 'muted', standby: 'primary' }
const EQUIP_TEXT = { running: 'text-accent', alarm: 'text-danger', cleaning: 'text-warn', idle: 'text-muted-foreground', standby: 'text-primary' }

function KpiCard({ label, badge, value, unit, sub, extra }) {
  return (
    <Card className="card-pad" data-component="kpi" data-qoder-id="qel-kpi-263ea19e" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-kpi-263ea19e&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;KpiCard&quot;,&quot;elementRole&quot;:&quot;kpi&quot;,&quot;loc&quot;:{&quot;line&quot;:24,&quot;column&quot;:5}}">
      <div className="flex items-center justify-between gap-2" data-qoder-id="qel-flex-d8797369" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-flex-d8797369&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;KpiCard&quot;,&quot;elementRole&quot;:&quot;flex&quot;,&quot;loc&quot;:{&quot;line&quot;:25,&quot;column&quot;:7}}">
        <span className="label-tech" data-qoder-id="qel-label-tech-2f9f400f" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-label-tech-2f9f400f&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;KpiCard&quot;,&quot;elementRole&quot;:&quot;label-tech&quot;,&quot;loc&quot;:{&quot;line&quot;:26,&quot;column&quot;:9}}">{label}</span>
        {badge}
      </div>
      <div className="mt-2.5 flex items-baseline gap-1.5" data-qoder-id="qel-mt-2-5-36904768" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-mt-2-5-36904768&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;KpiCard&quot;,&quot;elementRole&quot;:&quot;mt-2-5&quot;,&quot;loc&quot;:{&quot;line&quot;:29,&quot;column&quot;:7}}">
        <span className="text-[28px] font-semibold leading-none tracking-tight" data-qoder-id="qel-text-28px-139c49ee" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-text-28px-139c49ee&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;KpiCard&quot;,&quot;elementRole&quot;:&quot;text-28px&quot;,&quot;loc&quot;:{&quot;line&quot;:30,&quot;column&quot;:9}}">{value}</span>
        {unit && <span className="text-[13px] text-muted-foreground" data-qoder-id="qel-text-13px-962285e5" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-text-13px-962285e5&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;KpiCard&quot;,&quot;elementRole&quot;:&quot;text-13px&quot;,&quot;loc&quot;:{&quot;line&quot;:31,&quot;column&quot;:18}}">{unit}</span>}
      </div>
      {extra}
      <div className="num mt-2 text-[11px] text-faint" data-qoder-id="qel-num-5a962131" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-num-5a962131&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;KpiCard&quot;,&quot;elementRole&quot;:&quot;num&quot;,&quot;loc&quot;:{&quot;line&quot;:34,&quot;column&quot;:7}}">{sub}</div>
    </Card>
  )
}

export default function Dashboard({ onNavigate }) {
  // G4 · 厂区 + D2 · 产线双维度切换
  const [site, setSite] = useState(undefined)
  const [line, setLine] = useState(undefined)
  const { data: cockpit } = useCockpit(site, line)
  const kpis = cockpit?.kpis
  const equipment = cockpit?.equipment ?? []
  const productionTrend = cockpit?.productionTrend ?? []
  const runningBatches = cockpit?.runningBatches ?? []
  const recentAlarms = cockpit?.recentAlarms ?? []
  return (
    <div className="mx-auto max-w-[1600px] p-6" data-component="page-dashboard" data-qoder-id="qel-page-dashboard-52d9f4aa" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-page-dashboard-52d9f4aa&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;Dashboard&quot;,&quot;elementRole&quot;:&quot;page-dashboard&quot;,&quot;loc&quot;:{&quot;line&quot;:41,&quot;column&quot;:5}}">
      <PageHeader
        title="生产驾驶舱"
        desc="一车间实时生产总览 · 数据每 30 秒自动采集，最近更新 12 秒前"
        actions={
          <>
            <SiteSwitcher
              value={site}
              onChange={(s) => {
                setSite(s)
                setLine(undefined) // 切换厂区后清空产线，避免跨厂区残留
              }}
            />
            <LineSwitcher value={line} onChange={setLine} site={site} />
            <Button variant="ghost">
              <RefreshCw className="h-3.5 w-3.5" /> 刷新
            </Button>
            <ShiftReportActions line={line} site={site} />
          </>
        }
       data-qoder-id="qel-pageheader-e3a3cf44" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-pageheader-e3a3cf44&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;Dashboard&quot;,&quot;elementRole&quot;:&quot;pageheader&quot;,&quot;loc&quot;:{&quot;line&quot;:42,&quot;column&quot;:7}}"/>
      <StalenessBadge generatedAt={cockpit?.generatedAt} />

      {/* KPI 区 */}
      <div className="grid grid-cols-2 gap-4 xl:grid-cols-4" data-component="kpi-row" data-qoder-id="qel-kpi-row-b498e95c" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-kpi-row-b498e95c&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;Dashboard&quot;,&quot;elementRole&quot;:&quot;kpi-row&quot;,&quot;loc&quot;:{&quot;line&quot;:56,&quot;column&quot;:7}}">
        <KpiCard
          label="今日产量"
          badge={
            <Badge tone={kpis?.dailyOutput?.dataSufficient === false ? 'muted' : 'accent'} dot={false}>
              {kpis?.dailyOutput?.vsSchedulePct == null
                ? '暂无当日批次'
                : `超时间进度 ${kpis.dailyOutput.vsSchedulePct}%`}
            </Badge>
          }
          value={kpis?.dailyOutput?.actual ?? '—'}
          unit="t / 日计划 48 t"
          sub={
            kpis?.dailyOutput?.dataSufficient === false
              ? '当日无批次，如实显示 0（已移除示例基线）'
              : '截至 15:00 · 按批次进度折算'
          }
          extra={<Progress className="mt-3" value={kpis?.dailyOutput?.progressPct ?? 69.6} tone="primary" />}
         data-qoder-id="qel-kpicard-572efebf" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-kpicard-572efebf&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;Dashboard&quot;,&quot;elementRole&quot;:&quot;kpicard&quot;,&quot;loc&quot;:{&quot;line&quot;:57,&quot;column&quot;:9}}"/>
        <KpiCard
          label="批次合格率"
          badge={<Badge tone="accent" dot={false}>↑ 0.3 pp</Badge>}
          value={kpis?.batchPassRate?.value ?? '99.2'}
          unit="%"
          sub="近 7 日均值 98.9% · 目标 ≥ 98.5%"
         data-qoder-id="qel-kpicard-582f0052" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-kpicard-582f0052&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;Dashboard&quot;,&quot;elementRole&quot;:&quot;kpicard&quot;,&quot;loc&quot;:{&quot;line&quot;:65,&quot;column&quot;:9}}"/>
        <KpiCard
          label="综合效率 OEE"
          badge={
            <Badge tone={kpis?.oee?.dataSufficient === false ? 'warning' : 'primary'} dot={false}>
              {kpis?.oee?.dataSufficient === false ? '数据不足' : '目标 85%'}
            </Badge>
          }
          value={kpis?.oee?.value ?? '—'}
          unit={kpis?.oee?.value == null ? '' : '%'}
          sub={
            kpis?.oee?.value == null
              ? '无报工/停机原始数据，不采用兜底值（Phase I）'
              : `可用率 ${kpis?.oee?.availability} × 性能 ${kpis?.oee?.performance} × 良品 ${kpis?.oee?.quality}（按执行事实）`
          }
         data-qoder-id="qel-kpicard-592f01e5" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-kpicard-592f01e5&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;Dashboard&quot;,&quot;elementRole&quot;:&quot;kpicard&quot;,&quot;loc&quot;:{&quot;line&quot;:72,&quot;column&quot;:9}}"/>
        <KpiCard
          label="活跃报警"
          badge={<Badge tone="danger" pulse>{kpis?.activeAlarms?.critical ?? 1} 严重</Badge>}
          value={kpis?.activeAlarms?.total ?? '3'}
          unit="条待处理"
          sub={`严重 ${kpis?.activeAlarms?.critical ?? 1} · 重要 ${kpis?.activeAlarms?.major ?? 1} · 一般 ${kpis?.activeAlarms?.minor ?? 1}`}
         data-qoder-id="qel-kpicard-522ef6e0" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-kpicard-522ef6e0&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;Dashboard&quot;,&quot;elementRole&quot;:&quot;kpicard&quot;,&quot;loc&quot;:{&quot;line&quot;:79,&quot;column&quot;:9}}"/>
      </div>

      {/* 趋势 + 罐区 */}
      <div className="mt-4 grid grid-cols-1 gap-4 xl:grid-cols-3" data-qoder-id="qel-mt-4-653ac61a" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-mt-4-653ac61a&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;Dashboard&quot;,&quot;elementRole&quot;:&quot;mt-4&quot;,&quot;loc&quot;:{&quot;line&quot;:89,&quot;column&quot;:7}}">
        <Card className="card-pad xl:col-span-2" data-component="production-trend" data-qoder-id="qel-production-trend-ac4abc1f" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-production-trend-ac4abc1f&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;Dashboard&quot;,&quot;elementRole&quot;:&quot;production-trend&quot;,&quot;loc&quot;:{&quot;line&quot;:90,&quot;column&quot;:9}}">
          <div className="mb-3 flex flex-wrap items-center justify-between gap-2" data-qoder-id="qel-mb-3-0dc6ca01" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-mb-3-0dc6ca01&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;Dashboard&quot;,&quot;elementRole&quot;:&quot;mb-3&quot;,&quot;loc&quot;:{&quot;line&quot;:91,&quot;column&quot;:11}}">
            <div data-qoder-id="qel-div-0f2269a0" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-div-0f2269a0&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;Dashboard&quot;,&quot;elementRole&quot;:&quot;div&quot;,&quot;loc&quot;:{&quot;line&quot;:92,&quot;column&quot;:13}}">
              <div className="text-[15px] font-semibold tracking-tight" data-qoder-id="qel-text-15px-b084af6e" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-text-15px-b084af6e&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;Dashboard&quot;,&quot;elementRole&quot;:&quot;text-15px&quot;,&quot;loc&quot;:{&quot;line&quot;:93,&quot;column&quot;:15}}">生产趋势</div>
              <div className="mt-0.5 text-xs text-muted-foreground" data-qoder-id="qel-mt-0-5-e1741b84" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-mt-0-5-e1741b84&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;Dashboard&quot;,&quot;elementRole&quot;:&quot;mt-0-5&quot;,&quot;loc&quot;:{&quot;line&quot;:94,&quot;column&quot;:15}}">今日 00:00 – 15:00 · 产出速率（t/h）</div>
            </div>
            <div className="flex items-center gap-4 text-xs text-muted-foreground" data-qoder-id="qel-flex-2e431ca4" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-flex-2e431ca4&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;Dashboard&quot;,&quot;elementRole&quot;:&quot;flex&quot;,&quot;loc&quot;:{&quot;line&quot;:96,&quot;column&quot;:13}}">
              <span className="flex items-center gap-1.5" data-qoder-id="qel-flex-a75ef080" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-flex-a75ef080&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;Dashboard&quot;,&quot;elementRole&quot;:&quot;flex&quot;,&quot;loc&quot;:{&quot;line&quot;:97,&quot;column&quot;:15}}">
                <Dot tone="primary"  data-qoder-id="qel-dot-b33a8152" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-dot-b33a8152&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;Dashboard&quot;,&quot;elementRole&quot;:&quot;dot&quot;,&quot;loc&quot;:{&quot;line&quot;:98,&quot;column&quot;:17}}"/> 实际产出
              </span>
              <span className="num flex items-center gap-1.5" data-qoder-id="qel-num-0bc6619d" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-num-0bc6619d&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;Dashboard&quot;,&quot;elementRole&quot;:&quot;num&quot;,&quot;loc&quot;:{&quot;line&quot;:100,&quot;column&quot;:15}}">
                <span className="inline-block h-0 w-4 border-t border-dashed border-warn"  data-qoder-id="qel-inline-block-d6ff86db" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-inline-block-d6ff86db&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;Dashboard&quot;,&quot;elementRole&quot;:&quot;inline-block&quot;,&quot;loc&quot;:{&quot;line&quot;:101,&quot;column&quot;:17}}"/> 计划 {PLAN_RATE.toFixed(1)} t/h
              </span>
            </div>
          </div>
          <div className="h-[240px]" data-qoder-id="qel-h-240px-bab695e5" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-h-240px-bab695e5&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;Dashboard&quot;,&quot;elementRole&quot;:&quot;h-240px&quot;,&quot;loc&quot;:{&quot;line&quot;:105,&quot;column&quot;:11}}">
            <ResponsiveContainer width="100%" height="100%" data-qoder-id="qel-responsivecontainer-02b13776" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-responsivecontainer-02b13776&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;Dashboard&quot;,&quot;elementRole&quot;:&quot;responsivecontainer&quot;,&quot;loc&quot;:{&quot;line&quot;:106,&quot;column&quot;:13}}">
              <AreaChart data={productionTrend} margin={{ top: 8, right: 8, left: -18, bottom: 0 }} data-qoder-id="qel-areachart-0c651bab" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-areachart-0c651bab&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;Dashboard&quot;,&quot;elementRole&quot;:&quot;areachart&quot;,&quot;loc&quot;:{&quot;line&quot;:107,&quot;column&quot;:15}}">
                <defs data-qoder-id="qel-defs-a6d8cb4c" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-defs-a6d8cb4c&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;Dashboard&quot;,&quot;elementRole&quot;:&quot;defs&quot;,&quot;loc&quot;:{&quot;line&quot;:108,&quot;column&quot;:17}}">
                  <linearGradient id="outGrad" x1="0" y1="0" x2="0" y2="1" data-qoder-id="qel-outgrad-fed573bf" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-outgrad-fed573bf&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;Dashboard&quot;,&quot;elementRole&quot;:&quot;outgrad&quot;,&quot;loc&quot;:{&quot;line&quot;:109,&quot;column&quot;:19}}">
                    <stop offset="0%" style={{ stopColor: 'var(--seed-primary)' }} stopOpacity={0.28}  data-qoder-id="qel-stop-71499fc1" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-stop-71499fc1&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;Dashboard&quot;,&quot;elementRole&quot;:&quot;stop&quot;,&quot;loc&quot;:{&quot;line&quot;:110,&quot;column&quot;:21}}"/>
                    <stop offset="100%" style={{ stopColor: 'var(--seed-primary)' }} stopOpacity={0.02}  data-qoder-id="qel-stop-6e499b08" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-stop-6e499b08&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;Dashboard&quot;,&quot;elementRole&quot;:&quot;stop&quot;,&quot;loc&quot;:{&quot;line&quot;:111,&quot;column&quot;:21}}"/>
                  </linearGradient>
                </defs>
                <CartesianGrid stroke="var(--chart-grid)" vertical={false}  data-qoder-id="qel-cartesiangrid-44a916ff" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-cartesiangrid-44a916ff&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;Dashboard&quot;,&quot;elementRole&quot;:&quot;cartesiangrid&quot;,&quot;loc&quot;:{&quot;line&quot;:114,&quot;column&quot;:17}}"/>
                <XAxis dataKey="t" tick={AXIS_TICK} tickLine={false} axisLine={false} interval={2}  data-qoder-id="qel-xaxis-5c980692" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-xaxis-5c980692&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;Dashboard&quot;,&quot;elementRole&quot;:&quot;xaxis&quot;,&quot;loc&quot;:{&quot;line&quot;:115,&quot;column&quot;:17}}"/>
                <YAxis domain={[1.4, 2.6]} tick={AXIS_TICK} tickLine={false} axisLine={false} width={58}  data-qoder-id="qel-yaxis-f8eefc75" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-yaxis-f8eefc75&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;Dashboard&quot;,&quot;elementRole&quot;:&quot;yaxis&quot;,&quot;loc&quot;:{&quot;line&quot;:116,&quot;column&quot;:17}}"/>
                <Tooltip content={<ChartTip unit=" t/h" />} cursor={{ stroke: 'var(--border-strong)' }}  data-qoder-id="qel-tooltip-a021ec78" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-tooltip-a021ec78&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;Dashboard&quot;,&quot;elementRole&quot;:&quot;tooltip&quot;,&quot;loc&quot;:{&quot;line&quot;:117,&quot;column&quot;:17}}"/>
                <ReferenceLine
                  y={PLAN_RATE}
                  stroke="var(--warn)"
                  strokeDasharray="4 4"
                  label={{ value: '计划线', position: 'insideTopRight', fill: 'var(--warn)', fontSize: 10 }}
                 data-qoder-id="qel-referenceline-7dcbee4f" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-referenceline-7dcbee4f&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;Dashboard&quot;,&quot;elementRole&quot;:&quot;referenceline&quot;,&quot;loc&quot;:{&quot;line&quot;:118,&quot;column&quot;:17}}"/>
                <Area type="monotone" dataKey="out" name="实际产出" stroke="var(--seed-primary)" strokeWidth={2} fill="url(#outGrad)"  data-qoder-id="qel-area-dcd0e2ec" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-area-dcd0e2ec&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;Dashboard&quot;,&quot;elementRole&quot;:&quot;area&quot;,&quot;loc&quot;:{&quot;line&quot;:124,&quot;column&quot;:17}}"/>
              </AreaChart>
            </ResponsiveContainer>
          </div>
        </Card>

        <Card className="card-pad" data-component="tank-status" data-qoder-id="qel-tank-status-d4bd5cc6" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-tank-status-d4bd5cc6&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;Dashboard&quot;,&quot;elementRole&quot;:&quot;tank-status&quot;,&quot;loc&quot;:{&quot;line&quot;:130,&quot;column&quot;:9}}">
          <div className="mb-2 flex items-center justify-between" data-qoder-id="qel-mb-2-e9b8d3c8" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-mb-2-e9b8d3c8&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;Dashboard&quot;,&quot;elementRole&quot;:&quot;mb-2&quot;,&quot;loc&quot;:{&quot;line&quot;:131,&quot;column&quot;:11}}">
            <div className="text-[15px] font-semibold tracking-tight" data-qoder-id="qel-text-15px-3c8c4797" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-text-15px-3c8c4797&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;Dashboard&quot;,&quot;elementRole&quot;:&quot;text-15px&quot;,&quot;loc&quot;:{&quot;line&quot;:132,&quot;column&quot;:13}}">罐区与设备状态</div>
            <Badge tone="accent" dot={false} data-qoder-id="qel-badge-b1e47367" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-badge-b1e47367&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;Dashboard&quot;,&quot;elementRole&quot;:&quot;badge&quot;,&quot;loc&quot;:{&quot;line&quot;:133,&quot;column&quot;:13}}">8 台</Badge>
          </div>
          <div data-qoder-id="qel-div-922e70cc" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-div-922e70cc&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;Dashboard&quot;,&quot;elementRole&quot;:&quot;div&quot;,&quot;loc&quot;:{&quot;line&quot;:135,&quot;column&quot;:11}}">
            {equipment.map((e) => (
              <div key={e.code} className="flex items-center gap-3 border-b border-border py-2.5 last:border-0" data-qoder-id="qel-flex-af34dd2d" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-flex-af34dd2d&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;Dashboard&quot;,&quot;elementRole&quot;:&quot;flex&quot;,&quot;loc&quot;:{&quot;line&quot;:137,&quot;column&quot;:15}}">
                <Dot tone={EQUIP_DOT[e.status]} pulse={e.status === 'alarm'}  data-qoder-id="qel-dot-b12b73a2" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-dot-b12b73a2&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;Dashboard&quot;,&quot;elementRole&quot;:&quot;dot&quot;,&quot;loc&quot;:{&quot;line&quot;:138,&quot;column&quot;:17}}"/>
                <div className="min-w-0 flex-1" data-qoder-id="qel-min-w-0-17682b21" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-min-w-0-17682b21&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;Dashboard&quot;,&quot;elementRole&quot;:&quot;min-w-0&quot;,&quot;loc&quot;:{&quot;line&quot;:139,&quot;column&quot;:17}}">
                  <div className="flex items-center gap-2" data-qoder-id="qel-flex-ac34d874" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-flex-ac34d874&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;Dashboard&quot;,&quot;elementRole&quot;:&quot;flex&quot;,&quot;loc&quot;:{&quot;line&quot;:140,&quot;column&quot;:19}}">
                    <span className="num text-xs text-muted-foreground" data-qoder-id="qel-num-03d55f8f" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-num-03d55f8f&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;Dashboard&quot;,&quot;elementRole&quot;:&quot;num&quot;,&quot;loc&quot;:{&quot;line&quot;:141,&quot;column&quot;:21}}">{e.code}</span>
                    <span className="truncate text-[13px] font-medium" data-qoder-id="qel-truncate-9bc24404" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-truncate-9bc24404&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;Dashboard&quot;,&quot;elementRole&quot;:&quot;truncate&quot;,&quot;loc&quot;:{&quot;line&quot;:142,&quot;column&quot;:21}}">{e.name}</span>
                  </div>
                  <div className="num mt-0.5 truncate text-[11px] text-faint" data-qoder-id="qel-num-3568aa8d" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-num-3568aa8d&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;Dashboard&quot;,&quot;elementRole&quot;:&quot;num&quot;,&quot;loc&quot;:{&quot;line&quot;:144,&quot;column&quot;:19}}">{e.params}</div>
                </div>
                {e.status === 'alarm' && <TriangleAlert className="h-3.5 w-3.5 shrink-0 text-danger"  data-qoder-id="qel-h-3-5-9788cb5b" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-h-3-5-9788cb5b&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;Dashboard&quot;,&quot;elementRole&quot;:&quot;h-3-5&quot;,&quot;loc&quot;:{&quot;line&quot;:146,&quot;column&quot;:42}}"/>}
                <span className={cn('shrink-0 text-xs font-medium', EQUIP_TEXT[e.status])} data-qoder-id="qel-span-d390e48e" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-span-d390e48e&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;Dashboard&quot;,&quot;elementRole&quot;:&quot;span&quot;,&quot;loc&quot;:{&quot;line&quot;:147,&quot;column&quot;:17}}">{e.statusText}</span>
              </div>
            ))}
          </div>
        </Card>
      </div>

      {/* 在制批次 + 最新报警 */}
      <div className="mt-4 grid grid-cols-1 gap-4 xl:grid-cols-3" data-qoder-id="qel-mt-4-d74473ec" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-mt-4-d74473ec&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;Dashboard&quot;,&quot;elementRole&quot;:&quot;mt-4&quot;,&quot;loc&quot;:{&quot;line&quot;:155,&quot;column&quot;:7}}">
        <Card className="card-pad xl:col-span-2" data-component="running-batches" data-qoder-id="qel-running-batches-621d43ba" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-running-batches-621d43ba&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;Dashboard&quot;,&quot;elementRole&quot;:&quot;running-batches&quot;,&quot;loc&quot;:{&quot;line&quot;:156,&quot;column&quot;:9}}">
          <div className="mb-2 flex items-center justify-between" data-qoder-id="qel-mb-2-f2bb208a" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-mb-2-f2bb208a&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;Dashboard&quot;,&quot;elementRole&quot;:&quot;mb-2&quot;,&quot;loc&quot;:{&quot;line&quot;:157,&quot;column&quot;:11}}">
            <div className="text-[15px] font-semibold tracking-tight" data-qoder-id="qel-text-15px-3b8e849b" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-text-15px-3b8e849b&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;Dashboard&quot;,&quot;elementRole&quot;:&quot;text-15px&quot;,&quot;loc&quot;:{&quot;line&quot;:158,&quot;column&quot;:13}}">在制批次</div>
            <Button variant="ghost" size="sm" onClick={() => onNavigate('batches')} data-qoder-id="qel-button-2ffee211" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-button-2ffee211&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;Dashboard&quot;,&quot;elementRole&quot;:&quot;button&quot;,&quot;loc&quot;:{&quot;line&quot;:159,&quot;column&quot;:13}}">
              全部批次 <ChevronRight className="h-3.5 w-3.5"  data-qoder-id="qel-h-3-5-fd73f535" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-h-3-5-fd73f535&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;Dashboard&quot;,&quot;elementRole&quot;:&quot;h-3-5&quot;,&quot;loc&quot;:{&quot;line&quot;:160,&quot;column&quot;:20}}"/>
            </Button>
          </div>
          <div data-qoder-id="qel-div-982c3ba7" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-div-982c3ba7&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;Dashboard&quot;,&quot;elementRole&quot;:&quot;div&quot;,&quot;loc&quot;:{&quot;line&quot;:163,&quot;column&quot;:11}}">
            {runningBatches.map((b) => (
              <div key={b.id} className="border-b border-border py-3 last:border-0" data-qoder-id="qel-border-b-d97bb33d" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-border-b-d97bb33d&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;Dashboard&quot;,&quot;elementRole&quot;:&quot;border-b&quot;,&quot;loc&quot;:{&quot;line&quot;:165,&quot;column&quot;:15}}">
                <div className="flex items-center gap-3" data-qoder-id="qel-flex-96393300" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-flex-96393300&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;Dashboard&quot;,&quot;elementRole&quot;:&quot;flex&quot;,&quot;loc&quot;:{&quot;line&quot;:166,&quot;column&quot;:17}}">
                  <span className="num text-[13px] font-medium text-primary" data-qoder-id="qel-num-fdd0d8ef" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-num-fdd0d8ef&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;Dashboard&quot;,&quot;elementRole&quot;:&quot;num&quot;,&quot;loc&quot;:{&quot;line&quot;:167,&quot;column&quot;:19}}">{b.id}</span>
                  <span className="truncate text-[13px]" data-qoder-id="qel-truncate-25bd0d14" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-truncate-25bd0d14&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;Dashboard&quot;,&quot;elementRole&quot;:&quot;truncate&quot;,&quot;loc&quot;:{&quot;line&quot;:168,&quot;column&quot;:19}}">{b.product}</span>
                  <Badge tone={b.tone} dot={false} className="ml-auto" data-qoder-id="qel-ml-auto-3095b133" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-ml-auto-3095b133&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;Dashboard&quot;,&quot;elementRole&quot;:&quot;ml-auto&quot;,&quot;loc&quot;:{&quot;line&quot;:169,&quot;column&quot;:19}}">{b.stage}</Badge>
                  <span className="num w-24 text-right text-xs text-muted-foreground" data-qoder-id="qel-num-fad0d436" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-num-fad0d436&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;Dashboard&quot;,&quot;elementRole&quot;:&quot;num&quot;,&quot;loc&quot;:{&quot;line&quot;:170,&quot;column&quot;:19}}">{b.equipment}</span>
                  <span className="num w-10 text-right text-[13px] font-medium" data-qoder-id="qel-num-f9d0d2a3" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-num-f9d0d2a3&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;Dashboard&quot;,&quot;elementRole&quot;:&quot;num&quot;,&quot;loc&quot;:{&quot;line&quot;:171,&quot;column&quot;:19}}">{b.progress}%</span>
                </div>
                <div className="mt-2 flex items-center gap-3" data-qoder-id="qel-mt-2-e64a6295" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-mt-2-e64a6295&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;Dashboard&quot;,&quot;elementRole&quot;:&quot;mt-2&quot;,&quot;loc&quot;:{&quot;line&quot;:173,&quot;column&quot;:17}}">
                  <Progress value={b.progress} tone={b.tone} className="flex-1"  data-qoder-id="qel-flex-1-3ac80767" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-flex-1-3ac80767&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;Dashboard&quot;,&quot;elementRole&quot;:&quot;flex-1&quot;,&quot;loc&quot;:{&quot;line&quot;:174,&quot;column&quot;:19}}"/>
                  <span className="num hidden w-44 text-right text-[11px] text-faint md:block" data-qoder-id="qel-num-06d0e71a" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-num-06d0e71a&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;Dashboard&quot;,&quot;elementRole&quot;:&quot;num&quot;,&quot;loc&quot;:{&quot;line&quot;:175,&quot;column&quot;:19}}">{b.eta}</span>
                </div>
              </div>
            ))}
          </div>
        </Card>

        <Card className="card-pad" data-component="recent-alarms" data-qoder-id="qel-recent-alarms-1fb6eada" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-recent-alarms-1fb6eada&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;Dashboard&quot;,&quot;elementRole&quot;:&quot;recent-alarms&quot;,&quot;loc&quot;:{&quot;line&quot;:182,&quot;column&quot;:9}}">
          <div className="mb-2 flex items-center justify-between" data-qoder-id="qel-mb-2-5ac04170" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-mb-2-5ac04170&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;Dashboard&quot;,&quot;elementRole&quot;:&quot;mb-2&quot;,&quot;loc&quot;:{&quot;line&quot;:183,&quot;column&quot;:11}}">
            <div className="text-[15px] font-semibold tracking-tight" data-qoder-id="qel-text-15px-af93b865" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-text-15px-af93b865&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;Dashboard&quot;,&quot;elementRole&quot;:&quot;text-15px&quot;,&quot;loc&quot;:{&quot;line&quot;:184,&quot;column&quot;:13}}">最新报警</div>
            <Button variant="ghost" size="sm" onClick={() => onNavigate('alarms')} data-qoder-id="qel-button-9c040943" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-button-9c040943&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;Dashboard&quot;,&quot;elementRole&quot;:&quot;button&quot;,&quot;loc&quot;:{&quot;line&quot;:185,&quot;column&quot;:13}}">
              报警中心 <ChevronRight className="h-3.5 w-3.5"  data-qoder-id="qel-h-3-5-6d7922b3" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-h-3-5-6d7922b3&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;Dashboard&quot;,&quot;elementRole&quot;:&quot;h-3-5&quot;,&quot;loc&quot;:{&quot;line&quot;:186,&quot;column&quot;:20}}"/>
            </Button>
          </div>
          <div data-qoder-id="qel-div-20318eed" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-div-20318eed&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;Dashboard&quot;,&quot;elementRole&quot;:&quot;div&quot;,&quot;loc&quot;:{&quot;line&quot;:189,&quot;column&quot;:11}}">
            {recentAlarms.map((a) => (
              <div key={a.id} className="border-b border-border py-2.5 last:border-0" data-qoder-id="qel-border-b-d179680e" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-border-b-d179680e&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;Dashboard&quot;,&quot;elementRole&quot;:&quot;border-b&quot;,&quot;loc&quot;:{&quot;line&quot;:191,&quot;column&quot;:15}}">
                <div className="flex items-center gap-2" data-qoder-id="qel-flex-ac37170b" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-flex-ac37170b&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;Dashboard&quot;,&quot;elementRole&quot;:&quot;flex&quot;,&quot;loc&quot;:{&quot;line&quot;:192,&quot;column&quot;:17}}">
                  <Dot tone={alarmLevelMap[a.level].tone} pulse={a.status === 'unacked'}  data-qoder-id="qel-dot-bc2dc38a" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-dot-bc2dc38a&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;Dashboard&quot;,&quot;elementRole&quot;:&quot;dot&quot;,&quot;loc&quot;:{&quot;line&quot;:193,&quot;column&quot;:19}}"/>
                  <span className="truncate text-[13px] font-medium" data-qoder-id="qel-truncate-1bbabebf" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-truncate-1bbabebf&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;Dashboard&quot;,&quot;elementRole&quot;:&quot;truncate&quot;,&quot;loc&quot;:{&quot;line&quot;:194,&quot;column&quot;:19}}">{a.content}</span>
                  <span className="num ml-auto shrink-0 text-[11px] text-faint" data-qoder-id="qel-num-83b813fb" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-num-83b813fb&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;Dashboard&quot;,&quot;elementRole&quot;:&quot;num&quot;,&quot;loc&quot;:{&quot;line&quot;:195,&quot;column&quot;:19}}">{a.time}</span>
                </div>
                <div className="num mt-1 pl-4 text-[11px] text-muted-foreground" data-qoder-id="qel-num-a8701b5b" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-num-a8701b5b&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Dashboard.jsx&quot;,&quot;componentName&quot;:&quot;Dashboard&quot;,&quot;elementRole&quot;:&quot;num&quot;,&quot;loc&quot;:{&quot;line&quot;:197,&quot;column&quot;:17}}">
                  {a.source} · {a.value}（阈值 {a.threshold}）
                </div>
              </div>
            ))}
          </div>
        </Card>
      </div>
    </div>
  )
}
