import type { ComponentType, ReactNode } from 'react'
import { useState } from 'react'
import { CheckCircle2, Scale, ShieldAlert, TriangleAlert } from 'lucide-react'
import { Badge, Button, Card, Input, Progress, type BadgeTone } from '../components/ui'
import { PageHeader } from '../components/layout'
import {
  RESULT_TEXT,
  RESULT_TONE,
  TASK_STATUS_TEXT,
  TASK_STATUS_TONE,
  useCreateWeighingTask,
  useReviewWeighing,
  useWeigh,
  useWeighingSummary,
  useWeighingTasks,
} from '../api/weighing'
import { hasRole } from '../lib/auth'

/**
 * G3 · 配料称量与容差校验。
 * 服务端强制判定：|偏差| ≤ 容差判合格；超差自动建偏差单 + major 报警并阻断任务，
 * 须由质检员复核（复核人不得为称量人本人）后方可继续。
 */
function StatCard({
  icon: Icon,
  label,
  value,
  unit,
  sub,
}: {
  icon: ComponentType<{ className?: string }>
  label: ReactNode
  value: ReactNode
  unit?: ReactNode
  sub?: ReactNode
}) {
  return (
    <Card className="p-4">
      <div className="flex items-start justify-between">
        <span className="text-xs text-muted-foreground">{label}</span>
        <Icon className="h-4 w-4 text-muted-foreground" />
      </div>
      <div className="mt-2 flex items-baseline gap-1">
        <span className="num text-2xl font-semibold tabular-nums">{value}</span>
        {unit && <span className="text-xs text-muted-foreground">{unit}</span>}
      </div>
      {sub && <div className="mt-1 text-xs text-faint">{sub}</div>}
    </Card>
  )
}

function Field({ label, children }: { label: ReactNode; children: ReactNode }) {
  return (
    <label className="block">
      <span className="mb-1 block text-xs text-muted-foreground">{label}</span>
      {children}
    </label>
  )
}

