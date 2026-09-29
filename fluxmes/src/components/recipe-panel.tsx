import { useState } from 'react'
import {
  CheckCircle2,
  FileDiff,
  GitMerge,
  Loader2,
  Send,
  Sparkles,
  Workflow,
  XCircle,
} from 'lucide-react'
import { Badge, Button, Card, Input } from './ui'
import {
  useActivateVersion,
  useApproveVersion,
  useCreateDraft,
  useRecipeImpact,
  useRecipeVersions,
  useSubmitVersion,
  VERSION_ROLE,
} from '../api/recipes'
import { getSession } from '../lib/auth'
import { cn } from '../lib/utils'

/**
 * H2 · 配方版本受控面板。
 *
 * 覆盖 recipe-management FR-4 ~ FR-7：
 * - 基于已有版本创建草稿（次版本递增）
 * - 提交审批 / 审批 / 原子生效切换（状态机非法流转由服务端 409 拒绝）
 * - 变更影响面分析：执行旧版本的在制批次 + 是否需重评检验方法
 */

export function VersionControlPanel({ code, version }: { code?: string; version?: string }) {
  const session = getSession()
  const canEdit = ['OPERATOR', 'SUPERVISOR', 'ADMIN'].includes(session?.role ?? '')
  const canApprove = session?.role === 'ADMIN'

  const rc = code ?? ''
  const { data: versions = [] } = useRecipeVersions(rc)
  const { data: impact } = useRecipeImpact(rc, version)
  const [changeNote, setChangeNote] = useState('')
  const [comment, setComment] = useState('')
  const [msg, setMsg] = useState<{ tone: 'accent' | 'warn' | 'danger'; text: string } | null>(null)

  const draftMut = useCreateDraft()
  const submitMut = useSubmitVersion()
  const approveMut = useApproveVersion()
  const activateMut = useActivateVersion()
  const busy = draftMut.isPending || submitMut.isPending || approveMut.isPending || activateMut.isPending

  const pending = versions.filter((v) => v.status === 'pending')
  const effective = versions.find((v) => v.status === 'effective')

  const notify = (tone: 'accent' | 'warn' | 'danger', text: string) => setMsg({ tone, text })

  return (
    <div className="space-y-4" data-component="recipe-version-control">
      {/* 版本列表 */}
      <Card className="card-pad" data-component="recipe-versions">
        <div className="mb-2 flex items-center justify-between">
          <div className="flex items-center gap-2">
            <Workflow className="h-4 w-4 text-faint" />
            <div className="text-[15px] font-semibold tracking-tight">版本受控（recipe_version）</div>
          </div>
          <Badge tone={pending.length ? 'primary' : 'muted'} dot={false}>
            {pending.length} 个待审批
          </Badge>
        </div>

        {versions.length ? (
          <div className="overflow-x-auto">
            <table className="w-full text-[13px]">
              <thead>
                <tr className="border-y border-border text-left text-xs text-muted-foreground">
                  <th className="py-2 pr-2 font-medium">版本</th>
                  <th className="py-2 pr-2 font-medium">状态</th>
                  <th className="py-2 pr-2 font-medium">参数</th>
                  <th className="py-2 pr-2 font-medium">审批人</th>
                  <th className="py-2 font-medium">操作</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {versions.map((v) => {
                  const role = VERSION_ROLE[v.status] ?? { label: v.status, tone: 'muted' }
                  return (
                    <tr key={v.version}>
                      <td className="py-2.5 pr-2">
                        <div className="num font-medium text-primary">{v.version}</div>
                        <div className="num text-[11px] text-faint">派生自 {v.sourceVersion ?? '—'}</div>
                      </td>
                      <td className="py-2.5 pr-2">
                        <Badge tone={role.tone} dot={false}>{role.label}</Badge>
                      </td>
                      <td className="num py-2.5 pr-2 text-xs text-muted-foreground">{v.stepCount ?? 0} 项</td>
                      <td className="num py-2.5 pr-2 text-xs text-muted-foreground">{v.approvedBy ?? '—'}</td>
                      <td className="py-2.5">
                        <div className="flex flex-wrap items-center gap-1">
                          {v.status === 'draft' && canEdit && (
                            <Button
                              size="sm"
                              variant="ghost"
                              disabled={busy}
                              onClick={async () => {
                                try {
                                  await submitMut.mutateAsync({ code: rc, version: v.version })
                                  notify('accent', `${v.version} 已提交审批`)
                                } catch (e) {
                                  notify('danger', String((e as Error)?.message ?? e))
                                }
                              }}
                            >
                              <Send className="h-3.5 w-3.5" /> 提交
                            </Button>
                          )}
                          {v.status === 'pending' && canApprove && (
                            <>
                              <Button
                                size="sm"
                                variant="ghost"
                                disabled={busy}
                                onClick={async () => {
                                  try {
                                    const r = await approveMut.mutateAsync({
                                      code: rc, version: v.version, approved: true, comment: comment || undefined,
                                    })
                                    notify('accent', `已生效，停用旧版本 ${(r?.deactivated ?? []).join(', ') || '—'}`)
                                  } catch (e) {
                                    notify('danger', String((e as Error)?.message ?? e))
                                  }
                                }}
                              >
                                <CheckCircle2 className="h-3.5 w-3.5" /> 通过
                              </Button>
                              <Button
                                size="sm"
                                variant="ghost"
                                disabled={busy}
                                onClick={async () => {
                                  try {
                                    await approveMut.mutateAsync({
                                      code: rc, version: v.version, approved: false, comment: comment || '驳回',
                                    })
                                    notify('warn', `${v.version} 已驳回`)
                                  } catch (e) {
                                    notify('danger', String((e as Error)?.message ?? e))
                                  }
                                }}
                              >
                                <XCircle className="h-3.5 w-3.5" /> 驳回
                              </Button>
                            </>
                          )}
                          {['obsolete', 'rejected'].includes(v.status) && canApprove && (
                            <Button
                              size="sm"
                              variant="ghost"
                              disabled={busy}
                              onClick={async () => {
                                try {
                                  await activateMut.mutateAsync({ code: rc, version: v.version, comment: comment || undefined })
                                  notify('accent', `${v.version} 已重新生效（旧版本同步停用）`)
                                } catch (e) {
                                  notify('danger', String((e as Error)?.message ?? e))
                                }
                              }}
                            >
                              <GitMerge className="h-3.5 w-3.5" /> 生效
                            </Button>
                          )}
                          {v.status === 'effective' && <span className="text-xs text-faint">当前生效</span>}
                        </div>
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        ) : (
          <div className="py-6 text-center text-xs text-faint">该配方尚未纳入版本受控</div>
        )}
      </Card>

      {/* 生命周期操作 */}
      <Card className="card-pad" data-component="recipe-lifecycle">
        <div className="mb-2 text-[15px] font-semibold tracking-tight">版本操作</div>
        {canEdit ? (
          <div className="grid gap-2 lg:grid-cols-4">
            <Input
              placeholder="基于当前生效版本建草稿"
              value={changeNote}
              onChange={(e) => setChangeNote(e.target.value)}
              className="h-8 text-xs lg:col-span-2"
            />
            <Input
              placeholder="审批意见（可选）"
              value={comment}
              onChange={(e) => setComment(e.target.value)}
              className="h-8 text-xs"
            />
            <Button
              size="sm"
              variant="primary"
              disabled={busy || !rc}
              onClick={async () => {
                try {
                  const r = await draftMut.mutateAsync({
                    code: rc,
                    fromVersion: version ?? effective?.version ?? undefined,
                    changeNote: changeNote || undefined,
                  })
                  notify('accent', `草稿 ${r?.version ?? ''} 已创建（复制 ${r?.sourceVersion ?? ''} 参数）`)
                  setChangeNote('')
                } catch (e) {
                  notify('danger', String((e as Error)?.message ?? e))
                }
              }}
            >
              {draftMut.isPending ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Sparkles className="h-3.5 w-3.5" />}
              建草稿
            </Button>
          </div>
        ) : (
          <div className="text-xs text-faint">仅工艺员及以上可创建草稿，审批与生效切换需管理员（C4）</div>
        )}
        <div className="num mt-2 text-[11px] text-faint">
          流转规则：draft → pending → effective ⇄ obsolete；审批与生效均写入审计日志（C3 / FR-10）
        </div>
        {msg && (
          <div className={cn(
            'mt-2 text-[12px]',
            msg.tone === 'danger' ? 'text-danger' : msg.tone === 'warn' ? 'text-warn' : 'text-accent',
          )}>
            {msg.text}
          </div>
        )}
      </Card>

      {/* 变更影响 */}
      <Card className="card-pad" data-component="recipe-impact">
        <div className="mb-2 flex items-center gap-2">
          <FileDiff className="h-4 w-4 text-faint" />
          <div className="text-[15px] font-semibold tracking-tight">变更影响分析</div>
        </div>
        {impact ? (
          <>
            <div className="grid grid-cols-3 gap-2">
              <div className="rounded-md bg-muted px-3 py-2">
                <div className="label-tech">受影响批次</div>
                <div className="num mt-1 text-[17px] font-semibold">{impact.affectedBatchCount ?? 0}</div>
              </div>
              <div className="rounded-md bg-muted px-3 py-2">
                <div className="label-tech">版本迁移</div>
                <div className="num mt-1 text-[13px] font-semibold">
                  {impact.fromVersion ?? '—'} → {impact.toVersion ?? '—'}
                </div>
              </div>
              <div className="rounded-md bg-muted px-3 py-2">
                <div className="label-tech">检验方法</div>
                <div className="mt-1 text-[13px] font-semibold">
                  {impact.reviewRequired ? (
                    <Badge tone="warn" dot={false}>需重评</Badge>
                  ) : (
                    <Badge tone="accent" dot={false}>无需重评</Badge>
                  )}
                </div>
              </div>
            </div>
            {impact.reviewRequired && (
              <div className="mt-2 rounded-md border border-warn/30 bg-warn/5 px-3 py-2 text-[12px] text-warn">
                {impact.reviewReason}
              </div>
            )}
            {(impact.affectedBatches?.length ?? 0) > 0 && (
              <div className="mt-2 flex flex-wrap gap-1.5">
                {impact.affectedBatches?.map((b) => (
                  <Badge key={b.id} tone={b.released ? 'danger' : 'primary'} dot={false}>
                    {b.id} · {b.status}
                    {b.released ? ' · 已放行' : ''}
                  </Badge>
                ))}
              </div>
            )}
          </>
        ) : (
          <div className="text-xs text-faint">加载影响面…</div>
        )}
      </Card>
    </div>
  )
}
