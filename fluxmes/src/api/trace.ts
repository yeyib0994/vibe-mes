import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { request } from './http'
import { traceChains, traceableBatches } from '../data/mes'

const REFRESH_MS = 30_000
// Phase 1：默认走后端 /api/trace；置 VITE_USE_MOCK=true 回退本地 Mock。
const USE_MOCK = import.meta.env.VITE_USE_MOCK === 'true'


/* ---------------- 追溯 H3 响应模型 ---------------- */

/** 逆向追溯：某原料批被哪些成品批次消耗。 */
export type TraceBackwardBatch = {
  batchId: string
  product?: string
  qty?: number
  unit?: string
  fedAt?: string
  released?: boolean
  status?: string
  [key: string]: unknown
}
export type TraceBackwardResponse = {
  lotNo?: string
  material?: { code?: string; name?: string; allergen?: boolean; [key: string]: unknown } | null
  batchCount?: number
  batches?: TraceBackwardBatch[]
  [key: string]: unknown
}

/** 影响面分析（FR-4）。 */
export type TraceImpactResponse = {
  lotNo?: string
  affectedBatchCount?: number
  releasedCount?: number
  totalYieldT?: number
  suggestions?: string[]
  [key: string]: unknown
}

/** 档案完整性校验（FR-8）。 */
export type TraceCompletenessResponse = {
  batchId?: string
  complete?: boolean
  integrity?: string
  missingCount?: number
  missing?: string[]
  [key: string]: unknown
}

/** 追溯查询审计流水（FR-7）。 */
export type TraceLogItem = {
  id: number
  actor?: string | null
  queryType?: string
  queryKey?: string | null
  resultCount?: number
  durationMs?: number
  createdAt?: string
  [key: string]: unknown
}

/** 追溯报表导出结果（FR-6）。 */
export type TraceExportResponse = { fileName: string; content: string; nodeCount?: number }

function mockDelay<T>(value: T, ms = 150): Promise<T> {
  return new Promise((resolve) => setTimeout(() => resolve(value), ms))
}

/** 追溯链节点（fixture 与后端同口径）。 */
export type TraceNode = {
  type: string
  label?: string
  title?: string
  value?: string | number
  meta?: string
  [key: string]: unknown
}

/** 追溯链（正向 + 逆向）。 */
export type TraceChain = {
  product?: string
  recipe?: string
  qty?: string
  status?: string
  forward?: TraceNode[]
  backward?: TraceNode[]
  [key: string]: unknown
}

/** 可追溯批次候选。 */
export type TraceTarget = {
  id: string
  product?: string
  recipe?: string
  status?: string
  qty?: string
  wh?: string
  [key: string]: unknown
}

/** 追溯链查询响应。 */
export type TraceChainResponse = {
  generatedAt?: string
  batchId?: string
  chain?: TraceChain | null
  meta?: { levelCount?: number; recordCount?: number; querySec?: number; integrity?: string; [key: string]: unknown }
  [key: string]: unknown
}

function buildTrace(batchId?: string) {
  const chain = batchId ? (traceChains as Record<string, TraceChain>)[batchId] : undefined
  if (!chain) return { generatedAt: new Date().toISOString(), batchId, chain: null }
  // 追溯层级 = 工序/物料/检验/入库节点数；关联记录 = 节点上的批号/单据数（原型估算）。
  const levelCount = new Set((chain.forward ?? []).map((n) => n.type)).size
  const recordCount = (chain.forward ?? []).length + (chain.backward ?? []).length
  return {
    generatedAt: new Date().toISOString(),
    batchId,
    chain,
    meta: {
      levelCount,
      recordCount,
      // C1：任意成品批次须能在 ≤ 1 次操作内完成正/逆向追溯；此耗时为端到端演示值。
      querySec: 0.8,
      integrity: '已校验',
    },
  }
}

// 可追溯批次列表（下拉候选）。
export function useTraceTargets() {
  return useQuery<{ generatedAt?: string; batches?: TraceTarget[] }>({
    queryKey: ['trace', 'targets'],
    queryFn: () =>
      USE_MOCK
        ? mockDelay({ generatedAt: new Date().toISOString(), batches: traceableBatches as TraceTarget[] })
        : request<{ generatedAt?: string; batches?: TraceTarget[] }>('/api/trace/targets'),
    staleTime: Infinity,
  })
}

// 单批次正/逆向追溯链（T：追溯查询页经此服务层取数）。
export function useTraceChain(batchId?: string) {
  return useQuery<TraceChainResponse>({
    queryKey: ['trace', 'chain', batchId],
    queryFn: () =>
      USE_MOCK
        ? mockDelay(buildTrace(batchId))
        : request<TraceChainResponse>(`/api/trace/chain/${encodeURIComponent(batchId ?? '')}`),
    enabled: !!batchId,
    staleTime: Infinity,
  })
}

/* ------------------------- H3 · 追溯闭环 ------------------------- */

// FR-3 · 逆向追溯：消耗该原料批的成品批次。
export function useTraceBackward(lotNo?: string) {
  return useQuery<TraceBackwardResponse>({
    queryKey: ['trace', 'backward', lotNo],
    queryFn: () => request<TraceBackwardResponse>(`/api/trace/backward?lotNo=${encodeURIComponent(lotNo ?? '')}`),
    enabled: !!lotNo,
    staleTime: 30_000,
  })
}

// FR-4 · 影响面分析。
export function useTraceImpact(lotNo?: string) {
  return useQuery<TraceImpactResponse>({
    queryKey: ['trace', 'impact', lotNo],
    queryFn: () => request<TraceImpactResponse>(`/api/trace/impact?lotNo=${encodeURIComponent(lotNo ?? '')}`),
    enabled: !!lotNo,
    staleTime: 30_000,
  })
}

// FR-8 · 档案完整性与缺失项。
export function useTraceCompleteness(batchId?: string) {
  return useQuery<TraceCompletenessResponse>({
    queryKey: ['trace', 'completeness', batchId],
    queryFn: () => request<TraceCompletenessResponse>(`/api/trace/${encodeURIComponent(batchId ?? '')}/completeness`),
    enabled: !!batchId,
    staleTime: Infinity,
  })
}

// FR-7 · 追溯查询审计流水。
export function useTraceLogs(limit: number = 50) {
  return useQuery<TraceLogItem[]>({
    queryKey: ['trace', 'logs', limit],
    queryFn: () => request<TraceLogItem[]>(`/api/trace/logs?limit=${limit}`),
    refetchInterval: REFRESH_MS,
  })
}

// FR-6 · 追溯报表导出（Markdown）。
export function useExportTraceReport() {
  const qc = useQueryClient()
  return useMutation<TraceExportResponse, Error, string>({
    mutationFn: (batchId) => request<TraceExportResponse>(`/api/trace/${encodeURIComponent(batchId)}/export`),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['trace', 'logs'] }),
  })
}

// 登记中间品流转父子关系。
export function useLinkGenealogy() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body: Record<string, unknown>) => request('/api/trace/genealogy', { method: 'POST', body }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['trace'] }),
  })
}

/** 浏览器端下载 Markdown 报表（与班报导出同式）。 */
export function downloadMarkdown(fileName: string, content: string) {
  const blob = new Blob([content], { type: 'text/markdown;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = fileName
  a.click()
  URL.revokeObjectURL(url)
}
