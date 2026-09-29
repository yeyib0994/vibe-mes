import type { ComponentType, ReactNode } from 'react'
import { useState } from 'react'
import { Award, BadgeCheck, HeartPulse, ShieldAlert, TriangleAlert } from 'lucide-react'
import { Badge, Button, Card, Input, Tabs, type BadgeTone } from '../components/ui'
import { PageHeader } from '../components/layout'
import {
  CAPABILITY_TEXT,
  CERT_TYPE_TEXT,
  STATUS_TEXT,
  STATUS_TONE,
  useCapabilities,
  useCertificates,
  useIssueCertificate,
  usePersonnelAlerts,
  usePersonnelSummary,
  useRevokeCertificate,
} from '../api/personnel'
import { hasRole } from '../lib/auth'

/**
 * G2 · 人员资质与健康证。
 * 食品行业法定门槛：从业人员须持有效健康证；关键岗位（CCP 监控 / 配料称量 /
 * 成品放行 / 批记录复核 / 清场作业 / 理化检验）须持对应资质，后端做门禁校验。
 */
const TABS = [
  { key: 'alerts', label: '到期预警' },
  { key: 'ledger', label: '证书台账' },
  { key: 'capability', label: '能力项矩阵' },
]

function StatCard({
  icon: Icon,
  label,
  value,
  tone = 'primary',
  sub,
}: {
  icon: ComponentType<{ className?: string }>
  label: ReactNode
  value: ReactNode
  tone?: BadgeTone
  sub?: ReactNode
}) {
  void tone
  return (
    <Card className="p-4">
      <div className="flex items-start justify-between">
        <span className="text-xs text-muted-foreground">{label}</span>
        <Icon className="h-4 w-4 text-muted-foreground" />
      </div>
      <div className="num mt-2 text-2xl font-semibold tabular-nums">{value}</div>
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

export default function Personnel() {
  const [tab, setTab] = useState('alerts')
  const canManage = hasRole('SUPERVISOR')
  const { data: summary } = usePersonnelSummary()
  const { data: alertsRes } = usePersonnelAlerts(30)
  const { data: certs = [] } = useCertificates({})
  const { data: caps = [] } = useCapabilities()
  const issue = useIssueCertificate()
  const revoke = useRevokeCertificate()
  const [form, setForm] = useState({
    username: '',
    certType: 'QUALIFICATION',
    certName: '',
    capability: 'WEIGHING',
    certNo: '',
    issuedBy: '',
    validUntil: '',
  })

  function submit() {
    issue.mutate({
      username: form.username,
      certType: form.certType,
      certName:
        form.certName ||
        (form.certType === 'HEALTH' ? '食品从业人员健康证' : `${(CAPABILITY_TEXT as Record<string, string>)[form.capability]}岗位资质`),
      capability: form.certType === 'HEALTH' ? null : form.capability,
      certNo: form.certNo || null,
      issuedBy: form.issuedBy || null,
      validUntil: form.validUntil || null,
    })
    setForm({ ...form, certNo: '', issuedBy: '', validUntil: '' })
  }

  return (
    <div className="mx-auto max-w-[1400px] p-6">
      <PageHeader
        title="人员资质与健康证"
        desc="食品从业人员持证上岗管理 · 健康证年度体检 + 关键岗位资质，缺失或过期由后端自动阻断相关操作"
      />

      <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
        <StatCard
          icon={Award}
          label="证书总数"
          value={summary?.totalCertificates ?? '—'}
          sub={`健康证 ${summary?.healthCertificates ?? 0} · 岗位资质 ${summary?.qualifications ?? 0}`}
        />
        <StatCard
          icon={HeartPulse}
          label="健康证管控"
          value={summary?.healthCertEnforced ? '已启用' : '未启用'}
          sub="有人持证后自动强制校验"
        />
        <StatCard
          icon={TriangleAlert}
          label="已过期证书"
          value={alertsRes?.expiredCount ?? '—'}
          sub="过期即丧失对应操作权限"
        />
        <StatCard
          icon={ShieldAlert}
          label="30 天内到期"
          value={alertsRes?.expiringCount ?? '—'}
          sub="需提前安排复检 / 复审"
        />
      </div>

      <Tabs items={TABS} active={tab} onChange={setTab} className="mt-5" />

      {tab === 'alerts' && (
        <div className="mt-4 grid gap-4 lg:grid-cols-2">
          <Card className="p-4">
            <div className="flex items-center gap-2 text-[13px] font-medium">
              <TriangleAlert className="h-4 w-4 text-danger" /> 已过期（{alertsRes?.expiredCount ?? 0}）
            </div>
            <div className="mt-3 space-y-2">
              {(alertsRes?.expired ?? []).map((c) => (
                <div key={c.id} className="flex items-center justify-between rounded border border-border px-3 py-2">
                  <div>
                    <div className="text-[13px]">
                      {c.displayName ?? c.username} · {c.certName}
                    </div>
                    <div className="text-xs text-faint">
                      有效期至 {c.validUntil ?? '—'}，已过期 {c.overdueDays} 天
                    </div>
                  </div>
                  <Badge tone="danger">已过期</Badge>
                </div>
              ))}
              {!alertsRes?.expired?.length && <div className="text-xs text-faint">暂无过期证书</div>}
            </div>
          </Card>
          <Card className="p-4">
            <div className="flex items-center gap-2 text-[13px] font-medium">
              <ShieldAlert className="h-4 w-4 text-warn" /> 即将到期（{alertsRes?.expiringCount ?? 0}）
            </div>
            <div className="mt-3 space-y-2">
              {(alertsRes?.expiring ?? []).map((c) => (
                <div key={c.id} className="flex items-center justify-between rounded border border-border px-3 py-2">
                  <div>
                    <div className="text-[13px]">
                      {c.displayName ?? c.username} · {c.certName}
                    </div>
                    <div className="text-xs text-faint">
                      有效期至 {c.validUntil ?? '—'}，剩余 {c.daysLeft} 天
                    </div>
                  </div>
                  <Badge tone={(c.daysLeft ?? 99) <= 7 ? 'danger' : 'warning'}>{c.daysLeft} 天</Badge>
                </div>
              ))}
              {!alertsRes?.expiring?.length && <div className="text-xs text-faint">30 天内无到期证书</div>}
            </div>
          </Card>
        </div>
      )}

      {tab === 'ledger' && (
        <Card className="mt-4 p-4">
          <div className="mb-3 flex items-center gap-2 text-[13px] font-medium">
            <BadgeCheck className="h-4 w-4 text-primary" /> 证书台账（{certs.length}）
          </div>
          <div className="overflow-x-auto">
            <table className="w-full text-[13px]">
              <thead>
                <tr className="border-b border-border text-left text-xs text-muted-foreground">
                  <th className="py-2 pr-3">人员</th>
                  <th className="py-2 pr-3">类型</th>
                  <th className="py-2 pr-3">证书名称</th>
                  <th className="py-2 pr-3">能力项</th>
                  <th className="py-2 pr-3">发证机构</th>
                  <th className="py-2 pr-3">有效期至</th>
                  <th className="py-2 pr-3">状态</th>
                  {canManage && <th className="py-2">操作</th>}
                </tr>
              </thead>
              <tbody>
                {certs.map((c) => (
                  <tr key={c.id} className="border-b border-border/60">
                    <td className="py-2 pr-3">{c.displayName ?? c.username}</td>
                    <td className="py-2 pr-3">{(CERT_TYPE_TEXT as Record<string, string>)[c.certType ?? ''] ?? c.certType}</td>
                    <td className="py-2 pr-3">{c.certName}</td>
                    <td className="py-2 pr-3">{c.capability ? (CAPABILITY_TEXT as Record<string, string>)[c.capability] ?? c.capability : '—'}</td>
                    <td className="py-2 pr-3 text-xs text-muted-foreground">{c.issuedBy ?? '—'}</td>
                    <td className="num py-2 pr-3">{c.validUntil ?? '—'}</td>
                    <td className="py-2 pr-3">
                      <Badge tone={(STATUS_TONE as Record<string, BadgeTone>)[c.status ?? ''] ?? 'muted'}>{(STATUS_TEXT as Record<string, string>)[c.status ?? ''] ?? c.status}</Badge>
                    </td>
                    {canManage && (
                      <td className="py-2">
                        {c.status !== 'REVOKED' && (
                          <Button size="sm" variant="ghost" onClick={() => revoke.mutate({ id: c.id, reason: '管理吊销' })}>
                            吊销
                          </Button>
                        )}
                      </td>
                    )}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {canManage && (
            <div className="mt-5 rounded border border-border p-3">
              <div className="mb-3 text-[13px] font-medium">登记新证书</div>
              <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
                <Field label="用户名">
                  <Input
                    value={form.username}
                    onChange={(e) => setForm({ ...form, username: e.target.value })}
                    placeholder="operator"
                  />
                </Field>
                <Field label="证书类型">
                  <select
                    className="h-9 w-full rounded-md border border-border bg-background px-2 text-[13px]"
                    value={form.certType}
                    onChange={(e) => setForm({ ...form, certType: e.target.value })}
                  >
                    <option value="QUALIFICATION">岗位资质</option>
                    <option value="HEALTH">健康证</option>
                  </select>
                </Field>
                {form.certType === 'QUALIFICATION' && (
                  <Field label="能力项">
                    <select
                      className="h-9 w-full rounded-md border border-border bg-background px-2 text-[13px]"
                      value={form.capability}
                      onChange={(e) => setForm({ ...form, capability: e.target.value })}
                    >
                      {Object.entries(CAPABILITY_TEXT).map(([k, v]) => (
                        <option key={k} value={k}>
                          {v}
                        </option>
                      ))}
                    </select>
                  </Field>
                )}
                <Field label="有效期至">
                  <Input type="date" value={form.validUntil} onChange={(e) => setForm({ ...form, validUntil: e.target.value })} />
                </Field>
                <Field label="证书编号">
                  <Input value={form.certNo} onChange={(e) => setForm({ ...form, certNo: e.target.value })} />
                </Field>
                <Field label="发证机构">
                  <Input value={form.issuedBy} onChange={(e) => setForm({ ...form, issuedBy: e.target.value })} />
                </Field>
              </div>
              <div className="mt-3">
                <Button size="sm" variant="primary" onClick={submit} disabled={!form.username || issue.isPending}>
                  {issue.isPending ? '提交中…' : '登记发证'}
                </Button>
                {issue.isError && <span className="ml-3 text-xs text-danger">{issue.error?.message}</span>}
              </div>
            </div>
          )}
        </Card>
      )}

      {tab === 'capability' && (
        <Card className="mt-4 p-4">
          <div className="mb-3 flex items-center gap-2 text-[13px] font-medium">
            <Award className="h-4 w-4 text-primary" /> 能力项矩阵（持证人数 / 是否强制门禁）
          </div>
          <div className="grid gap-3 md:grid-cols-2 lg:grid-cols-3">
            {caps.map((c) => (
              <div key={c.code} className="rounded border border-border p-3">
                <div className="flex items-center justify-between">
                  <span className="text-[13px] font-medium">{c.name}</span>
                  <Badge tone={c.enforced ? 'success' : 'muted'}>{c.enforced ? '强制校验' : '未启用'}</Badge>
                </div>
                <div className="mt-1 text-xs text-muted-foreground">{c.description}</div>
                <div className="mt-2 flex items-center gap-3 text-xs text-faint">
                  <span>
                    持证 <b className="num">{c.holders ?? 0}</b> 人
                  </span>
                  <span>建议角色 {c.requiredRole}</span>
                </div>
              </div>
            ))}
          </div>
        </Card>
      )}
    </div>
  )
}
