import { useEffect, useState } from 'react'
import { FileDown, KeyRound, Lock, ShieldCheck, TriangleAlert } from 'lucide-react'
import { Badge, Button, Input } from './ui'
import {
  MEANING_LABEL,
  downloadText,
  useExportSignatures,
  useMySignatureAttempts,
  useSignatureList,
  useSignaturePolicy,
  useVerifySignature,
} from '../api/regtech'
import { hasRole } from '../lib/auth'

/**
 * 电子签名组件（21 CFR Part 11 / EU GMP 附录 11）。
 *
 * `SignatureDialog`：受控动作的二次身份确认（密码）+ 含义声明。签名与业务动作同一次请求提交
 *   （后端在业务校验通过后才要求签名，避免无效签名被消耗）。
 * `SignatureList`：某记录（批次 / 偏差 / CAPA / 配方版本）的签名清单，支持哈希复核（篡改检测）与
 *   证据包导出。
 */

const EMPTY = '—'

function statusTone(status) {
  if (status === 'VALID') return 'success'
  if (status === 'VOID') return 'muted'
  return 'muted'
}

export function SignatureDialog({
  open,
  action,
  recordType,
  recordId,
  headline,
  hint,
  busy,
  error,
  onCancel,
  onConfirm,
}) {
  const { data: policyRes } = useSignaturePolicy()
  const policy = (policyRes?.items ?? []).find((p) => p.action === action)
  const { data: attempt } = useMySignatureAttempts({ enabled: open })
  const [password, setPassword] = useState('')

  useEffect(() => {
    if (open) setPassword('')
  }, [open, action, recordId])

  if (!open) return null

  const meaning = policy?.requiredMeaning ?? 'APPROVED'
  const locked = !!attempt?.locked
  const remaining = Math.max(0, (attempt?.maxFailures ?? 5) - (attempt?.failedCount ?? 0))

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4">
      <div className="w-full max-w-md rounded-lg border border-border bg-background p-5 shadow-lg">
        <div className="flex items-center gap-2">
          <ShieldCheck className="h-4 w-4 text-primary" />
          <h2 className="text-[15px] font-semibold">电子签名确认</h2>
          <Badge tone="accent" className="ml-auto">
            Part 11
          </Badge>
        </div>

        <div className="mt-3 rounded border border-border p-3 text-[13px]">
          <div className="font-medium">{headline ?? action}</div>
          <div className="num mt-1 text-xs text-muted-foreground">
            {recordType} · {recordId ?? EMPTY}
          </div>
          <div className="mt-2 flex items-center gap-2 text-xs text-muted-foreground">
            <span>签名含义</span>
            <Badge tone="primary">
              {meaning} · {MEANING_LABEL[meaning] ?? meaning}
            </Badge>
            <span>所需角色 {policy?.requiredRole ?? '—'}</span>
          </div>
        </div>

        <label className="mt-4 block">
          <span className="mb-1 block text-xs text-muted-foreground">
            请输入登录密码以完成二次身份确认
          </span>
          <Input
            type="password"
            autoFocus
            value={password}
            disabled={locked || busy}
            onChange={(e) => setPassword(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && password && !locked && !busy) onConfirm({ meaning, password })
            }}
            placeholder="登录密码"
          />
        </label>

        {locked && (
          <div className="mt-3 flex items-start gap-2 rounded border border-danger bg-danger-soft p-2 text-xs text-danger">
            <Lock className="mt-0.5 h-3.5 w-3.5" />
            <span>
              签名能力已锁定（连续 {attempt?.maxFailures} 次密码校验失败），
              解锁时间 {attempt?.lockedUntil ?? EMPTY}
            </span>
          </div>
        )}

        {!locked && remaining < (attempt?.maxFailures ?? 5) && (
          <div className="mt-3 flex items-start gap-2 rounded border border-warn bg-warn-soft p-2 text-xs text-warn">
            <TriangleAlert className="mt-0.5 h-3.5 w-3.5" />
            <span>
              已有 {attempt?.failedCount} 次失败，再失败 {remaining} 次将锁定 {attempt?.lockMinutes} 分钟
            </span>
          </div>
        )}

        {hint && <div className="mt-3 text-xs text-faint">{hint}</div>}
        {error && <div className="mt-3 text-xs text-danger">{error}</div>}

        <div className="mt-4 flex items-center justify-end gap-2">
          <Button size="sm" variant="ghost" onClick={onCancel} disabled={busy}>
            取消
          </Button>
          <Button
            size="sm"
            variant="primary"
            disabled={!password || locked || busy}
            onClick={() => onConfirm({ meaning, password })}
          >
            <KeyRound className="mr-1.5 h-3.5 w-3.5" />
            {busy ? '签名中…' : '签名并提交'}
          </Button>
        </div>
        <div className="mt-3 text-[11px] leading-relaxed text-faint">
          签名将记录：签名人 / 姓名 / 当时角色 / 服务器时间 / 含义 / 记录类型与 ID / 记录修订号 /
          记录内容 SHA-256 哈希 / IP 与 User-Agent。签名记录只追加，不可修改或删除。
        </div>
      </div>
    </div>
  )
}

