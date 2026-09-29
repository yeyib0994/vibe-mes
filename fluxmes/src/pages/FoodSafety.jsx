import { useState } from 'react'
import {
  Activity,
  CheckCircle2,
  Droplets,
  ShieldCheck,
  SprayCan,
  Thermometer,
  TriangleAlert,
} from 'lucide-react'
import { Badge, Button, Card, Input, Tabs } from '../components/ui'
import { PageHeader } from '../components/layout'
import {
  CLEANING_TYPE_LABEL,
  HAZARD_LABEL,
  METRIC_LABEL,
  RESULT_TONE,
  useCcpPoints,
  useCcpRecords,
  useCcpSummary,
  useCleaningRecords,
  useCreateCleaning,
  useEnvRecords,
  useEnvSummary,
  useRecordCcp,
  useRecordEnv,
  useSanitationStatus,
  useVerifyCleaning,
  useVerifyCcp,
} from '../api/foodsafety'
import { hasRole } from '../lib/auth'
import StalenessBadge from '../components/StalenessBadge'

const TABS = [
  { key: 'ccp', label: 'HACCP 关键控制点' },
  { key: 'cleaning', label: '清场与过敏原' },
  { key: 'env', label: '环境与卫生' },
]

const LINES = [
  { code: 'LINE-1', name: '柠檬酸发酵一线' },
  { code: 'LINE-2', name: '柠檬酸发酵二线' },
  { code: 'LINE-3', name: '精制包装线' },
]

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

/* ============================== CCP ============================== */

