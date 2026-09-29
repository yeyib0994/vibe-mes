import { useState } from 'react'
import {
  Boxes,
  Clock,
  PackageSearch,
  Radio,
  ShieldAlert,
  Siren,
  TestTube,
  Wheat,
} from 'lucide-react'
import { Badge, Button, Card, Input, Tabs } from '../components/ui'
import { PageHeader } from '../components/layout'
import {
  RESULT_TONE,
  useBatchInputs,
  useCreateLot,
  useDisposeSample,
  useExpiryAlerts,
  useFeed,
  useGenealogy,
  useInspectLot,
  useMaterialLots,
  useMaterials,
  useRecall,
  useRetainSample,
  useRetentionSamples,
} from '../api/foodsafety'
import { hasRole } from '../lib/auth'
import StalenessBadge from '../components/StalenessBadge'

const TABS = [
  { key: 'lots', label: '物料与原料批' },
  { key: 'genealogy', label: '投料谱系与召回' },
  { key: 'shelf', label: '留样与效期' },
]

const QC_TONE = { RELEASED: 'success', PENDING: 'warning', FAIL: 'danger' }
const QC_TEXT = { RELEASED: '已放行', PENDING: '待检验', FAIL: '不合格' }

function StatCard({ icon: Icon, label, value, unit, tone = 'primary', sub }) {
  return (
    <Card className="p-4">
      <div className="flex items-start justify-between">
        <span className="text-xs text-muted-foreground">{label}</span>
        <Icon className={`h-4 w-4 text-${tone}`} />
      </div>
      <div className="mt-2 flex items-baseline gap-1">
        <span className="num text-2xl font-semibold tabular-nums">{value}</span>
        {unit && <span className="text-xs text-muted-foreground">{unit}</span>}
      </div>
      {sub && <div className="mt-1 text-xs text-faint">{sub}</div>}
    </Card>
  )
}

function Field({ label, children }) {
  return (
    <label className="block">
      <span className="mb-1 block text-xs text-muted-foreground">{label}</span>
      {children}
    </label>
  )
}

/* ============================ 物料与原料批 ============================ */

