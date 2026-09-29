import { ArrowRight, ShieldAlert } from 'lucide-react'
import { Badge, Button, Card } from './ui'
import { PageHeader } from './layout'
import { useDeviations, useQualityDetect, useTransitionDeviation } from '../api/quality'
import { hasRole } from '../lib/auth'

type DeviationStatus = 'open' | 'investigating' | 'capa' | 'closed'
const STATUS_TONE: Record<DeviationStatus, 'danger' | 'warn' | 'primary' | 'accent'> = { open: 'danger', investigating: 'warn', capa: 'primary', closed: 'accent' }
const STATUS_LABEL: Record<DeviationStatus, string> = { open: '待处理', investigating: '调查中', capa: '整改中', closed: '已关闭' }
const NEXT: Record<DeviationStatus, DeviationStatus> = { open: 'investigating', investigating: 'capa', capa: 'closed', closed: 'closed' }

/**
 * 质量控制面板：Western Electric 判异结果（T3）+ 控制限来源（T2/C5）+ 偏差工作流（T8）。
 * 关闭偏差需值班长及以上角色，按钮按角色禁用。
 */
export default function QualityPanel() {
  const { data: detect } = useQualityDetect()
  const { data: deviations = [] } = useDeviations()
  const transition = useTransitionDeviation()

  const violations = detect?.violations ?? []
  const limit = detect?.limit

  return (
    <Card className="card-pad mt-4">
      <PageHeader
        title="SPC 判异与偏差"
        desc={
          limit
            ? `控制限来自配置（${limit.feature} · ${limit.standardVersion ?? '未提供标准版本'}）`
            : '读取控制限配置…'
        }
        actions={<ArrowRight className="h-4 w-4 text-faint" />}
      />

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        {/* 判异命中 */}
        <div>
          <div className="flex items-center gap-1.5 pb-2">
            <ShieldAlert className="h-3.5 w-3.5 text-muted-foreground" />
            <span className="label-tech">判异命中（Western Electric）</span>
            <Badge tone={violations.length ? 'danger' : 'accent'}>{violations.length} 处</Badge>
          </div>
          {violations.length === 0 ? (
            <p className="text-[13px] text-muted-foreground">当前序列未命中判异规则。</p>
          ) : (
            <ul className="space-y-1">
              {violations.slice(0, 8).map((v, i) => (
                <li key={`${v.index}-${v.rule}-${i}`} className="flex items-start gap-2 text-[13px]">
                  <Badge tone="warn" dot={false}>
                    {v.rule}
                  </Badge>
                  <span className="num text-faint">#{v.index}</span>
                  <span className="text-muted-foreground">{v.desc}</span>
                </li>
              ))}
            </ul>
          )}
        </div>

        {/* 偏差单 */}
        <div>
          <div className="flex items-center gap-1.5 pb-2">
            <span className="label-tech">偏差单</span>
            <Badge tone={deviations.length ? 'warn' : 'accent'}>{deviations.length} 条</Badge>
          </div>
          {deviations.length === 0 ? (
            <p className="text-[13px] text-muted-foreground">暂无需跟踪的偏差。</p>
          ) : (
            <ul className="divide-y divide-border">
              {deviations.map((d) => {
                const next = NEXT[d.status as DeviationStatus]
                return (
                  <li key={d.id} className="flex items-center gap-2 py-1.5">
                    <span className="num text-[13px]">{d.id}</span>
                    <Badge tone={STATUS_TONE[d.status] ?? 'muted'}>{STATUS_LABEL[d.status] ?? d.status}</Badge>
                    <span className="truncate text-xs text-muted-foreground">{d.description}</span>
                    {next && (
                      <Button
                        size="sm"
                        variant="ghost"
                        className="ml-auto"
                        disabled={transition.isPending || (next === 'closed' && !hasRole('SUPERVISOR'))}
                        onClick={() => {
                          if (next === 'closed') {
                            const rootCause = window.prompt('关闭偏差需填写根因', d.rootCause ?? '')
                            if (rootCause === null) return
                            transition.mutate({ id: d.id, target: next as string, rootCause })
                          } else {
                            transition.mutate({ id: d.id, target: next as string })
                          }
                        }}
                      >
                        {STATUS_LABEL[next]}
                      </Button>
                    )}
                  </li>
                )
              })}
            </ul>
          )}
          {transition.isError && (
            <p className="mt-1 text-xs text-danger">{(transition.error as Error).message}</p>
          )}
        </div>
      </div>
    </Card>
  )
}
