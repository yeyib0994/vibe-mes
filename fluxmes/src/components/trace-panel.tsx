import { useState } from 'react'
import {
  AlertTriangle,
  ClipboardList,
  Download,
  FileWarning,
  Loader2,
  Network,
  Search,
  ShieldAlert,
  ShieldCheck,
} from 'lucide-react'
import { Badge, Button, Card, Input } from './ui'
import {
  downloadMarkdown,
  useExportTraceReport,
  useTraceBackward,
  useTraceCompleteness,
  useTraceImpact,
  useTraceLogs,
} from '../api/trace'
import { cn } from '../lib/utils'

/**
 * H3 · 追溯闭环面板。
 *
 * 覆盖 traceability FR-3 ~ FR-8：
 * - 逆向追溯（按原料批号反查消耗它的成品批次）
 * - 影响面分析（受影响批次、是否已放行、处置建议）
 * - 档案完整性校验（缺原料/设备/操作人即列缺失项）
 * - 追溯查询审计流水
 * - 报表导出（Markdown，含操作人与生成时间水印）
 */

type StatTone = 'default' | 'danger' | 'warn' | 'accent'

function StatCell({ label, value, unit, tone = 'default' }: { label: string; value: React.ReactNode; unit?: string; tone?: StatTone }) {
  return (
    <div className="rounded-md bg-muted px-3 py-2">
      <div className="label-tech">{label}</div>
      <div className={cn(
        'num mt-1 text-[17px] font-semibold tracking-tight',
        tone === 'danger' && 'text-danger',
        tone === 'warn' && 'text-warn',
        tone === 'accent' && 'text-accent',
      )}>
        {value}
        {unit && <span className="ml-0.5 text-[11px] font-normal text-muted-foreground">{unit}</span>}
      </div>
    </div>
  )
}

