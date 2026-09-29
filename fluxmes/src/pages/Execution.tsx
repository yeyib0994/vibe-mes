import { useState, type ComponentType, type ReactNode } from 'react'
import {
  Activity,
  ClipboardList,
  Gauge,
  PlayCircle,
  TriangleAlert,
  UserPlus,
} from 'lucide-react'
import { Badge, Button, Card, Input, Progress, type BadgeTone } from '../components/ui'
import { PageHeader } from '../components/layout'
import {
  DOWNTIME_CATEGORY_TEXT,
  ORDER_STATUS_TEXT,
  ORDER_STATUS_TONE,
  useCreateWorkOrder,
  useDispatch,
  useDowntime,
  useDowntimeReasons,
  useOee,
  useOeePareto,
  useRecordDowntime,
  useReportStep,
  useRevokeDispatch,
  useTransitionOrder,
  useWorkOrder,
  useWorkOrders,
  type DowntimeReason,
} from '../api/execution'
import { hasRole } from '../lib/auth'

/**
 * Phase I · 生产执行。
 * 工单 → 派工（资质门禁）→ 报工（驱动批次进度）→ 停机（原因码 + 真实 OEE）。
 * 权限（C4）：下达/完工/派工 = 值班长+；开工/报工/停机录入 = 工艺员+；关闭 = 管理员。
 */

const TONE_BADGE: Record<string, BadgeTone> = { success: 'success', warning: 'warning', danger: 'danger', primary: 'primary', muted: 'muted' }

