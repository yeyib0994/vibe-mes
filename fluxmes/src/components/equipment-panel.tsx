import { useState } from 'react'
import {
  CalendarClock,
  CheckCircle2,
  ClipboardList,
  Gauge,
  Loader2,
  Plus,
  ShieldAlert,
  Timer,
  Wrench,
} from 'lucide-react'
import { Badge, Button, Card, Input } from './ui'
import {
  EQUIPMENT_STATUSES,
  ORDER_TYPES,
  useChangeEquipmentStatus,
  useCompleteMaintenanceOrder,
  useCreateMaintenanceOrder,
  useEquipmentAlerts,
  useEquipmentOee,
  useMaintenanceOrders,
} from '../api/equipment'
import { getSession } from '../lib/auth'
import { cn } from '../lib/utils'

/**
 * H1 · 设备保全面板。
 *
 * 覆盖 equipment-management T5–T10：
 * - 状态变更（写事件流水与审计，工艺员+）
 * - 保养到期与校准超期预警（FR-6 / FR-8）
 * - 维护工单创建与完成，完成时顺延保养周期，校准类工单同时续期校准（FR-7）
 * - OEE 三因子分解（可用率取自设备状态事件停机时长，FR-9）
 */

type OrderStatus = 'OPEN' | 'IN_PROGRESS' | 'DONE' | 'CANCELLED'
type Tone = 'warn' | 'primary' | 'accent' | 'muted' | 'danger'
const STATUS_TONE: Record<OrderStatus, Tone> = {
  OPEN: 'warn',
  IN_PROGRESS: 'primary',
  DONE: 'accent',
  CANCELLED: 'muted',
}

function Row({ icon: Icon, label, children }: { icon?: React.ComponentType<{ className?: string }>; label?: string; children?: React.ReactNode }) {
  return (
    <div className="flex items-center gap-2">
      {Icon && <Icon className="h-3.5 w-3.5 shrink-0 text-faint" />}
      <span className="label-tech shrink-0">{label}</span>
      <span className="num ml-auto truncate text-[12px]">{children}</span>
    </div>
  )
}

