import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { request } from './http'

/**
 * Phase I · 生产执行服务层（工单 / 派工 / 报工 / 停机 / 真实 OEE）。
 * 与后端 api-java/com/fluxmes/api/execution/ExecutionController 一一对应。
 *
 * OEE 口径：可用率=(计划工时−计划外停机)/计划工时；性能率=标准工时/实际工时；
 * 良品率=合格量/(合格量+废次品量)。后端在数据不足时返回 null + dataSufficient=false，
 * 前端必须展示「数据不足」而非 0（禁止伪造数值）。
 */

const REFRESH_MS = 30_000

export const ORDER_STATUS_TONE = {
  CREATED: 'muted',
  RELEASED: 'primary',
  RUNNING: 'success',
  FINISHED: 'warning',
  CLOSED: 'muted',
}
export const ORDER_STATUS_TEXT = {
  CREATED: '已创建',
  RELEASED: '已下达',
  RUNNING: '执行中',
  FINISHED: '已完工',
  CLOSED: '已关闭',
}
export const DOWNTIME_CATEGORY_TEXT = { PLANNED: '计划停机', UNPLANNED: '非计划停机' }

export function useWorkOrders(params = {}) {
  const qs = new URLSearchParams()
  if (params.batchId) qs.set('batchId', params.batchId)
  if (params.line) qs.set('line', params.line)
  if (params.site) qs.set('site', params.site)
  if (params.status) qs.set('status', params.status)
  if (params.from) qs.set('from', params.from)
  if (params.to) qs.set('to', params.to)
  const s = qs.toString()
  return useQuery({
    queryKey: ['execution', 'orders', params],
    queryFn: () => request(`/api/execution/orders${s ? `?${s}` : ''}`),
    refetchInterval: REFRESH_MS,
  })
}

export function useWorkOrder(id) {
  return useQuery({
    queryKey: ['execution', 'order', id],
    queryFn: () => request(`/api/execution/orders/${id}`),
    enabled: Boolean(id),
    refetchInterval: REFRESH_MS,
  })
}

export function useCreateWorkOrder() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body) => request('/api/execution/orders', { method: 'POST', body }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['execution'] }),
  })
}

/** 状态流转：target ∈ release | start | finish | close。 */
export function useTransitionOrder() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, target }) =>
      request(`/api/execution/orders/${id}/${target}`, { method: 'POST' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['execution'] }),
  })
}

export function useDispatch() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, ...body }) =>
      request(`/api/execution/orders/${id}/dispatch`, { method: 'POST', body }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['execution'] }),
  })
}

export function useRevokeDispatch() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, aid, reason }) =>
      request(
        `/api/execution/orders/${id}/dispatch/${aid}${reason ? `?reason=${encodeURIComponent(reason)}` : ''}`,
        { method: 'DELETE' },
      ),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['execution'] }),
  })
}

export function useReportStep() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, ...body }) =>
      request(`/api/execution/orders/${id}/reports`, { method: 'POST', body }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['execution'] })
      qc.invalidateQueries({ queryKey: ['batches'] })
      qc.invalidateQueries({ queryKey: ['dashboard'] })
    },
  })
}

export function useDowntime(params = {}) {
  const qs = new URLSearchParams()
  if (params.equipment) qs.set('equipment', params.equipment)
  if (params.orderId) qs.set('orderId', params.orderId)
  if (params.site) qs.set('site', params.site)
  const s = qs.toString()
  return useQuery({
    queryKey: ['execution', 'downtime', params],
    queryFn: () => request(`/api/execution/downtime${s ? `?${s}` : ''}`),
    refetchInterval: REFRESH_MS,
  })
}

export function useDowntimeReasons() {
  return useQuery({
    queryKey: ['execution', 'downtime', 'reasons'],
    queryFn: () => request('/api/execution/downtime/reasons'),
    staleTime: 5 * 60_000,
  })
}

export function useRecordDowntime() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body) => request('/api/execution/downtime', { method: 'POST', body }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['execution'] })
      qc.invalidateQueries({ queryKey: ['alarms'] })
    },
  })
}

export function useOee(params = {}) {
  const qs = new URLSearchParams()
  if (params.site) qs.set('site', params.site)
  if (params.line) qs.set('line', params.line)
  if (params.equipment) qs.set('equipment', params.equipment)
  if (params.from) qs.set('from', params.from)
  if (params.to) qs.set('to', params.to)
  const s = qs.toString()
  return useQuery({
    queryKey: ['execution', 'oee', params],
    queryFn: () => request(`/api/execution/oee${s ? `?${s}` : ''}`),
    refetchInterval: REFRESH_MS,
  })
}

export function useOeePareto(params = {}) {
  const qs = new URLSearchParams()
  if (params.site) qs.set('site', params.site)
  if (params.line) qs.set('line', params.line)
  if (params.from) qs.set('from', params.from)
  if (params.to) qs.set('to', params.to)
  const s = qs.toString()
  return useQuery({
    queryKey: ['execution', 'oee', 'pareto', params],
    queryFn: () => request(`/api/execution/oee/pareto${s ? `?${s}` : ''}`),
    refetchInterval: 60_000,
  })
}

export function useRecomputeOee() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (params = {}) => {
      const qs = new URLSearchParams()
      if (params.date) qs.set('date', params.date)
      if (params.site) qs.set('site', params.site)
      if (params.line) qs.set('line', params.line)
      const s = qs.toString()
      return request(`/api/execution/oee/recompute${s ? `?${s}` : ''}`, { method: 'POST' })
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ['execution'] }),
  })
}
