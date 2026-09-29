import { useEffect } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { request } from './http'
import { alarmData } from '../data/mes'

import { getSession } from '../lib/auth'

const REFRESH_MS = 30_000
// 操作人优先取 JWT 会话用户名（RBAC，constitution C4）；未登录时回退示例值班人。
const operatorOf = () => getSession()?.username ?? '陈志远'

// 报警接口响应形状（后端 AlarmDto / 统计口径见 apps/api-java）
export type AlarmItem = {
  id: string
  status: string
  /** 报警级别：critical / major / minor */
  level?: string
  source?: string
  content?: string
  value?: string | number
  threshold?: string | number
  time?: string
  triggeredAt?: string | null
  ackBy?: string | null
  recoveredAt?: string | null
  /** 是否已超时未确认（SLA 判定） */
  overdue?: boolean
  /** 是否已升级至值班长 */
  escalated?: boolean
  [key: string]: unknown
}
export type AlarmListResponse = { generatedAt?: string; unackedCount?: number; alarms: AlarmItem[] }
type UnackedCountResponse = { unacked?: number }
/** SLA 政策行（G1 运行时可配置）。 */
export type SlaPolicy = { level: string; minutes: number; enabled: boolean }

// Phase 1：默认走后端 HTTP（Vite proxy /api → Spring Boot）。
// 置 VITE_USE_MOCK=true 可回退到 Phase 0 的本地 Mock 适配器（plan「Mock 与真实可切换」）。
const USE_MOCK = import.meta.env.VITE_USE_MOCK === 'true'

// 所有报警相关查询前缀；状态变更后统一失效回查（含 SSE 推送触发）。
const ALARM_QUERY_KEYS = [
  ['alarms'],
  ['alarms-unacked-count'],
  ['alarm-stats'],
  ['alarm-trend'],
  ['alarm-top-sources'],
]

function invalidateAlarms(qc) {
  for (const key of ALARM_QUERY_KEYS) qc.invalidateQueries({ queryKey: key })
}

function mockDelay<T>(value: T, ms = 150): Promise<T> {
  return new Promise((resolve) => setTimeout(() => resolve(value), ms))
}

function buildMockList() {
  const unacked = alarmData.filter((a) => a.status === 'unacked').length
  return { generatedAt: new Date().toISOString(), unackedCount: unacked, alarms: alarmData }
}

/* ------------------------- 列表 + 未确认计数（FR-1/7） ------------------------- */

export function useAlarms() {
  return useQuery<AlarmListResponse>({
    queryKey: ['alarms'],
    queryFn: () => (USE_MOCK ? mockDelay(buildMockList()) : request<AlarmListResponse>('/api/alarms')),
    refetchInterval: REFRESH_MS,
  })
}

// 侧栏 badge 与顶栏铃铛共用此查询（queryKey 唯一 → react-query 单点缓存，计数来源唯一，FR-7）。
export function useUnackedCount() {
  return useQuery<{ unacked?: number }, Error, number>({
    queryKey: ['alarms-unacked-count'],
    queryFn: () =>
      USE_MOCK
        ? mockDelay({ unacked: alarmData.filter((a) => a.status === 'unacked').length })
        : request<{ unacked?: number }>('/api/alarms/unacked-count'),
    select: (d) => d?.unacked ?? 0,
    refetchInterval: REFRESH_MS,
  })
}

/* ------------------------- 确认（乐观更新 + 审计落库，FR-3/10） ------------------------- */

