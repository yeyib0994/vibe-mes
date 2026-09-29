import type { ComponentType, ReactNode } from 'react'
import { useState } from 'react'
import {
  ArrowRightLeft,
  Boxes,
  ClipboardCheck,
  Clock,
  Cog,
  FileSearch,
  FlaskConical,
  Layers,
  Package,
  ShieldCheck,
  Truck,
  Wheat,
} from 'lucide-react'
import { Badge, Card, Dot, type BadgeTone } from '../components/ui'
import { PageHeader } from '../components/layout'
import { traceNodeType } from '../data/mes'
import { useTraceChain, useTraceTargets, type TraceNode } from '../api/trace'
import { useGenealogy } from '../api/foodsafety'
import { TraceClosurePanel } from '../components/trace-panel'
import { cn } from '../lib/utils'

/** 追溯节点类型 → 图标。 */
const TYPE_ICON: Record<string, ComponentType<{ className?: string }>> = {
  material: FlaskConical,
  process: Cog,
  batch: Boxes,
  test: ClipboardCheck,
  warehouse: Package,
  shipment: Truck,
}
const TYPE_DOT: Record<string, BadgeTone> = { primary: 'primary', accent: 'accent', warn: 'warn', muted: 'muted' }

function StatCell({ icon: Icon, label, value }: { icon: ComponentType<{ className?: string }>; label: ReactNode; value: ReactNode }) {
  return (
    <div className="flex items-center gap-2.5">
      <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-md bg-muted">
        <Icon className="h-4 w-4 text-muted-foreground" />
      </div>
      <div>
        <div className="label-tech">{label}</div>
        <div className="num mt-0.5 text-[13px] font-medium">{value}</div>
      </div>
    </div>
  )
}

