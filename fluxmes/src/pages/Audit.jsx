import { useState } from 'react'
import {
  BadgeCheck,
  ClipboardList,
  FileSearch,
  FolderCheck,
  PlusCircle,
  ShieldAlert,
  TrendingUp,
} from 'lucide-react'
import { Badge, Button, Card, Input } from '../components/ui'
import { PageHeader } from '../components/layout'
import {
  AUDIT_TYPES,
  FINDING_SEVERITIES,
  FINDING_TONE,
  useAddFinding,
  useAuditDetail,
  useAuditList,
  useCloseAudit,
  useCreateAudit,
  useFindingToCapa,
  useTransitionAudit,
} from '../api/regtech'
import { hasRole } from '../lib/auth'

/**
 * Phase I · 内审管理（specs/quality-regtech FR-9 ~ FR-13）。
 * 内审计划 → 发现项分级（条款关联）→ 一键转 CAPA → 关闭门禁（critical/major 未转 CAPA 拒绝关闭）。
 */

const AUDIT_STATUS = {
  planned: { label: '已计划', tone: 'muted' },
  in_progress: { label: '审核中', tone: 'primary' },
  reported: { label: '已出报告', tone: 'warn' },
  closed: { label: '已关闭', tone: 'success' },
}

const NEXT_STATUS = { planned: 'in_progress', in_progress: 'reported' }

