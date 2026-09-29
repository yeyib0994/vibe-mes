import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { request } from './http'

/**
 * G3 · 配料称量与容差校验服务层。
 * 与后端 api-java/com/fluxmes/api/weighing/WeighingController 一一对应。
 *
 * 容差判定：偏差% =（实际 − 目标）/ 目标 × 100；|偏差| ≤ 容差判 PASS，
 * 否则 OVER / UNDER 并自动建偏差单 + major 报警、任务置 BLOCKED，须 QC 复核。
 */

const REFRESH_MS = 30_000

export const RESULT_TONE = { PASS: 'success', OVER: 'danger', UNDER: 'warning' }
export const RESULT_TEXT = { PASS: '合格', OVER: '超出上限', UNDER: '低于下限' }
export const TASK_STATUS_TONE = { OPEN: 'primary', BLOCKED: 'danger', DONE: 'success' }
export const TASK_STATUS_TEXT = { OPEN: '进行中', BLOCKED: '超差阻断', DONE: '已完成' }

/** 单次称量记录行。 */
export type WeighingItem = {
  id: number | string
  seq?: number
  actualQty?: number
  deviationPct?: number
  result?: string
  operator?: string | null
  reviewer?: string | null
  deviationId?: number | string | null
  [key: string]: unknown
}

/** 称量任务（含明细）。 */
export type WeighingTask = {
  id: number | string
  batchId?: string
  materialCode?: string | null
  materialName?: string | null
  targetQty?: number
  totalWeighed?: number
  unit?: string
  tolerancePct?: number
  progressPercent?: number
  status?: string
  items?: WeighingItem[]
  [key: string]: unknown
}

/** 称量汇总 KPI（GET /api/weighing/summary）。 */
export type WeighingSummary = {
  taskCount?: number
  weighCount?: number
  passCount?: number
  outOfToleranceCount?: number
  overCount?: number
  underCount?: number
  passRatePercent?: number
  pendingReviewCount?: number
  [key: string]: unknown
}

export function useWeighingTasks(params: { batchId?: string; status?: string } = {}) {
  const qs = new URLSearchParams()
  if (params.batchId) qs.set('batchId', params.batchId)
  if (params.status) qs.set('status', params.status)
  const s = qs.toString()
  return useQuery<WeighingTask[]>({
    queryKey: ['weighing', 'tasks', params],
    queryFn: () => request<WeighingTask[]>(`/api/weighing/tasks${s ? `?${s}` : ''}`),
    refetchInterval: REFRESH_MS,
  })
}

export function useWeighingSummary() {
  return useQuery<WeighingSummary>({
    queryKey: ['weighing', 'summary'],
    queryFn: () => request<WeighingSummary>('/api/weighing/summary'),
    refetchInterval: REFRESH_MS,
  })
}

export function useCreateWeighingTask() {
  const qc = useQueryClient()
  return useMutation<unknown, Error, Record<string, unknown>>({
    mutationFn: (body) => request('/api/weighing/tasks', { method: 'POST', body }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['weighing'] }),
  })
}

export function useWeigh() {
  const qc = useQueryClient()
  return useMutation<unknown, Error, { taskId: string | number } & Record<string, unknown>>({
    mutationFn: ({ taskId, ...body }) => request(`/api/weighing/tasks/${taskId}/weigh`, { method: 'POST', body }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['weighing'] })
      qc.invalidateQueries({ queryKey: ['alarms'] })
      qc.invalidateQueries({ queryKey: ['quality'] })
    },
  })
}

export function useReviewWeighing() {
  const qc = useQueryClient()
  return useMutation<unknown, Error, string | number>({
    mutationFn: (id) => request(`/api/weighing/items/${id}/review`, { method: 'POST' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['weighing'] }),
  })
}