export function SignatureList({ recordType, recordId, compact = false }) {
  const { data, isLoading } = useSignatureList({ recordType, recordId })
  const verify = useVerifySignature()
  const exportSig = useExportSignatures()
  const rows = data?.items ?? []
  const canExport = hasRole('ADMIN')

  return (
    <div>
      <div className="mb-2 flex items-center gap-2">
        <ShieldCheck className="h-3.5 w-3.5 text-primary" />
        <span className="text-[13px] font-medium">电子签名（{rows.length}）</span>
        {canExport && (
          <Button
            size="sm"
            variant="ghost"
            className="ml-auto"
            disabled={exportSig.isPending}
            onClick={() =>
              exportSig.mutate(
                { recordType, recordId, format: 'md' },
                {
                  onSuccess: (res) =>
                    downloadText(res?.filename ?? 'signatures.md', res?.markdown ?? '', 'text/markdown'),
                },
              )
            }
          >
            <FileDown className="mr-1 h-3 w-3" />
            导出证据包
          </Button>
        )}
      </div>

      {isLoading && <div className="text-xs text-faint">加载中…</div>}
      {!isLoading && !rows.length && (
        <div className="text-xs text-faint">该记录暂无电子签名</div>
      )}

      {!!rows.length && (
        <div className="overflow-x-auto">
          <table className="w-full text-[13px]">
            <thead>
              <tr className="border-b border-border text-left text-xs text-muted-foreground">
                <th className="py-2 pr-3">签名人</th>
                <th className="py-2 pr-3">角色</th>
                <th className="py-2 pr-3">服务器时间</th>
                <th className="py-2 pr-3">含义</th>
                <th className="py-2 pr-3">动作</th>
                <th className="py-2 pr-3">版本</th>
                <th className="py-2 pr-3">哈希</th>
                <th className="py-2 pr-3">状态</th>
                <th className="py-2">复核</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((s) => (
                <tr key={s.id} className="border-b border-border/60">
                  <td className="py-2 pr-3">
                    {s.signerName ?? s.signer}
                    <span className="num ml-1 text-xs text-faint">{s.signer}</span>
                  </td>
                  <td className="py-2 pr-3">{s.signerRole}</td>
                  <td className="num py-2 pr-3 text-xs">{(s.signedAt ?? '').replace('T', ' ').slice(0, 19)}</td>
                  <td className="py-2 pr-3">
                    <Badge tone="primary">{s.meaningLabel ?? s.meaning}</Badge>
                  </td>
                  <td className="py-2 pr-3 text-xs">{s.action}</td>
                  <td className="num py-2 pr-3">v{s.recordVersion}</td>
                  <td className="num py-2 pr-3 text-[11px] text-faint" title={s.payloadHash}>
                    {(s.payloadHash ?? '').slice(0, 12)}…
                  </td>
                  <td className="py-2 pr-3">
                    <Badge tone={statusTone(s.status)}>{s.status === 'VALID' ? '有效' : '已作废'}</Badge>
                  </td>
                  <td className="py-2">
                    <Button size="sm" variant="ghost" onClick={() => verify.mutate(s.id)}>
                      校验
                    </Button>
                    {verify.data && verify.variables === s.id && (
                      <span className={`ml-2 text-xs ${verify.data.tampered ? 'text-danger' : 'text-success'}`}>
                        {verify.data.tampered ? '记录已被改动' : '哈希一致'}
                        {verify.data.stale ? ' · 已修订需重签' : ''}
                      </span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {!compact && verify.data && (
        <div className="mt-3 rounded border border-border p-3 text-xs">
          <div className="mb-1 font-medium">哈希复核结果</div>
          <div className="grid gap-1 md:grid-cols-2">
            <div>
              签名时哈希：<span className="num text-faint">{verify.data.payloadHash}</span>
            </div>
            <div>
              当前重算：<span className="num text-faint">{verify.data.currentHash ?? EMPTY}</span>
            </div>
            <div>
              篡改（tampered）：
              <Badge tone={verify.data.tampered ? 'danger' : 'success'}>
                {String(verify.data.tampered)}
              </Badge>
            </div>
            <div>
              已修订（stale）：
              <Badge tone={verify.data.stale ? 'warn' : 'success'}>
                {String(verify.data.stale)} v{verify.data.recordVersion} → v{verify.data.currentRecordVersion}
              </Badge>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
