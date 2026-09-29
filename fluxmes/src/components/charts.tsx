/* Recharts 通用样式与提示框 */

export const AXIS_TICK = {
  fill: 'var(--faint)',
  fontSize: 11,
  fontFamily: "'Geist Mono', ui-monospace, monospace",
}

/** recharts Tooltip content 渲染器 props（recharts 会注入，故全部可选）。 */
export type ChartTipProps = {
  active?: boolean
  label?: React.ReactNode
  payload?: { dataKey?: string | number; color?: string; fill?: string; name?: string; value?: string | number | null }[]
  unit?: string
}

export function ChartTip({ active, payload, label, unit = '' }: ChartTipProps) {
  if (!active || !payload || payload.length === 0) return null
  return (
    <div className="rounded-md border border-border-strong bg-card px-3 py-2 shadow-xl" data-qoder-id="qel-rounded-md-9f002ac0" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-rounded-md-9f002ac0&quot;,&quot;filePath&quot;:&quot;react-vite/src/components/charts.jsx&quot;,&quot;componentName&quot;:&quot;ChartTip&quot;,&quot;elementRole&quot;:&quot;rounded-md&quot;,&quot;loc&quot;:{&quot;line&quot;:12,&quot;column&quot;:5}}">
      <div className="num mb-1 text-[11px] text-faint" data-qoder-id="qel-num-94fd6244" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-num-94fd6244&quot;,&quot;filePath&quot;:&quot;react-vite/src/components/charts.jsx&quot;,&quot;componentName&quot;:&quot;ChartTip&quot;,&quot;elementRole&quot;:&quot;num&quot;,&quot;loc&quot;:{&quot;line&quot;:13,&quot;column&quot;:7}}">{label}</div>
      <div className="space-y-1" data-qoder-id="qel-space-y-1-9bb719b1" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-space-y-1-9bb719b1&quot;,&quot;filePath&quot;:&quot;react-vite/src/components/charts.jsx&quot;,&quot;componentName&quot;:&quot;ChartTip&quot;,&quot;elementRole&quot;:&quot;space-y-1&quot;,&quot;loc&quot;:{&quot;line&quot;:14,&quot;column&quot;:7}}">
        {payload.map((p) => (
          <div key={p.dataKey} className="flex items-center gap-2 text-xs" data-qoder-id="qel-flex-02edda41" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-flex-02edda41&quot;,&quot;filePath&quot;:&quot;react-vite/src/components/charts.jsx&quot;,&quot;componentName&quot;:&quot;ChartTip&quot;,&quot;elementRole&quot;:&quot;flex&quot;,&quot;loc&quot;:{&quot;line&quot;:16,&quot;column&quot;:11}}">
            <span className="h-2 w-2 rounded-full" style={{ background: p.color || p.fill }}  data-qoder-id="qel-h-2-a4de7753" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-h-2-a4de7753&quot;,&quot;filePath&quot;:&quot;react-vite/src/components/charts.jsx&quot;,&quot;componentName&quot;:&quot;ChartTip&quot;,&quot;elementRole&quot;:&quot;h-2&quot;,&quot;loc&quot;:{&quot;line&quot;:17,&quot;column&quot;:13}}"/>
            <span className="text-muted-foreground" data-qoder-id="qel-text-muted-foreground-0d3cdb08" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-text-muted-foreground-0d3cdb08&quot;,&quot;filePath&quot;:&quot;react-vite/src/components/charts.jsx&quot;,&quot;componentName&quot;:&quot;ChartTip&quot;,&quot;elementRole&quot;:&quot;text-muted-foreground&quot;,&quot;loc&quot;:{&quot;line&quot;:18,&quot;column&quot;:13}}">{p.name}</span>
            <span className="num ml-auto pl-4 text-foreground" data-qoder-id="qel-num-a11c3b5a" data-qoder-source="{&quot;qoderId&quot;:&quot;qel-num-a11c3b5a&quot;,&quot;filePath&quot;:&quot;react-vite/src/components/charts.jsx&quot;,&quot;componentName&quot;:&quot;ChartTip&quot;,&quot;elementRole&quot;:&quot;num&quot;,&quot;loc&quot;:{&quot;line&quot;:19,&quot;column&quot;:13}}">
              {p.value}
              {unit}
            </span>
          </div>
        ))}
      </div>
    </div>
  )
}