function StatCard({ icon: Icon, label, value, unit, sub }: {
  icon: ComponentType<{ className?: string }>
  label?: ReactNode
  value?: ReactNode
  unit?: ReactNode
  sub?: ReactNode
}) {
  return (
    <Card className="p-4" data-component="execution-stat">
      <div className="flex items-start justify-between">
        <span className="text-xs text-muted-foreground">{label}</span>
        <Icon className="h-4 w-4 text-muted-foreground" />
      </div>
      <div className="mt-2 flex items-baseline gap-1">
        <span className="num text-2xl font-semibold tabular-nums">{value ?? '—'}</span>
        {unit && <span className="text-xs text-muted-foreground">{unit}</span>}
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

/** 因子卡：dataSufficient=false 时明确显示「数据不足」而非 0（FR-21）。 */
function FactorCard({ label, value, sufficient, missing }: {
  label?: ReactNode
  value?: number | null
  sufficient?: boolean
  missing?: string | null
}) {
  return (
    <div className="rounded-lg border border-border p-3">
      <div className="flex items-center justify-between">
        <span className="text-xs text-muted-foreground">{label}</span>
        {!sufficient && <Badge tone="warning">数据不足</Badge>}
      </div>
      <div className="mt-1.5 num text-xl font-semibold tabular-nums">
        {value == null ? '—' : `${value}%`}
      </div>
      {!sufficient && missing && (
        <div className="mt-1 line-clamp-2 text-[11px] text-faint" title={missing}>
          {missing}
        </div>
      )}
    </div>
  )
}

function toIso(localValue: string): string | null {
  if (!localValue) return null
  // datetime-local 为本地时间（Asia/Shanghai），补 +08:00 供后端解析
  return `${localValue}:00+08:00`
}

export default function Execution({ session }: { session?: unknown }) {
  void session
  const [statusFilter, setStatusFilter] = useState('')
  const [selected, setSelected] = useState<string | number>('')
  const canOperate = hasRole('OPERATOR')
  const canSupervise = hasRole('SUPERVISOR')
  const isAdmin = hasRole('ADMIN')

  const { data: list, isLoading } = useWorkOrders(statusFilter ? { status: statusFilter } : {})
  const orders = list?.orders ?? []
  const { data: detail } = useWorkOrder(selected)
  const { data: oee } = useOee({})
  const { data: pareto } = useOeePareto({})
  const { data: reasons } = useDowntimeReasons()
  const { data: downtime } = useDowntime({})

  const createOrder = useCreateWorkOrder()
  const transition = useTransitionOrder()
  const dispatch = useDispatch()
  const revoke = useRevokeDispatch()
  const report = useReportStep()
  const recordDowntime = useRecordDowntime()

  const [orderForm, setOrderForm] = useState({ batchId: '', planQty: '', shift: 'DAY', planStart: '', planEnd: '' })
  const [dispatchForm, setDispatchForm] = useState({ username: '', roleInOrder: 'OPERATOR', capability: 'WEIGHING' })
  const [reportForm, setReportForm] = useState({
    stepNo: '', stepName: '', equipment: '', startedAt: '', finishedAt: '',
    inputQty: '', goodQty: '', scrapQty: '', stdMinutes: '', reviewer: '',
  })
  const [stopForm, setStopForm] = useState({
    equipment: '', reasonCode: 'MECH', startedAt: '', endedAt: '', description: '',
  })
  const [message, setMessage] = useState<{ tone: string; text: ReactNode } | null>(null)

  const notify = (tone: string, text: ReactNode) => setMessage({ tone, text })

  const runningCount = orders.filter((o) => o.status === 'RUNNING').length
  const finishedCount = orders.filter((o) => o.status === 'FINISHED').length

  const submitOrder = () => {
    createOrder.mutate(
      {
        batchId: orderForm.batchId,
        planQty: Number(orderForm.planQty),
        shift: orderForm.shift,
        planStart: toIso(orderForm.planStart),
        planEnd: toIso(orderForm.planEnd),
      },
      {
        onSuccess: (r) => {
          notify('success', `工单 ${r.id} 已创建（继承配方版本 ${r.recipeVersion || '—'}）`)
          setOrderForm({ batchId: '', planQty: '', shift: 'DAY', planStart: '', planEnd: '' })
        },
        onError: (e: Error) => notify('danger', e.message),
      },
    )
  }

  const doTransition = (id: string | number, target: string) => {
    transition.mutate(
      { id, target },
      {
        onSuccess: (r) => {
          const extra = r.confirmNeeded ? ` ⚠ ${r.confirmNote}` : ''
          notify(r.confirmNeeded ? 'warning' : 'success', `工单 ${id} → ${ORDER_STATUS_TEXT[r.order?.status ?? ''] ?? ''}${extra}`)
        },
        onError: (e: Error) => notify('danger', e.message),
      },
    )
  }

  const submitDispatch = () => {
    if (!selected) return notify('warning', '请先选择工单')
    dispatch.mutate(
      { id: selected, ...dispatchForm },
      {
        onSuccess: () => {
          notify('success', `已派工：${dispatchForm.username}`)
          setDispatchForm({ username: '', roleInOrder: 'OPERATOR', capability: 'WEIGHING' })
        },
        onError: (e: Error) => notify('danger', e.message),
      },
    )
  }

  const submitReport = () => {
    if (!selected) return notify('warning', '请先选择工单')
    report.mutate(
      {
        id: selected,
        stepNo: reportForm.stepNo ? Number(reportForm.stepNo) : null,
        stepName: reportForm.stepName || null,
        equipment: reportForm.equipment || null,
        startedAt: toIso(reportForm.startedAt),
        finishedAt: toIso(reportForm.finishedAt),
        inputQty: Number(reportForm.inputQty || 0),
        goodQty: Number(reportForm.goodQty || 0),
        scrapQty: Number(reportForm.scrapQty || 0),
        stdMinutes: reportForm.stdMinutes ? Number(reportForm.stdMinutes) : null,
        reviewer: reportForm.reviewer || null,
      },
      {
        onSuccess: (r) => {
          notify(r.idempotent ? 'warning' : 'success',
            r.idempotent ? '重复提交，已返回既有报工记录（幂等）' : `报工成功，批次进度 ${r.order?.progressPct ?? 0}%`)
          setReportForm({
            stepNo: '', stepName: '', equipment: '', startedAt: '', finishedAt: '',
            inputQty: '', goodQty: '', scrapQty: '', stdMinutes: '', reviewer: '',
          })
        },
        onError: (e: Error) => notify('danger', e.message),
      },
    )
  }

  const submitDowntime = () => {
    recordDowntime.mutate(
      {
        orderId: selected || null,
        equipment: stopForm.equipment || null,
        reasonCode: stopForm.reasonCode,
        startedAt: toIso(stopForm.startedAt),
        endedAt: toIso(stopForm.endedAt),
        description: stopForm.description || null,
      },
      {
        onSuccess: (r) =>
          notify(r.merged ? 'warning' : 'success',
            r.merged
              ? `时间窗重叠，已合并至既有停机 #${r.downtime.id}`
              : `停机已记录（${DOWNTIME_CATEGORY_TEXT[r.downtime.category ?? ''] || '—'}${r.alarmId ? `，触发报警 ${r.alarmId}` : ''}）`),
        onError: (e: Error) => notify('danger', e.message),
      },
    )
  }

  return (
    <div className="p-5">
      <PageHeader
        title="生产执行"
        description="工单派工 · 工序报工 · 停机原因码 · 真实 OEE（可用率×性能率×良品率）"
      />

      {message && (
        <div className="mb-4">
          <Badge tone={TONE_BADGE[message.tone] || 'muted'} pulse={message.tone === 'danger'}>
            {message.text}
          </Badge>        </div>
      )}

      <div className="mb-5 grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard icon={ClipboardList} label="执行中工单" value={runningCount} unit="张" sub={`共 ${orders.length} 张在列`} />
        <StatCard icon={Activity} label="已完工工单" value={finishedCount} unit="张" />
        <StatCard
          icon={Gauge}
          label="真实 OEE"
          value={oee?.oee == null ? '—' : oee.oee}
          unit={oee?.oee == null ? '' : '%'}
          sub={oee?.dataSufficient === false ? '数据不足，不采用兜底值' : oee?.note || '可用率×性能率×良品率'}
        />
        <StatCard
          icon={TriangleAlert}
          label="计划外停机"
          value={oee?.unplannedStopMinutes ?? '—'}
          unit="min"
          sub={oee?.plannedMinutesDerived ? '计划工时由作业+停机推导' : '近 7 天窗口（含工单计划工时）'}
        />
      </div>

      <Card className="mb-5 p-4" data-component="oee-factors">
        <div className="mb-3 flex items-center justify-between">
          <span className="text-sm font-medium">OEE 三因子分解</span>
          <span className="text-xs text-faint">{oee?.formula}</span>
        </div>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
          <FactorCard label="可用率" value={oee?.availability} sufficient={oee?.availability != null} missing={oee?.missing} />
          <FactorCard label="性能率" value={oee?.performance} sufficient={oee?.performance != null} missing={oee?.missing} />
          <FactorCard label="良品率" value={oee?.quality} sufficient={oee?.quality != null} missing={oee?.missing} />
        </div>
        {oee?.performanceOverrun && (
          <div className="mt-2 text-xs text-warn">性能率 &gt; 100%，请复核标准工时维护</div>
        )}
      </Card>

      <div className="grid grid-cols-1 gap-5 xl:grid-cols-[minmax(0,1fr)_380px]">
        <Card className="min-w-0 p-4" data-component="work-order-list">
          <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
            <span className="text-sm font-medium">工单</span>
            <div className="flex items-center gap-2">
              <select
                className="rounded-md border border-border bg-transparent px-2 py-1 text-xs"
                value={statusFilter}
                onChange={(e) => setStatusFilter(e.target.value)}
              >
                <option value="">全部状态</option>
                <option value="CREATED">已创建</option>
                <option value="RELEASED">已下达</option>
                <option value="RUNNING">执行中</option>
                <option value="FINISHED">已完工</option>
                <option value="CLOSED">已关闭</option>
              </select>
            </div>
          </div>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-border text-left text-xs text-muted-foreground">
                  <th className="py-2 pr-3">工单号</th>
                  <th className="py-2 pr-3">批次</th>
                  <th className="py-2 pr-3">计划量</th>
                  <th className="py-2 pr-3">状态</th>
                  <th className="py-2 pr-3">进度</th>
                  <th className="py-2 pr-3">操作</th>
                </tr>
              </thead>
              <tbody>
                {isLoading && (
                  <tr><td className="py-4 text-xs text-faint" colSpan={6}>加载中…</td></tr>
                )}
                {!isLoading && orders.length === 0 && (
                  <tr><td className="py-4 text-xs text-faint" colSpan={6}>暂无工单（值班长可在下方新建，或由种子回填）</td></tr>
                )}
                {orders.map((o) => (
                  <tr
                    key={o.id}
                    className={`cursor-pointer border-b border-border/60 ${selected === o.id ? 'bg-primary-soft/40' : ''}`}
                    onClick={() => setSelected(o.id)}
                  >
                    <td className="num py-2 pr-3">{o.id}</td>
                    <td className="py-2 pr-3 text-xs">{o.batchId}</td>
                    <td className="num py-2 pr-3">{o.planQty} {o.unit}</td>
                    <td className="py-2 pr-3">
                      <Badge tone={TONE_BADGE[ORDER_STATUS_TONE[o.status ?? ''] ?? 'muted'] ?? 'muted'}>
                        {ORDER_STATUS_TEXT[o.status ?? ''] || o.status}
                      </Badge>
                    </td>
                    <td className="py-2 pr-3">
                      <div className="flex items-center gap-2">
                        <Progress value={o.progressPct} className="w-20" />
                        <span className="num text-xs">{o.progressPct}%</span>
                      </div>
                    </td>
                    <td className="py-2 pr-3">
                      <div className="flex flex-wrap gap-1">
                        {o.status === 'CREATED' && canSupervise && (
                          <Button size="sm" onClick={(e) => { e.stopPropagation(); doTransition(o.id, 'release') }}>下达</Button>
                        )}
                        {o.status === 'RELEASED' && canOperate && (
                          <Button size="sm" onClick={(e) => { e.stopPropagation(); doTransition(o.id, 'start') }}>开工</Button>
                        )}
                        {o.status === 'RUNNING' && canSupervise && (
                          <Button size="sm" onClick={(e) => { e.stopPropagation(); doTransition(o.id, 'finish') }}>完工</Button>
                        )}
                        {o.status === 'FINISHED' && isAdmin && (
                          <Button size="sm" onClick={(e) => { e.stopPropagation(); doTransition(o.id, 'close') }}>关闭</Button>
                        )}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>

        <div className="flex flex-col gap-5">
          {canSupervise && (
            <Card className="p-4" data-component="create-order">
              <div className="mb-3 flex items-center gap-2 text-sm font-medium">
                <PlayCircle className="h-4 w-4" /> 新建工单
              </div>
              <div className="grid grid-cols-2 gap-3">
                <Field label="批次号"><Input value={orderForm.batchId} onChange={(e) => setOrderForm({ ...orderForm, batchId: e.target.value })} placeholder="B-260915-001" /></Field>
                <Field label="计划量（kg）"><Input value={orderForm.planQty} onChange={(e) => setOrderForm({ ...orderForm, planQty: e.target.value })} placeholder="40000" /></Field>
                <Field label="计划开工"><Input type="datetime-local" value={orderForm.planStart} onChange={(e) => setOrderForm({ ...orderForm, planStart: e.target.value })} /></Field>
                <Field label="计划完工"><Input type="datetime-local" value={orderForm.planEnd} onChange={(e) => setOrderForm({ ...orderForm, planEnd: e.target.value })} /></Field>
                <Field label="班次">
                  <select className="w-full rounded-md border border-border bg-transparent px-2 py-1.5 text-sm" value={orderForm.shift} onChange={(e) => setOrderForm({ ...orderForm, shift: e.target.value })}>
                    <option value="DAY">白班</option>
                    <option value="NIGHT">夜班</option>
                  </select>
                </Field>
              </div>
              <Button className="mt-3 w-full" variant="primary" disabled={createOrder.isPending} onClick={submitOrder}>
                创建工单
              </Button>
            </Card>
          )}

          {canSupervise && (
            <Card className="p-4" data-component="dispatch">
              <div className="mb-3 flex items-center gap-2 text-sm font-medium">
                <UserPlus className="h-4 w-4" /> 派工
              </div>
              {!selected && <div className="mb-2 text-xs text-faint">请先在上方选择工单</div>}
              <div className="grid grid-cols-2 gap-3">
                <Field label="人员账号"><Input value={dispatchForm.username} onChange={(e) => setDispatchForm({ ...dispatchForm, username: e.target.value })} placeholder="operator" /></Field>
                <Field label="角色">
                  <select className="w-full rounded-md border border-border bg-transparent px-2 py-1.5 text-sm" value={dispatchForm.roleInOrder} onChange={(e) => setDispatchForm({ ...dispatchForm, roleInOrder: e.target.value })}>
                    <option value="OPERATOR">操作</option>
                    <option value="REVIEWER">复核</option>
                  </select>
                </Field>
                <Field label="能力项">
                  <select className="w-full rounded-md border border-border bg-transparent px-2 py-1.5 text-sm" value={dispatchForm.capability} onChange={(e) => setDispatchForm({ ...dispatchForm, capability: e.target.value })}>
                    <option value="WEIGHING">配料称量</option>
                    <option value="CCP_MONITOR">CCP 监控</option>
                    <option value="RELEASE">成品放行</option>
                    <option value="BATCH_REVIEW">批记录复核</option>
                    <option value="SANITATION">清场作业</option>
                  </select>
                </Field>
              </div>
              <Button className="mt-3 w-full" disabled={!selected || dispatch.isPending} onClick={submitDispatch}>
                派工（校验资质与健康证）
              </Button>
              {detail && (detail.assignments?.length ?? 0) > 0 && (
                <div className="mt-3 space-y-1.5">
                  {detail.assignments?.map((a) => (
                    <div key={a.id} className="flex items-center justify-between rounded-md border border-border px-2 py-1.5 text-xs">
                      <span>
                        {a.displayName || a.username}
                        <span className="ml-1 text-faint">{a.roleInOrder} · {a.capability}</span>
                      </span>
                      {a.status === 'ACTIVE' ? (
                        <Button size="sm" onClick={() => revoke.mutate({ id: selected, aid: a.id, reason: '手动撤销' }, {
                          onSuccess: () => notify('success', `已撤销 ${a.username} 的派工`),
                          onError: (e: Error) => notify('danger', e.message),
                        })}>撤销</Button>
                      ) : (
                        <Badge tone="muted">已撤销</Badge>
                      )}
                    </div>
                  ))}
                </div>
              )}
            </Card>
          )}

          {canOperate && (
            <Card className="p-4" data-component="step-report">
              <div className="mb-3 text-sm font-medium">工序报工</div>
              {!selected && <div className="mb-2 text-xs text-faint">请先在上方选择工单</div>}
              <div className="grid grid-cols-2 gap-3">
                <Field label="工序号"><Input value={reportForm.stepNo} onChange={(e) => setReportForm({ ...reportForm, stepNo: e.target.value })} placeholder="2" /></Field>
                <Field label="工序名"><Input value={reportForm.stepName} onChange={(e) => setReportForm({ ...reportForm, stepName: e.target.value })} placeholder="发酵" /></Field>
                <Field label="设备"><Input value={reportForm.equipment} onChange={(e) => setReportForm({ ...reportForm, equipment: e.target.value })} placeholder="F-101" /></Field>
                <Field label="标准工时（min）"><Input value={reportForm.stdMinutes} onChange={(e) => setReportForm({ ...reportForm, stdMinutes: e.target.value })} placeholder="120" /></Field>
                <Field label="开工时间"><Input type="datetime-local" value={reportForm.startedAt} onChange={(e) => setReportForm({ ...reportForm, startedAt: e.target.value })} /></Field>
                <Field label="完工时间"><Input type="datetime-local" value={reportForm.finishedAt} onChange={(e) => setReportForm({ ...reportForm, finishedAt: e.target.value })} /></Field>
                <Field label="投入（kg）"><Input value={reportForm.inputQty} onChange={(e) => setReportForm({ ...reportForm, inputQty: e.target.value })} /></Field>
                <Field label="合格（kg）"><Input value={reportForm.goodQty} onChange={(e) => setReportForm({ ...reportForm, goodQty: e.target.value })} /></Field>
                <Field label="废次品（kg）"><Input value={reportForm.scrapQty} onChange={(e) => setReportForm({ ...reportForm, scrapQty: e.target.value })} /></Field>
                <Field label="复核人（关键工序必填）"><Input value={reportForm.reviewer} onChange={(e) => setReportForm({ ...reportForm, reviewer: e.target.value })} placeholder="qc" /></Field>
              </div>
              <Button className="mt-3 w-full" variant="primary" disabled={!selected || report.isPending} onClick={submitReport}>
                提交报工
              </Button>
              {detail && (detail.reports?.length ?? 0) > 0 && (
                <div className="mt-3 max-h-40 space-y-1.5 overflow-y-auto">
                  {detail.reports?.map((r) => (
                    <div key={r.id} className="rounded-md border border-border px-2 py-1.5 text-xs">
                      <span className="num">#{r.stepNo} {r.stepName}</span>
                      <span className="ml-2 text-faint">
                        合格 {r.goodQty} / 废次 {r.scrapQty} · 良品率 {r.yieldPct ?? '—'}%
                      </span>
                      {r.critical && <Badge tone="warning" className="ml-2">关键工序 · {r.reviewer || '待复核'}</Badge>}
                    </div>
                  ))}
                </div>
              )}
            </Card>
          )}

          {canOperate && (
            <Card className="p-4" data-component="downtime-entry">
              <div className="mb-3 flex items-center gap-2 text-sm font-medium">
                <TriangleAlert className="h-4 w-4" /> 停机录入
              </div>
              <div className="grid grid-cols-2 gap-3">
                <Field label="设备"><Input value={stopForm.equipment} onChange={(e) => setStopForm({ ...stopForm, equipment: e.target.value })} placeholder="C-601" /></Field>
                <Field label="原因码">
                  <select className="w-full rounded-md border border-border bg-transparent px-2 py-1.5 text-sm" value={stopForm.reasonCode} onChange={(e) => setStopForm({ ...stopForm, reasonCode: e.target.value })}>
                    {(reasons ?? []).map((r: DowntimeReason) => (
                      <option key={r.code} value={r.code}>
                        {r.name}{r.planned ? '（计划）' : '（非计划）'}
                      </option>
                    ))}
                  </select>
                </Field>
                <Field label="开始时间"><Input type="datetime-local" value={stopForm.startedAt} onChange={(e) => setStopForm({ ...stopForm, startedAt: e.target.value })} /></Field>
                <Field label="结束时间"><Input type="datetime-local" value={stopForm.endedAt} onChange={(e) => setStopForm({ ...stopForm, endedAt: e.target.value })} /></Field>
              </div>
              <div className="mt-3">
                <Field label="说明"><Input value={stopForm.description} onChange={(e) => setStopForm({ ...stopForm, description: e.target.value })} placeholder="搅拌电机过热跳停" /></Field>
              </div>
              <Button className="mt-3 w-full" disabled={recordDowntime.isPending} onClick={submitDowntime}>
                记录停机
              </Button>
            </Card>
          )}
        </div>
      </div>

      <div className="mt-5 grid grid-cols-1 gap-5 lg:grid-cols-2">
        <Card className="p-4" data-component="downtime-pareto">
          <div className="mb-3 text-sm font-medium">停机原因帕累托（近 30 天）</div>
          {(pareto?.items || []).length === 0 && <div className="text-xs text-faint">暂无停机记录</div>}
          <div className="space-y-2">
            {(pareto?.items || []).map((it) => (
              <div key={it.reasonCode}>
                <div className="flex items-center justify-between text-xs">
                  <span>
                    {it.reasonName}
                    <span className="ml-1.5 text-faint">{it.planned ? '计划' : '非计划'} · {it.count} 次</span>
                  </span>
                  <span className="num">{it.minutes} min（{it.sharePct}% / 累计 {it.cumPct}%）</span>
                </div>
                <Progress value={it.sharePct} className="mt-1" />
              </div>
            ))}
          </div>
        </Card>

        <Card className="p-4" data-component="downtime-recent">
          <div className="mb-3 text-sm font-medium">最近停机事件</div>
          {(downtime || []).length === 0 && <div className="text-xs text-faint">暂无停机记录</div>}
          <div className="max-h-64 space-y-1.5 overflow-y-auto">
            {(downtime || []).slice(0, 12).map((d) => (
              <div key={d.id} className="rounded-md border border-border px-2 py-1.5 text-xs">
                <div className="flex items-center justify-between">
                  <span>{d.equipment || '—'} · {d.reasonName}</span>
                  <Badge tone={d.planned ? 'muted' : 'danger'}>{d.planned ? '计划' : '非计划'}</Badge>
                </div>
                <div className="mt-0.5 text-faint">
                  {d.durationMin ?? '—'} min · {(d.startedAt || '').slice(0, 16).replace('T', ' ')}
                  {d.mergedFrom ? ` · ${d.mergedFrom}` : ''}
                </div>
              </div>
            ))}
          </div>
        </Card>
      </div>
    </div>
  )
}
