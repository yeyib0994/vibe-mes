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

/** 工单查询过滤条件 */
export type OrderQuery = {
  batchId?: string
  line?: string
  site?: string
  status?: string
  from?: string
  to?: string
}
/** 停机查询过滤条件 */
export type DowntimeQuery = { equipment?: string; orderId?: string; site?: string }
/** OEE 查询过滤条件 */
export type OeeQuery = { site?: string; line?: string; equipment?: string; from?: string; to?: string }
/** OEE 重算参数 */
export type RecomputeOeeParams = { date?: string; site?: string; line?: string }

export const ORDER_STATUS_TONE: Record<string, string> = {
  CREATED: 'muted',
  RELEASED: 'primary',
  RUNNING: 'success',
  FINISHED: 'warning',
  CLOSED: 'muted',
}
export const ORDER_STATUS_TEXT: Record<string, string> = {
  CREATED: '已创建',
  RELEASED: '已下达',
  RUNNING: '执行中',
  FINISHED: '已完工',
  CLOSED: '已关闭',
}
export const DOWNTIME_CATEGORY_TEXT: Record<string, string> = { PLANNED: '计划停机', UNPLANNED: '非计划停机' }

/* ------------------------------ 数据行类型 ------------------------------ */

/** 工单行（work_order）。 */
export type WorkOrder = {
  id: string | number
  batchId?: string
  product?: string
  status?: 'CREATED' | 'RELEASED' | 'RUNNING' | 'FINISHED' | 'CLOSED' | string
  planQty?: number | null
  unit?: string
  progressPct?: number
  recipeVersion?: string | null
  line?: string
  shift?: string
  planStart?: string | null
  planEnd?: string | null
  actualStart?: string | null
  actualEnd?: string | null
  [key: string]: unknown
}

export type WorkOrdersResponse = { orders?: WorkOrder[]; [key: string]: unknown }

/** 派工（工单内角色分配，含资质门禁结果）。 */
export type WorkOrderAssignment = {
  id: string | number
  username?: string
  displayName?: string
  roleInOrder?: string
  capability?: string
  status?: 'ACTIVE' | 'REVOKED' | string
  reason?: string | null
  [key: string]: unknown
}

/** 报工记录（工序）。 */
export type WorkOrderReport = {
  id: string | number
  stepNo?: number
  stepName?: string
  equipment?: string | null
  inputQty?: number | null
  goodQty?: number | null
  scrapQty?: number | null
  yieldPct?: number | null
  stdMinutes?: number | null
  critical?: boolean
  reviewer?: string | null
  reviewed?: boolean
  reportedBy?: string
  reportedAt?: string
  [key: string]: unknown
}

/** 工单详情（含派工与报工列表）。 */
export type WorkOrderDetail = WorkOrder & {
  assignments?: WorkOrderAssignment[]
  reports?: WorkOrderReport[]
  [key: string]: unknown
}

/** OEE 响应：数据不足时各因子为 null + dataSufficient=false（禁止兜底）。 */
export type OeeResponse = {
  oee?: number | null
  availability?: number | null
  performance?: number | null
  quality?: number | null
  dataSufficient?: boolean
  note?: string | null
  formula?: string | null
  missing?: string | null
  unplannedStopMinutes?: number | null
  plannedMinutesDerived?: boolean
  performanceOverrun?: boolean
  [key: string]: unknown
}

/** 停机帕累托行。 */
export type OeeParetoItem = {
  reasonCode: string
  reasonName?: string
  planned?: boolean
  count?: number
  minutes?: number | null
  sharePct?: number
  cumPct?: number
  [key: string]: unknown
}

export type OeeParetoResponse = { items?: OeeParetoItem[]; [key: string]: unknown }

/** 停机事件行。 */
export type DowntimeEvent = {
  id: string | number
  equipment?: string | null
  reasonCode?: string
  reasonName?: string
  category?: 'PLANNED' | 'UNPLANNED' | string
  planned?: boolean
  startedAt?: string | null
  endedAt?: string | null
  durationMin?: number | null
  mergedFrom?: string | null
  [key: string]: unknown
}

export function useWorkOrders(params: OrderQuery = {}) {
  const qs = new URLSearchParams()
  if (params.batchId) qs.set('batchId', params.batchId)
  if (params.line) qs.set('line', params.line)
  if (params.site) qs.set('site', params.site)
  if (params.status) qs.set('status', params.status)
  if (params.from) qs.set('from', params.from)
  if (params.to) qs.set('to', params.to)
  const s = qs.toString()
  return useQuery<WorkOrdersResponse>({
    queryKey: ['execution', 'orders', params],
    queryFn: () => request<WorkOrdersResponse>(`/api/execution/orders${s ? `?${s}` : ''}`),
    refetchInterval: REFRESH_MS,
  })
}

export function useWorkOrder(id?: string | number) {
  return useQuery<WorkOrderDetail>({
    queryKey: ['execution', 'order', id],
    queryFn: () => request<WorkOrderDetail>(`/api/execution/orders/${id}`),
    enabled: Boolean(id),
    refetchInterval: REFRESH_MS,
  })
}

/** 建单结果（继承配方版本）。 */
export type CreateWorkOrderResult = WorkOrder & { recipeVersion?: string | null }