export function MaintenancePanel({ code }: { code?: string }) {
  const session = getSession()
  const canOperate = ['OPERATOR', 'SUPERVISOR', 'ADMIN'].includes(session?.role ?? '')

  const { data: alerts } = useEquipmentAlerts(7)
  const { data: orders = [] } = useMaintenanceOrders({ equipmentCode: code })
  const { data: oee } = useEquipmentOee(code)
  const [toStatus, setToStatus] = useState('RUNNING')
  const [reason, setReason] = useState('')
  const [showOrder, setShowOrder] = useState(false)
  const [orderType, setOrderType] = useState('PREVENTIVE')
  const [planDate, setPlanDate] = useState('')
  const [orderRemark, setOrderRemark] = useState('')
  const [runtimeHours, setRuntimeHours] = useState('')
  const [msg, setMsg] = useState<{ tone: 'accent' | 'danger'; text: string } | null>(null)

  const statusMut = useChangeEquipmentStatus()
  const createMut = useCreateMaintenanceOrder()
  const doneMut = useCompleteMaintenanceOrder()
  const busy = statusMut.isPending || createMut.isPending || doneMut.isPending

  const ownAlert = alerts?.maintenance?.find((m) => m.code === code)
  const ownCalibration = alerts?.calibration?.find((c) => c.code === code)

  const notify = (tone: 'accent' | 'danger', text: string) => setMsg({ tone, text })

  return (
    <div className="space-y-4" data-component="equipment-maintenance">
      {/* 保养与校准状态 */}
      <Card className="card-pad" data-component="equip-compliance">
        <div className="mb-2 flex items-center justify-between">
          <div className="text-[15px] font-semibold tracking-tight">保养与校准合规</div>
          <Badge tone={ownCalibration ? 'danger' : ownAlert?.overdue ? 'warn' : 'accent'} dot={false}>
            {ownCalibration ? '校准超期' : ownAlert?.overdue ? '保养逾期' : '合规'}
          </Badge>
        </div>
        <div className="space-y-2">
          <Row icon={CalendarClock} label="下次保养">
            {ownAlert ? `${ownAlert.nextMaintenanceAt}（${ownAlert.daysRemaining} 天）` : '—'}
          </Row>
          <Row icon={Timer} label="保养周期">
            {ownAlert?.cycleDays ?? '—'} 天
          </Row>
          <Row icon={ShieldAlert} label="校准有效期">
            {ownCalibration
              ? `${ownCalibration.calibrationDueAt}（已超期 ${ownCalibration.daysOverdue} 天）`
              : '— · 在有效期内'}
          </Row>
        </div>
        {ownCalibration && (
          <div className="mt-3 rounded-md border border-danger/30 bg-danger/5 px-3 py-2 text-[12px] text-danger">
            {ownCalibration.calibrationItem} 已超期，该设备不可被新批次引用，其检验数据不得作为放行依据（C5 /
            FR-8）。须完成计量校准工单后自动解除。
          </div>
        )}
      </Card>

      {/* OEE 三因子 */}
      <Card className="card-pad" data-component="equip-oee">
        <div className="mb-2 flex items-center justify-between">
          <div className="text-[15px] font-semibold tracking-tight">OEE 三因子</div>
          <Gauge className="h-4 w-4 text-faint" />
        </div>
        {oee ? (
          <>
            <div className="grid grid-cols-4 gap-2">
              {[
                ['可用率', oee.availability],
                ['性能率', oee.performance],
                ['良品率', oee.quality],
                ['OEE', oee.oee],
              ].map(([label, v]) => (
                <div key={label} className="rounded-md bg-muted px-2.5 py-2">
                  <div className="label-tech">{label}</div>
                  <div className="num mt-1 text-[15px] font-semibold">{v}%</div>
                </div>
              ))}
            </div>
            <div className="num mt-2 text-[11px] text-faint">
              停机 {oee.downtimeHours} h · 事件 {oee.eventCount} 条 · {oee.note}
            </div>
          </>
        ) : (
          <div className="text-xs text-faint">加载 OEE…</div>
        )}
      </Card>

      {/* 维护工单 */}
      <Card className="card-pad" data-component="equip-orders">
        <div className="mb-2 flex items-center justify-between">
          <div className="flex items-center gap-2">
            <div className="text-[15px] font-semibold tracking-tight">维护工单</div>
            <Badge tone={orders.length ? 'primary' : 'muted'} dot={false}>{orders.length} 单</Badge>
          </div>
          {canOperate && (
            <Button size="sm" variant="ghost" onClick={() => setShowOrder((v) => !v)}>
              {showOrder ? '收起' : <><Plus className="h-3.5 w-3.5" /> 新建工单</>}
            </Button>
          )}
        </div>

        {showOrder && (
          <div className="mb-3 grid gap-2 rounded-md border border-border bg-muted/30 p-3 lg:grid-cols-4">
            <select
              className="h-8 rounded-md border border-border bg-background px-2 text-xs"
              value={orderType}
              onChange={(e) => setOrderType(e.target.value)}
            >
              {ORDER_TYPES.map((t) => (
                <option key={t.code} value={t.code}>{t.label}</option>
              ))}
            </select>
            <Input type="date" value={planDate} onChange={(e) => setPlanDate(e.target.value)} className="h-8 text-xs" />
            <Input
              placeholder="备注（可选）"
              value={orderRemark}
              onChange={(e) => setOrderRemark(e.target.value)}
              className="h-8 text-xs"
            />
            <Button
              size="sm"
              variant="primary"
              disabled={busy}
              onClick={async () => {
                try {
                  await createMut.mutateAsync({
                    equipmentCode: code ?? '',
                    type: orderType,
                    planDate: planDate || undefined,
                    remark: orderRemark || undefined,
                  })
                  notify('accent', '工单已创建')
                  setShowOrder(false)
                  setOrderRemark('')
                } catch (e) {
                  notify('danger', String((e as Error)?.message ?? e))
                }
              }}
            >
              {createMut.isPending && <Loader2 className="h-3.5 w-3.5 animate-spin" />} 提交
            </Button>
          </div>
        )}

        {orders.length ? (
          <div>
            {orders.map((o) => (
              <div key={o.id} className="border-b border-border py-2.5 last:border-0">
                <div className="flex items-center gap-2">
                  <Wrench className="h-3.5 w-3.5 shrink-0 text-faint" />
                  <span className="num text-xs text-muted-foreground">{o.id}</span>
                  <Badge tone="muted" dot={false}>
                    {ORDER_TYPES.find((t) => t.code === o.type)?.label ?? o.type}
                  </Badge>
                  <Badge tone={STATUS_TONE[o.status as OrderStatus] ?? 'muted'} dot={false}>{o.status}</Badge>
                  {o.status !== 'DONE' && canOperate ? (
                    <Button
                      size="sm"
                      variant="ghost"
                      className="ml-auto"
                      disabled={busy}
                      onClick={async () => {
                        const hours = window.prompt('登记完工后的累计运行小时（留空表示不变）', runtimeHours)
                        if (hours === null) return
                        try {
                          const res = await doneMut.mutateAsync({
                            id: o.id,
                            runtimeHours: hours === '' ? undefined : Number(hours),
                          })
                          notify('accent', `工单完成，下次保养 ${res?.equipment?.maint?.next ?? '已顺延'}`)
                        } catch (e) {
                          notify('danger', String((e as Error)?.message ?? e))
                        }
                      }}
                    >
                      <CheckCircle2 className="h-3.5 w-3.5" /> 完成
                    </Button>
                  ) : (
                    <span className="num ml-auto text-[11px] text-faint">{o.doneAt ? `完成 ${o.doneAt.slice(0, 10)}` : ''}</span>
                  )}
                </div>
                <div className="num mt-1 pl-5 text-[11px] text-muted-foreground">
                  计划 {o.planDate ?? '—'} · 建单 {o.createdBy ?? '—'}
                  {o.runtimeAfter != null && ` · 运行 ${o.runtimeAfter} h`}
                  {o.remark ? ` · ${o.remark}` : ''}
                </div>
              </div>
            ))}
          </div>
        ) : (
          <div className="flex h-[88px] flex-col items-center justify-center gap-1.5 text-center text-xs text-faint">
            <ClipboardList className="h-5 w-5" />
            该设备暂无维护工单
          </div>
        )}
      </Card>

      {/* 状态变更 */}
      <Card className="card-pad" data-component="equip-status">
        <div className="mb-2 text-[15px] font-semibold tracking-tight">状态变更</div>
        {canOperate ? (
          <div className="grid gap-2 lg:grid-cols-4">
            <select
              className="h-8 rounded-md border border-border bg-background px-2 text-xs"
              value={toStatus}
              onChange={(e) => setToStatus(e.target.value)}
            >
              {EQUIPMENT_STATUSES.map((s) => (
                <option key={s.code} value={s.code}>{s.label}</option>
              ))}
            </select>
            <Input
              placeholder="变更原因"
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              className="h-8 text-xs lg:col-span-2"
            />
            <Button
              size="sm"
              variant="primary"
              disabled={busy}
              onClick={async () => {
                try {
                  await statusMut.mutateAsync({ code: code ?? '', toStatus, reason: reason || undefined })
                  notify('accent', `状态已切换为 ${EQUIPMENT_STATUSES.find((s) => s.code === toStatus)?.label}`)
                  setReason('')
                } catch (e) {
                  notify('danger', String((e as Error)?.message ?? e))
                }
              }}
            >
              {statusMut.isPending && <Loader2 className="h-3.5 w-3.5 animate-spin" />} 应用
            </Button>
          </div>
        ) : (
          <div className="text-xs text-faint">仅工艺员及以上可执行设备状态变更（C4）</div>
        )}
        <div className="num mt-2 text-[11px] text-faint">状态变更将写入设备事件流水与审计日志（C3 / FR-5）</div>
        {msg && (
          <div className={cn('mt-2 text-[12px]', msg.tone === 'danger' ? 'text-danger' : 'text-accent')}>
            {msg.text}
          </div>
        )}
      </Card>
    </div>
  )
}

