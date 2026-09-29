import type { ReactNode } from 'react'
import {
  Bar,
  CartesianGrid,
  ComposedChart,
  Line,
  LineChart,
  ReferenceLine,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import { ClipboardCheck } from 'lucide-react'
import { Badge, Card, type BadgeTone } from '../components/ui'
import { PageHeader } from '../components/layout'
import QualityPanel from '../components/quality-panel'
import { AXIS_TICK, ChartTip } from '../components/charts'
import { SPC_CL, SPC_LCL, SPC_UCL, qcStatusMap } from '../data/mes'
import { useQuality, type QualityItem } from '../api/quality'
import StalenessBadge from '../components/StalenessBadge'

/** recharts 自定义点渲染器 props（仅用到的字段）。 */
type SpcDotProps = {
  cx?: number | null
  cy?: number | null
  index?: number
  payload?: { ooc?: boolean; [key: string]: unknown }
  className?: string
  style?: React.CSSProperties
  [key: string]: unknown
}

function SpcDot(props: SpcDotProps) {
  const { cx, cy, payload = {}, index } = props
  if (cx == null || cy == null) return <g key={`d-${index}`}  style={props?.style} className={props?.className} data-qoder-id={props?.["data-qoder-id"]} data-qoder-source={props?.["data-qoder-source"]}/>
  return (
    <circle
      key={`d-${index}`}
      cx={cx}
      cy={cy}
      r={payload.ooc ? 4 : 2.5}
      fill={payload.ooc ? 'var(--danger)' : 'var(--seed-primary)'}
      stroke={payload.ooc ? 'var(--background)' : 'none'}
      strokeWidth={payload.ooc ? 2 : 0}
     data-qoder-id="qel-circle-8cb3d08f" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-circle-8cb3d08f&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;SpcDot&quot;,&quot;elementRole&quot;:&quot;circle&quot;,&quot;loc&quot;:{&quot;line&quot;:23,&quot;column&quot;:5}}"/>
  )
}

function MiniStat({
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
    <Card className="card-pad" data-component="qc-kpi" data-qoder-id="qel-qc-kpi-66862c2f" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-qc-kpi-66862c2f&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;MiniStat&quot;,&quot;elementRole&quot;:&quot;qc-kpi&quot;,&quot;loc&quot;:{&quot;line&quot;:37,&quot;column&quot;:5}}">
      <div className="flex items-center justify-between" data-qoder-id="qel-flex-fe345b7f" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-flex-fe345b7f&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;MiniStat&quot;,&quot;elementRole&quot;:&quot;flex&quot;,&quot;loc&quot;:{&quot;line&quot;:38,&quot;column&quot;:7}}">
        <span className="label-tech" data-qoder-id="qel-label-tech-e8f77959" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-label-tech-e8f77959&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;MiniStat&quot;,&quot;elementRole&quot;:&quot;label-tech&quot;,&quot;loc&quot;:{&quot;line&quot;:39,&quot;column&quot;:9}}">{label}</span>
        {badge}
      </div>
      <div className="mt-2.5 flex items-baseline gap-1.5" data-qoder-id="qel-mt-2-5-d8143dbe" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-mt-2-5-d8143dbe&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;MiniStat&quot;,&quot;elementRole&quot;:&quot;mt-2-5&quot;,&quot;loc&quot;:{&quot;line&quot;:42,&quot;column&quot;:7}}">
        <span className="text-[26px] font-semibold leading-none tracking-tight" data-qoder-id="qel-text-26px-4df4a9d2" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-text-26px-4df4a9d2&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;MiniStat&quot;,&quot;elementRole&quot;:&quot;text-26px&quot;,&quot;loc&quot;:{&quot;line&quot;:43,&quot;column&quot;:9}}">{value}</span>
        {unit && <span className="text-[13px] text-muted-foreground" data-qoder-id="qel-text-13px-f91ac8db" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-text-13px-f91ac8db&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;MiniStat&quot;,&quot;elementRole&quot;:&quot;text-13px&quot;,&quot;loc&quot;:{&quot;line&quot;:44,&quot;column&quot;:18}}">{unit}</span>}
      </div>
    </Card>
  )
}