export function useAckAlarm() {
  const qc = useQueryClient()
  return useMutation<unknown, Error, string>({
    mutationFn: (id: string) =>
      USE_MOCK
        ? mockDelay({ id, status: 'acked', ackBy: operatorOf() })
        : request(`/api/alarms/${encodeURIComponent(id)}/ack`, {
            method: 'POST',
            body: { operator: operatorOf() },
          }),
    // 乐观更新：确认即本地置 acked，失败回滚（风险 R2）
    onMutate: async (id: string) => {
      await qc.cancelQueries({ queryKey: ['alarms'] })
      const prevList = qc.getQueryData<AlarmListResponse>(['alarms'])
      const prevCount = qc.getQueryData<UnackedCountResponse>(['alarms-unacked-count'])
      qc.setQueryData<AlarmListResponse>(['alarms'], (old) =>
        old
          ? {
              ...old,
              unackedCount:
                old.alarms.find((a) => a.id === id)?.status === 'unacked'
                  ? Math.max(0, (old.unackedCount ?? 0) - 1)
                  : old.unackedCount,
              alarms: old.alarms.map((a) =>
                a.id === id ? { ...a, status: 'acked', ackBy: operatorOf() } : a,
              ),
            }
          : old,
      )
      qc.setQueryData<UnackedCountResponse>(['alarms-unacked-count'], (old) => {
        // 确认仅未确认态会减少计数；此处以本地列表判定
        const wasUnacked = prevList?.alarms?.find((a) => a.id === id)?.status === 'unacked'
        return old && wasUnacked ? { unacked: Math.max(0, (old.unacked ?? 0) - 1) } : old
      })
      return { prevList, prevCount }
    },
    onError: (_err, _id, _onMutateResult, ctx) => {
      const prev = ctx as { prevList?: AlarmListResponse; prevCount?: UnackedCountResponse } | undefined
      if (prev?.prevList) qc.setQueryData(['alarms'], prev.prevList)
      if (prev?.prevCount) qc.setQueryData(['alarms-unacked-count'], prev.prevCount)
    },
    // 成功后以服务端为准回填（含审计与真实确认时间）
    onSuccess: () => invalidateAlarms(qc),
  })
}

/* ------------------------- 恢复（乐观更新 + 审计落库，FR-4 / T4） ------------------------- */

// opts.auto = true → 系统自动恢复；否则人工标记，操作人为当前值班人员。
export function useRecoverAlarm() {
  const qc = useQueryClient()
  return useMutation<unknown, Error, { id: string; auto?: boolean }>({
    mutationFn: ({ id, auto = false }: { id: string; auto?: boolean }) =>
      USE_MOCK
        ? mockDelay({ id, status: 'recovered' })
        : request(
            `/api/alarms/${encodeURIComponent(id)}/recover${auto ? '?auto=true' : ''}`,
            { method: 'POST', body: { operator: operatorOf() } },
          ),
    onMutate: async ({ id }: { id: string }) => {
      await qc.cancelQueries({ queryKey: ['alarms'] })
      const prevList = qc.getQueryData<AlarmListResponse>(['alarms'])
      const prevCount = qc.getQueryData<UnackedCountResponse>(['alarms-unacked-count'])
      const wasUnacked = prevList?.alarms?.find((a) => a.id === id)?.status === 'unacked'
      qc.setQueryData<AlarmListResponse>(['alarms'], (old) =>
        old
          ? {
              ...old,
              unackedCount: wasUnacked ? Math.max(0, (old.unackedCount ?? 0) - 1) : old.unackedCount,
              alarms: old.alarms.map((a) =>
                a.id === id ? { ...a, status: 'recovered', recoveredAt: new Date().toISOString() } : a,
              ),
            }
          : old,
      )
      if (wasUnacked) {
        qc.setQueryData<UnackedCountResponse>(['alarms-unacked-count'], (old) =>
          old ? { unacked: Math.max(0, (old.unacked ?? 0) - 1) } : old,
        )
      }
      return { prevList, prevCount }
    },
    onError: (_err, _vars, _onMutateResult, ctx) => {
      const prev = ctx as { prevList?: AlarmListResponse; prevCount?: UnackedCountResponse } | undefined
      if (prev?.prevList) qc.setQueryData(['alarms'], prev.prevList)
      if (prev?.prevCount) qc.setQueryData(['alarms-unacked-count'], prev.prevCount)
    },
    onSuccess: () => invalidateAlarms(qc),
  })
}

/* ------------------------- 趋势 / 高频源 / 统计（FR-5/6） ------------------------- */

/** 报警趋势点（后端返回，Mock 模式为 null → 页面回退静态数据）。 */
export type AlarmTrendPoint = { t?: string; time?: string; count?: number; critical?: number; major?: number; minor?: number; [key: string]: unknown }

export function useAlarmTrend(hours = 8) {
  return useQuery<AlarmTrendPoint[] | null>({
    queryKey: ['alarm-trend', hours],
    queryFn: () =>
      USE_MOCK
        ? mockDelay<AlarmTrendPoint[] | null>(null) // Mock 模式返回 null，页面回退到静态数据
        : request<AlarmTrendPoint[]>(`/api/alarms/trend?hours=${hours}`),
    refetchInterval: REFRESH_MS,
  })
}

