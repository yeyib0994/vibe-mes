import { useMemo, useState, type ComponentType, type ReactNode } from 'react'
import {
  CalendarClock,
  CheckCircle2,
  ClipboardList,
  ListChecks,
  PlusCircle,
  RotateCcw,
  ShieldAlert,
  TriangleAlert,
  XCircle,
} from 'lucide-react'
import { Badge, Button, Card, Input, Tabs, type BadgeTone } from '../components/ui'
import { PageHeader } from '../components/layout'
import { SignatureDialog, SignatureList } from '../components/signature-bar'
import {
  CAPA_STATUS,
  CAPA_TYPES,
  SEVERITIES,
  SEVERITY_TONE,
  SOURCE_TYPES,
  VERIFY_METHODS,
  useAddCapaTask,
  useCapaDetail,
  useCapaList,
  useCapaMetrics,
  useCapaOverdue,
  useCloseCapa,
  useCreateCapa,
  useDoneCapaTask,
  useTransitionCapa,
  useUpdateCapa,
  useVerifyCapa,
  type CapaItem,
} from '../api/regtech'
import { hasRole } from '../lib/auth'

/**
 * Phase I · CAPA 闭环（specs/quality-regtech FR-1 ~ FR-8）。
 * 偏差 / 内审发现项 → CAPA → 行动项 → 有效性验证 → 关闭，全程留痕并可电子签名。
 */

const TABS = [
  { key: 'list', label: 'CAPA 清单' },
  { key: 'overdue', label: '临期与逾期' },
  { key: 'create', label: '新建 CAPA' },
]

const STATUS_ORDER = ['open', 'in_progress', 'pending_verify', 'verified', 'closed', 'rejected']

/** JS 常量模块索引安全化（保持运行时行为不变）。 */
const STATUS_META = CAPA_STATUS as Record<string, { label: string; tone: BadgeTone }>
const SEV_TONE = SEVERITY_TONE as Record<string, BadgeTone>

type SignMode = 'verify' | 'close'
type SignRequest = { mode: SignMode; id: string | number } | null

function StatCard({ icon: Icon, label, value, sub, tone = 'primary' }: {
  icon: ComponentType<{ className?: string }>
  label?: ReactNode
  value?: ReactNode
  sub?: ReactNode
  tone?: string
}) {
  return (
    <Card className="p-4">
      <div className="flex items-start justify-between">
        <span className="text-xs text-muted-foreground">{label}</span>
        <Icon className="h-4 w-4 text-muted-foreground" />
      </div>
      <div className={`num mt-2 text-2xl font-semibold tabular-nums ${tone === 'danger' ? 'text-danger' : ''}`}>
        {value}
      </div>
      {sub && <div className="mt-1 text-xs text-faint">{sub}</div>}
    </Card>
  )
}

function Field({ label, children }: { label?: ReactNode; children?: ReactNode }) {
  return (
    <label className="block">
      <span className="mb-1 block text-xs text-muted-foreground">{label}</span>
      {children}
    </label>
  )
}

function StatusBadge({ capa }: { capa: CapaItem }) {
  const meta = STATUS_META[capa.status ?? ''] ?? { label: capa.status ?? '', tone: 'muted' as BadgeTone }
  return (
    <>
      <Badge tone={meta.tone}>{meta.label}</Badge>
      {capa.overdue && (
        <Badge tone="danger" className="ml-1">
          逾期 {Math.abs(capa.daysLeft ?? 0)} 天
        </Badge>
      )}
      {!capa.overdue && capa.dueSoon && (
        <Badge tone="warn" className="ml-1">
          临期 {capa.daysLeft} 天
        </Badge>
      )}
    </>
  )
}

