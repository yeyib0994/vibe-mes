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

export function useWeighingTasks(params = {}) {
  const qs = new URLSearchParams()
  if (params.batchId) qs.set('batchId', params.batchId)
  if (params.status) qs.set('status', params.status)
  const s = qs.toString()
  return useQuery({
    queryKey: ['weighing', 'tasks', params],
    queryFn: () => request(`/api/weighing/tasks${s ? `?${s}` : ''}`),
    refetchInterval: REFRESH_MS,
  })
}

export function useWeighingSummary() {
  return useQuery({
    queryKey: ['weighing', 'summary'],
    queryFn: () => request('/api/weighing/summary'),
    refetchInterval: REFRESH_MS,
  })
}

export function useCreateWeighingTask() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body) => request('/api/weighing/tasks', { method: 'POST', body }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['weighing'] }),
  })
}

export function useWeigh() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ taskId, ...body }) =>
      request(`/api/weighing/tasks/${taskId}/weigh`, { method: 'POST', body }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['weighing'] })
      qc.invalidateQueries({ queryKey: ['alarms'] })
      qc.invalidateQueries({ queryKey: ['quality'] })
    },
  })
}

export function useReviewWeighing() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id) => request(`/api/weighing/items/${id}/review`, { method: 'POST' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['weighing'] }),
  })
}
