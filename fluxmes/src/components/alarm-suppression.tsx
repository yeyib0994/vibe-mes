import { useState } from 'react'
import { Plus, ShieldOff, Timer, Trash2 } from 'lucide-react'
import { Badge, Button, Card, Input } from './ui'
import {
  useSuppressions,
  useCreateSuppression,
  useDeleteSuppression,
  useSlaPolicy,
  useSlaSweep,
  useUpdateSlaPolicy,
} from '../api/alarms'
import { getSession } from '../lib/auth'

const LEVEL_TEXT = { critical: '严重', major: '重要', minor: '次要' }

/**
 * D1 · 报警抑制规则与 SLA 面板。
 *
 * SLA：默认 critical 5 / major 15 / minor 30 分钟内必须确认，超时由后端巡检任务自动标记升级
 *      （写审计 + SSE 推送 escalated 事件）。
 * G1：响应时限改为运行时可配置（alarm_sla_policy 表），值班长可在此调整；停用（enabled=false）
 *     表示该级别豁免考核。政策变更只影响此后新建的报警，在途报警保留创建时的时限（审计口径）。
 * 抑制：同一报警源在窗口期内重复触发时只计数不入库，避免报警洪水（alarm flood）。
 *      规则维护需值班长（SUPERVISOR）及以上，后端 RBAC 二次校验。
 */