function LotsPanel() {
  const { data: matRes } = useMaterials()
  const [qcStatus, setQcStatus] = useState('')
  const [expiringSoon, setExpiringSoon] = useState(false)
  const { data: lotsRes } = useMaterialLots({
    qcStatus: qcStatus || undefined,
    expiringSoon: expiringSoon || undefined,
  })
  const inspect = useInspectLot()
  const createLot = useCreateLot()
  const [form, setForm] = useState({ materialCode: '', supplier: '', supplierLot: '', qty: '' })

  const materials = matRes?.materials ?? []
  const lots = lotsRes?.lots ?? []

  return (
    <div className="space-y-4">
      <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <StatCard icon={Wheat} label="物料主数据" value={materials.length} unit="种" sub={`${materials.filter((m) => m.allergen).length} 种含过敏原`} />
        <StatCard icon={Boxes} label="原料批" value={lots.length} unit="批" />
        <StatCard icon={TestTube} label="待检验" value={lots.filter((l) => l.qcStatus === 'PENDING').length} unit="批" tone="warning" />
        <StatCard icon={ShieldAlert} label="不合格" value={lots.filter((l) => l.qcStatus === 'FAIL').length} unit="批" tone="danger" />
      </div>

      <div className="grid gap-4 xl:grid-cols-[1fr_360px]">
        <Card className="p-4">
          <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
            <h2 className="text-sm font-medium">原料批台账</h2>
            <div className="flex items-center gap-2">
              <select
                className="h-8 rounded-md border border-border bg-background px-2 text-sm"
                value={qcStatus}
                onChange={(e) => setQcStatus(e.target.value)}
              >
                <option value="">全部状态</option>
                <option value="PENDING">待检验</option>
                <option value="RELEASED">已放行</option>
                <option value="FAIL">不合格</option>
              </select>
              <Button variant="ghost" onClick={() => setExpiringSoon((v) => !v)}>
                {expiringSoon ? '全部效期' : '近 30 天到期'}
              </Button>
            </div>
          </div>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-border text-left text-xs text-muted-foreground">
                  <th className="py-2 pr-3">原料批号</th>
                  <th className="py-2 pr-3">物料</th>
                  <th className="py-2 pr-3">供应商</th>
                  <th className="py-2 pr-3">数量</th>
                  <th className="py-2 pr-3">到期日</th>
                  <th className="py-2 pr-3">库位</th>
                  <th className="py-2 pr-3">检验状态</th>
                  <th className="py-2">操作</th>
                </tr>
              </thead>
              <tbody>
                {lots.map((l) => (
                  <tr key={l.id} className="border-b border-border/60 last:border-0">
                    <td className="num py-2 pr-3 text-xs">{l.id}</td>
                    <td className="py-2 pr-3">
                      {l.materialName}
                      <span className="num ml-1 text-xs text-faint">{l.materialCode}</span>
                      {l.allergen && (
                        <Badge className="ml-1" tone="warning">
                          {l.allergenName ?? '过敏原'}
                        </Badge>
                      )}
                    </td>
                    <td className="py-2 pr-3 text-xs">{l.supplier}</td>
                    <td className="num py-2 pr-3">
                      {l.qty} {l.unit}
                    </td>
                    <td className="num py-2 pr-3 text-xs">
                      {l.expiryDate}
                      <span className={`ml-1 ${l.daysToExpiry <= 30 ? 'text-danger' : 'text-faint'}`}>
                        ({l.daysToExpiry}d)
                      </span>
                    </td>
                    <td className="py-2 pr-3 text-xs">{l.warehouseBin}</td>
                    <td className="py-2 pr-3">
                      <Badge tone={QC_TONE[l.qcStatus]}>{QC_TEXT[l.qcStatus]}</Badge>
                    </td>
                    <td className="py-2">
                      {l.qcStatus === 'PENDING' && hasRole('QC') && (
                        <div className="flex gap-1">
                          <Button variant="ghost" onClick={() => inspect.mutate({ id: l.id, result: 'PASS' })}>
                            放行
                          </Button>
                          <Button variant="ghost" onClick={() => inspect.mutate({ id: l.id, result: 'FAIL' })}>
                            判不合格
                          </Button>
                        </div>
                      )}
                      {l.qcStatus === 'RELEASED' && <span className="text-xs text-faint">{l.coaNo}</span>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>

        <Card className="p-4">
          <h2 className="mb-3 text-sm font-medium">原料到货登记</h2>
          <form
            className="space-y-3"
            onSubmit={async (e) => {
              e.preventDefault()
              await createLot.mutateAsync({
                materialCode: form.materialCode,
                supplier: form.supplier,
                supplierLot: form.supplierLot,
                qty: Number(form.qty),
              })
              setForm({ materialCode: '', supplier: '', supplierLot: '', qty: '' })
            }}
          >
            <Field label="物料">
              <select
                className="h-8 w-full rounded-md border border-border bg-background px-2 text-sm"
                value={form.materialCode}
                onChange={(e) => setForm((f) => ({ ...f, materialCode: e.target.value }))}
                required
              >
                <option value="">请选择</option>
                {materials.map((m) => (
                  <option key={m.code} value={m.code}>
                    {m.code} {m.name}
                    {m.allergen ? `（过敏原·${m.allergenName}）` : ''}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="供应商">
              <Input value={form.supplier} onChange={(e) => setForm((f) => ({ ...f, supplier: e.target.value }))} required />
            </Field>
            <Field label="供应商批号">
              <Input value={form.supplierLot} onChange={(e) => setForm((f) => ({ ...f, supplierLot: e.target.value }))} />
            </Field>
            <Field label="到货数量">
              <Input type="number" value={form.qty} onChange={(e) => setForm((f) => ({ ...f, qty: e.target.value }))} required />
            </Field>
            <Button type="submit" variant="primary" disabled={createLot.isPending}>
              登记（默认待检验）
            </Button>
          </form>
          <p className="mt-3 text-xs text-faint">未检验放行的原料批在投料登记时会被后端拒绝。</p>
        </Card>
      </div>
    </div>
  )
}

/* ============================ 谱系与召回 ============================ */

function GenealogyPanel() {
  const [batchId, setBatchId] = useState('')
  const [lotId, setLotId] = useState('')
  const [runRecall, setRunRecall] = useState(false)
  const { data: gen, refetch: reloadGen } = useGenealogy(batchId)
  const { data: inputsRes } = useBatchInputs(batchId)
  const { data: recall } = useRecall(lotId, runRecall)

  return (
    <div className="grid gap-4 xl:grid-cols-2">
      <Card className="p-4">
        <div className="mb-3 flex items-center justify-between">
          <h2 className="text-sm font-medium">批次谱系（正向：成品 → 原料）</h2>
          <StalenessBadge generatedAt={gen?.generatedAt} />
        </div>
        <div className="flex gap-2">
          <Input placeholder="批次号，如 B-260914-001" value={batchId} onChange={(e) => setBatchId(e.target.value)} />
          <Button variant="primary" onClick={() => reloadGen()}>
            查询
          </Button>
        </div>
        {gen && (
          <div className="mt-3 space-y-2">
            <div className="text-xs text-muted-foreground">
              {gen.batchId} · {gen.product} · 上游 {gen.upstreamCount} 个原料批 · 下游 {gen.downstreamCount} 个用途
            </div>
            {(gen.upstream ?? []).map((u) => (
              <div key={u.lotId} className="rounded-lg border border-border p-3">
                <div className="flex items-center justify-between">
                  <span className="num text-sm">{u.lotId}</span>
                  <Badge tone={QC_TONE[u.qcStatus]}>{QC_TEXT[u.qcStatus]}</Badge>
                </div>
                <div className="mt-1 text-xs text-muted-foreground">
                  {u.materialName} · {u.supplier}（供应商批 {u.supplierLot}）
                </div>
                <div className="mt-1 text-xs text-faint">
                  投料 {u.qty} {u.unit} · COA {u.coaNo ?? '—'} · 到期 {u.expiryDate ?? '—'}
                </div>
              </div>
            ))}
            {(gen.upstream ?? []).length === 0 && (
              <p className="py-4 text-center text-xs text-faint">该批次尚未登记投料</p>
            )}
          </div>
        )}
        {inputsRes && inputsRes.inputs?.length > 0 && (
          <div className="mt-3 text-xs text-faint">投料记录 {inputsRes.inputs.length} 条</div>
        )}
      </Card>

      <Card className="p-4">
        <div className="mb-3 flex items-center gap-2">
          <Siren className="h-4 w-4 text-danger" />
          <h2 className="text-sm font-medium">召回影响分析（反向：原料 → 成品）</h2>
        </div>
        <div className="flex gap-2">
          <Input placeholder="问题原料批号，如 LOT-001" value={lotId} onChange={(e) => setLotId(e.target.value)} />
          <Button variant="primary" onClick={() => setRunRecall(true)}>
            分析
          </Button>
        </div>
        {recall && (
          <div className="mt-3 space-y-3">
            <div className="grid grid-cols-3 gap-2">
              <StatCard icon={PackageSearch} label="受影响批次" value={recall.affectedBatchCount} unit="个" tone="danger" />
              <StatCard icon={ShieldAlert} label="已放行需召回" value={recall.releasedBatchCount} unit="个" tone="danger" />
              <StatCard icon={Boxes} label="涉及产量" value={recall.totalYieldT} unit="吨" />
            </div>
            <div className="text-xs text-muted-foreground">
              {recall.materialLotId} · {recall.materialName} · {recall.supplier}（供应商批 {recall.supplierLot}）
            </div>
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-border text-left text-xs text-muted-foreground">
                    <th className="py-2 pr-3">成品批次</th>
                    <th className="py-2 pr-3">产品</th>
                    <th className="py-2 pr-3">状态</th>
                    <th className="py-2 pr-3">库位</th>
                    <th className="py-2">产量(吨)</th>
                  </tr>
                </thead>
                <tbody>
                  {(recall.affectedBatches ?? []).map((b) => (
                    <tr key={b.batchId} className="border-b border-border/60 last:border-0">
                      <td className="num py-2 pr-3 text-xs">{b.batchId}</td>
                      <td className="py-2 pr-3">{b.product}</td>
                      <td className="py-2 pr-3">
                        <Badge tone={b.released ? 'danger' : 'warning'}>{b.released ? '已放行' : b.status}</Badge>
                      </td>
                      <td className="py-2 pr-3 text-xs">{b.warehouseBin ?? '—'}</td>
                      <td className="num py-2">{b.planYieldT}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div className="rounded-lg border border-danger/40 bg-danger/5 p-3">
              <div className="mb-1 text-xs font-medium text-danger">处置建议</div>
              <ul className="list-inside list-disc space-y-1 text-xs text-muted-foreground">
                {(recall.suggestions ?? []).map((s) => (
                  <li key={s}>{s}</li>
                ))}
              </ul>
            </div>
          </div>
        )}
      </Card>
    </div>
  )
}

/* ============================ 留样与效期 ============================ */

function ShelfPanel() {
  const [status, setStatus] = useState('')
  const { data: samplesRes } = useRetentionSamples({ status: status || undefined })
  const { data: alerts } = useExpiryAlerts(30)
  const retain = useRetainSample()
  const dispose = useDisposeSample()
  const [batchId, setBatchId] = useState('')
  const [location, setLocation] = useState('留样柜 A-01')

  const samples = samplesRes?.samples ?? []

  return (
    <div className="space-y-4">
      <div className="grid gap-3 sm:grid-cols-3">
        <StatCard icon={TestTube} label="在留样品" value={samples.filter((s) => s.status === 'RETAINED').length} unit="件" />
        <StatCard icon={Clock} label="近 30 天到期成品" value={alerts?.expiringCount ?? 0} unit="批" tone="warning" />
        <StatCard icon={ShieldAlert} label="已过效期成品" value={alerts?.expiredCount ?? 0} unit="批" tone="danger" />
      </div>

      <div className="grid gap-4 xl:grid-cols-2">
        <Card className="p-4">
          <div className="mb-3 flex items-center justify-between">
            <h2 className="text-sm font-medium">留样台账</h2>
            <select
              className="h-8 rounded-md border border-border bg-background px-2 text-sm"
              value={status}
              onChange={(e) => setStatus(e.target.value)}
            >
              <option value="">全部</option>
              <option value="RETAINED">在留</option>
              <option value="TESTED">已复检</option>
              <option value="DISCARDED">已处置</option>
            </select>
          </div>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-border text-left text-xs text-muted-foreground">
                  <th className="py-2 pr-3">留样号</th>
                  <th className="py-2 pr-3">批次</th>
                  <th className="py-2 pr-3">产品</th>
                  <th className="py-2 pr-3">数量</th>
                  <th className="py-2 pr-3">位置</th>
                  <th className="py-2 pr-3">留样到期</th>
                  <th className="py-2">操作</th>
                </tr>
              </thead>
              <tbody>
                {samples.map((s) => (
                  <tr key={s.id} className="border-b border-border/60 last:border-0">
                    <td className="num py-2 pr-3 text-xs">{s.id}</td>
                    <td className="num py-2 pr-3 text-xs">{s.batchId}</td>
                    <td className="py-2 pr-3 text-xs">{s.product}</td>
                    <td className="num py-2 pr-3">
                      {s.qty}
                      {s.unit}
                    </td>
                    <td className="py-2 pr-3 text-xs">{s.location}</td>
                    <td className="num py-2 pr-3 text-xs">
                      {s.expiryDate}
                      <span className="ml-1 text-faint">({s.daysLeft}d)</span>
                    </td>
                    <td className="py-2">
                      {s.status !== 'DISCARDED' && hasRole('QC') && (
                        <Button variant="ghost" onClick={() => dispose.mutate({ id: s.id })}>
                          处置
                        </Button>
                      )}
                      {s.status === 'DISCARDED' && <span className="text-xs text-faint">{s.disposedBy}</span>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {hasRole('QC') && (
            <form
              className="mt-3 flex flex-wrap items-end gap-2"
              onSubmit={async (e) => {
                e.preventDefault()
                await retain.mutateAsync({ batchId, location })
                setBatchId('')
              }}
            >
              <Field label="登记留样（批次号）">
                <Input value={batchId} onChange={(e) => setBatchId(e.target.value)} placeholder="B-260914-001" required />
              </Field>
              <Field label="留样位置">
                <Input value={location} onChange={(e) => setLocation(e.target.value)} />
              </Field>
              <Button type="submit" variant="primary" disabled={retain.isPending}>
                登记
              </Button>
            </form>
          )}
          <p className="mt-2 text-xs text-faint">留样到期日 = 成品效期 + 180 天（保质期后 6 个月）。</p>
        </Card>

        <Card className="p-4">
          <h2 className="mb-3 text-sm font-medium">近效期 / 过期成品预警</h2>
          <div className="space-y-2">
            {(alerts?.expiring ?? []).map((b) => (
              <div key={b.batchId} className="flex items-center justify-between rounded-lg border border-warn/40 bg-warn/5 p-3">
                <div>
                  <div className="num text-sm">{b.batchId}</div>
                  <div className="text-xs text-muted-foreground">{b.product}</div>
                </div>
                <div className="text-right">
                  <div className="num text-sm">{b.daysLeft} 天</div>
                  <div className="text-xs text-faint">{b.expiryDate}</div>
                </div>
              </div>
            ))}
            {(alerts?.expired ?? []).map((b) => (
              <div key={b.batchId} className="flex items-center justify-between rounded-lg border border-danger/40 bg-danger/5 p-3">
                <div>
                  <div className="num text-sm">{b.batchId}</div>
                  <div className="text-xs text-muted-foreground">{b.product}</div>
                </div>
                <div className="text-right">
                  <div className="num text-sm text-danger">过期 {Math.abs(b.daysLeft)} 天</div>
                  <div className="text-xs text-faint">{b.expiryDate}</div>
                </div>
              </div>
            ))}
            {(alerts?.expiring ?? []).length === 0 && (alerts?.expired ?? []).length === 0 && (
              <p className="py-6 text-center text-xs text-faint">暂无近效期或过期成品</p>
            )}
          </div>
        </Card>
      </div>
    </div>
  )
}

export default function Materials({ session }) {
  const [tab, setTab] = useState('lots')
  void session
  return (
    <div className="mx-auto w-full max-w-[1600px] p-4 sm:p-6">
      <PageHeader
        title="物料与追溯"
        desc="物料主数据 · 原料批检验放行 · 投料谱系 · 召回影响分析 · 留样与效期"
        actions={
          <div className="flex items-center gap-2 text-xs text-muted-foreground">
            <Radio className="h-3.5 w-3.5" />
            谱系基于真实投料记录
          </div>
        }
      />
      <Tabs items={TABS} active={tab} onChange={setTab} className="mb-4" />
      {tab === 'lots' && <LotsPanel />}
      {tab === 'genealogy' && <GenealogyPanel />}
      {tab === 'shelf' && <ShelfPanel />}
    </div>
  )
}
