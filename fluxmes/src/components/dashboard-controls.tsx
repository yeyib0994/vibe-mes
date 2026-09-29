import { useState } from 'react'
import { Building2, Download, Factory, Loader2 } from 'lucide-react'
import { Badge, Button } from './ui'
import { useLines, useSites, downloadShiftReport } from '../api/dashboard'
import { cn } from '../lib/utils'

/**
 * G4 · 厂区切换器：按厂区聚合驾驶舱 KPI。
 * 数据源 /api/dashboard/sites（厂区主数据，含各厂区产线与批次统计）。
 */
export function SiteSwitcher({
  value,
  onChange,
  className,
}: {
  value?: string
  onChange: (code?: string) => void
  className?: string
}) {
  const { data } = useSites()
  const sites = data?.sites ?? []
  if (!sites.length) return null

  return (
    <div className={cn('flex items-center gap-1 rounded-md border border-border p-0.5', className)}>
      <span className="flex items-center gap-1 pl-1.5 pr-0.5 text-xs text-faint">
        <Building2 className="h-3.5 w-3.5" />
      </span>
      <button
        type="button"
        onClick={() => onChange(undefined)}
        className={cn(
          'rounded px-2.5 py-1 text-xs transition-colors',
          !value ? 'bg-primary text-primary-foreground' : 'text-muted-foreground hover:bg-muted hover:text-foreground',
        )}
      >
        全厂区
      </button>
      {sites.map((s) => (
        <button
          key={s.code}
          type="button"
          onClick={() => onChange(s.code)}
          title={s.address}
          className={cn(
            'rounded px-2.5 py-1 text-xs transition-colors',
            value === s.code
              ? 'bg-primary text-primary-foreground'
              : 'text-muted-foreground hover:bg-muted hover:text-foreground',
          )}
        >
          {s.name ?? s.code}
        </button>
      ))}
    </div>
  )
}

/**
 * D2 · 产线切换器：按产线过滤驾驶舱 KPI 与在产批次。
 * 数据源 /api/dashboard/lines（产线主数据，含各线批次统计）；G4 可按厂区收窄。
 */
export function LineSwitcher({
  value,
  onChange,
  site,
  className,
}: {
  value?: string
  onChange: (code?: string) => void
  site?: string
  className?: string
}) {
  const { data } = useLines(site)
  const lines = data?.lines ?? []
  if (!lines.length) return null

  const item = (code?: string, label?: string) => {
    const active = (value ?? undefined) === code
    return (
      <button
        key={code ?? 'ALL'}
        type="button"
        onClick={() => onChange(code)}
        className={cn(
          'rounded px-2.5 py-1 text-xs transition-colors',
          active
            ? 'bg-primary text-primary-foreground'
            : 'text-muted-foreground hover:bg-muted hover:text-foreground',
        )}
      >
        {label}
      </button>
    )
  }

  return (
    <div className={cn('flex items-center gap-1 rounded-md border border-border p-0.5', className)}>
      <span className="flex items-center gap-1 pl-1.5 pr-0.5 text-xs text-faint">
        <Factory className="h-3.5 w-3.5" />
      </span>
      {item(undefined, '全部产线')}
      {lines.map((l: { code: string; name?: string }) => item(l.code, l.name ?? l.code))}
    </div>
  )
}

/**
 * D3 · 班报导出：选择班次后调用 /api/dashboard/shift-report，
 * 后端返回结构化数据 + Markdown，前端以 Blob 直接下载 .md 文件。
 */
export function ShiftReportActions({ date, line, site }: { date?: string; line?: string; site?: string }) {
  const [shift, setShift] = useState<'DAY' | 'NIGHT'>('DAY')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function run() {
    setBusy(true)
    setError(null)
    try {
      await downloadShiftReport({ date, shift, line, site })
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="flex items-center gap-2">
      {error && <Badge tone="danger">{error}</Badge>}
      <select
        value={shift}
        onChange={(e) => setShift(e.target.value as 'DAY' | 'NIGHT')}
        className="h-8 rounded-md border border-border bg-card px-2 text-xs text-foreground"
        aria-label="班次"
      >
        <option value="DAY">白班 08:00–20:00</option>
        <option value="NIGHT">夜班 20:00–次日08:00</option>
      </select>
      <Button variant="primary" onClick={run} disabled={busy}>
        {busy ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Download className="h-3.5 w-3.5" />}
        {busy ? '生成中…' : '生成班报'}
      </Button>
    </div>
  )
}