export function TraceClosurePanel({ batchId }: { batchId?: string }) {
  const { data: completeness } = useTraceCompleteness(batchId)
  const exportMut = useExportTraceReport()
  const [lotNo, setLotNo] = useState('LOT-001')
  const [query, setQuery] = useState('LOT-001')
  const [msg, setMsg] = useState<{ tone: 'accent' | 'danger'; text: string } | null>(null)

  const { data: backward } = useTraceBackward(query)
  const { data: impact } = useTraceImpact(query)
  const { data: logs = [] } = useTraceLogs(30)

  return (
    <div className="space-y-4" data-component="trace-closure">
      {/* 完整性 */}
      <Card className="card-pad" data-component="trace-integrity">
        <div className="mb-2 flex items-center justify-between">
          <div className="flex items-center gap-2">
            <ShieldCheck className="h-4 w-4 text-faint" />
            <div className="text-[15px] font-semibold tracking-tight">档案完整性校验</div>
          </div>
          <Badge tone={completeness?.complete ? 'accent' : 'danger'} dot={false}>
            {completeness?.integrity ?? '检查中'}
          </Badge>
        </div>
        {(completeness?.missing?.length ?? 0) > 0 && (
          <div className="rounded-md border border-danger/30 bg-danger/5 px-3 py-2">
            <div className="flex items-center gap-1.5 text-[12px] font-medium text-danger">
              <FileWarning className="h-3.5 w-3.5" /> 缺失 {completeness?.missingCount} 项
            </div>
            <ul className="mt-1 space-y-0.5 pl-5 text-[12px] text-danger/90">
              {completeness?.missing?.map((m) => (
                <li key={m} className="list-disc">{m}</li>
              ))}
            </ul>
          </div>
        )}
        {completeness?.complete && (
          <div className="num text-[12px] text-faint">
            原料批号 / 主设备 / 操作员 / 配方版本快照 / COA 齐全，符合 C2 批次档案完整性要求。
          </div>
        )}
        <div className="mt-3 flex flex-wrap items-center gap-2">
          <Button
            size="sm"
            variant="primary"
            disabled={exportMut.isPending || !batchId}
            onClick={async () => {
              try {
                if (!batchId) return
                const r = await exportMut.mutateAsync(batchId)
                downloadMarkdown(r.fileName, r.content)
                setMsg({ tone: 'accent', text: `已导出 ${r.fileName}（${r.nodeCount} 个链路节点）` })
              } catch (e) {
                setMsg({ tone: 'danger', text: String((e as Error)?.message ?? e) })
              }
            }}
          >
            {exportMut.isPending ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Download className="h-3.5 w-3.5" />}
            导出追溯报表
          </Button>
          <span className="num text-[11px] text-faint">报表含生成时间与操作人水印，导出动作写入审计（FR-6 / C3）</span>
          {msg && (
            <span className={cn('text-[12px]', msg.tone === 'danger' ? 'text-danger' : 'text-accent')}>{msg.text}</span>
          )}
        </div>
      </Card>

      {/* 逆向 + 影响面 */}
      <Card className="card-pad" data-component="trace-lot-query">
        <div className="mb-2 flex items-center gap-2">
          <Network className="h-4 w-4 text-faint" />
          <div className="text-[15px] font-semibold tracking-tight">按原料批号反查</div>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <Input
            value={lotNo}
            onChange={(e) => setLotNo(e.target.value)}
            placeholder="原料批号，如 LOT-001"
            className="h-8 w-56 text-xs"
          />
          <Button size="sm" onClick={() => setQuery(lotNo)} disabled={!lotNo}>
            <Search className="h-3.5 w-3.5" /> 查询
          </Button>
          {backward?.material && (
            <span className="num text-[11px] text-muted-foreground">
              {backward.material.name}
              {backward.material.allergen && <Badge className="ml-1" tone="warn">过敏原</Badge>}
            </span>
          )}
        </div>

        <div className="mt-3 grid gap-2 sm:grid-cols-3">
          <StatCell label="消耗批次" value={backward?.batchCount ?? '—'} unit="批" />
          <StatCell
            label="已放行"
            value={impact?.releasedCount ?? '—'}
            unit="批"
            tone={(impact?.releasedCount ?? 0) > 0 ? 'danger' : 'accent'}
          />
          <StatCell label="涉及产量" value={impact?.totalYieldT ?? '—'} unit="t" />
        </div>

        {(impact?.suggestions?.length ?? 0) > 0 && (
          <div className="mt-3 rounded-md border border-warn/30 bg-warn/5 px-3 py-2">
            <div className="flex items-center gap-1.5 text-[12px] font-medium text-warn">
              <ShieldAlert className="h-3.5 w-3.5" /> 处置建议
            </div>
            <ul className="mt-1 space-y-0.5 pl-5 text-[12px] text-warn/90">
              {impact?.suggestions?.map((s) => (
                <li key={s} className="list-disc">{s}</li>
              ))}
            </ul>
          </div>
        )}

        {(backward?.batches?.length ?? 0) > 0 && (
          <div className="mt-3 overflow-x-auto">
            <table className="w-full text-[13px]">
              <thead>
                <tr className="border-y border-border text-left text-xs text-muted-foreground">
                  <th className="py-2 pr-2 font-medium">成品批次</th>
                  <th className="py-2 pr-2 font-medium">产品</th>
                  <th className="py-2 pr-2 font-medium">投用量</th>
                  <th className="py-2 pr-2 font-medium">投料时间</th>
                  <th className="py-2 font-medium">放行</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {backward?.batches?.map((b) => (
                  <tr key={b.batchId}>
                    <td className="num py-2.5 pr-2 font-medium text-primary">{b.batchId}</td>
                    <td className="py-2.5 pr-2">{b.product ?? '—'}</td>
                    <td className="num py-2.5 pr-2">{b.qty ?? '—'}{b.unit ?? ''}</td>
                    <td className="num py-2.5 pr-2 text-xs text-muted-foreground">
                      {String(b.fedAt ?? '').slice(0, 16).replace('T', ' ') || '—'}
                    </td>
                    <td className="py-2.5">
                      {b.released ? (
                        <Badge tone="danger" dot={false}>已放行 · 需召回</Badge>
                      ) : (
                        <Badge tone="muted" dot={false}>{b.status ?? '未放行'}</Badge>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      {/* 查询审计 */}
      <Card className="card-pad" data-component="trace-audit">
        <div className="mb-2 flex items-center gap-2">
          <ClipboardList className="h-4 w-4 text-faint" />
          <div className="text-[15px] font-semibold tracking-tight">追溯查询审计</div>
          <Badge tone="muted" dot={false}>{logs.length} 条</Badge>
        </div>
        {logs.length ? (
          <div className="overflow-x-auto">
            <table className="w-full text-[12px]">
              <thead>
                <tr className="border-y border-border text-left text-xs text-muted-foreground">
                  <th className="py-2 pr-2 font-medium">时间</th>
                  <th className="py-2 pr-2 font-medium">操作人</th>
                  <th className="py-2 pr-2 font-medium">类型</th>
                  <th className="py-2 pr-2 font-medium">对象</th>
                  <th className="py-2 pr-2 font-medium">结果</th>
                  <th className="py-2 font-medium">耗时</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {logs.map((l) => (
                  <tr key={l.id}>
                    <td className="num py-2 pr-2 text-muted-foreground">
                      {String(l.createdAt ?? '').slice(0, 19).replace('T', ' ')}
                    </td>
                    <td className="py-2 pr-2">{l.actor ?? '—'}</td>
                    <td className="py-2 pr-2">
                      <Badge tone="primary" dot={false}>{l.queryType}</Badge>
                    </td>
                    <td className="num py-2 pr-2">{l.queryKey ?? '—'}</td>
                    <td className="num py-2 pr-2">{l.resultCount ?? 0} 项</td>
                    <td className="num py-2">{l.durationMs ?? 0} ms</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <div className="flex items-center gap-1.5 py-4 text-xs text-faint">
            <AlertTriangle className="h-3.5 w-3.5" /> 暂无追溯查询记录
          </div>
        )}
      </Card>
    </div>
  )
}
