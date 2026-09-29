import { useState } from 'react'
import {
  AlertTriangle,
  CheckCircle2,
  FileCheck2,
  Plus,
  ShieldCheck,
  SkipForward,
  X,
} from 'lucide-react'
import { Badge, Button, Input } from './ui'
import { useAdvanceBatch, useCreateBatch, useMarkAbnormal } from '../api/batches'
import { useReleaseBatch } from '../api/quality'
import { useSanitationStatus } from '../api/foodsafety'
import { hasRole } from '../lib/auth'

/** 极简模态：沿用卡片 + 遮罩样式，不引入额外依赖。 */
function Modal({ open, title, onClose, children }) {
  if (!open) return null
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4" onClick={onClose}>
      <div className="card w-full max-w-md p-4" onClick={(e) => e.stopPropagation()}>
        <div className="mb-3 flex items-center justify-between">
          <h3 className="text-[15px] font-semibold tracking-tight">{title}</h3>
          <button className="rounded-md p-1 text-muted-foreground hover:bg-muted" onClick={onClose}>
            <X className="h-4 w-4" />
          </button>
        </div>
        {children}
      </div>
    </div>
  )
}

/** 新建批次（FR-8）：后端校验必填项并返回新批号；需工艺员及以上角色。 */
export function NewBatchButton() {
  const [open, setOpen] = useState(false)
  const [form, setForm] = useState({
    product: '一水柠檬酸',
    recipe: 'R-CA-07',
    recipeVersion: 'v3',
    equipment: 'F-101 发酵罐 #1',
    planYield: '12',
    materialLots: 'RM-玉米粉-0724, RM-硫酸-0721',
    line: 'LINE-1',
    shelfLifeDays: '540',
  })
  const [force, setForce] = useState(false)
  const create = useCreateBatch()
  const canCreate = hasRole('OPERATOR')
  const { data: sanitation } = useSanitationStatus(form.line)
  const cleared = sanitation?.cleared

  const submit = () => {
    create.mutate(
      {
        product: form.product,
        recipe: form.recipe,
        recipeVersion: form.recipeVersion,
        equipment: form.equipment,
        planYield: Number(form.planYield),
        materialLots: form.materialLots.split(/[,，]/).map((s) => s.trim()).filter(Boolean),
        line: form.line,
        shelfLifeDays: Number(form.shelfLifeDays) || 540,
        force: force ? true : undefined,
      },
      { onSuccess: () => setOpen(false) },
    )
  }

  return (
    <>
      <Button variant="primary" disabled={!canCreate} onClick={() => setOpen(true)}>
        <Plus className="h-3.5 w-3.5" /> 新建批次
      </Button>
      <Modal open={open} title="新建批次" onClose={() => setOpen(false)}>
        <div className="space-y-2.5">
          {[
            ['product', '产品'],
            ['recipe', '配方号'],
            ['recipeVersion', '配方版本'],
            ['equipment', '设备'],
            ['planYield', '计划产量 (t)'],
            ['materialLots', '原料批号（逗号分隔）'],
            ['shelfLifeDays', '保质期（天）'],
          ].map(([key, label]) => (
            <label key={key} className="block">
              <span className="label-tech">{label}</span>
              <Input
                className="mt-1"
                value={form[key]}
                onChange={(e) => setForm({ ...form, [key]: e.target.value })}
              />
            </label>
          ))}
          <label className="block">
            <span className="label-tech">产线</span>
            <select
              className="mt-1 h-8 w-full rounded-md border border-border bg-background px-2 text-sm"
              value={form.line}
              onChange={(e) => setForm({ ...form, line: e.target.value })}
            >
              <option value="LINE-1">LINE-1 柠檬酸发酵一线</option>
              <option value="LINE-2">LINE-2 柠檬酸发酵二线</option>
              <option value="LINE-3">LINE-3 精制包装线</option>
            </select>
          </label>
          <div
            className={`rounded-md border px-2.5 py-2 text-[13px] ${
              cleared ? 'border-accent/40 bg-accent/5 text-accent' : 'border-danger-soft bg-danger-soft text-danger'
            }`}
          >
            <div className="flex items-center gap-1.5">
              <ShieldCheck className="h-3.5 w-3.5" />
              清场门禁：{cleared ? '产线清场有效，允许开工' : `禁止开工 — ${sanitation?.reason ?? '校验中…'}`}
            </div>
            {!cleared && hasRole('SUPERVISOR') && (
              <label className="mt-2 flex items-center gap-2 text-foreground">
                <input type="checkbox" checked={force} onChange={(e) => setForce(e.target.checked)} />
                <span className="text-xs">强制开工（值班长特权，将写入审计日志）</span>
              </label>
            )}
          </div>
          {create.isError && (
            <div className="rounded-md border border-danger-soft bg-danger-soft px-2.5 py-2 text-[13px] text-danger">
              {(create.error as Error)?.message}
            </div>
          )}
          <div className="flex justify-end gap-2 pt-1">
            <Button variant="ghost" onClick={() => setOpen(false)}>
              取消
            </Button>
            <Button variant="primary" onClick={submit} disabled={create.isPending}>
              {create.isPending ? '提交中…' : '提交'}
            </Button>
          </div>
        </div>
      </Modal>
    </>
  )
}

/** 批次行操作：工序推进 / 标记异常（自动建偏差单）/ 成品放行生成 COA。 */
export function BatchRowActions({ batch }) {
  const advance = useAdvanceBatch()
  const abnormal = useMarkAbnormal()
  const release = useReleaseBatch()
  const locked = batch.released
  const pending = advance.isPending || abnormal.isPending || release.isPending
  const err = advance.error || abnormal.error || release.error

  if (locked) {
    return (
      <div className="flex items-center gap-2 border-t border-border px-4 py-2.5 text-xs text-muted-foreground">
        <FileCheck2 className="h-3.5 w-3.5 text-accent" />
        <span className="num">已放行 · COA {batch.coaNo ?? '—'}</span>
        <Badge tone="accent" dot={false}>
          放行后只读锁定（C2）
        </Badge>
      </div>
    )
  }

  return (
    <div className="flex flex-wrap items-center gap-2 border-t border-border px-4 py-2.5">
      <Button
        size="sm"
        variant="ghost"
        disabled={pending || batch.status === 'abnormal'}
        onClick={() => advance.mutate(batch.id)}
      >
        <SkipForward className="h-3.5 w-3.5" /> 推进工序
      </Button>
      <Button
        size="sm"
        variant="ghost"
        disabled={pending}
        onClick={() => {
          const reason = window.prompt('异常原因（将自动创建偏差单）', '过程参数偏离')
          if (reason === null) return
          abnormal.mutate({ id: batch.id, reason })
        }}
      >
        <AlertTriangle className="h-3.5 w-3.5" /> 标记异常
      </Button>
      <Button
        size="sm"
        variant="primary"
        disabled={pending || !hasRole('QC') || (batch.status !== 'waiting' && batch.status !== 'done')}
        onClick={() => release.mutate(batch.id)}
      >
        <CheckCircle2 className="h-3.5 w-3.5" /> 放行并生成 COA
      </Button>
      {batch.deviationNo && <Badge tone="warn">偏差单 {batch.deviationNo}</Badge>}
      {err && <span className="text-xs text-danger">{(err as Error).message}</span>}
    </div>
  )
}
