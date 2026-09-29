import { AlertTriangle } from 'lucide-react'
import { useStale } from '../lib/rt'

// 时效徽标：新鲜时显示「数据更新于 Ns 前」，超期（默认 >30s）时转为警告态，体现 constitution P4 与 spec FR-8。
export default function StalenessBadge({ generatedAt, ttl = 30_000 }) {
  const { stale, ageSec } = useStale(generatedAt, ttl)
  if (stale) {
    return (
      <div className="mb-4 flex items-center gap-2 rounded-md bg-warn-soft px-3 py-2 text-xs text-warn">
        <AlertTriangle className="h-3.5 w-3.5 shrink-0" />
        数据已 {ageSec}s 未更新（阈值 {Math.round(ttl / 1000)}s），当前展示可能为陈旧值，请检查采集链路。
      </div>
    )
  }
  return (
    <div className="mb-4 flex items-center gap-2 text-xs text-muted-foreground">
      <span className="h-1.5 w-1.5 shrink-0 rounded-full bg-accent" />
      数据更新于 {ageSec}s 前 · 每 {Math.round(ttl / 1000)}s 自动刷新
    </div>
  )
}