function CcpPanel() {
  const { data: pointsRes } = useCcpPoints()
  const { data: summary } = useCcpSummary()
  const [onlyDeviation, setOnlyDeviation] = useState(false)
  const { data: recordsRes } = useCcpRecords({ onlyDeviation, limit: 50 })
  const recordCcp = useRecordCcp()
  const verifyCcp = useVerifyCcp()
  const [form, setForm] = useState({ ccpCode: '', batchId: '', value: '' })
  const [msg, setMsg] = useState(null)

  const points = pointsRes?.points ?? []
  const records = recordsRes?.records ?? []
  const s = summary ?? {}

  async function submit(e) {
    e.preventDefault()
    setMsg(null)
    try {
      const r = await recordCcp.mutateAsync({
        ccpCode: form.ccpCode,
        batchId: form.batchId || null,
        value: Number(form.value),
      })
      setMsg(
        r.inLimit
          ? `已记录：${r.ccpName} ${r.value}${r.unit ?? ''} 在关键限值内`
          : `关键限值偏离！已自动生成偏差单 ${r.deviationId ?? '—'} 并触发报警，关联批次已阻断`,
      )
      setForm((f) => ({ ...f, value: '' }))
    } catch (err) {
      setMsg(String(err?.message ?? err))
    }
  }

  return (
    <div className="space-y-4">
      <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <StatCard icon={ShieldCheck} label="CCP 合规率" value={s.complianceRate ?? '—'} unit="%" tone="accent" />
        <StatCard icon={Activity} label="监控记录" value={s.recordCount ?? 0} unit="条" sub={`${s.pointCount ?? 0} 个控制点`} />
        <StatCard icon={TriangleAlert} label="关键限值偏离" value={s.deviationCount ?? 0} unit="条" tone="danger" sub={`${s.openDeviationCount ?? 0} 条偏差未关闭`} />
        <StatCard icon={CheckCircle2} label="待 QA 复核" value={s.unverifiedCount ?? 0} unit="条" tone="warning" />
      </div>

      <Card className="p-4">
        <div className="mb-3 flex items-center justify-between">
          <h2 className="text-sm font-medium">关键控制点（CCP）与关键限值</h2>
          <StalenessBadge generatedAt={summary?.generatedAt} />
        </div>
        <div className="grid gap-3 lg:grid-cols-2">
          {points.map((p) => {
            const hz = HAZARD_LABEL[p.hazardType] ?? { text: p.hazardType, tone: 'muted' }
            return (
              <div key={p.code} className="rounded-lg border border-border p-3">
                <div className="flex items-start justify-between gap-2">
                  <div>
                    <div className="flex items-center gap-2">
                      <span className="num text-xs text-faint">{p.code}</span>
                      <span className="text-sm font-medium">{p.name}</span>
                    </div>
                    <div className="mt-1 text-xs text-muted-foreground">
                      {p.line} · {p.stepName} · {p.monitorFreq}
                    </div>
                  </div>
                  <Badge tone={hz.tone}>{hz.text}</Badge>
                </div>
                <div className="mt-2 grid grid-cols-2 gap-2 text-xs">
                  <div>
                    <span className="text-faint">关键限值 </span>
                    <span className="num font-medium">
                      {p.clMin} ~ {p.clMax} {p.unit}
                    </span>
                  </div>
                  <div>
                    <span className="text-faint">控制措施 </span>
                    {p.controlMeasure}
                  </div>
                </div>
                <div className="mt-2 rounded-md bg-muted/40 p-2 text-xs text-muted-foreground">
                  <span className="text-faint">危害：</span>
                  {p.hazard}
                </div>
                <div className="mt-1 rounded-md bg-muted/40 p-2 text-xs text-muted-foreground">
                  <span className="text-faint">纠偏措施：</span>
                  {p.correctiveAction}
                </div>
              </div>
            )
          })}
        </div>
      </Card>

      <div className="grid gap-4 xl:grid-cols-[1fr_360px]">
        <Card className="p-4">
          <div className="mb-3 flex items-center justify-between">
            <h2 className="text-sm font-medium">监控记录</h2>
            <Button variant="ghost" onClick={() => setOnlyDeviation((v) => !v)}>
              {onlyDeviation ? '显示全部' : '仅看偏离'}
            </Button>
          </div>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-border text-left text-xs text-muted-foreground">
                  <th className="py-2 pr-3">时间</th>
                  <th className="py-2 pr-3">控制点</th>
                  <th className="py-2 pr-3">实测</th>
                  <th className="py-2 pr-3">限值</th>
                  <th className="py-2 pr-3">批次</th>
                  <th className="py-2 pr-3">记录/复核</th>
                  <th className="py-2">结果</th>
                </tr>
              </thead>
              <tbody>
                {records.map((r) => (
                  <tr key={r.id} className="border-b border-border/60 last:border-0">
                    <td className="py-2 pr-3 text-xs text-muted-foreground">
                      {String(r.recordedAt ?? '').slice(5, 16).replace('T', ' ')}
                    </td>
                    <td className="py-2 pr-3">
                      {r.ccpName}
                      <span className="num ml-1 text-xs text-faint">{r.ccpCode}</span>
                    </td>
                    <td className={`num py-2 pr-3 font-medium ${r.inLimit ? '' : 'text-danger'}`}>
                      {r.value}
                      {r.unit}
                    </td>
                    <td className="num py-2 pr-3 text-xs text-muted-foreground">
                      {r.clMin} ~ {r.clMax}
                    </td>
                    <td className="num py-2 pr-3 text-xs">{r.batchId ?? '—'}</td>
                    <td className="py-2 pr-3 text-xs">
                      {r.operator}
                      {r.verifier ? (
                        <span className="text-accent"> / {r.verifier}</span>
                      ) : (
                        <Button
                          className="ml-2"
                          variant="ghost"
                          disabled={!hasRole('QC') || verifyCcp.isPending}
                          onClick={() => verifyCcp.mutate(r.id)}
                        >
                          复核
                        </Button>
                      )}
                    </td>
                    <td className="py-2">
                      <Badge tone={r.inLimit ? 'success' : 'danger'}>
                        {r.inLimit ? '符合' : `偏离 ${r.deviationId ?? ''}`}
                      </Badge>
                    </td>
                  </tr>
                ))}
                {records.length === 0 && (
                  <tr>
                    <td colSpan={7} className="py-6 text-center text-xs text-faint">
                      暂无监控记录
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        </Card>

        <Card className="p-4">
          <h2 className="mb-3 text-sm font-medium">上报监控值</h2>
          <form className="space-y-3" onSubmit={submit}>
            <Field label="控制点">
              <select
                className="h-8 w-full rounded-md border border-border bg-background px-2 text-sm"
                value={form.ccpCode}
                onChange={(e) => setForm((f) => ({ ...f, ccpCode: e.target.value }))}
                required
              >
                <option value="">请选择</option>
                {points.map((p) => (
                  <option key={p.code} value={p.code}>
                    {p.code} {p.name}（{p.clMin}~{p.clMax}
                    {p.unit}）
                  </option>
                ))}
              </select>
            </Field>
            <Field label="实测值">
              <Input
                type="number"
                step="0.01"
                value={form.value}
                onChange={(e) => setForm((f) => ({ ...f, value: e.target.value }))}
                required
              />
            </Field>
            <Field label="关联批次（可选）">
              <Input
                placeholder="B-260914-001"
                value={form.batchId}
                onChange={(e) => setForm((f) => ({ ...f, batchId: e.target.value }))}
              />
            </Field>
            <Button type="submit" variant="primary" disabled={recordCcp.isPending}>
              提交
            </Button>
            {msg && (
              <p className={`text-xs ${msg.includes('偏离') ? 'text-danger' : 'text-accent'}`}>{msg}</p>
            )}
          </form>
          <p className="mt-3 text-xs text-faint">
            越出关键限值将自动创建偏差单、触发 critical 报警，并把关联批次置为异常以阻断推进。
          </p>
        </Card>
      </div>
    </div>
  )
}

/* ============================== 清场 ============================== */

function CleaningPanel() {
  const [line, setLine] = useState('LINE-1')
  const { data: status } = useSanitationStatus(line)
  const { data: recordsRes } = useCleaningRecords()
  const createCleaning = useCreateCleaning()
  const verifyCleaning = useVerifyCleaning()
  const [form, setForm] = useState({ type: 'ROUTINE', method: '' })

  const records = recordsRes?.records ?? []

  return (
    <div className="space-y-4">
      <Card className="p-4">
        <div className="mb-3 flex items-center justify-between">
          <h2 className="text-sm font-medium">开工前置校验（清场门禁）</h2>
          <select
            className="h-8 rounded-md border border-border bg-background px-2 text-sm"
            value={line}
            onChange={(e) => setLine(e.target.value)}
          >
            {LINES.map((l) => (
              <option key={l.code} value={l.code}>
                {l.code} {l.name}
              </option>
            ))}
          </select>
        </div>
        <div
          className={`rounded-lg border p-3 ${
            status?.cleared ? 'border-accent/40 bg-accent/5' : 'border-danger/40 bg-danger/5'
          }`}
        >
          <div className="flex items-center gap-2">
            {status?.cleared ? (
              <ShieldCheck className="h-4 w-4 text-accent" />
            ) : (
              <TriangleAlert className="h-4 w-4 text-danger" />
            )}
            <span className="text-sm font-medium">
              {status?.cleared ? '允许开工' : '禁止开工'}
            </span>
            <Badge tone={status?.cleared ? 'success' : 'danger'}>
              {status?.cleared ? '清场有效' : '清场无效'}
            </Badge>
          </div>
          <p className="mt-1 text-xs text-muted-foreground">
            {status?.cleared
              ? `最新清场 ${status.latest?.id} · ${status.latest?.method} · 有效期至 ${String(
                  status.latest?.validUntil ?? '',
                ).slice(0, 16).replace('T', ' ')}`
              : status?.reason}
          </p>
        </div>
        <p className="mt-2 text-xs text-faint">
          新建批次时后端强制校验：目标产线须有 PASS 且 QA 已确认且在有效期（72 小时）内的清场记录，
          否则拒绝开工；值班长及以上可强制作业并记录审计。
        </p>
      </Card>

      <div className="grid gap-4 xl:grid-cols-[1fr_360px]">
        <Card className="p-4">
          <h2 className="mb-3 text-sm font-medium">清场记录</h2>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-border text-left text-xs text-muted-foreground">
                  <th className="py-2 pr-3">编号</th>
                  <th className="py-2 pr-3">产线/设备</th>
                  <th className="py-2 pr-3">类型</th>
                  <th className="py-2 pr-3">方式</th>
                  <th className="py-2 pr-3">执行/确认</th>
                  <th className="py-2 pr-3">ATP</th>
                  <th className="py-2">结果</th>
                </tr>
              </thead>
              <tbody>
                {records.map((c) => {
                  const t = CLEANING_TYPE_LABEL[c.type] ?? { text: c.type, tone: 'muted' }
                  const expired = c.validUntil && new Date(c.validUntil) < new Date()
                  return (
                    <tr key={c.id} className="border-b border-border/60 last:border-0">
                      <td className="num py-2 pr-3 text-xs">{c.id}</td>
                      <td className="py-2 pr-3">
                        {c.line}
                        <span className="num ml-1 text-xs text-faint">{c.equipmentCode}</span>
                      </td>
                      <td className="py-2 pr-3">
                        <Badge tone={t.tone}>{t.text}</Badge>
                      </td>
                      <td className="py-2 pr-3 text-xs text-muted-foreground">{c.method}</td>
                      <td className="py-2 pr-3 text-xs">
                        {c.executedBy}
                        {c.verifiedBy ? ` / ${c.verifiedBy}` : ' / 未确认'}
                      </td>
                      <td className="py-2 pr-3 text-xs">{c.swabResult ?? '—'}</td>
                      <td className="py-2">
                        <Badge tone={c.result === 'PASS' ? (expired ? 'warning' : 'success') : RESULT_TONE[c.result]}>
                          {c.result === 'PASS' && expired ? '已过期' : c.result}
                        </Badge>
                        {c.result === 'PENDING' && hasRole('QC') && (
                          <Button
                            className="ml-2"
                            variant="ghost"
                            onClick={() =>
                              verifyCleaning.mutate({ id: c.id, result: 'PASS', swabResult: 'ATP 120 RLU' })
                            }
                          >
                            确认
                          </Button>
                        )}
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        </Card>

        <Card className="p-4">
          <h2 className="mb-3 text-sm font-medium">登记清场</h2>
          <form
            className="space-y-3"
            onSubmit={async (e) => {
              e.preventDefault()
              await createCleaning.mutateAsync({ line, type: form.type, method: form.method })
              setForm((f) => ({ ...f, method: '' }))
            }}
          >
            <Field label="产线">
              <select
                className="h-8 w-full rounded-md border border-border bg-background px-2 text-sm"
                value={line}
                onChange={(e) => setLine(e.target.value)}
              >
                {LINES.map((l) => (
                  <option key={l.code} value={l.code}>
                    {l.code} {l.name}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="清场类型">
              <select
                className="h-8 w-full rounded-md border border-border bg-background px-2 text-sm"
                value={form.type}
                onChange={(e) => setForm((f) => ({ ...f, type: e.target.value }))}
              >
                <option value="ROUTINE">日常清场</option>
                <option value="CHANGEOVER">换型清场</option>
                <option value="ALLERGEN">过敏原换型</option>
                <option value="DEEP">深度清洁</option>
              </select>
            </Field>
            <Field label="清洗方式">
              <Input
                placeholder="CIP 碱洗 2% → 冲洗 → 酸洗"
                value={form.method}
                onChange={(e) => setForm((f) => ({ ...f, method: e.target.value }))}
              />
            </Field>
            <Button type="submit" variant="primary" disabled={createCleaning.isPending}>
              登记并等待 QA 确认
            </Button>
          </form>
        </Card>
      </div>
    </div>
  )
}

/* ============================== 环境 ============================== */

function EnvPanel() {
  const { data: summary } = useEnvSummary()
  const { data: recordsRes } = useEnvRecords()
  const recordEnv = useRecordEnv()
  const [form, setForm] = useState({ area: '洁净灌装区', metric: 'TEMP', value: '', limitMin: '', limitMax: '' })
  const [msg, setMsg] = useState(null)

  const records = recordsRes?.records ?? []
  const s = summary ?? {}

  async function submit(e) {
    e.preventDefault()
    setMsg(null)
    try {
      const r = await recordEnv.mutateAsync({
        area: form.area,
        metric: form.metric,
        value: Number(form.value),
        limitMin: form.limitMin === '' ? null : Number(form.limitMin),
        limitMax: form.limitMax === '' ? null : Number(form.limitMax),
        line: form.area === '发酵间' ? 'LINE-1' : 'LINE-3',
      })
      setMsg(r.result === 'PASS' ? '已记录，指标合格' : `指标超标，已自动生成偏差单 ${r.deviationId}`)
    } catch (err) {
      setMsg(String(err?.message ?? err))
    }
  }

  return (
    <div className="space-y-4">
      <div className="grid gap-3 sm:grid-cols-3">
        <StatCard icon={Thermometer} label="环境监测合格率" value={s.passRate ?? '—'} unit="%" tone="accent" />
        <StatCard icon={Activity} label="采样记录" value={s.recordCount ?? 0} unit="条" />
        <StatCard icon={TriangleAlert} label="超标次数" value={s.failCount ?? 0} unit="次" tone="danger" />
      </div>

      <div className="grid gap-4 xl:grid-cols-[1fr_360px]">
        <Card className="p-4">
          <h2 className="mb-3 text-sm font-medium">监测记录</h2>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-border text-left text-xs text-muted-foreground">
                  <th className="py-2 pr-3">时间</th>
                  <th className="py-2 pr-3">区域</th>
                  <th className="py-2 pr-3">指标</th>
                  <th className="py-2 pr-3">实测</th>
                  <th className="py-2 pr-3">限值</th>
                  <th className="py-2 pr-3">采样人</th>
                  <th className="py-2">结果</th>
                </tr>
              </thead>
              <tbody>
                {records.map((r) => (
                  <tr key={r.id} className="border-b border-border/60 last:border-0">
                    <td className="py-2 pr-3 text-xs text-muted-foreground">
                      {String(r.sampledAt ?? '').slice(5, 16).replace('T', ' ')}
                    </td>
                    <td className="py-2 pr-3">{r.area}</td>
                    <td className="py-2 pr-3">{METRIC_LABEL[r.metric] ?? r.metric}</td>
                    <td className={`num py-2 pr-3 font-medium ${r.result === 'PASS' ? '' : 'text-danger'}`}>
                      {r.value}
                      {r.unit}
                    </td>
                    <td className="num py-2 pr-3 text-xs text-muted-foreground">
                      {r.limitMin} ~ {r.limitMax}
                    </td>
                    <td className="py-2 pr-3 text-xs">{r.sampledBy}</td>
                    <td className="py-2">
                      <Badge tone={RESULT_TONE[r.result]}>
                        {r.result === 'PASS' ? '合格' : `超标 ${r.deviationId ?? ''}`}
                      </Badge>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <h2 className="mb-2 mt-5 text-sm font-medium">分区合格率</h2>
          <div className="grid gap-2 sm:grid-cols-3">
            {(s.areas ?? []).map((a) => (
              <div key={a.area} className="rounded-lg border border-border p-3">
                <div className="text-sm">{a.area}</div>
                <div className="num mt-1 text-lg font-semibold">{a.passRate}%</div>
                <div className="mt-1 text-xs text-faint">
                  {a.recordCount} 条 · 超标 {a.failCount} 次
                </div>
              </div>
            ))}
          </div>
        </Card>

        <Card className="p-4">
          <h2 className="mb-3 text-sm font-medium">上报环境指标</h2>
          <form className="space-y-3" onSubmit={submit}>
            <Field label="区域">
              <select
                className="h-8 w-full rounded-md border border-border bg-background px-2 text-sm"
                value={form.area}
                onChange={(e) => setForm((f) => ({ ...f, area: e.target.value }))}
              >
                <option>发酵间</option>
                <option>洁净灌装区</option>
                <option>包装间</option>
                <option>原料暂存</option>
              </select>
            </Field>
            <Field label="指标">
              <select
                className="h-8 w-full rounded-md border border-border bg-background px-2 text-sm"
                value={form.metric}
                onChange={(e) => setForm((f) => ({ ...f, metric: e.target.value }))}
              >
                <option value="TEMP">温度</option>
                <option value="HUMIDITY">湿度</option>
                <option value="PRESSURE_DIFF">压差</option>
                <option value="MICRO">沉降菌</option>
                <option value="ATP">ATP</option>
              </select>
            </Field>
            <div className="grid grid-cols-2 gap-2">
              <Field label="实测值">
                <Input
                  type="number"
                  step="0.1"
                  value={form.value}
                  onChange={(e) => setForm((f) => ({ ...f, value: e.target.value }))}
                  required
                />
              </Field>
              <Field label="上限">
                <Input
                  type="number"
                  step="0.1"
                  value={form.limitMax}
                  onChange={(e) => setForm((f) => ({ ...f, limitMax: e.target.value }))}
                />
              </Field>
            </div>
            <Button type="submit" variant="primary" disabled={recordEnv.isPending}>
              提交
            </Button>
            {msg && <p className={`text-xs ${msg.includes('超标') ? 'text-danger' : 'text-accent'}`}>{msg}</p>}
          </form>
        </Card>
      </div>
    </div>
  )
}

/* ============================== 页面 ============================== */

export default function FoodSafety({ session }) {
  const [tab, setTab] = useState('ccp')
  void session
  return (
    <div className="mx-auto w-full max-w-[1600px] p-4 sm:p-6">
      <PageHeader
        title="食品安全"
        desc="HACCP 关键控制点监控 · 清场与过敏原换型 · 环境与卫生监测"
        actions={
          <div className="flex items-center gap-2 text-xs text-muted-foreground">
            <SprayCan className="h-3.5 w-3.5" />
            清场门禁已启用
            <Droplets className="ml-2 h-3.5 w-3.5" />
            偏离自动联动偏差
          </div>
        }
      />
      <Tabs items={TABS} active={tab} onChange={setTab} className="mb-4" />
      {tab === 'ccp' && <CcpPanel />}
      {tab === 'cleaning' && <CleaningPanel />}
      {tab === 'env' && <EnvPanel />}
    </div>
  )
}
