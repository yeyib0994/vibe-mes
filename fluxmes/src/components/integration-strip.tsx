import { useState } from 'react'
import { CheckCircle2, Loader2, Plug, RefreshCw, XCircle } from 'lucide-react'
import { Badge, Button, Card } from './ui'
import {
  sourceMeta,
  useCollectMetrics,
  useIntegrationSummary,
  useProbeIntegration,
} from '../api/integration'
import { getSession } from '../lib/auth'
import { cn } from '../lib/utils'

/**
 * Phase J · 外部系统集成状态条。
 *
 * 回答一个具体问题：**当前屏幕上这些设备数据，到底是谁给的？**
 * 四个系统（SCADA/LIMS/ERP/WMS）可能处于 mock、也可能已接真实系统，
 * 这里把「模式 + 连通状态 + 数据来源」直接摊开，避免演示数据被当成现场数据
 * （constitution P4：实时数据须标注时效与来源）。
 *
 * 手工触发按钮会**真的**去调外部系统，因此按后端 RBAC 只给值班长/管理员。
 */

/** 数据来源徽标（设备页多处复用）。 */
export function DataSourceBadge({ dataSource, className = '' }) {
  const meta = sourceMeta(dataSource)
  return (
    <Badge tone={meta.tone} dot={false} className={cn('font-normal', className)}>
      来源 {meta.label}
    </Badge>
  )
}

type SystemItem = {
  system: string
  mode: string
  endpoint: string
  available: boolean
  detail: string
  lastSuccessAt: string | null
  dataSource: string | null
}

type Notice = { tone: 'accent' | 'danger'; text: string }

function SystemRow({
  item,
  onProbe,
  probing,
  canProbe,
}: {
  item: SystemItem
  onProbe: (system: string) => void
  probing: boolean
  canProbe: boolean
}) {
  const reachable = item.available
  return (
    <div className="flex items-center gap-2 border-b border-border py-2 last:border-0">
      {reachable ? (
        <CheckCircle2 className="h-3.5 w-3.5 shrink-0 text-success" />
      ) : (
        <XCircle className="h-3.5 w-3.5 shrink-0 text-danger" />
      )}
      <span className="w-14 shrink-0 text-[13px] font-medium">{item.system}</span>
      <Badge tone={item.mode === 'MOCK' ? 'warn' : 'accent'} dot={false}>
        {item.mode === 'MOCK' ? '模拟' : '真实'}
      </Badge>
      <DataSourceBadge dataSource={item.dataSource} />
      <span className="min-w-0 flex-1 truncate text-[11px] text-faint" title={item.detail}>
        {item.detail}
      </span>
      {canProbe && (
        <Button
          variant="ghost"
          size="sm"
          className=""
          disabled={probing}
          onClick={() => onProbe(item.system)}
          title="主动探测（会真的调用外部系统）"
        >
          {probing ? <Loader2 className="h-3 w-3 animate-spin" /> : <Plug className="h-3 w-3" />}
          探测
        </Button>
      )}
    </div>
  )
}

export function IntegrationStrip() {
  const role = getSession()?.role
  const canProbe = role === 'SUPERVISOR' || role === 'ADMIN'
  const { data, isLoading, isError, error } = useIntegrationSummary()
  const probe = useProbeIntegration()
  const collect = useCollectMetrics()
  const [msg, setMsg] = useState<Notice | null>(null)

  const systems: SystemItem[] = data?.systems ?? []
  const collector = data?.collector
  const mockCount = systems.filter((s) => s.mode === 'MOCK').length

  const runProbe = (system: string) => {
    setMsg(null)
    probe.mutate(system, {
      onSuccess: (res) => setMsg({ tone: 'accent', text: `${system}：${res?.detail ?? '完成'}` }),
      onError: (e) => setMsg({ tone: 'danger', text: `${system} 探测失败：${e.message}` }),
    })
  }

  const runCollect = () => {
    setMsg(null)
    collect.mutate(undefined, {
      onSuccess: (res) => setMsg({ tone: 'accent', text: res?.detail ?? '采集完成' }),
      onError: (e) => setMsg({ tone: 'danger', text: `采集失败：${e.message}` }),
    })
  }

  if (isError) {
    return (
      <Card className="card-pad">
        <div className="flex items-center gap-2 text-xs text-danger">
          <XCircle className="h-3.5 w-3.5" />
          集成状态不可用：{error?.message}
        </div>
      </Card>
    )
  }

  return (
    <Card className="card-pad" data-component="integration-strip">
      <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <div className="text-[15px] font-semibold tracking-tight">外部系统集成</div>
          {mockCount > 0 ? (
            <Badge tone="warn" dot={false}>
              {mockCount}/4 为模拟数据
            </Badge>
          ) : (
            <Badge tone="accent" dot={false}>全部真实接入</Badge>
          )}
        </div>
        <div className="flex items-center gap-2">
          {collector && (
            <span className="num text-[11px] text-faint">
              采集 {collector.lastBatchSize ?? 0} 条/次 · 周期 {collector.intervalSeconds ?? '—'}s
              {' · '}表内 {collector.storedCount ?? '—'} 条
            </span>
          )}
          {canProbe && (
            <Button
              variant="outline"
              size="sm"
              className=""
              disabled={collect.isPending}
              onClick={runCollect}
            >
              {collect.isPending ? (
                <Loader2 className="h-3 w-3 animate-spin" />
              ) : (
                <RefreshCw className="h-3 w-3" />
              )}
              立即采集
            </Button>
          )}
        </div>
      </div>

      {isLoading ? (
        <div className="py-3 text-xs text-faint">读取集成状态…</div>
      ) : (
        <div>
          {systems.map((s) => (
            <SystemRow
              key={s.system}
              item={s}
              onProbe={runProbe}
              probing={probe.isPending && probe.variables === s.system}
              canProbe={canProbe}
            />
          ))}
        </div>
      )}

      {(msg || collector?.lastRunStatus) && (
        <div
          className={cn(
            'mt-2 rounded-md px-2 py-1.5 text-[11px]',
            msg?.tone === 'danger' ? 'bg-danger-soft text-danger' : 'bg-muted text-muted-foreground',
          )}
        >
          {msg?.text ?? collector?.lastRunStatus}
        </div>
      )}
    </Card>
  )
}

export default IntegrationStrip