function StatCard({ icon: Icon, label, value, sub, tone }) {
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

function Field({ label, children }) {
  return (
    <label className="block">
      <span className="mb-1 block text-xs text-muted-foreground">{label}</span>
      {children}
    </label>
  )
}

export default function Audit() {
  const year = new Date().getFullYear()
  const [selected, setSelected] = useState(null)
  const canManage = hasRole('ADMIN')
  const canRegister = hasRole('QC')

  const { data: listRes } = useAuditList({})
  const audits = listRes?.items ?? []
  const summary = listRes?.summary

  const createAudit = useCreateAudit()
  const transition = useTransitionAudit()
  const addFinding = useAddFinding()
  const toCapa = useFindingToCapa()
  const closeAudit = useCloseAudit()

  const [auditForm, setAuditForm] = useState({
    title: '',
    auditType: 'SYSTEM',
    scope: '',
    lead: '',
    planStart: '',
    planEnd: '',
  })
  const [findingForm, setFindingForm] = useState({
    clause: '',
    severity: 'MAJOR',
    description: '',
    area: '',
    owner: '',
  })

  function submitAudit() {
    createAudit.mutate(
      {
        ...auditForm,
        team: ['admin', 'qc'],
        planStart: auditForm.planStart || null,
        planEnd: auditForm.planEnd || null,
      },
      {
        onSuccess: (res) => {
          setAuditForm({ title: '', auditType: 'SYSTEM', scope: '', lead: '', planStart: '', planEnd: '' })
          setSelected(res?.id ?? null)
        },
      },
    )
  }

  function submitFinding(id) {
    addFinding.mutate(
      {
        id,
        clause: findingForm.clause || null,
        severity: findingForm.severity,
        description: findingForm.description,
        area: findingForm.area || null,
        owner: findingForm.owner || null,
      },
      {
        onSuccess: () =>
          setFindingForm({ clause: '', severity: 'MAJOR', description: '', area: '', owner: '' }),
      },
    )
  }

  return (
    <div className="mx-auto max-w-[1400px] p-6">
      <PageHeader
        title="内审管理"
        desc="年度内审计划 · 发现项分级与标准条款关联 · major 及以上一键转 CAPA，未转办将阻断内审关闭"
      />

      <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
        <StatCard icon={FolderCheck} label="内审次数" value={summary?.auditCount ?? '—'} sub={`年度 ${year}`} />
        <StatCard icon={FileSearch} label="发现项" value={summary?.findingCount ?? '—'} sub="四级分级" />
        <StatCard
          icon={ShieldAlert}
          label="未转 CAPA 的 major+"
          value={summary?.openMajorFindings ?? '—'}
          tone="danger"
          sub="阻断内审关闭（FR-12）"
        />
        <StatCard
          icon={TrendingUp}
          label="CAPA 转化率"
          value={summary?.capaConversionRate != null ? `${summary.capaConversionRate}%` : '—'}
          sub={`已转办 ${summary?.capaCreated ?? 0} 项`}
        />
      </div>

      <Card className="mt-5 p-4">
        <div className="mb-3 flex items-center gap-2 text-[13px] font-medium">
          <ClipboardList className="h-4 w-4 text-primary" /> 内审计划（{audits.length}）
        </div>
        <div className="overflow-x-auto">
          <table className="w-full text-[13px]">
            <thead>
              <tr className="border-b border-border text-left text-xs text-muted-foreground">
                <th className="py-2 pr-3">编号</th>
                <th className="py-2 pr-3">类型</th>
                <th className="py-2 pr-3">标题</th>
                <th className="py-2 pr-3">审核组长</th>
                <th className="py-2 pr-3">计划区间</th>
                <th className="py-2 pr-3">发现项</th>
                <th className="py-2 pr-3">状态</th>
                <th className="py-2" />
              </tr>
            </thead>
            <tbody>
              {audits.map((a) => (
                <tr key={a.id} className="border-b border-border/60">
                  <td className="num py-2 pr-3">{a.id}</td>
                  <td className="py-2 pr-3 text-xs">{a.auditTypeLabel}</td>
                  <td className="py-2 pr-3">{a.title}</td>
                  <td className="py-2 pr-3">{a.lead}</td>
                  <td className="num py-2 pr-3 text-xs">
                    {a.planStart} ~ {a.planEnd}
                  </td>
                  <td className="num py-2 pr-3 text-xs">
                    {a.findingCount}
                    {a.blockingFindings > 0 && (
                      <span className="ml-1 text-danger">（{a.blockingFindings} 项待转办）</span>
                    )}
                  </td>
                  <td className="py-2 pr-3">
                    <Badge tone={AUDIT_STATUS[a.status]?.tone ?? 'muted'}>
                      {AUDIT_STATUS[a.status]?.label ?? a.status}
                    </Badge>
                  </td>
                  <td className="py-2 text-right">
                    <Button size="sm" variant="ghost" onClick={() => setSelected(selected === a.id ? null : a.id)}>
                      {selected === a.id ? '收起' : '详情'}
                    </Button>
                  </td>
                </tr>
              ))}
              {!audits.length && (
                <tr>
                  <td colSpan={8} className="py-4 text-center text-xs text-faint">
                    暂无内审计划
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>

        {selected && (
          <AuditDetail
            id={selected}
            canManage={canManage}
            canRegister={canRegister}
            transition={transition}
            addFinding={addFinding}
            toCapa={toCapa}
            closeAudit={closeAudit}
            findingForm={findingForm}
            setFindingForm={setFindingForm}
            onSubmitFinding={submitFinding}
          />
        )}
      </Card>

      {canManage && (
        <Card className="mt-4 p-4">
          <div className="mb-3 flex items-center gap-2 text-[13px] font-medium">
            <PlusCircle className="h-4 w-4 text-primary" /> 新建内审计划
          </div>
          <div className="grid grid-cols-2 gap-3 lg:grid-cols-3">
            <Field label="标题">
              <Input
                value={auditForm.title}
                onChange={(e) => setAuditForm({ ...auditForm, title: e.target.value })}
                placeholder="2026 年度 FSSC 22000 体系内审"
              />
            </Field>
            <Field label="审核类型">
              <select
                className="h-9 w-full rounded-md border border-border bg-background px-2 text-[13px]"
                value={auditForm.auditType}
                onChange={(e) => setAuditForm({ ...auditForm, auditType: e.target.value })}
              >
                {AUDIT_TYPES.map((t) => (
                  <option key={t.value} value={t.value}>
                    {t.label}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="审核组长">
              <Input
                value={auditForm.lead}
                onChange={(e) => setAuditForm({ ...auditForm, lead: e.target.value })}
                placeholder="admin"
              />
            </Field>
            <Field label="计划开始">
              <Input
                type="date"
                value={auditForm.planStart}
                onChange={(e) => setAuditForm({ ...auditForm, planStart: e.target.value })}
              />
            </Field>
            <Field label="计划结束">
              <Input
                type="date"
                value={auditForm.planEnd}
                onChange={(e) => setAuditForm({ ...auditForm, planEnd: e.target.value })}
              />
            </Field>
            <div className="col-span-2 lg:col-span-3">
              <Field label="审核范围">
                <Input
                  value={auditForm.scope}
                  onChange={(e) => setAuditForm({ ...auditForm, scope: e.target.value })}
                  placeholder="车间 / 工序 / 体系要素"
                />
              </Field>
            </div>
          </div>
          <div className="mt-3 flex items-center gap-3">
            <Button
              size="sm"
              variant="primary"
              disabled={
                !auditForm.title || !auditForm.lead || !auditForm.planStart || !auditForm.planEnd || createAudit.isPending
              }
              onClick={submitAudit}
            >
              {createAudit.isPending ? '提交中…' : '创建内审'}
            </Button>
            {createAudit.isError && <span className="text-xs text-danger">{createAudit.error?.message}</span>}
          </div>
        </Card>
      )}
    </div>
  )
}

function AuditDetail({
  id,
  canManage,
  canRegister,
  transition,
  toCapa,
  closeAudit,
  findingForm,
  setFindingForm,
  onSubmitFinding,
}) {
  const { data: a } = useAuditDetail(id)
  const findings = a?.findings ?? []
  const next = NEXT_STATUS[a?.status]

  if (!a) return <div className="mt-4 text-xs text-faint">加载内审详情…</div>

  return (
    <div className="mt-4 rounded border border-border p-4">
      <div className="flex flex-wrap items-center gap-2">
        <span className="num text-[13px] font-medium">{a.id}</span>
        <Badge tone={AUDIT_STATUS[a.status]?.tone ?? 'muted'}>
          {AUDIT_STATUS[a.status]?.label ?? a.status}
        </Badge>
        <span className="text-xs text-faint">{a.scope}</span>
        <div className="ml-auto flex items-center gap-2">
          {canManage && next && (
            <Button
              size="sm"
              variant="outline"
              disabled={transition.isPending}
              onClick={() => transition.mutate({ id, target: next, comment: '页面推进' })}
            >
              推进至 {AUDIT_STATUS[next]?.label ?? next}
            </Button>
          )}
          {canManage && a.status === 'reported' && (
            <Button
              size="sm"
              variant="primary"
              disabled={closeAudit.isPending}
              onClick={() => closeAudit.mutate({ id, comment: '审核报告发布，问题项已转办' })}
            >
              关闭内审（FR-12 门禁）
            </Button>
          )}
        </div>
      </div>

      {transition.isError && <div className="mt-2 text-xs text-danger">{transition.error?.message}</div>}
      {closeAudit.isError && <div className="mt-2 text-xs text-danger">{closeAudit.error?.message}</div>}
      {closeAudit.isSuccess && <div className="mt-2 text-xs text-success">内审已关闭</div>}

      <div className="mt-3 overflow-x-auto">
        <table className="w-full text-[13px]">
          <thead>
            <tr className="border-b border-border text-left text-xs text-muted-foreground">
              <th className="py-2 pr-3">#</th>
              <th className="py-2 pr-3">分级</th>
              <th className="py-2 pr-3">条款</th>
              <th className="py-2 pr-3">描述</th>
              <th className="py-2 pr-3">责任区域</th>
              <th className="py-2 pr-3">责任人</th>
              <th className="py-2 pr-3">CAPA</th>
              <th className="py-2" />
            </tr>
          </thead>
          <tbody>
            {findings.map((f) => (
              <tr key={f.id} className="border-b border-border/60">
                <td className="num py-2 pr-3">{f.seq}</td>
                <td className="py-2 pr-3">
                  <Badge tone={FINDING_TONE[f.severity] ?? 'muted'}>{f.severityLabel}</Badge>
                </td>
                <td className="num py-2 pr-3 text-xs">{f.clause ?? '—'}</td>
                <td className="py-2 pr-3">{f.description}</td>
                <td className="py-2 pr-3 text-xs">{f.area ?? '—'}</td>
                <td className="py-2 pr-3">{f.owner ?? '—'}</td>
                <td className="num py-2 pr-3 text-xs">
                  {f.capaId ? (
                    <span className="text-success">{f.capaId}</span>
                  ) : (
                    <span className="text-faint">未转办</span>
                  )}
                </td>
                <td className="py-2 text-right">
                  {!f.capaId && canRegister && (
                    <Button
                      size="sm"
                      variant="ghost"
                      disabled={toCapa.isPending}
                      onClick={() => toCapa.mutate({ findingId: f.id, owner: f.owner })}
                    >
                      转 CAPA
                    </Button>
                  )}
                </td>
              </tr>
            ))}
            {!findings.length && (
              <tr>
                <td colSpan={8} className="py-3 text-center text-xs text-faint">
                  暂无发现项
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      {toCapa.isError && <div className="mt-2 text-xs text-danger">{toCapa.error?.message}</div>}
      {toCapa.isSuccess && (
        <div className="mt-2 flex items-center gap-1.5 text-xs text-success">
          <BadgeCheck className="h-3.5 w-3.5" /> 已生成 CAPA 并回写发现项
        </div>
      )}

      {canRegister && a.status !== 'closed' && (
        <div className="mt-4 rounded border border-border p-3">
          <div className="mb-2 text-[13px] font-medium">登记发现项（FR-10）</div>
          <div className="grid grid-cols-2 gap-3 lg:grid-cols-3">
            <Field label="分级">
              <select
                className="h-9 w-full rounded-md border border-border bg-background px-2 text-[13px]"
                value={findingForm.severity}
                onChange={(e) => setFindingForm({ ...findingForm, severity: e.target.value })}
              >
                {FINDING_SEVERITIES.map((s) => (
                  <option key={s.value} value={s.value}>
                    {s.label}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="条款号（critical/major 必填）">
              <Input
                value={findingForm.clause}
                onChange={(e) => setFindingForm({ ...findingForm, clause: e.target.value })}
                placeholder="FSSC 22000 8.9.5"
              />
            </Field>
            <Field label="责任区域">
              <Input
                value={findingForm.area}
                onChange={(e) => setFindingForm({ ...findingForm, area: e.target.value })}
                placeholder="一车间 · 精制工段"
              />
            </Field>
            <Field label="责任人（默认审核组长）">
              <Input
                value={findingForm.owner}
                onChange={(e) => setFindingForm({ ...findingForm, owner: e.target.value })}
                placeholder="supervisor"
              />
            </Field>
            <div className="col-span-2">
              <Field label="发现项描述">
                <Input
                  value={findingForm.description}
                  onChange={(e) => setFindingForm({ ...findingForm, description: e.target.value })}
                />
              </Field>
            </div>
          </div>
          <div className="mt-3 flex items-center gap-3">
            <Button
              size="sm"
              variant="primary"
              disabled={!findingForm.description}
              onClick={() => onSubmitFinding(id)}
            >
              登记发现项
            </Button>
            {findingForm.severity === 'MAJOR' || findingForm.severity === 'CRITICAL' ? (
              <span className="text-[11px] text-faint">
                该级别未转 CAPA 时将阻断内审关闭（FR-12）
              </span>
            ) : null}
          </div>
        </div>
      )}
    </div>
  )
}