export function useCreateWorkOrder() {
  const qc = useQueryClient()
  return useMutation<CreateWorkOrderResult, Error, Record<string, unknown>>({
    mutationFn: (body: Record<string, unknown>) =>
      request<CreateWorkOrderResult>('/api/execution/orders', { method: 'POST', body }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['execution'] }),
  })
}

/** 状态流转结果：confirmNeeded=true 时带 confirmNote（如跳过门禁的告警说明）。 */
export type TransitionOrderResult = {
  id?: string | number
  order?: WorkOrder
  confirmNeeded?: boolean
  confirmNote?: string
  [key: string]: unknown
}

/** 状态流转：target ∈ release | start | finish | close。 */
export function useTransitionOrder() {
  const qc = useQueryClient()
  return useMutation<TransitionOrderResult, Error, { id: string | number; target: string }>({
    mutationFn: ({ id, target }) =>
      request<TransitionOrderResult>(`/api/execution/orders/${id}/${target}`, { method: 'POST' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['execution'] }),
  })
}

export function useDispatch() {
  const qc = useQueryClient()
  return useMutation<unknown, Error, { id: string | number } & Record<string, unknown>>({
    mutationFn: ({ id, ...body }) =>
      request(`/api/execution/orders/${id}/dispatch`, { method: 'POST', body }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['execution'] }),
  })
}

export function useRevokeDispatch() {
  const qc = useQueryClient()
  return useMutation<unknown, Error, { id: string | number; aid: string | number; reason?: string }>({
    mutationFn: ({ id, aid, reason }) =>
      request(
        `/api/execution/orders/${id}/dispatch/${aid}${reason ? `?reason=${encodeURIComponent(reason)}` : ''}`,
        { method: 'DELETE' },
      ),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['execution'] }),
  })
}

/** 报工结果：idempotent=true 表示重复提交，返回既有记录。 */
export type ReportStepResult = {
  id?: string | number
  idempotent?: boolean
  order?: WorkOrder
  [key: string]: unknown
}

export function useReportStep() {
  const qc = useQueryClient()
  return useMutation<ReportStepResult, Error, { id: string | number } & Record<string, unknown>>({
    mutationFn: ({ id, ...body }) =>
      request<ReportStepResult>(`/api/execution/orders/${id}/reports`, { method: 'POST', body }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['execution'] })
      qc.invalidateQueries({ queryKey: ['batches'] })
      qc.invalidateQueries({ queryKey: ['dashboard'] })
    },
  })
}

export function useDowntime(params: DowntimeQuery = {}) {
  const qs = new URLSearchParams()
  if (params.equipment) qs.set('equipment', params.equipment)
  if (params.orderId) qs.set('orderId', params.orderId)
  if (params.site) qs.set('site', params.site)
  const s = qs.toString()
  return useQuery<DowntimeEvent[]>({
    queryKey: ['execution', 'downtime', params],
    queryFn: () => request<DowntimeEvent[]>(`/api/execution/downtime${s ? `?${s}` : ''}`),
    refetchInterval: REFRESH_MS,
  })
}

/** 停机原因码（planned=true 为计划停机）。 */
export type DowntimeReason = {
  code: string
  name?: string
  planned?: boolean
  category?: string
  [key: string]: unknown
}

export function useDowntimeReasons() {
  return useQuery<DowntimeReason[]>({
    queryKey: ['execution', 'downtime', 'reasons'],
    queryFn: () => request<DowntimeReason[]>('/api/execution/downtime/reasons'),
    staleTime: 5 * 60_000,
  })
}

/** 停机录入结果：时间窗重叠时 merged=true 并合并至既有停机。 */
export type RecordDowntimeResult = {
  merged?: boolean
  downtime: DowntimeEvent
  alarmId?: string | number | null
  [key: string]: unknown
}

export function useRecordDowntime() {
  const qc = useQueryClient()
  return useMutation<RecordDowntimeResult, Error, Record<string, unknown>>({
    mutationFn: (body: Record<string, unknown>) =>
      request<RecordDowntimeResult>('/api/execution/downtime', { method: 'POST', body }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['execution'] })
      qc.invalidateQueries({ queryKey: ['alarms'] })
    },
  })
}

export function useOee(params: OeeQuery = {}) {
  const qs = new URLSearchParams()
  if (params.site) qs.set('site', params.site)
  if (params.line) qs.set('line', params.line)
  if (params.equipment) qs.set('equipment', params.equipment)
  if (params.from) qs.set('from', params.from)
  if (params.to) qs.set('to', params.to)
  const s = qs.toString()
  return useQuery<OeeResponse>({
    queryKey: ['execution', 'oee', params],
    queryFn: () => request<OeeResponse>(`/api/execution/oee${s ? `?${s}` : ''}`),
    refetchInterval: REFRESH_MS,
  })
}

export function useOeePareto(params: OeeQuery = {}) {
  const qs = new URLSearchParams()
  if (params.site) qs.set('site', params.site)
  if (params.line) qs.set('line', params.line)
  if (params.from) qs.set('from', params.from)
  if (params.to) qs.set('to', params.to)
  const s = qs.toString()
  return useQuery<OeeParetoResponse>({
    queryKey: ['execution', 'oee', 'pareto', params],
    queryFn: () => request<OeeParetoResponse>(`/api/execution/oee/pareto${s ? `?${s}` : ''}`),
    refetchInterval: 60_000,
  })
}

export function useRecomputeOee() {
  const qc = useQueryClient()
  return useMutation<unknown, Error, RecomputeOeeParams>({
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
