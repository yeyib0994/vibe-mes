import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { request } from './http'
import { traceChains, traceableBatches } from '../data/mes'

const REFRESH_MS = 30_000
// Phase 1：默认走后端 /api/trace；置 VITE_USE_MOCK=true 回退本地 Mock。
const USE_MOCK = import.meta.env.VITE_USE_MOCK === 'true'

function mockDelay(value, ms = 150) {
  return new Promise((resolve) => setTimeout(() => resolve(value), ms))
}

function buildTrace(batchId) {
  const chain = traceChains[batchId]
  if (!chain) return { generatedAt: new Date().toISOString(), batchId, chain: null }
  // 追溯层级 = 工序/物料/检验/入库节点数；关联记录 = 节点上的批号/单据数（原型估算）。
  const levelCount = new Set(chain.forward.map((n) => n.type)).size
  const recordCount = chain.forward.length + chain.backward.length
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
  return useQuery({
    queryKey: ['trace', 'targets'],
    queryFn: () =>
      USE_MOCK
        ? mockDelay({ generatedAt: new Date().toISOString(), batches: traceableBatches })
        : request('/api/trace/targets'),
    staleTime: Infinity,
  })
}

// 单批次正/逆向追溯链（T：追溯查询页经此服务层取数）。
export function useTraceChain(batchId) {
  return useQuery({
    queryKey: ['trace', 'chain', batchId],
    queryFn: () =>
      USE_MOCK
        ? mockDelay(buildTrace(batchId))
        : request(`/api/trace/chain/${encodeURIComponent(batchId)}`),
    enabled: !!batchId,
    staleTime: Infinity,
  })
}

/* ------------------------- H3 · 追溯闭环 ------------------------- */

// FR-3 · 逆向追溯：消耗该原料批的成品批次。
export function useTraceBackward(lotNo) {
  return useQuery({
    queryKey: ['trace', 'backward', lotNo],
    queryFn: () => request(`/api/trace/backward?lotNo=${encodeURIComponent(lotNo)}`),
    enabled: !!lotNo,
    staleTime: 30_000,
  })
}

// FR-4 · 影响面分析。
export function useTraceImpact(lotNo) {
  return useQuery({
    queryKey: ['trace', 'impact', lotNo],
    queryFn: () => request(`/api/trace/impact?lotNo=${encodeURIComponent(lotNo)}`),
    enabled: !!lotNo,
    staleTime: 30_000,
  })
}

// FR-8 · 档案完整性与缺失项。
export function useTraceCompleteness(batchId) {
  return useQuery({
    queryKey: ['trace', 'completeness', batchId],
    queryFn: () => request(`/api/trace/${encodeURIComponent(batchId)}/completeness`),
    enabled: !!batchId,
    staleTime: Infinity,
  })
}

// FR-7 · 追溯查询审计流水。
export function useTraceLogs(limit = 50) {
  return useQuery({
    queryKey: ['trace', 'logs', limit],
    queryFn: () => request(`/api/trace/logs?limit=${limit}`),
    refetchInterval: REFRESH_MS,
  })
}

// FR-6 · 追溯报表导出（Markdown）。
export function useExportTraceReport() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (batchId) => request(`/api/trace/${encodeURIComponent(batchId)}/export`),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['trace', 'logs'] }),
  })
}

// 登记中间品流转父子关系。
export function useLinkGenealogy() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body) => request('/api/trace/genealogy', { method: 'POST', body }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['trace'] }),
  })
}

/** 浏览器端下载 Markdown 报表（与班报导出同式）。 */
export function downloadMarkdown(fileName, content) {
  const blob = new Blob([content], { type: 'text/markdown;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = fileName
  a.click()
  URL.revokeObjectURL(url)
}