/** 保全预警汇总条：放在页面 KPI 下方，统筹全部设备的到期情况。 */
export function ComplianceStrip() {
  const { data: alerts } = useEquipmentAlerts(7)
  if (!alerts) return null
  const expired = alerts.calibrationExpired
  const overdue = alerts.maintenanceOverdue
  const due = alerts.maintenanceDue
  const items: { label: string; value?: number; tone: Tone }[] = [
    { label: '校准超期', value: expired, tone: 'danger' },
    { label: '保养逾期', value: overdue, tone: 'warn' },
    { label: `${alerts.withinDays} 日内到期`, value: due, tone: 'primary' },
  ]
  return (
    <Card className="card-pad" data-component="equip-compliance-strip">
      <div className="flex flex-wrap items-center gap-x-6 gap-y-2">
        <div className="flex items-center gap-2">
          <ShieldAlert className="h-4 w-4 text-faint" />
          <span className="text-[13px] font-medium">设备合规</span>
        </div>
        {items.map((it) => (
          <div key={it.label} className="flex items-center gap-2">
            <span className="label-tech">{it.label}</span>
            <span className="num text-[15px] font-semibold">{it.value}</span>
            <Badge tone={(it.value ?? 0) > 0 ? it.tone : 'muted'} dot={false}>{(it.value ?? 0) > 0 ? '需处理' : '正常'}</Badge>
          </div>
        ))}
        {(expired ?? 0) > 0 && (
          <span className="text-[11px] text-danger">
            校准超期设备已被移出批次建单可选清单，并阻断以其数据为依据的放行（FR-8 / FR-10）
          </span>
        )}
      </div>
    </Card>
  )
}
