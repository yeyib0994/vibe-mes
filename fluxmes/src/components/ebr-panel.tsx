import { CheckCircle2, ClipboardList, FileStack, ShieldCheck } from 'lucide-react'
import { Badge, Button, Card } from './ui'
import { useEbr, useReviewStep } from '../api/foodsafety'
import { hasRole } from '../lib/auth'

/**
 * F3 · 电子批记录 eBR：展示批次各工序的工艺设定值 vs 实际值，
 * 关键工序须由另一人复核（双人复核，复核人不得为操作人）。
 */
type EbrStatus = 'DONE' | 'RUNNING' | 'PENDING' | 'SKIPPED'
const STATUS_TONE: Record<EbrStatus, 'success' | 'primary' | 'muted' | 'warning'> = { DONE: 'success', RUNNING: 'primary', PENDING: 'muted', SKIPPED: 'warning' }
const STATUS_TEXT: Record<EbrStatus, string> = { DONE: '已完成', RUNNING: '执行中', PENDING: '待执行', SKIPPED: '跳过' }

function Params({ value }: { value?: unknown }) {
  if (!value || typeof value !== 'object') return <span className="text-faint">—</span>
  return (
    <div className="flex flex-wrap gap-1">
      {Object.entries(value).map(([k, v]) => (
        <span key={k} className="rounded bg-muted/60 px-1.5 py-0.5 text-[11px]">
          {k} <span className="num font-medium">{String(v)}</span>
        </span>
      ))}
    </div>
  )
}

export default function EbrPanel({ batchId }: { batchId?: string }) {
  const { data, isLoading } = useEbr(batchId)
  const review = useReviewStep()

  if (!batchId) return null
  if (isLoading) return <div className="px-4 py-3 text-xs text-faint">加载批记录…</div>
  if (!data) return null

  return (
    <Card className="m-3 p-4">
      <div className="mb-3 flex items-center justify-between">
        <div className="flex items-center gap-2">
          <FileStack className="h-4 w-4 text-primary" />
          <h3 className="text-sm font-medium">电子批记录 eBR</h3>
          <Badge tone={data.complete ? 'success' : 'warning'}>
            {data.complete ? '记录完整' : `${data.unreviewedCount} 项待复核`}
          </Badge>
        </div>
        <span className="text-xs text-faint">
          {data.doneCount}/{data.stepCount} 工序完成
        </span>
      </div>

      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-border text-left text-xs text-muted-foreground">
              <th className="py-2 pr-3">#</th>
              <th className="py-2 pr-3">工序</th>
              <th className="py-2 pr-3">工艺设定值</th>
              <th className="py-2 pr-3">实际值</th>
              <th className="py-2 pr-3">操作人</th>
              <th className="py-2 pr-3">复核人</th>
              <th className="py-2">状态</th>
            </tr>
          </thead>
          <tbody>
            {(data.steps ?? []).map((s) => (
              <tr key={s.id} className="border-b border-border/60 last:border-0">
                <td className="num py-2 pr-3 text-xs text-faint">{s.stepNo}</td>
                <td className="py-2 pr-3">{s.stepName}</td>
                <td className="py-2 pr-3">
                  <Params value={s.targetParams} />
                </td>
                <td className="py-2 pr-3">
                  <Params value={s.actualParams} />
                </td>
                <td className="py-2 pr-3 text-xs">{s.operator ?? '—'}</td>
                <td className="py-2 pr-3 text-xs">
                  {s.reviewer ? (
                    <span className="inline-flex items-center gap-1 text-accent">
                      <CheckCircle2 className="h-3 w-3" />
                      {s.reviewer}
                    </span>
                  ) : s.status === 'DONE' && hasRole('QC') ? (
                    <Button variant="ghost" disabled={review.isPending} onClick={() => review.mutate(s.id as string | number)}>
                      复核
                    </Button>
                  ) : (
                    <span className="text-faint">未复核</span>
                  )}
                </td>
                <td className="py-2">
                  <Badge tone={STATUS_TONE[s.status as EbrStatus] ?? 'muted'}>{STATUS_TEXT[s.status as EbrStatus] ?? s.status}</Badge>
                </td>
              </tr>
            ))}
            {(data.steps ?? []).length === 0 && (
              <tr>
                <td colSpan={7} className="py-6 text-center text-xs text-faint">
                  该批次尚无工序执行记录
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      <div className="mt-3 flex items-center gap-3 text-xs text-faint">
        <span className="inline-flex items-center gap-1">
          <ShieldCheck className="h-3 w-3" />
          关键工序须双人复核，复核人不得为操作人本人
        </span>
        <span className="inline-flex items-center gap-1">
          <ClipboardList className="h-3 w-3" />
          批次放行后 eBR 只读
        </span>
      </div>
    </Card>
  )
}