export default function Weighing() {
  const canReview = hasRole('QC')
  const { data: summary } = useWeighingSummary()
  const { data: tasks = [] } = useWeighingTasks({})
  const createTask = useCreateWeighingTask()
  const weigh = useWeigh()
  const review = useReviewWeighing()

  const [form, setForm] = useState({
    batchId: '',
    materialCode: '',
    materialName: '',
    targetQty: '',
    tolerancePct: '1',
  })
  const [weighQty, setWeighQty] = useState<Record<string, string>>({})
  const [open, setOpen] = useState(false)

  return (
    <div className="mx-auto max-w-[1400px] p-6">
      <PageHeader
        title="配料称量与容差校验"
        desc="配方称量偏差服务端强制判定 · 超差自动建偏差单与报警，称量人须持「配料称量」资质"
      />

      <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
        <StatCard icon={Scale} label="称量任务" value={summary?.taskCount ?? '—'} sub={`累计称量 ${summary?.weighCount ?? 0} 次`} />
        <StatCard
          icon={CheckCircle2}
          label="称量合格率"
          value={summary?.passRatePercent ?? '—'}
          unit="%"
          sub={`合格 ${summary?.passCount ?? 0} / 超差 ${summary?.outOfToleranceCount ?? 0}`}
        />
        <StatCard
          icon={TriangleAlert}
          label="超差次数"
          value={summary?.outOfToleranceCount ?? '—'}
          sub={`超出 ${summary?.overCount ?? 0} · 不足 ${summary?.underCount ?? 0}`}
        />
        <StatCard
          icon={ShieldAlert}
          label="待复核"
          value={summary?.pendingReviewCount ?? '—'}
          sub="超差记录须 QC 复核后解除阻断"
        />
      </div>

      <Card className="mt-5 p-4">
        <div className="mb-3 flex items-center justify-between">
          <div className="flex items-center gap-2 text-[13px] font-medium">
            <Scale className="h-4 w-4 text-primary" /> 称量任务（{tasks.length}）
          </div>
          <Button size="sm" variant="primary" onClick={() => setOpen((v) => !v)}>
            {open ? '收起' : '新建称量任务'}
          </Button>
        </div>

        {open && (
          <div className="mb-4 grid grid-cols-2 gap-3 rounded border border-border p-3 lg:grid-cols-5">
            <Field label="批次号">
              <Input value={form.batchId} onChange={(e) => setForm({ ...form, batchId: e.target.value })} placeholder="B-260914-001" />
            </Field>
            <Field label="物料编码">
              <Input value={form.materialCode} onChange={(e) => setForm({ ...form, materialCode: e.target.value })} placeholder="RM-001" />
            </Field>
            <Field label="物料名称">
              <Input value={form.materialName} onChange={(e) => setForm({ ...form, materialName: e.target.value })} placeholder="玉米淀粉" />
            </Field>
            <Field label="目标量">
              <Input value={form.targetQty} onChange={(e) => setForm({ ...form, targetQty: e.target.value })} placeholder="500" />
            </Field>
            <Field label="允许容差 %">
              <Input value={form.tolerancePct} onChange={(e) => setForm({ ...form, tolerancePct: e.target.value })} placeholder="1" />
            </Field>
            <div className="col-span-2 flex items-end gap-2 lg:col-span-5">
              <Button
                size="sm"
                variant="primary"
                disabled={!form.batchId || !form.targetQty || createTask.isPending}
                onClick={() => {
                  createTask.mutate({
                    batchId: form.batchId,
                    materialCode: form.materialCode || null,
                    materialName: form.materialName || null,
                    targetQty: Number(form.targetQty),
                    tolerancePct: Number(form.tolerancePct) || 1,
                  })
                  setForm({ batchId: '', materialCode: '', materialName: '', targetQty: '', tolerancePct: '1' })
                  setOpen(false)
                }}
              >
                {createTask.isPending ? '提交中…' : '创建任务'}
              </Button>
              {createTask.isError && <span className="text-xs text-danger">{createTask.error?.message}</span>}
            </div>
          </div>
        )}

        <div className="space-y-3">
          {tasks.map((t) => (
            <div key={t.id} className="rounded border border-border p-3">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <div>
                  <div className="text-[13px] font-medium">
                    {t.id} · {t.materialName ?? t.materialCode ?? '物料'}
                  </div>
                  <div className="text-xs text-faint">
                    批次 {t.batchId} · 目标 {t.targetQty}
                    {t.unit} ±{t.tolerancePct}% · 已称 {t.totalWeighed}
                    {t.unit}
                  </div>
                </div>
                <Badge tone={(TASK_STATUS_TONE as Record<string, BadgeTone>)[t.status ?? ''] ?? 'muted'}>{(TASK_STATUS_TEXT as Record<string, string>)[t.status ?? ''] ?? t.status}</Badge>
              </div>

              <div className="mt-2">
                <Progress value={t.progressPercent ?? 0} tone={t.status === 'BLOCKED' ? 'danger' : 'primary'} />
              </div>

              {t.status !== 'DONE' && (
                <div className="mt-3 flex flex-wrap items-end gap-2">
                  <Field label={`实际称量（${t.unit ?? ''}）`}>
                    <Input
                      className="w-40"
                      value={weighQty[t.id] ?? ''}
                      onChange={(e) => setWeighQty({ ...weighQty, [t.id]: e.target.value })}
                      placeholder={String(t.targetQty)}
                    />
                  </Field>
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={!weighQty[t.id] || weigh.isPending}
                    onClick={() => {
                      weigh.mutate({ taskId: t.id, actualQty: Number(weighQty[t.id]) })
                      setWeighQty({ ...weighQty, [t.id]: '' })
                    }}
                  >
                    登记称量
                  </Button>
                  {weigh.isError && <span className="text-xs text-danger">{weigh.error?.message}</span>}
                </div>
              )}

              <div className="mt-3 overflow-x-auto">
                <table className="w-full text-[13px]">
                  <thead>
                    <tr className="border-b border-border text-left text-xs text-muted-foreground">
                      <th className="py-1.5 pr-3">#</th>
                      <th className="py-1.5 pr-3">实际值</th>
                      <th className="py-1.5 pr-3">偏差</th>
                      <th className="py-1.5 pr-3">判定</th>
                      <th className="py-1.5 pr-3">称量人</th>
                      <th className="py-1.5 pr-3">偏差单</th>
                      <th className="py-1.5 pr-3">复核</th>
                    </tr>
                  </thead>
                  <tbody>
                    {(t.items ?? []).map((i) => (
                      <tr key={i.id} className="border-b border-border/60">
                        <td className="num py-1.5 pr-3">{i.seq}</td>
                        <td className="num py-1.5 pr-3">
                          {i.actualQty} {t.unit}
                        </td>
                        <td className="num py-1.5 pr-3">{i.deviationPct}%</td>
                        <td className="py-1.5 pr-3">
                          <Badge tone={(RESULT_TONE as Record<string, BadgeTone>)[i.result ?? ''] ?? 'muted'}>{(RESULT_TEXT as Record<string, string>)[i.result ?? ''] ?? i.result}</Badge>
                        </td>
                        <td className="py-1.5 pr-3">{i.operator ?? '—'}</td>
                        <td className="num py-1.5 pr-3">{i.deviationId ?? '—'}</td>
                        <td className="py-1.5 pr-3">
                          {i.result === 'PASS' ? (
                            '—'
                          ) : i.reviewer ? (
                            <span className="text-xs">{i.reviewer}</span>
                          ) : canReview ? (
                            <Button size="sm" variant="ghost" onClick={() => review.mutate(i.id)}>
                              复核
                            </Button>
                          ) : (
                            <span className="text-xs text-faint">待复核</span>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          ))}
          {!tasks.length && <div className="text-xs text-faint">暂无称量任务</div>}
        </div>
      </Card>
    </div>
  )
}