export default function Quality() {
  const { data: quality } = useQuality()
  const spcData = (quality?.spcData ?? []) as QualityItem[]
  const paretoRaw = (quality?.pareto ?? []) as QualityItem[]
  const pareto = paretoRaw.map((p) => ({
    name: String(p.name ?? ''),
    n: Number(p.n ?? 0),
    cum: Number(p.cum ?? 0),
  }))
  const qcTasks = (quality?.qcTasks ?? []) as QualityItem[]
  const paretoTotal = pareto.reduce((s, d) => s + d.n, 0)
  return (
    <div className="mx-auto max-w-[1600px] p-6" data-component="page-quality" data-qoder-id="qel-page-quality-c4c9ce38" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-page-quality-c4c9ce38&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;page-quality&quot;,&quot;loc&quot;:{&quot;line&quot;:52,&quot;column&quot;:5}}">
      <PageHeader
        title="质量管理"
        desc="过程检验与成品放行 · SPC 在线监控 · 检验依据 GB 1886.25—2016"
        actions={<ClipboardCheck className="h-4 w-4 text-faint" />}
       data-qoder-id="qel-pageheader-d0df40e8" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-pageheader-d0df40e8&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;pageheader&quot;,&quot;loc&quot;:{&quot;line&quot;:53,&quot;column&quot;:7}}"/>
      <StalenessBadge generatedAt={quality?.generatedAt} />

      <div className="grid grid-cols-2 gap-4 xl:grid-cols-4" data-qoder-id="qel-grid-e614dbe2" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-grid-e614dbe2&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;grid&quot;,&quot;loc&quot;:{&quot;line&quot;:59,&quot;column&quot;:7}}">
        <MiniStat label="成品一次合格率" value="99.2" unit="%" badge={<Badge tone="accent" dot={false}>↑ 0.3 pp</Badge>}  data-qoder-id="qel-ministat-c182a8cc" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-ministat-c182a8cc&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;ministat&quot;,&quot;loc&quot;:{&quot;line&quot;:60,&quot;column&quot;:9}}"/>
        <MiniStat label="今日检验完成" value="12" unit="批 / 计划 15"  data-qoder-id="qel-ministat-c282aa5f" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-ministat-c282aa5f&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;ministat&quot;,&quot;loc&quot;:{&quot;line&quot;:61,&quot;column&quot;:9}}"/>
        <MiniStat label="SPC 失控" value="1" unit="次" badge={<Badge tone="danger">10:30 超上限</Badge>}  data-qoder-id="qel-ministat-bf82a5a6" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-ministat-bf82a5a6&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;ministat&quot;,&quot;loc&quot;:{&quot;line&quot;:62,&quot;column&quot;:9}}"/>
        <MiniStat label="本月客户投诉" value="0" unit="起" badge={<Badge tone="accent" dot={false}>连续 3 月</Badge>}  data-qoder-id="qel-ministat-c082a739" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-ministat-c082a739&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;ministat&quot;,&quot;loc&quot;:{&quot;line&quot;:63,&quot;column&quot;:9}}"/>
      </div>

      <div className="mt-4 grid grid-cols-1 gap-4 xl:grid-cols-3" data-qoder-id="qel-mt-4-b092b35b" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-mt-4-b092b35b&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;mt-4&quot;,&quot;loc&quot;:{&quot;line&quot;:66,&quot;column&quot;:7}}">
        {/* SPC 控制图 */}
        <Card className="card-pad xl:col-span-2" data-component="spc-chart" data-qoder-id="qel-spc-chart-fef8d7ea" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-spc-chart-fef8d7ea&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;spc-chart&quot;,&quot;loc&quot;:{&quot;line&quot;:68,&quot;column&quot;:9}}">
          <div className="mb-3 flex flex-wrap items-center justify-between gap-2" data-qoder-id="qel-mb-3-6b90e338" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-mb-3-6b90e338&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;mb-3&quot;,&quot;loc&quot;:{&quot;line&quot;:69,&quot;column&quot;:11}}">
            <div data-qoder-id="qel-div-27696d17" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-div-27696d17&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;div&quot;,&quot;loc&quot;:{&quot;line&quot;:70,&quot;column&quot;:13}}">
              <div className="text-[15px] font-semibold tracking-tight" data-qoder-id="qel-text-15px-c05910ce" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-text-15px-c05910ce&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;text-15px&quot;,&quot;loc&quot;:{&quot;line&quot;:71,&quot;column&quot;:15}}">SPC 控制图 · 成品柠檬酸含量</div>
              <div className="mt-0.5 text-xs text-muted-foreground" data-qoder-id="qel-mt-0-5-4aa422cb" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-mt-0-5-4aa422cb&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;mt-0-5&quot;,&quot;loc&quot;:{&quot;line&quot;:72,&quot;column&quot;:15}}">批次 B-260826-007 · 滴定法 · 每 30 分钟抽样（%）</div>
            </div>
            <div className="flex items-center gap-3 text-xs text-muted-foreground" data-qoder-id="qel-flex-6a33cde1" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-flex-6a33cde1&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;flex&quot;,&quot;loc&quot;:{&quot;line&quot;:74,&quot;column&quot;:13}}">
              <span className="num" data-qoder-id="qel-num-67ae6124" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-num-67ae6124&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;num&quot;,&quot;loc&quot;:{&quot;line&quot;:75,&quot;column&quot;:15}}">UCL {SPC_UCL.toFixed(2)}</span>
              <span className="num" data-qoder-id="qel-num-66ae5f91" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-num-66ae5f91&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;num&quot;,&quot;loc&quot;:{&quot;line&quot;:76,&quot;column&quot;:15}}">CL {SPC_CL.toFixed(2)}</span>
              <span className="num" data-qoder-id="qel-num-65ae5dfe" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-num-65ae5dfe&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;num&quot;,&quot;loc&quot;:{&quot;line&quot;:77,&quot;column&quot;:15}}">LCL {SPC_LCL.toFixed(2)}</span>
            </div>
          </div>
          <div className="h-[260px]" data-qoder-id="qel-h-260px-22d345cb" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-h-260px-22d345cb&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;h-260px&quot;,&quot;loc&quot;:{&quot;line&quot;:80,&quot;column&quot;:11}}">
            <ResponsiveContainer width="100%" height="100%" data-qoder-id="qel-responsivecontainer-50e09462" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-responsivecontainer-50e09462&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;responsivecontainer&quot;,&quot;loc&quot;:{&quot;line&quot;:81,&quot;column&quot;:13}}">
              <LineChart data={spcData} margin={{ top: 8, right: 8, left: -14, bottom: 0 }} data-qoder-id="qel-linechart-c169100b" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-linechart-c169100b&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;linechart&quot;,&quot;loc&quot;:{&quot;line&quot;:82,&quot;column&quot;:15}}">
                <CartesianGrid stroke="var(--chart-grid)" vertical={false}  data-qoder-id="qel-cartesiangrid-fbd37dc4" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-cartesiangrid-fbd37dc4&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;cartesiangrid&quot;,&quot;loc&quot;:{&quot;line&quot;:83,&quot;column&quot;:17}}"/>
                <XAxis dataKey="t" tick={AXIS_TICK} tickLine={false} axisLine={false} interval={1}  data-qoder-id="qel-xaxis-f67d076a" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-xaxis-f67d076a&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;xaxis&quot;,&quot;loc&quot;:{&quot;line&quot;:84,&quot;column&quot;:17}}"/>
                <YAxis domain={[99.0, 100.0]} tick={AXIS_TICK} tickLine={false} axisLine={false} width={56}  data-qoder-id="qel-yaxis-7bb8b6fd" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-yaxis-7bb8b6fd&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;yaxis&quot;,&quot;loc&quot;:{&quot;line&quot;:85,&quot;column&quot;:17}}"/>
                <Tooltip content={<ChartTip unit=" %" />} cursor={{ stroke: 'var(--border-strong)' }}  data-qoder-id="qel-tooltip-19b4b188" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-tooltip-19b4b188&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;tooltip&quot;,&quot;loc&quot;:{&quot;line&quot;:86,&quot;column&quot;:17}}"/>
                <ReferenceLine y={SPC_UCL} stroke="var(--warn)" strokeDasharray="4 4"  data-qoder-id="qel-referenceline-433267e7" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-referenceline-433267e7&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;referenceline&quot;,&quot;loc&quot;:{&quot;line&quot;:87,&quot;column&quot;:17}}"/>
                <ReferenceLine y={SPC_CL} stroke="var(--faint)" strokeDasharray="2 4"  data-qoder-id="qel-referenceline-4032632e" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-referenceline-4032632e&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;referenceline&quot;,&quot;loc&quot;:{&quot;line&quot;:88,&quot;column&quot;:17}}"/>
                <ReferenceLine y={SPC_LCL} stroke="var(--warn)" strokeDasharray="4 4"  data-qoder-id="qel-referenceline-413264c1" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-referenceline-413264c1&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;referenceline&quot;,&quot;loc&quot;:{&quot;line&quot;:89,&quot;column&quot;:17}}"/>
                <Line
                  type="monotone"
                  dataKey="v"
                  name="柠檬酸含量"
                  stroke="var(--seed-primary)"
                  strokeWidth={1.5}
                  dot={<SpcDot />}
                  isAnimationActive={false}
                 data-qoder-id="qel-line-bf4a1f58" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-line-bf4a1f58&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;line&quot;,&quot;loc&quot;:{&quot;line&quot;:90,&quot;column&quot;:17}}"/>
              </LineChart>
            </ResponsiveContainer>
          </div>
          <div className="mt-2 flex items-center gap-2 rounded-md bg-danger-soft px-3 py-2 text-xs text-danger" data-qoder-id="qel-mt-2-a91ec4f4" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-mt-2-a91ec4f4&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;mt-2&quot;,&quot;loc&quot;:{&quot;line&quot;:102,&quot;column&quot;:11}}">
            <span className="h-1.5 w-1.5 shrink-0 rounded-full bg-danger"  data-qoder-id="qel-h-1-5-5d639634" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-h-1-5-5d639634&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;h-1-5&quot;,&quot;loc&quot;:{&quot;line&quot;:103,&quot;column&quot;:13}}"/>
            10:30 样本超出上限（99.92% &gt; UCL 99.82%），已触发偏差 DEV-260826-002，工艺组调查中。
          </div>
        </Card>

        {/* 帕累托图 */}
        <Card className="card-pad" data-component="pareto-chart" data-qoder-id="qel-pareto-chart-4c800d19" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-pareto-chart-4c800d19&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;pareto-chart&quot;,&quot;loc&quot;:{&quot;line&quot;:109,&quot;column&quot;:9}}">
          <div className="mb-3" data-qoder-id="qel-mb-3-019d084d" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-mb-3-019d084d&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;mb-3&quot;,&quot;loc&quot;:{&quot;line&quot;:110,&quot;column&quot;:11}}">
            <div className="text-[15px] font-semibold tracking-tight" data-qoder-id="qel-text-15px-bd4a018b" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-text-15px-bd4a018b&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;text-15px&quot;,&quot;loc&quot;:{&quot;line&quot;:111,&quot;column&quot;:13}}">不合格项帕累托</div>
            <div className="mt-0.5 text-xs text-muted-foreground" data-qoder-id="qel-mt-0-5-4bb32ee8" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-mt-0-5-4bb32ee8&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;mt-0-5&quot;,&quot;loc&quot;:{&quot;line&quot;:112,&quot;column&quot;:13}}">近 30 日 · 共 {paretoTotal} 项次</div>
          </div>
          <div className="h-[260px]" data-qoder-id="qel-h-260px-9dcdf73e" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-h-260px-9dcdf73e&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;h-260px&quot;,&quot;loc&quot;:{&quot;line&quot;:114,&quot;column&quot;:11}}">
            <ResponsiveContainer width="100%" height="100%" data-qoder-id="qel-responsivecontainer-d3e5dfc9" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-responsivecontainer-d3e5dfc9&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;responsivecontainer&quot;,&quot;loc&quot;:{&quot;line&quot;:115,&quot;column&quot;:13}}">
              <ComposedChart data={pareto} margin={{ top: 8, right: 0, left: -22, bottom: 0 }} data-qoder-id="qel-composedchart-baa9eae6" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-composedchart-baa9eae6&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;composedchart&quot;,&quot;loc&quot;:{&quot;line&quot;:116,&quot;column&quot;:15}}">
                <CartesianGrid stroke="var(--chart-grid)" vertical={false}  data-qoder-id="qel-cartesiangrid-f2c4650f" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-cartesiangrid-f2c4650f&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;cartesiangrid&quot;,&quot;loc&quot;:{&quot;line&quot;:117,&quot;column&quot;:17}}"/>
                <XAxis dataKey="name" tick={{ ...AXIS_TICK, fontSize: 10 }} tickLine={false} axisLine={false} interval={0}  data-qoder-id="qel-xaxis-61846ba0" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-xaxis-61846ba0&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;xaxis&quot;,&quot;loc&quot;:{&quot;line&quot;:118,&quot;column&quot;:17}}"/>
                <YAxis yAxisId="left" tick={AXIS_TICK} tickLine={false} axisLine={false} allowDecimals={false}  data-qoder-id="qel-yaxis-f4c0313d" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-yaxis-f4c0313d&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;yaxis&quot;,&quot;loc&quot;:{&quot;line&quot;:119,&quot;column&quot;:17}}"/>
                <YAxis yAxisId="right" orientation="right" domain={[0, 100]} hide  data-qoder-id="qel-yaxis-f3c02faa" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-yaxis-f3c02faa&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;yaxis&quot;,&quot;loc&quot;:{&quot;line&quot;:120,&quot;column&quot;:17}}"/>
                <Tooltip content={<ChartTip />} cursor={{ fill: 'var(--muted)' }}  data-qoder-id="qel-tooltip-17a5a3d8" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-tooltip-17a5a3d8&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;tooltip&quot;,&quot;loc&quot;:{&quot;line&quot;:121,&quot;column&quot;:17}}"/>
                <Bar yAxisId="left" dataKey="n" name="项次" fill="var(--seed-primary)" radius={[3, 3, 0, 0]} barSize={22}  data-qoder-id="qel-bar-6693c997" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-bar-6693c997&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;bar&quot;,&quot;loc&quot;:{&quot;line&quot;:122,&quot;column&quot;:17}}"/>
                <Line
                  yAxisId="right"
                  type="monotone"
                  dataKey="cum"
                  name="累计占比"
                  stroke="var(--warn)"
                  strokeWidth={1.5}
                  dot={{ r: 2, fill: 'var(--warn)', strokeWidth: 0 }}
                  isAnimationActive={false}
                 data-qoder-id="qel-line-334f5322" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-line-334f5322&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;line&quot;,&quot;loc&quot;:{&quot;line&quot;:123,&quot;column&quot;:17}}"/>
              </ComposedChart>
            </ResponsiveContainer>
          </div>
          <div className="num mt-2 text-[11px] text-faint" data-qoder-id="qel-num-6e00c048" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-num-6e00c048&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;num&quot;,&quot;loc&quot;:{&quot;line&quot;:136,&quot;column&quot;:11}}">前三项（色度 / 水分 / pH）占 81.1%，已列入本月质量改进专项。</div>
        </Card>
      </div>

      {/* 检验任务 */}
      <Card className="mt-4" data-component="qc-tasks" data-qoder-id="qel-qc-tasks-f8af36fb" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-qc-tasks-f8af36fb&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;qc-tasks&quot;,&quot;loc&quot;:{&quot;line&quot;:141,&quot;column&quot;:7}}">
        <div className="flex items-center justify-between px-4 pb-2 pt-4" data-qoder-id="qel-flex-6b360e0b" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-flex-6b360e0b&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;flex&quot;,&quot;loc&quot;:{&quot;line&quot;:142,&quot;column&quot;:9}}">
          <div className="text-[15px] font-semibold tracking-tight" data-qoder-id="qel-text-15px-c44c4b27" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-text-15px-c44c4b27&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;text-15px&quot;,&quot;loc&quot;:{&quot;line&quot;:143,&quot;column&quot;:11}}">检验任务</div>
          <span className="text-xs text-muted-foreground" data-qoder-id="qel-text-xs-c2440e8a" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-text-xs-c2440e8a&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;text-xs&quot;,&quot;loc&quot;:{&quot;line&quot;:144,&quot;column&quot;:11}}">今日 5 项 · 1 项待复核</span>
        </div>
        <div className="overflow-x-auto" data-qoder-id="qel-overflow-x-auto-a7124866" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-overflow-x-auto-a7124866&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;overflow-x-auto&quot;,&quot;loc&quot;:{&quot;line&quot;:146,&quot;column&quot;:9}}">
          <table className="w-full text-[13px]" data-qoder-id="qel-w-full-b3f94cae" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-w-full-b3f94cae&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;w-full&quot;,&quot;loc&quot;:{&quot;line&quot;:147,&quot;column&quot;:11}}">
            <thead data-qoder-id="qel-thead-fbd54073" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-thead-fbd54073&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;thead&quot;,&quot;loc&quot;:{&quot;line&quot;:148,&quot;column&quot;:13}}">
              <tr className="border-y border-border text-left text-xs text-muted-foreground" data-qoder-id="qel-border-y-1e8bd08e" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-border-y-1e8bd08e&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;border-y&quot;,&quot;loc&quot;:{&quot;line&quot;:149,&quot;column&quot;:15}}">
                <th className="px-4 py-2.5 font-medium" data-qoder-id="qel-px-4-f12c16da" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-px-4-f12c16da&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;px-4&quot;,&quot;loc&quot;:{&quot;line&quot;:150,&quot;column&quot;:17}}">任务号</th>
                <th className="px-3 py-2.5 font-medium" data-qoder-id="qel-px-3-8de705ee" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-px-3-8de705ee&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;px-3&quot;,&quot;loc&quot;:{&quot;line&quot;:151,&quot;column&quot;:17}}">类型</th>
                <th className="px-3 py-2.5 font-medium" data-qoder-id="qel-px-3-90e70aa7" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-px-3-90e70aa7&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;px-3&quot;,&quot;loc&quot;:{&quot;line&quot;:152,&quot;column&quot;:17}}">关联批次</th>
                <th className="px-3 py-2.5 font-medium" data-qoder-id="qel-px-3-8fe70914" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-px-3-8fe70914&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;px-3&quot;,&quot;loc&quot;:{&quot;line&quot;:153,&quot;column&quot;:17}}">检验项目</th>
                <th className="px-3 py-2.5 font-medium" data-qoder-id="qel-px-3-92e70dcd" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-px-3-92e70dcd&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;px-3&quot;,&quot;loc&quot;:{&quot;line&quot;:154,&quot;column&quot;:17}}">样本</th>
                <th className="px-3 py-2.5 font-medium" data-qoder-id="qel-px-3-91e70c3a" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-px-3-91e70c3a&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;px-3&quot;,&quot;loc&quot;:{&quot;line&quot;:155,&quot;column&quot;:17}}">合格</th>
                <th className="px-3 py-2.5 font-medium" data-qoder-id="qel-px-3-84e6f7c3" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-px-3-84e6f7c3&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;px-3&quot;,&quot;loc&quot;:{&quot;line&quot;:156,&quot;column&quot;:17}}">合格率</th>
                <th className="px-3 py-2.5 font-medium" data-qoder-id="qel-px-3-83e6f630" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-px-3-83e6f630&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;px-3&quot;,&quot;loc&quot;:{&quot;line&quot;:157,&quot;column&quot;:17}}">检验员</th>
                <th className="px-4 py-2.5 font-medium" data-qoder-id="qel-px-4-f52e5bbd" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-px-4-f52e5bbd&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;px-4&quot;,&quot;loc&quot;:{&quot;line&quot;:158,&quot;column&quot;:17}}">状态</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border" data-qoder-id="qel-divide-y-a2b2858e" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-divide-y-a2b2858e&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;divide-y&quot;,&quot;loc&quot;:{&quot;line&quot;:161,&quot;column&quot;:13}}">
              {qcTasks.map((t) => {
                const st = qcStatusMap[t.status]
                const rate = Math.round((t.pass / t.sample) * 1000) / 10
                return (
                  <tr key={t.id} className="transition-colors hover:bg-muted" data-qoder-id="qel-transition-colors-f1d35dea" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-transition-colors-f1d35dea&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;transition-colors&quot;,&quot;loc&quot;:{&quot;line&quot;:166,&quot;column&quot;:19}}">
                    <td className="num px-4 py-3 font-medium text-primary" data-qoder-id="qel-num-499bd6dd" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-num-499bd6dd&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;num&quot;,&quot;loc&quot;:{&quot;line&quot;:167,&quot;column&quot;:21}}">{t.id}</td>
                    <td className="px-3 py-3" data-qoder-id="qel-px-3-c9443d8a" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-px-3-c9443d8a&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;px-3&quot;,&quot;loc&quot;:{&quot;line&quot;:168,&quot;column&quot;:21}}">{t.type}</td>
                    <td className="num px-3 py-3 text-muted-foreground" data-qoder-id="qel-num-439bcd6b" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-num-439bcd6b&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;num&quot;,&quot;loc&quot;:{&quot;line&quot;:169,&quot;column&quot;:21}}">{t.batch}</td>
                    <td className="px-3 py-3 text-muted-foreground" data-qoder-id="qel-px-3-c7443a64" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-px-3-c7443a64&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;px-3&quot;,&quot;loc&quot;:{&quot;line&quot;:170,&quot;column&quot;:21}}">{t.item}</td>
                    <td className="num px-3 py-3" data-qoder-id="qel-num-459bd091" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-num-459bd091&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;num&quot;,&quot;loc&quot;:{&quot;line&quot;:171,&quot;column&quot;:21}}">{t.sample}</td>
                    <td className="num px-3 py-3" data-qoder-id="qel-num-3e9bc58c" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-num-3e9bc58c&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;num&quot;,&quot;loc&quot;:{&quot;line&quot;:172,&quot;column&quot;:21}}">{t.pass}</td>
                    <td className="num px-3 py-3" data-qoder-id="qel-num-3f9bc71f" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-num-3f9bc71f&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;num&quot;,&quot;loc&quot;:{&quot;line&quot;:173,&quot;column&quot;:21}}">
                      <span className={rate < 90 ? 'text-warn' : 'text-foreground'} data-qoder-id="qel-span-a1769e51" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-span-a1769e51&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;span&quot;,&quot;loc&quot;:{&quot;line&quot;:174,&quot;column&quot;:23}}">{rate}%</span>
                    </td>
                    <td className="px-3 py-3 text-muted-foreground" data-qoder-id="qel-px-3-36249576" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-px-3-36249576&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;px-3&quot;,&quot;loc&quot;:{&quot;line&quot;:176,&quot;column&quot;:21}}">{t.inspector}</td>
                    <td className="px-4 py-3" data-qoder-id="qel-px-4-c28b1840" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-px-4-c28b1840&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;px-4&quot;,&quot;loc&quot;:{&quot;line&quot;:177,&quot;column&quot;:21}}">
                      <Badge tone={st.tone} pulse={t.status === 'review'} data-qoder-id="qel-badge-c743d47c" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-badge-c743d47c&quot;,&quot;filePath&quot;:&quot;react-vite/src/pages/Quality.jsx&quot;,&quot;componentName&quot;:&quot;Quality&quot;,&quot;elementRole&quot;:&quot;badge&quot;,&quot;loc&quot;:{&quot;line&quot;:178,&quot;column&quot;:23}}">{st.label}</Badge>
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      </Card>
      <QualityPanel />
    </div>
  )
}
