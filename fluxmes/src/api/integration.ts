import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { request } from './http'
import type { BadgeTone } from '../components/ui'

/**
 * 外部系统集成（Phase J）。
 *
 * 四个系统（SCADA/LIMS/ERP/WMS）各自有 mock 与 real 两种模式，由后端配置项决定：
 * `fluxmes.integration.{sys}.mode`。本模块只读健康状态 + 手工触发；
 * 每个响应都带 `dataSource`（MOCK/OPCUA/LIMS/ERP/WMS），页面据此标注来源。
 */

const HEALTH_MS = 30_000

/** 单个外部系统的健康与模式（dataSource 为硬约定，见章程 P4）。 */
export type SystemHealth = {
  system: string
  mode: string
  endpoint: string
  available: boolean
  detail: string
  lastSuccessAt: string | null
  dataSource: string | null
  [key: string]: unknown
}

/** 采集器统计（SCADA 采集周期与最近一次写入）。 */
export type CollectorStats = {
  lastBatchSize?: number
  intervalSeconds?: number
  storedCount?: number
  lastRunStatus?: string
  lastRunAt?: string
  [key: string]: unknown
}

export type IntegrationHealthResponse = { systems?: SystemHealth[]; [key: string]: unknown }
export type IntegrationSummaryResponse = {
  systems?: SystemHealth[]
  collector?: CollectorStats
  mockCount?: number
  [key: string]: unknown
}

// 四个系统的模式与连通状态（只读最近已知状态，不会真的去连外部系统）。
export function useIntegrationHealth() {
  return useQuery<IntegrationHealthResponse>({
    queryKey: ['integration', 'health'],
    queryFn: () => request<IntegrationHealthResponse>('/api/integration/health'),
    refetchInterval: HEALTH_MS,
  })
}

// 集成总览：健康 + 采集器统计 + 是否含 mock 数据源。
export function useIntegrationSummary() {
  return useQuery<IntegrationSummaryResponse>({
    queryKey: ['integration', 'summary'],
    queryFn: () => request<IntegrationSummaryResponse>('/api/integration/summary'),
    refetchInterval: HEALTH_MS,
  })
}

const invalidate = (qc: ReturnType<typeof useQueryClient>) => qc.invalidateQueries({ queryKey: ['integration'] })

/** 主动探测指定系统（值班长+，有副作用：会真的读一次外部系统）。 */
export function useProbeIntegration() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (system: string) =>
      request<{ detail?: string }>(`/api/integration/${encodeURIComponent(system)}/probe`, { method: 'POST' }),
    onSuccess: () => {
      invalidate(qc)
      // 采集会写 equipment_metric，趋势随之变化
      qc.invalidateQueries({ queryKey: ['equipment'] })
    },
  })
}

/** 立即采集一次设备参数（值班长+）。 */
export function useCollectMetrics() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: () => request<{ detail?: string }>('/api/integration/scada/collect', { method: 'POST' }),
    onSuccess: () => {
      invalidate(qc)
      qc.invalidateQueries({ queryKey: ['equipment'] })
    },
  })
}

/** 拉取 LIMS 待回流检验结果（管理员，消费式：拉一次就少一批）。 */
export function usePollLims() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: () => request<{ detail?: string }>('/api/integration/lims/results', { method: 'POST' }),
    onSuccess: () => {
      invalidate(qc)
      qc.invalidateQueries({ queryKey: ['quality'] })
    },
  })
}

/** 重置 mock LIMS 存量，便于反复演示回流（管理员，仅 mock 模式）。 */
export function useResetLims() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: () => request<{ detail?: string }>('/api/integration/lims/reset', { method: 'POST' }),
    onSuccess: () => invalidate(qc),
  })
}

/** 拉取 ERP 待开工工单（管理员）。 */
export function usePullErp() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: () => request<{ detail?: string }>('/api/integration/erp/work-orders', { method: 'POST' }),
    onSuccess: () => invalidate(qc),
  })
}

/** 试一次 WMS 入库申请（管理员，不落库，只验链路与应答）。 */
export function useTestWms() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body: Record<string, unknown>) =>
      request<{ detail?: string }>('/api/integration/wms/test', { method: 'POST', body }),
    onSuccess: () => invalidate(qc),
  })
}

/** 数据来源标识 → 中文说明 + 徽标色调。MOCK 一律走警示色，防止演示数据被误认。 */
export const SOURCE_META: Record<string, { label: string; tone: BadgeTone }> = {
  MOCK: { label: '模拟数据', tone: 'warn' },
  OPCUA: { label: 'OPC-UA', tone: 'accent' },
  LIMS: { label: 'LIMS', tone: 'accent' },
  ERP: { label: 'ERP', tone: 'accent' },
  WMS: { label: 'WMS', tone: 'accent' },
  MANUAL: { label: '人工录入', tone: 'muted' },
}

export function sourceMeta(dataSource?: string | null): { label: string; tone: BadgeTone } {
  if (!dataSource) return { label: '来源未知', tone: 'danger' }
  return SOURCE_META[dataSource] ?? { label: dataSource, tone: 'muted' }
}