/** 高频报警源行。 */
export type AlarmTopSource = { source?: string; name?: string; count?: number; level?: string; [key: string]: unknown }

export function useAlarmTopSources(days = 7, limit = 5) {
  return useQuery<AlarmTopSource[] | null>({
    queryKey: ['alarm-top-sources', days, limit],
    queryFn: () =>
      USE_MOCK
        ? mockDelay<AlarmTopSource[] | null>(null)
        : request<AlarmTopSource[]>(`/api/alarms/top-sources?days=${days}&limit=${limit}`),
    refetchInterval: REFRESH_MS,
  })
}

/** 报警统计（GET /api/alarms/stats）。 */
export type AlarmStats = {
  todayTotal?: number
  active?: number
  unackedCritical?: number
  overdueUnacked?: number
  avgResponseMinutes?: number
  ackRatePercent?: number
  [key: string]: unknown
}

export function useAlarmStats() {
  return useQuery<AlarmStats | null>({
    queryKey: ['alarm-stats'],
    queryFn: () => (USE_MOCK ? mockDelay<AlarmStats | null>(null) : request<AlarmStats>('/api/alarms/stats')),
    refetchInterval: REFRESH_MS,
  })
}

/* ------------------------- D1 · 抑制规则（防报警洪水） ------------------------- */

export type SuppressionRule = {
  id: number
  name: string
  source?: string | null
  content?: string | null
  level?: string | null
  windowMinutes?: number
  enabled?: boolean
  reason?: string | null
  createdBy?: string | null
  createdAt?: string | null
}

export function useSuppressions() {
  return useQuery<SuppressionRule[]>({
    queryKey: ['alarm-suppressions'],
    queryFn: () => (USE_MOCK ? mockDelay([]) : request<SuppressionRule[]>('/api/alarms/suppressions')),
    refetchInterval: REFRESH_MS,
  })
}

/** 新建抑制规则（值班长及以上，后端 RBAC 校验）。 */
export function useCreateSuppression() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body: Partial<SuppressionRule>) =>
      request('/api/alarms/suppressions', { method: 'POST', body }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['alarm-suppressions'] }),
  })
}

export function useDeleteSuppression() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => request(`/api/alarms/suppressions/${id}`, { method: 'DELETE' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['alarm-suppressions'] }),
  })
}

/* ------------------------- G1 · SLA 政策可配置 ------------------------- */

export type SlaPolicyItem = {
  level: string
  minutes: number
  enabled: boolean
  note?: string | null
  updatedBy?: string | null
  updatedAt?: string | null
  effectiveMinutes: number
}

export function useSlaPolicy() {
  return useQuery<SlaPolicy[]>({
    queryKey: ['alarm-sla-policy'],
    queryFn: () => request<SlaPolicy[]>('/api/alarms/sla/policy'),
    refetchInterval: REFRESH_MS,
  })
}

/** 调整某级别响应时限（值班长及以上）。 */
export function useUpdateSlaPolicy() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ level, minutes, enabled, note }: { level: string; minutes: number; enabled: boolean; note?: string }) =>
      request(`/api/alarms/sla/policy/${level}`, { method: 'PUT', body: { minutes, enabled, note } }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['alarm-sla-policy'] })
      qc.invalidateQueries({ queryKey: ['alarm-stats'] })
    },
  })
}

/** 手动触发一次 SLA 巡检（值班长及以上；演示与联调用）。 */
export function useSlaSweep() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: () => request('/api/alarms/sla/sweep', { method: 'POST', body: {} }),
    onSuccess: () => invalidateAlarms(qc),
  })
}

/* ------------------------- 实时推送（SSE，NFR-1 / T5） ------------------------- */

// 订阅后端报警事件流；任意 created/acked/recovered 事件到来即失效回查相关查询，
// 使新报警与计数即时可见（远快于 30s 轮询）。EventSource 断线自动重连。
export function useAlarmRealtime() {
  const qc = useQueryClient()
  useEffect(() => {
    // 未登录时不建立 SSE（后端 401）；Mock 模式与环境不支持 EventSource 亦跳过
    if (USE_MOCK || typeof EventSource === 'undefined' || !getSession()) return undefined
    const es = new EventSource('/api/alarms/stream')
    es.addEventListener('alarm', () => invalidateAlarms(qc))
    es.onerror = () => {
      /* 断线由 EventSource 自动重连；此处不打扰用户 */
    }
    return () => es.close()
  }, [qc])
}
