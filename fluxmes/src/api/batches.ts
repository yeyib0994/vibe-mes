import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { request } from './http'
import { batches } from '../data/mes'

const REFRESH_MS = 30_000
// Phase 1：默认走后端 /api/batches；置 VITE_USE_MOCK=true 回退本地 Mock。
const USE_MOCK = import.meta.env.VITE_USE_MOCK === 'true'

function buildBatches() {
  return {
    generatedAt: new Date().toISOString(),
    batches,
  }
}

function mockDelay<T>(value: T, ms = 150): Promise<T> {
  return new Promise((resolve) => setTimeout(() => resolve(value), ms))
}

/** 批次台账响应（GET /api/batches）。 */
export type BatchListResponse = {
  generatedAt?: string
  batches?: BatchListItem[]
  [key: string]: unknown
}

/**
 * 批次条目。字段较杂（后端 schema 与 fixture 缝合），
 * 故除已知字段外允许扩展键，避免页面里大量 `unknown` 断言。
 */
export type BatchListItem = {
  id: string
  product?: string
  recipe?: string
  status?: string
  stage?: string
  line?: string
  qty?: number
  planYield?: number
  [key: string]: unknown
}

// 批次台账数据（T1：批次列表经此服务层取数，支持服务端过滤）。
export function useBatches() {
  return useQuery<BatchListResponse>({
    queryKey: ['batches'],
    queryFn: () => (USE_MOCK ? mockDelay(buildBatches()) : request<BatchListResponse>('/api/batches')),
    refetchInterval: REFRESH_MS,
  })
}

/* ---------------- 写操作（状态机 + 新建，RBAC 由后端校验） ---------------- */

function useInvalidate(refetchKey = 'batches') {
  const qc = useQueryClient()
  return () => {
    qc.invalidateQueries({ queryKey: [refetchKey] })
    qc.invalidateQueries({ queryKey: ['dashboard'] }) // 驾驶舱在产批次联动
  }
}

/** 新建批次请求体。line / shelfLifeDays / force 为 Phase E-G 扩展字段。 */
export type CreateBatchBody = {
  product: string
  recipe: string
  equipment: string
  recipeVersion?: string
  planYield: number
  materialLots: string[]
  line?: string
  shelfLifeDays?: number
  /** 值班长强制开工（绕过清场门禁，写审计）。 */
  force?: boolean
}

/** POST /api/batches —— 新建批次（FR-8）。需工艺员及以上角色。 */
export function useCreateBatch() {
  const invalidate = useInvalidate()
  return useMutation<unknown, Error, CreateBatchBody>({
    mutationFn: (body) => request('/api/batches', { method: 'POST', body }),
    onSuccess: invalidate,
  })
}

/** POST /api/batches/{id}/advance —— 工序推进（状态机 T5）。异常/已放行批次后端会阻断。 */
export function useAdvanceBatch() {
  const invalidate = useInvalidate()
  return useMutation<unknown, Error, string>({
    mutationFn: (id) => request(`/api/batches/${encodeURIComponent(id)}/advance`, { method: 'POST' }),
    onSuccess: invalidate,
  })
}

/** POST /api/batches/{id}/abnormal —— 标记异常并自动创建偏差单。 */
export function useMarkAbnormal() {
  const invalidate = useInvalidate()
  return useMutation<unknown, Error, { id: string; reason?: string }>({
    mutationFn: ({ id, reason }) =>
      request(`/api/batches/${encodeURIComponent(id)}/abnormal`, { method: 'POST', body: { reason } }),
    onSuccess: invalidate,
  })
}