export default function Capa() {
  const [tab, setTab] = useState('list')
  const [selected, setSelected] = useState<string | number | null>(null)
  const [statusFilter, setStatusFilter] = useState('')
  const canCreate = hasRole('QC')
  const canVerify = hasRole('ADMIN')

  const { data: metrics } = useCapaMetrics()
  const { data: listRes } = useCapaList(statusFilter ? { status: statusFilter } : {})
  const { data: overdueRes } = useCapaOverdue()

  const createCapa = useCreateCapa()
  const transition = useTransitionCapa()
  const updateCapa = useUpdateCapa()
  const addTask = useAddCapaTask()
  const doneTask = useDoneCapaTask()
  const verifyCapa = useVerifyCapa()
  const closeCapa = useCloseCapa()

  const [form, setForm] = useState({
    sourceType: 'MANUAL',
    sourceId: '',
    type: 'CORRECTIVE',
    title: '',
    description: '',
    rootCause: '',
    owner: '',
    dueDate: '',
    severity: 'major',
    task1: '',
    task1Owner: '',
  })
  const [taskForm, setTaskForm] = useState({ action: '', owner: '', dueDate: '' })
  const [verifyForm, setVerifyForm] = useState({
    verificationMethod: 'DOC_REVIEW',
    effectiveness: 'EFFECTIVE',
    comment: '',
  })
  // 签名对话框状态：{ mode: 'verify' | 'close', id }
  const [signFor, setSignFor] = useState<SignRequest>(null)

  const items = listRes?.items ?? []
  const byOwner = overdueRes?.byOwner ?? []

  function submitCreate() {
    if (!canCreate) return
    createCapa.mutate(
      {
        sourceType: form.sourceType,
        sourceId: form.sourceId || null,
        type: form.type,
        title: form.title,
        description: form.description || null,
        rootCause: form.rootCause,
        owner: form.owner,
        dueDate: form.dueDate || null,
        severity: form.severity,
        tasks: [
          {
            action: form.task1,
            owner: form.task1Owner || form.owner,
            dueDate: form.dueDate || null,
          },
        ],
      },
      {
        onSuccess: (res) => {
          setForm({
            sourceType: 'MANUAL',
            sourceId: '',
            type: 'CORRECTIVE',
            title: '',
            description: '',
            rootCause: '',
            owner: '',
            dueDate: '',
            severity: 'major',
            task1: '',
            task1Owner: '',
          })
          setSelected(res?.id ?? null)
          setTab('list')
        },
      },
    )
  }

  function onSigned({ meaning, password }: { meaning?: string; password?: string }) {
    if (!signFor) return
    const signature = { meaning, password }
    const done = () => setSignFor(null)
    if (signFor.mode === 'verify') {
      verifyCapa.mutate(
        { id: signFor.id, ...verifyForm, signature },
        { onSuccess: (res) => { done(); setSelected(res?.id ?? signFor.id) } },
      )
    } else {
      closeCapa.mutate({ id: signFor.id, comment: verifyForm.comment, signature }, { onSuccess: done })
    }
  }

  const signError = signFor?.mode === 'verify' ? verifyCapa.error : closeCapa.error

  return (
    <div className="mx-auto max-w-[1400px] p-6">
      <PageHeader
        title="CAPA 闭环"
        desc="纠正与预防措施全生命周期 · 偏差 / 内审发现项 → 根因 → 行动项 → 有效性验证 → 电子签名关闭"
      />

      <div className="grid grid-cols-2 gap-4 lg:grid-cols-5">
        <StatCard icon={ClipboardList} label="CAPA 总数" value={metrics?.total ?? '—'} sub={`在办 ${metrics?.open ?? 0}`} />
        <StatCard
          icon={TriangleAlert}
          label="逾期"
          value={metrics?.overdue ?? '—'}
          tone="danger"
          sub="主要及以上自动产生报警"
        />
        <StatCard icon={CalendarClock} label="临期" value={metrics?.dueSoon ?? '—'} sub={`${metrics?.dueSoonDays ?? 3} 天内到期`} />
        <StatCard
          icon={ShieldAlert}
          label="内审发现项"
          value={metrics?.findingCount ?? '—'}
          sub={`未转 CAPA 的 major+ ${metrics?.openMajorFindings ?? 0}`}
        />
        <StatCard
          icon={CheckCircle2}
          label="CAPA 转化率"
          value={metrics?.capaConversionRate != null ? `${metrics.capaConversionRate}%` : '—'}
          sub={`内审 ${metrics?.auditCount ?? 0} 次`}
        />
      </div>

      <Tabs items={TABS} active={tab} onChange={setTab} className="mt-5" />

      {tab === 'list' && (
        <Card className="mt-4 p-4">
          <div className="mb-3 flex flex-wrap items-center gap-2">
            <ListChecks className="h-4 w-4 text-primary" />
            <span className="text-[13px] font-medium">CAPA 清单（{items.length}）</span>
            <select
              className="ml-auto h-8 rounded-md border border-border bg-background px-2 text-[13px]"
              value={statusFilter}
              onChange={(e) => setStatusFilter(e.target.value)}
            >
              <option value="">全部状态</option>
              {STATUS_ORDER.map((s) => (
                <option key={s} value={s}>
                  {STATUS_META[s]?.label ?? s}
                </option>
              ))}
            </select>
          </div>

          <div className="overflow-x-auto">
            <table className="w-full text-[13px]">
              <thead>
                <tr className="border-b border-border text-left text-xs text-muted-foreground">
                  <th className="py-2 pr-3">编号</th>
                  <th className="py-2 pr-3">来源</th>
                  <th className="py-2 pr-3">类型</th>
                  <th className="py-2 pr-3">标题</th>
                  <th className="py-2 pr-3">责任人</th>
                  <th className="py-2 pr-3">到期</th>
                  <th className="py-2 pr-3">行动项</th>
                  <th className="py-2 pr-3">状态</th>
                  <th className="py-2" />
                </tr>
              </thead>
              <tbody>
                {items.map((c) => (
                  <tr key={c.id} className="border-b border-border/60 align-middle">
                    <td className="num py-2 pr-3">{c.id}</td>
                    <td className="py-2 pr-3 text-xs">
                      {c.sourceLabel}
                      {c.sourceId ? <span className="num text-faint"> {c.sourceId}</span> : null}
                    </td>
                    <td className="py-2 pr-3 text-xs">{c.typeLabel}</td>
                    <td className="py-2 pr-3">
                      <Badge tone={SEV_TONE[c.severity ?? ''] ?? 'muted'} className="mr-1.5">
                        {c.severity}
                      </Badge>
                      {c.title}
                    </td>
                    <td className="py-2 pr-3">{c.owner}</td>
                    <td className="num py-2 pr-3 text-xs">
                      {c.dueDate}
                      <span className="ml-1 text-faint">({c.daysLeft}天)</span>
                    </td>
                    <td className="num py-2 pr-3 text-xs">
                      {c.taskSummary?.done ?? 0}/{c.taskSummary?.total ?? 0}
                    </td>
                    <td className="py-2 pr-3">
                      <StatusBadge capa={c} />
                    </td>
                    <td className="py-2 text-right">
                      <Button size="sm" variant="ghost" onClick={() => setSelected(selected === c.id ? null : c.id)}>
                        {selected === c.id ? '收起' : '详情'}
                      </Button>
                    </td>
                  </tr>
                ))}
                {!items.length && (
                  <tr>
                    <td colSpan={9} className="py-4 text-center text-xs text-faint">
                      暂无 CAPA
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>

          {selected && (
            <CapaDetail
              id={selected}
              canCreate={canCreate}
              canVerify={canVerify}
              transition={transition}
              addTask={addTask}
              doneTask={doneTask}
              updateCapa={updateCapa}
              taskForm={taskForm}
              setTaskForm={setTaskForm}
              verifyForm={verifyForm}
              setVerifyForm={setVerifyForm}
              onRequestSign={(mode) => setSignFor({ mode, id: selected })}
            />
          )}
        </Card>
      )}

      {tab === 'overdue' && (
        <div className="mt-4 grid gap-4 lg:grid-cols-2">
          <Card className="p-4">
            <div className="flex items-center gap-2 text-[13px] font-medium">
              <TriangleAlert className="h-4 w-4 text-danger" /> 已逾期（{overdueRes?.overdueCount ?? 0}）
            </div>
            <div className="mt-3 space-y-2">
              {(overdueRes?.overdue ?? []).map((c) => (
                <div key={c.id} className="flex items-center justify-between rounded border border-border px-3 py-2">
                  <div>
                    <div className="text-[13px]">
                      <span className="num">{c.id}</span> · {c.title}
                    </div>
                    <div className="text-xs text-faint">
                      责任人 {c.owner} · 到期 {c.dueDate} · 逾期 {Math.abs(c.daysLeft ?? 0)} 天
                    </div>
                  </div>
                  <Badge tone={SEV_TONE[c.severity ?? ''] ?? 'muted'}>{c.severity}</Badge>
                </div>
              ))}
              {!overdueRes?.overdue?.length && <div className="text-xs text-faint">无逾期 CAPA</div>}
            </div>
            {(overdueRes?.alarmRaised ?? 0) > 0 && (
              <div className="mt-3 text-xs text-danger">
                本次检查新产生 {overdueRes?.alarmRaised} 条逾期报警（同源同内容去重）
              </div>
            )}
          </Card>

          <div className="space-y-4">
            <Card className="p-4">
              <div className="flex items-center gap-2 text-[13px] font-medium">
                <CalendarClock className="h-4 w-4 text-warn" /> 临期（{overdueRes?.dueSoonCount ?? 0}，≤
                {overdueRes?.dueSoonDays ?? 3} 天）
              </div>
              <div className="mt-3 space-y-2">
                {(overdueRes?.dueSoon ?? []).map((c) => (
                  <div key={c.id} className="flex items-center justify-between rounded border border-border px-3 py-2">
                    <div className="text-[13px]">
                      <span className="num">{c.id}</span> · {c.title}
                    </div>
                    <Badge tone="warn">剩余 {c.daysLeft} 天</Badge>
                  </div>
                ))}
                {!overdueRes?.dueSoon?.length && <div className="text-xs text-faint">无临期 CAPA</div>}
              </div>
            </Card>

            <Card className="p-4">
              <div className="mb-3 text-[13px] font-medium">按责任人汇总（US2）</div>
              <table className="w-full text-[13px]">
                <thead>
                  <tr className="border-b border-border text-left text-xs text-muted-foreground">
                    <th className="py-2 pr-3">责任人</th>
                    <th className="py-2 pr-3">逾期</th>
                    <th className="py-2 pr-3">临期</th>
                    <th className="py-2 pr-3">正常</th>
                    <th className="py-2">合计</th>
                  </tr>
                </thead>
                <tbody>
                  {byOwner.map((o) => (
                    <tr key={o.owner} className="border-b border-border/60">
                      <td className="py-2 pr-3">{o.owner}</td>
                      <td className="num py-2 pr-3 text-danger">{o.overdue}</td>
                      <td className="num py-2 pr-3 text-warn">{o.dueSoon}</td>
                      <td className="num py-2 pr-3">{o.onTrack}</td>
                      <td className="num py-2">{o.total}</td>
                    </tr>
                  ))}
                  {!byOwner.length && (
                    <tr>
                      <td colSpan={5} className="py-3 text-center text-xs text-faint">
                        暂无在办 CAPA
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </Card>
          </div>
        </div>
      )}

      {tab === 'create' && (
        <Card className="mt-4 p-4">
          <div className="mb-3 flex items-center gap-2 text-[13px] font-medium">
            <PlusCircle className="h-4 w-4 text-primary" /> 新建 CAPA
            <span className="ml-2 text-xs font-normal text-faint">
              根因分析与至少一条行动项为必填（FR-2）
            </span>
          </div>
          {!canCreate && <div className="mb-3 text-xs text-danger">需要质检员及以上角色才能创建 CAPA</div>}
          <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
            <Field label="来源类型">
              <select
                className="h-9 w-full rounded-md border border-border bg-background px-2 text-[13px]"
                value={form.sourceType}
                onChange={(e) => setForm({ ...form, sourceType: e.target.value })}
              >
                {SOURCE_TYPES.map((s) => (
                  <option key={s.value} value={s.value}>
                    {s.label}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="来源单号（可选）">
              <Input
                value={form.sourceId}
                onChange={(e) => setForm({ ...form, sourceId: e.target.value })}
                placeholder="DEV-260915-001 / 发现项 ID"
              />
            </Field>
            <Field label="CAPA 类型">
              <select
                className="h-9 w-full rounded-md border border-border bg-background px-2 text-[13px]"
                value={form.type}
                onChange={(e) => setForm({ ...form, type: e.target.value })}
              >
                {CAPA_TYPES.map((t) => (
                  <option key={t.value} value={t.value}>
                    {t.label}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="严重度">
              <select
                className="h-9 w-full rounded-md border border-border bg-background px-2 text-[13px]"
                value={form.severity}
                onChange={(e) => setForm({ ...form, severity: e.target.value })}
              >
                {SEVERITIES.map((s) => (
                  <option key={s.value} value={s.value}>
                    {s.label}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="标题">
              <Input value={form.title} onChange={(e) => setForm({ ...form, title: e.target.value })} />
            </Field>
            <Field label="责任人">
              <Input
                value={form.owner}
                onChange={(e) => setForm({ ...form, owner: e.target.value })}
                placeholder="supervisor"
              />
            </Field>
            <Field label="到期日">
              <Input
                type="date"
                value={form.dueDate}
                onChange={(e) => setForm({ ...form, dueDate: e.target.value })}
              />
            </Field>
            <Field label="第一条行动项">
              <Input value={form.task1} onChange={(e) => setForm({ ...form, task1: e.target.value })} />
            </Field>
            <div className="col-span-2 lg:col-span-4">
              <Field label="问题描述">
                <Input
                  value={form.description}
                  onChange={(e) => setForm({ ...form, description: e.target.value })}
                />
              </Field>
            </div>
            <div className="col-span-2 lg:col-span-4">
              <Field label="根本原因（必填）">
                <textarea
                  className="min-h-[68px] w-full rounded-md border border-border bg-background px-2 py-1.5 text-[13px]"
                  value={form.rootCause}
                  onChange={(e) => setForm({ ...form, rootCause: e.target.value })}
                  placeholder="5Why / 鱼骨图结论，说明已发生或潜在原因"
                />
              </Field>
            </div>
          </div>
          <div className="mt-3 flex items-center gap-3">
            <Button
              size="sm"
              variant="primary"
              disabled={
                !canCreate ||
                !form.title ||
                !form.rootCause ||
                !form.owner ||
                !form.dueDate ||
                !form.task1 ||
                createCapa.isPending
              }
              onClick={submitCreate}
            >
              {createCapa.isPending ? '提交中…' : '创建 CAPA'}
            </Button>
            {createCapa.isError && <span className="text-xs text-danger">{createCapa.error?.message}</span>}
          </div>
        </Card>
      )}

      <SignatureDialog
        open={!!signFor}
        action={signFor?.mode === 'verify' ? 'CAPA_VERIFY' : 'CAPA_CLOSE'}
        recordType="CAPA"
        recordId={signFor?.id}
        headline={signFor?.mode === 'verify' ? 'CAPA 有效性验证' : 'CAPA 关闭批准'}
        hint={
          signFor?.mode === 'verify'
            ? '验证结论为「无效」时需填写说明，系统将退回并重开全部行动项。'
            : '关闭后 CAPA 不可再修改；该动作同时受 CAPA_CLOSE 签名策略约束。'
        }
        busy={verifyCapa.isPending || closeCapa.isPending}
        error={signError?.message}
        onCancel={() => setSignFor(null)}
        onConfirm={onSigned}
      />
    </div>
  )
}

function CapaDetail({
  id,
  canCreate,
  canVerify,
  transition,
  addTask,
  doneTask,
  updateCapa,
  taskForm,
  setTaskForm,
  verifyForm,
  setVerifyForm,
  onRequestSign,
}: {
  id: string | number
  canCreate: boolean
  canVerify: boolean
  transition: ReturnType<typeof useTransitionCapa>
  addTask: ReturnType<typeof useAddCapaTask>
  doneTask: ReturnType<typeof useDoneCapaTask>
  updateCapa: ReturnType<typeof useUpdateCapa>
  taskForm: { action: string; owner: string; dueDate: string }
  setTaskForm: (v: { action: string; owner: string; dueDate: string }) => void
  verifyForm: { verificationMethod: string; effectiveness: string; comment: string }
  setVerifyForm: (v: { verificationMethod: string; effectiveness: string; comment: string }) => void
  onRequestSign: (mode: SignMode) => void
}) {
  // 详情单独拉取（含行动项 / 状态流水 / 签名），列表只带行动项汇总
  const { data: c } = useCapaDetail(id)
  const [edit, setEdit] = useState<string | null>(null)

  const tasks = c?.tasks ?? []
  const events = c?.events ?? []

  const nextAction = useMemo(() => {
    if (!c) return null
    if (c.status === 'open') return { label: '启动执行', target: 'in_progress' }
    if (c.status === 'in_progress') return { label: '提交验证', target: 'pending_verify' }
    if (c.status === 'rejected') return { label: '重新执行', target: 'in_progress' }
    return null
  }, [c])

  if (!c) return <div className="mt-4 text-xs text-faint">加载 CAPA 详情…</div>

  return (
    <div className="mt-4 rounded border border-border p-4">
      <div className="flex flex-wrap items-center gap-2">
        <span className="num text-[13px] font-medium">{c.id}</span>
        <StatusBadge capa={c} />
        <Badge tone="muted">修订 v{c.recordRevision}</Badge>
        <span className="text-xs text-faint">
          {c.sourceLabel}
          {c.sourceId ? ` · ${c.sourceId}` : ''}
        </span>

        <div className="ml-auto flex flex-wrap items-center gap-2">
          {nextAction && canCreate && (
            <Button
              size="sm"
              variant="outline"
              disabled={transition.isPending}
              onClick={() => transition.mutate({ id, target: nextAction.target, comment: '页面操作' })}
            >
              {nextAction.label}
            </Button>
          )}
          {canVerify && c.status === 'pending_verify' && (
            <Button size="sm" variant="primary" onClick={() => onRequestSign('verify')}>
              验证（需签名）
            </Button>
          )}
          {canVerify && c.status === 'verified' && (
            <Button size="sm" variant="primary" onClick={() => onRequestSign('close')}>
              关闭（需签名）
            </Button>
          )}
        </div>
      </div>

      {transition.isError && <div className="mt-2 text-xs text-danger">{transition.error?.message}</div>}

      <div className="mt-3 grid gap-3 lg:grid-cols-2">
        <div className="rounded border border-border p-3">
          <div className="mb-2 text-[13px] font-medium">根本原因与处置</div>
          <div className="text-xs leading-relaxed text-muted-foreground">{c.rootCause}</div>
          {c.description && <div className="mt-2 text-xs text-faint">{c.description}</div>}
          {c.effectiveness && (
            <div className="mt-2 text-xs">
              验证方式 <b>{c.verificationMethodLabel}</b> · 结论{' '}
              <Badge tone={c.effectiveness === 'EFFECTIVE' ? 'success' : 'danger'}>
                {c.effectiveness === 'EFFECTIVE' ? '有效' : '无效'}
              </Badge>
              {c.verifyComment ? <span className="ml-2 text-faint">{c.verifyComment}</span> : null}
            </div>
          )}
          {c.rejectReason && <div className="mt-2 text-xs text-danger">退回原因：{c.rejectReason}</div>}
          {canCreate && c.status !== 'closed' && (
            <div className="mt-3 flex flex-wrap items-end gap-2">
              <Input
                className="max-w-[220px]"
                placeholder="修订责任人"
                value={edit ?? c.owner}
                onChange={(e) => setEdit(e.target.value)}
              />
              <Button
                size="sm"
                variant="ghost"
                disabled={updateCapa.isPending || !edit || edit === c.owner}
                onClick={() => updateCapa.mutate({ id, owner: edit })}
              >
                修订内容
              </Button>
              <span className="text-[11px] text-faint">修订会作废既有签名（FR-18）</span>
            </div>
          )}
        </div>

        <div className="rounded border border-border p-3">
          <div className="mb-2 flex items-center gap-2 text-[13px] font-medium">
            <ListChecks className="h-3.5 w-3.5 text-primary" /> 行动项（{tasks.filter((t) => t.done).length}/
            {tasks.length}）
          </div>
          <div className="space-y-2">
            {tasks.map((t) => (
              <div key={t.id} className="flex items-start justify-between gap-2 rounded border border-border/70 px-2 py-1.5">
                <div className="min-w-0">
                  <div className="text-[13px]">
                    #{t.seq} {t.action}
                  </div>
                  <div className="text-xs text-faint">
                    责任人 {t.owner} · 期限 {t.dueDate ?? '—'}
                    {t.evidence ? ` · 证据：${t.evidence}` : ''}
                  </div>
                </div>
                {t.done ? (
                  <Badge tone="success">已完成</Badge>
                ) : (
                  <Button
                    size="sm"
                    variant="ghost"
                    disabled={doneTask.isPending}
                    onClick={() =>
                      doneTask.mutate({ taskId: t.id, evidence: `由 ${t.owner} 于页面标记完成` })
                    }
                  >
                    标记完成
                  </Button>
                )}
              </div>
            ))}
            {!tasks.length && <div className="text-xs text-faint">暂无行动项</div>}
          </div>
          {canCreate && c.status !== 'closed' && (
            <div className="mt-3 flex flex-wrap items-end gap-2">
              <Input
                className="max-w-[220px]"
                placeholder="新增行动项"
                value={taskForm.action}
                onChange={(e) => setTaskForm({ ...taskForm, action: e.target.value })}
              />
              <Input
                className="max-w-[130px]"
                placeholder="责任人"
                value={taskForm.owner}
                onChange={(e) => setTaskForm({ ...taskForm, owner: e.target.value })}
              />
              <Button
                size="sm"
                variant="outline"
                disabled={addTask.isPending || !taskForm.action}
                onClick={() =>
                  addTask.mutate(
                    { id, action: taskForm.action, owner: taskForm.owner || undefined, dueDate: taskForm.dueDate || undefined },
                    { onSuccess: () => setTaskForm({ action: '', owner: '', dueDate: '' }) },
                  )
                }
              >
                追加
              </Button>
            </div>
          )}
        </div>
      </div>

      <div className="mt-3 grid gap-3 lg:grid-cols-2">
        <div className="rounded border border-border p-3">
          <div className="mb-2 text-[13px] font-medium">状态流水</div>
          <div className="space-y-1.5">
            {events.map((e, i) => (
              <div key={i} className="flex items-start gap-2 text-xs">
                <RotateCcw className="mt-0.5 h-3 w-3 text-faint" />
                <div>
                  <span className="num">
                    {e.fromStatus ?? '—'} → {e.toStatus}
                  </span>
                  <span className="ml-2 text-faint">{e.operator}</span>
                  <span className="ml-2 text-faint">{(e.createdAt ?? '').replace('T', ' ').slice(0, 19)}</span>
                  {e.comment && <div className="text-faint">{e.comment}</div>}
                </div>
              </div>
            ))}
            {!events.length && <div className="text-xs text-faint">暂无流水</div>}
          </div>
        </div>

        <div className="rounded border border-border p-3">
          {canVerify && c.status === 'pending_verify' && (
            <div className="mb-3 rounded border border-border/70 p-2">
              <div className="mb-2 flex items-center gap-1.5 text-[12px] font-medium">
                {verifyForm.effectiveness === 'EFFECTIVE' ? (
                  <CheckCircle2 className="h-3.5 w-3.5 text-success" />
                ) : (
                  <XCircle className="h-3.5 w-3.5 text-danger" />
                )}
                验证结论（将随签名一并提交）
              </div>
              <div className="flex flex-wrap gap-2">
                <select
                  className="h-8 rounded-md border border-border bg-background px-2 text-[12px]"
                  value={verifyForm.verificationMethod}
                  onChange={(e) => setVerifyForm({ ...verifyForm, verificationMethod: e.target.value })}
                >
                  {VERIFY_METHODS.map((m) => (
                    <option key={m.value} value={m.value}>
                      {m.label}
                    </option>
                  ))}
                </select>
                <select
                  className="h-8 rounded-md border border-border bg-background px-2 text-[12px]"
                  value={verifyForm.effectiveness}
                  onChange={(e) => setVerifyForm({ ...verifyForm, effectiveness: e.target.value })}
                >
                  <option value="EFFECTIVE">有效</option>
                  <option value="INEFFECTIVE">无效（退回重开行动项）</option>
                </select>
                <Input
                  className="max-w-[240px]"
                  placeholder="验证说明"
                  value={verifyForm.comment}
                  onChange={(e) => setVerifyForm({ ...verifyForm, comment: e.target.value })}
                />
              </div>
            </div>
          )}
          <SignatureList recordType="CAPA" recordId={id} />
        </div>
      </div>
    </div>
  )
}