export function SuppressionPanel() {
  const session = getSession()
  const canManage = session?.role === 'SUPERVISOR' || session?.role === 'ADMIN'
  const { data: policy = [] } = useSlaPolicy()
  const updatePolicy = useUpdateSlaPolicy()
  const { data: rules = [] } = useSuppressions()
  const create = useCreateSuppression()
  const remove = useDeleteSuppression()
  const sweep = useSlaSweep()
  const [open, setOpen] = useState(false)
  const [form, setForm] = useState({
    name: '',
    source: '',
    content: '',
    level: '',
    windowMinutes: '10',
    reason: '',
  })

  function submit() {
    create.mutate({
      name: form.name,
      source: form.source || null,
      content: form.content || null,
      level: form.level || null,
      windowMinutes: Number(form.windowMinutes) || 10,
      reason: form.reason || null,
    })
    setForm({ name: '', source: '', content: '', level: '', windowMinutes: '10', reason: '' })
    setOpen(false)
  }

  return (
    <Card className="card-pad mt-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <div className="flex items-center gap-2 text-[13px] font-medium">
            <Timer className="h-4 w-4 text-primary" />
            报警响应 SLA 与抑制策略
          </div>
          <div className="mt-1 text-xs text-muted-foreground">
            响应时限：
            {policy.length
              ? policy.map((p, i) => (
                  <span key={p.level}>
                    {i > 0 && ' · '}
                    {LEVEL_TEXT[p.level] ?? p.level}{' '}
                    <b className="num">{p.enabled ? p.minutes : '不限'}</b>
                    {p.enabled ? ' min' : ''}
                  </span>
                ))
              : '严重 5 min · 重要 15 min · 次要 30 min'}
            ；超时未确认自动升级至值班长并写入审计。
          </div>
        </div>
        <div className="flex items-center gap-2">
          {canManage && (
            <Button size="sm" variant="outline" onClick={() => sweep.mutate()} disabled={sweep.isPending}>
              <Timer className="h-3.5 w-3.5" /> {sweep.isPending ? '巡检中…' : '立即巡检 SLA'}
            </Button>
          )}
          {canManage && (
            <Button size="sm" variant="primary" onClick={() => setOpen((v) => !v)}>
              <Plus className="h-3.5 w-3.5" /> 新建抑制规则
            </Button>
          )}
        </div>
      </div>

      {/* G1 · SLA 政策可配置：值班长可调整各级别响应时限与启停 */}
      {policy.length > 0 && (
        <div className="mt-3 grid gap-2 rounded-md border border-border bg-muted/30 p-3 md:grid-cols-3">
          {policy.map((p) => (
            <div key={p.level} className="flex items-center gap-2">
              <span className="w-10 shrink-0 text-xs text-muted-foreground">
                {LEVEL_TEXT[p.level] ?? p.level}
              </span>
              <Input
                className="h-8 w-20"
                type="number"
                min={1}
                max={1440}
                defaultValue={p.minutes}
                disabled={!canManage || !p.enabled}
                onBlur={(e) => {
                  const v = Number(e.target.value)
                  if (!canManage || !Number.isFinite(v) || v === p.minutes) return
                  updatePolicy.mutate({ level: p.level, minutes: v, enabled: p.enabled })
                }}
              />
              <span className="text-xs text-faint">min</span>
              {canManage && (
                <Button
                  size="sm"
                  variant="ghost"
                  title={p.enabled ? '停用该级别的 SLA 考核' : '启用该级别的 SLA 考核'}
                  onClick={() => updatePolicy.mutate({ level: p.level, minutes: p.minutes, enabled: !p.enabled })}
                >
                  {p.enabled ? '停用' : '启用'}
                </Button>
              )}
            </div>
          ))}
          {!canManage && (
            <div className="text-xs text-faint md:col-span-3">调整响应时限需值班长及以上角色</div>
          )}
        </div>
      )}

      {open && canManage && (
        <div className="mt-3 grid grid-cols-2 gap-2 rounded-md border border-border bg-muted/30 p-3 lg:grid-cols-6">
          <Input
            placeholder="规则名称 *"
            value={form.name}
            onChange={(e) => setForm({ ...form, name: e.target.value })}
            className="lg:col-span-2"
          />
          <Input
            placeholder="报警源（精确，可空）"
            value={form.source}
            onChange={(e) => setForm({ ...form, source: e.target.value })}
          />
          <Input
            placeholder="内容关键字（可空）"
            value={form.content}
            onChange={(e) => setForm({ ...form, content: e.target.value })}
          />
          <select
            value={form.level}
            onChange={(e) => setForm({ ...form, level: e.target.value })}
            className="h-8 rounded-md border border-border bg-card px-2 text-xs"
            aria-label="级别"
          >
            <option value="">全部级别</option>
            <option value="critical">严重</option>
            <option value="major">重要</option>
            <option value="minor">次要</option>
          </select>
          <Input
            placeholder="抑制窗口（分钟）"
            value={form.windowMinutes}
            onChange={(e) => setForm({ ...form, windowMinutes: e.target.value })}
          />
          <div className="col-span-2 flex items-center gap-2 lg:col-span-6">
            <Input
              placeholder="抑制原因（可空）"
              value={form.reason}
              onChange={(e) => setForm({ ...form, reason: e.target.value })}
              className="flex-1"
            />
            <Button size="sm" variant="primary" onClick={submit} disabled={!form.name.trim()}>
              保存
            </Button>
            <Button size="sm" variant="ghost" onClick={() => setOpen(false)}>
              取消
            </Button>
          </div>
        </div>
      )}

      <div className="mt-3 overflow-x-auto">
        <table className="w-full text-[13px]">
          <thead>
            <tr className="border-b border-border text-left text-xs text-faint">
              <th className="py-2 pr-3 font-normal">规则名称</th>
              <th className="py-2 pr-3 font-normal">报警源</th>
              <th className="py-2 pr-3 font-normal">内容关键字</th>
              <th className="py-2 pr-3 font-normal">级别</th>
              <th className="py-2 pr-3 font-normal">窗口</th>
              <th className="py-2 pr-3 font-normal">创建人</th>
              {canManage && <th className="py-2 font-normal">操作</th>}
            </tr>
          </thead>
          <tbody>
            {rules.map((r) => (
              <tr key={r.id} className="border-b border-border/50 last:border-0">
                <td className="py-2 pr-3">
                  <div className="flex items-center gap-1.5">
                    <ShieldOff className="h-3.5 w-3.5 text-muted-foreground" />
                    {r.name}
                  </div>
                </td>
                <td className="py-2 pr-3 text-muted-foreground">{r.source || '—'}</td>
                <td className="py-2 pr-3 text-muted-foreground">{r.content || '—'}</td>
                <td className="py-2 pr-3">
                  {r.level ? <Badge tone={r.level === 'critical' ? 'danger' : 'warn'}>{r.level}</Badge> : '全部'}
                </td>
                <td className="num py-2 pr-3">{r.windowMinutes} min</td>
                <td className="py-2 pr-3 text-muted-foreground">{r.createdBy || '—'}</td>
                {canManage && (
                  <td className="py-2">
                    <Button size="sm" variant="ghost" onClick={() => remove.mutate(r.id)}>
                      <Trash2 className="h-3.5 w-3.5" />
                    </Button>
                  </td>
                )}
              </tr>
            ))}
            {rules.length === 0 && (
              <tr>
                <td colSpan={canManage ? 7 : 6} className="py-6 text-center text-xs text-muted-foreground">
                  暂无抑制规则{canManage && '，可点击「新建抑制规则」按报警源/级别/时间窗口配置'}
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </Card>
  )
}
