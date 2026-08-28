import { useEffect } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { request } from './http'
import { alarmData } from '../data/mes'

const REFRESH_MS = 30_000
const CURRENT_OPERATOR = '陈志远' // 当前值班工艺员；后端应通过 RBAC 解析（constitution C4）

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

function mockDelay(value, ms = 150) {
  return new Promise((resolve) => setTimeout(() => resolve(value), ms))
}

function buildMockList() {
  const unacked = alarmData.filter((a) => a.status === 'unacked').length
  return { generatedAt: new Date().toISOString(), unackedCount: unacked, alarms: alarmData }
}

/* ------------------------- 列表 + 未确认计数（FR-1/7） ------------------------- */

export function useAlarms() {
  return useQuery({
    queryKey: ['alarms'],
    queryFn: () => (USE_MOCK ? mockDelay(buildMockList()) : request('/api/alarms')),
    refetchInterval: REFRESH_MS,
  })
}

// 侧栏 badge 与顶栏铃铛共用此查询（queryKey 唯一 → react-query 单点缓存，计数来源唯一，FR-7）。
export function useUnackedCount() {
  return useQuery({
    queryKey: ['alarms-unacked-count'],
    queryFn: () =>
      USE_MOCK
        ? mockDelay({ unacked: alarmData.filter((a) => a.status === 'unacked').length })
        : request('/api/alarms/unacked-count'),
    select: (d) => d?.unacked ?? 0,
    refetchInterval: REFRESH_MS,
  })
}

/* ------------------------- 确认（乐观更新 + 审计落库，FR-3/10） ------------------------- */

export function useAckAlarm() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id) =>
      USE_MOCK
        ? mockDelay({ id, status: 'acked', ackBy: CURRENT_OPERATOR })
        : request(`/api/alarms/${encodeURIComponent(id)}/ack`, {
            method: 'POST',
            body: { operator: CURRENT_OPERATOR },
          }),
    // 乐观更新：确认即本地置 acked，失败回滚（风险 R2）
    onMutate: async (id) => {
      await qc.cancelQueries({ queryKey: ['alarms'] })
      const prevList = qc.getQueryData(['alarms'])
      const prevCount = qc.getQueryData(['alarms-unacked-count'])
      qc.setQueryData(['alarms'], (old) =>
        old
          ? {
              ...old,
              unackedCount:
                old.alarms.find((a) => a.id === id)?.status === 'unacked'
                  ? Math.max(0, (old.unackedCount ?? 0) - 1)
                  : old.unackedCount,
              alarms: old.alarms.map((a) =>
                a.id === id ? { ...a, status: 'acked', ackBy: CURRENT_OPERATOR } : a,
              ),
            }
          : old,
      )
      qc.setQueryData(['alarms-unacked-count'], (old) => {
        // 确认仅未确认态会减少计数；此处以本地列表判定
        const wasUnacked = prevList?.alarms?.find((a) => a.id === id)?.status === 'unacked'
        return old && wasUnacked ? { unacked: Math.max(0, (old.unacked ?? 0) - 1) } : old
      })
      return { prevList, prevCount }
    },
    onError: (_err, _id, ctx) => {
      if (ctx?.prevList) qc.setQueryData(['alarms'], ctx.prevList)
      if (ctx?.prevCount) qc.setQueryData(['alarms-unacked-count'], ctx.prevCount)
    },
    // 成功后以服务端为准回填（含审计与真实确认时间）
    onSuccess: () => invalidateAlarms(qc),
  })
}

/* ------------------------- 恢复（乐观更新 + 审计落库，FR-4 / T4） ------------------------- */

// opts.auto = true → 系统自动恢复；否则人工标记，操作人为当前值班人员。
export function useRecoverAlarm() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, auto = false }) =>
      USE_MOCK
        ? mockDelay({ id, status: 'recovered' })
        : request(
            `/api/alarms/${encodeURIComponent(id)}/recover${auto ? '?auto=true' : ''}`,
            { method: 'POST', body: { operator: CURRENT_OPERATOR } },
          ),
    onMutate: async ({ id }) => {
      await qc.cancelQueries({ queryKey: ['alarms'] })
      const prevList = qc.getQueryData(['alarms'])
      const prevCount = qc.getQueryData(['alarms-unacked-count'])
      const wasUnacked = prevList?.alarms?.find((a) => a.id === id)?.status === 'unacked'
      qc.setQueryData(['alarms'], (old) =>
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
        qc.setQueryData(['alarms-unacked-count'], (old) =>
          old ? { unacked: Math.max(0, (old.unacked ?? 0) - 1) } : old,
        )
      }
      return { prevList, prevCount }
    },
    onError: (_err, _vars, ctx) => {
      if (ctx?.prevList) qc.setQueryData(['alarms'], ctx.prevList)
      if (ctx?.prevCount) qc.setQueryData(['alarms-unacked-count'], ctx.prevCount)
    },
    onSuccess: () => invalidateAlarms(qc),
  })
}

/* ------------------------- 趋势 / 高频源 / 统计（FR-5/6） ------------------------- */

export function useAlarmTrend(hours = 8) {
  return useQuery({
    queryKey: ['alarm-trend', hours],
    queryFn: () =>
      USE_MOCK
        ? mockDelay(null) // Mock 模式返回 null，页面回退到静态数据
        : request(`/api/alarms/trend?hours=${hours}`),
    refetchInterval: REFRESH_MS,
  })
}

export function useAlarmTopSources(days = 7, limit = 5) {
  return useQuery({
    queryKey: ['alarm-top-sources', days, limit],
    queryFn: () =>
      USE_MOCK
        ? mockDelay(null)
        : request(`/api/alarms/top-sources?days=${days}&limit=${limit}`),
    refetchInterval: REFRESH_MS,
  })
}

export function useAlarmStats() {
  return useQuery({
    queryKey: ['alarm-stats'],
    queryFn: () => (USE_MOCK ? mockDelay(null) : request('/api/alarms/stats')),
    refetchInterval: REFRESH_MS,
  })
}

/* ------------------------- 实时推送（SSE，NFR-1 / T5） ------------------------- */

// 订阅后端报警事件流；任意 created/acked/recovered 事件到来即失效回查相关查询，
// 使新报警与计数即时可见（远快于 30s 轮询）。EventSource 断线自动重连。
export function useAlarmRealtime() {
  const qc = useQueryClient()
  useEffect(() => {
    if (USE_MOCK || typeof EventSource === 'undefined') return undefined
    const es = new EventSource('/api/alarms/stream')
    es.addEventListener('alarm', () => invalidateAlarms(qc))
    es.onerror = () => {
      /* 断线由 EventSource 自动重连；此处不打扰用户 */
    }
    return () => es.close()
  }, [qc])
}