export default function Trace() {
  const { data: targets } = useTraceTargets()
  const candidates = targets?.batches ?? []
  const [batchId, setBatchId] = useState('B-260826-007')
  const [dir, setDir] = useState<'forward' | 'backward'>('forward')
  const { data } = useTraceChain(batchId)
  const { data: gen } = useGenealogy(batchId)

  const chain = data?.chain
  const nodes: TraceNode[] = chain ? ((dir === 'forward' ? chain.forward : chain.backward) ?? []) : []
  const selectedBatch = candidates.find((c) => c.id === batchId)

  return (
    <div className="mx-auto max-w-[1600px] p-6" data-component="page-trace">
      <PageHeader
        title="追溯查询"
        desc="以批次为中心的正向 / 逆向追溯 · 原料↔工序↔设备↔操作员↔检验↔入库↔发货（合规 C1）"
        actions={<FileSearch className="h-4 w-4 text-faint" />}
      />

      {/* 选择 + 方向 */}
      <Card className="card-pad" data-component="trace-search">
        <div className="flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
          <div className="flex flex-wrap items-center gap-2">
            <span className="label-tech">成品批次</span>
            {candidates.map((c) => {
              const isActive = c.id === batchId
              return (
                <button
                  key={c.id}
                  onClick={() => setBatchId(c.id)}
                  className={cn(
                    'flex items-center gap-2 rounded-md border px-2.5 py-1.5 text-xs font-medium transition-colors',
                    isActive
                      ? 'border-primary bg-primary-soft text-primary'
                      : 'border-border text-muted-foreground hover:bg-muted',
                  )}
                >
                  <span className="num">{c.id}</span>
                  <span className="text-faint">·</span>
                  {c.product}
                </button>
              )
            })}
          </div>

          <div className="flex shrink-0 items-center gap-2">
            <span className="label-tech">方向</span>
            <div className="inline-flex rounded-md border border-border p-0.5">
              {[
                { k: 'forward', t: '正向（原料→发货）' },
                { k: 'backward', t: '逆向（成品→原料）' },
              ].map((o) => (
                <button
                  key={o.k}
                  onClick={() => setDir(o.k as 'forward' | 'backward')}
                  className={cn(
                    'rounded px-2.5 py-1 text-xs font-medium transition-colors',
                    dir === o.k ? 'bg-foreground text-background' : 'text-muted-foreground hover:text-foreground',
                  )}
                >
                  {o.t}
                </button>
              ))}
            </div>
          </div>
        </div>
      </Card>

      {/* 批次概要 + 追溯指标 */}
      {selectedBatch && (
        <Card className="mt-4 card-pad" data-component="trace-summary">
          <div className="flex flex-wrap items-center justify-between gap-4">
            <div className="flex items-center gap-3">
              <Badge tone="accent" dot={false}>成品</Badge>
              <span className="num text-[15px] font-semibold text-primary">{selectedBatch.id}</span>
              <span className="text-[13px]">{selectedBatch.product}</span>
              <span className="num text-xs text-muted-foreground">{selectedBatch.recipe}</span>
            </div>
            <div className="grid grid-cols-2 gap-x-8 gap-y-3 sm:grid-cols-4">
              <StatCell icon={Layers} label="追溯层级" value={`${data?.meta?.levelCount ?? '—'} 类节点`} />
              <StatCell icon={Boxes} label="关联记录" value={`${data?.meta?.recordCount ?? '—'} 条`} />
              <StatCell icon={Clock} label="查询耗时" value={`${data?.meta?.querySec ?? '—'} s`} />
              <StatCell icon={ShieldCheck} label="完整性" value={data?.meta?.integrity ?? '—'} />
            </div>
          </div>
        </Card>
      )}

      {/* F2 · 真实投料谱系（基于 batch_input，非 fixture 硬编码） */}
      {selectedBatch && (
        <Card className="mt-4 card-pad" data-component="trace-genealogy">
          <div className="mb-3 flex items-center justify-between">
            <div className="flex items-center gap-2">
              <Wheat className="h-4 w-4 text-accent" />
              <h2 className="text-sm font-medium">真实投料谱系</h2>
              <Badge tone={(gen?.upstreamCount ?? 0) > 0 ? 'success' : 'warning'}>
                {(gen?.upstreamCount ?? 0) > 0 ? '已登记投料' : '未登记投料'}
              </Badge>
            </div>
            <span className="text-xs text-faint">
              上游 {gen?.upstreamCount ?? 0} 个原料批 · 下游 {gen?.downstreamCount ?? 0} 个用途
            </span>
          </div>
          <div className="grid gap-2 sm:grid-cols-2 xl:grid-cols-3">
            {(gen?.upstream ?? []).map((u) => (
              <div key={u.lotId} className="rounded-lg border border-border p-3">
                <div className="flex items-center justify-between">
                  <span className="num text-[13px] font-medium">{u.lotId}</span>
                  <Badge tone={u.qcStatus === 'RELEASED' ? 'success' : 'warning'}>
                    {u.qcStatus === 'RELEASED' ? '已放行' : u.qcStatus}
                  </Badge>
                </div>
                <div className="mt-1 text-xs text-muted-foreground">
                  {u.materialName}
                  {u.allergen && <Badge className="ml-1" tone="warning">{u.allergenName ?? '过敏原'}</Badge>}
                </div>
                <div className="mt-1 text-xs text-faint">
                  {u.supplier} · 供应商批 {u.supplierLot}
                </div>
                <div className="mt-1 num text-xs text-faint">
                  投料 {u.qty}
                  {u.unit} · COA {u.coaNo ?? '—'}
                </div>
              </div>
            ))}
            {(gen?.upstream ?? []).length === 0 && (
              <p className="py-3 text-xs text-faint">该批次尚无真实投料记录，可前往「物料与追溯 → 投料谱系与召回」登记。</p>
            )}
          </div>
        </Card>
      )}

      {/* 时间轴 */}
      <div className="mt-4 grid grid-cols-1 gap-4 xl:grid-cols-3">
        <Card className="card-pad xl:col-span-2" data-component="trace-timeline">
          <div className="mb-4 flex items-center justify-between">
            <div className="text-[15px] font-semibold tracking-tight">
              {dir === 'forward' ? '正向追溯链' : '逆向追溯链'}
            </div>
            <div className="flex items-center gap-1.5 text-xs text-muted-foreground">
              <ArrowRightLeft className="h-3.5 w-3.5" />
              {dir === 'forward' ? '从原料到成品流向下游' : '从成品反查原料与工序'}
            </div>
          </div>

          <div className="relative">
            {nodes.map((n, i) => {
              const Icon = TYPE_ICON[n.type] ?? FlaskConical
              const cfg = (traceNodeType as Record<string, { tone?: string; label?: string }>)[n.type] ?? { tone: 'muted', label: n.type }
              const last = i === nodes.length - 1
              return (
                <div key={`${n.label}-${i}`} className="relative flex gap-3.5 pb-5 last:pb-0">
                  {!last && <span className="absolute left-[15px] top-8 h-full w-px bg-border" />}
                  <div className={cn(
                    'z-10 flex h-8 w-8 shrink-0 items-center justify-center rounded-full border bg-card',
                    cfg.tone === 'danger' && 'border-danger',
                    cfg.tone === 'warn' && 'border-warn',
                    cfg.tone === 'accent' && 'border-accent',
                    (cfg.tone === 'primary' || cfg.tone === 'muted') && 'border-border-strong',
                  )}>
                    <Icon className={cn(
                      'h-4 w-4',
                      cfg.tone === 'warn' ? 'text-warn' : cfg.tone === 'accent' ? 'text-accent' : cfg.tone === 'primary' ? 'text-primary' : 'text-muted-foreground',
                    )} />
                  </div>
                  <div className="min-w-0 flex-1 rounded-lg border border-border bg-muted/40 px-3.5 py-2.5">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="label-tech">{n.label}</span>
                      <span className="num text-[13px] font-medium">{n.title}</span>
                      <Badge tone={(cfg.tone ?? 'muted') as BadgeTone} dot={false} className="ml-auto">{n.value}</Badge>
                    </div>
                    <div className="mt-1 text-xs text-muted-foreground">{n.meta}</div>
                  </div>
                </div>
              )
            })}
          </div>
        </Card>

        {/* 图例 + 合规说明 */}
        <div className="space-y-4">
          <Card className="card-pad" data-component="trace-legend">
            <div className="mb-2 text-[15px] font-semibold tracking-tight">节点类型</div>
            <div className="grid grid-cols-2 gap-2">
              {Object.entries(traceNodeType).map(([k, v]) => {
                const Icon = TYPE_ICON[k] ?? FlaskConical
                return (
                  <div key={k} className="flex items-center gap-2 text-xs text-muted-foreground">
                    <Icon className="h-3.5 w-3.5" />
                    <Dot tone={TYPE_DOT[v.tone] ?? 'muted'} />
                    {v.label}
                  </div>
                )
              })}
            </div>
          </Card>

          <Card className="card-pad" data-component="trace-compliance">
            <div className="mb-2 flex items-center gap-1.5 text-[15px] font-semibold tracking-tight">
              <ShieldCheck className="h-4 w-4 text-accent" /> 合规依据
            </div>
            <ul className="space-y-2 text-xs text-muted-foreground">
              <li className="flex gap-2"><span className="num mt-0.5 text-accent">C1</span>任意成品批次可在 ≤ 1 次操作内完成正向与逆向追溯。</li>
              <li className="flex gap-2"><span className="num mt-0.5 text-accent">C2</span>批次档案含配方版本、原料批号、关键参数、操作员、检验与入库位，放行后只读。</li>
              <li className="flex gap-2"><span className="num mt-0.5 text-accent">C3</span>追溯与放行动作记录 who/when/what，保留 ≥ 3 年。</li>
              <li className="flex gap-2"><span className="num mt-0.5 text-accent">C5</span>质量判定标注执行标准（柠檬酸含量依据 GB 1886.25—2016）。</li>
            </ul>
          </Card>
        </div>
      </div>

      {/* H3 · 追溯闭环：完整性 / 逆向 / 影响面 / 审计 / 导出 */}
      {selectedBatch && <div className="mt-4"><TraceClosurePanel batchId={batchId} /></div>}
    </div>
  )
}
