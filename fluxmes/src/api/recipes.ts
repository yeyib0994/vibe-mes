import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { request } from './http'
import { batches, recipes } from '../data/mes'

const REFRESH_MS = 30_000
// Phase 1：默认走后端 /api/recipes；置 VITE_USE_MOCK=true 回退本地 Mock。
const USE_MOCK = import.meta.env.VITE_USE_MOCK === 'true'

/** 受控版本行（H2）。 */
export type RecipeVersion = {
  version: string
  status: 'draft' | 'pending' | 'effective' | 'obsolete' | 'rejected' | string
  sourceVersion?: string | null
  stepCount?: number
  approvedBy?: string | null
  changeNote?: string | null
  [key: string]: unknown
}

/** 配方变更影响面（FR-7）。 */
export type RecipeImpact = {
  code?: string
  fromVersion?: string | null
  toVersion?: string | null
  affectedBatchCount?: number
  reviewRequired?: boolean
  reviewReason?: string | null
  affectedBatches?: { id: string; status?: string; released?: boolean }[]
  [key: string]: unknown
}

/** 审批响应（含被停用的旧版本号）。 */
export type ApproveResult = { deactivated?: string[]; [key: string]: unknown }
/** 建草稿响应。 */
export type DraftResult = { version?: string; sourceVersion?: string; [key: string]: unknown }

/** 版本流转变量（FR-4~FR-6 / NFR-3）。 */
export type DraftVars = { code: string; fromVersion?: string; changeNote?: string; steps?: unknown }
export type VersionVars = { code: string; version: string }
export type ApproveVars = { code: string; version: string; approved?: boolean; comment?: string }
export type ActivateVars = { code: string; version: string; comment?: string }

function mockDelay<T>(value: T, ms = 150): Promise<T> {
  return new Promise((resolve) => setTimeout(() => resolve(value), ms))
}

function buildRecipes() {
  // 统计每个「配方号」当前正在跑的批次数（配方号 = code，忽略版本）。
  const activeBatchCount: Record<string, number> = {}
  for (const b of batches) {
    const code = String(b.recipe || '').split(' ')[0]
    if (!code) continue
    activeBatchCount[code] = (activeBatchCount[code] || 0) + 1
  }
  const ccpTotal = recipes.reduce((s, r) => s + r.params.filter((p) => p.ccp).length, 0)
  return {
    generatedAt: new Date().toISOString(),
    recipes,
    activeBatchCount,
    summary: {
      total: recipes.length,
      active: recipes.filter((r) => r.status === 'active').length,
      draft: recipes.filter((r) => r.status === 'draft').length,
      obsolete: recipes.filter((r) => r.status === 'obsolete').length,
      ccpTotal,
    },
  }
}

/** 配方汇总统计。 */
export type RecipeSummary = {
  total?: number
  active?: number
  draft?: number
  obsolete?: number
  ccpTotal?: number
  [key: string]: unknown
}

/** 配方条目（字段较杂故开放扩展）。 */
export type RecipeItem = { [key: string]: any }

/** 配方台账响应（GET /api/recipes）。 */
export type RecipeListResponse = {
  generatedAt?: string
  recipes?: RecipeItem[]
  activeBatchCount?: Record<string, number>
  summary?: RecipeSummary
  [key: string]: unknown
}

// 配方台账与版本数据（T：配方管理页经此服务层取数）。
export function useRecipes() {
  return useQuery<RecipeListResponse>({
    queryKey: ['recipes'],
    queryFn: () => (USE_MOCK ? mockDelay(buildRecipes()) : request<RecipeListResponse>('/api/recipes')),
    refetchInterval: REFRESH_MS,
  })
}

/* ------------------------- H2 · 版本受控 ------------------------- */

/** 版本状态与前端展示口径映射（recipeStatusMap 只有 active/draft/obsolete 三态）。 */
export const VERSION_ROLE = {
  draft: { label: '草稿', tone: 'warn' },
  pending: { label: '待审批', tone: 'primary' },
  effective: { label: '生效', tone: 'accent' },
  obsolete: { label: '已停用', tone: 'muted' },
  rejected: { label: '已驳回', tone: 'danger' },
}

// 全部版本倒序（NFR-2）。
export function useRecipeVersions(code?: string, enabled = true) {
  return useQuery<RecipeVersion[]>({
    queryKey: ['recipes', code, 'versions'],
    queryFn: () => request<RecipeVersion[]>(`/api/recipes/${encodeURIComponent(code ?? '')}/versions`),
    enabled: !!code && enabled,
    staleTime: 30_000,
  })
}

// 版本历史（兼容原抽屉契约）。
export function useRecipeHistory(code: string) {
  return useQuery({
    queryKey: ['recipes', code, 'history'],
    queryFn: () => request(`/api/recipes/${encodeURIComponent(code)}/history`),
    enabled: !!code,
    staleTime: 30_000,
  })
}

// 指定版本的工序参数与容差（FR-3）。
export function useRecipeSteps(code: string, version?: string) {
  return useQuery({
    queryKey: ['recipes', code, 'steps', version ?? 'LATEST'],
    queryFn: () =>
      request(`/api/recipes/${encodeURIComponent(code)}/steps${version ? `?version=${encodeURIComponent(version)}` : ''}`),
    enabled: !!code,
    staleTime: 30_000,
  })
}

// 变更影响分析（FR-7）。
export function useRecipeImpact(code?: string, fromVersion?: string) {
  const qs = fromVersion ? `?fromVersion=${encodeURIComponent(fromVersion)}` : ''
  return useQuery<RecipeImpact>({
    queryKey: ['recipes', code, 'impact', fromVersion ?? 'AUTO'],
    queryFn: () => request<RecipeImpact>(`/api/recipes/${encodeURIComponent(code ?? '')}/impact${qs}`),
    enabled: !!code,
  })
}

const invalidate = (qc: ReturnType<typeof useQueryClient>, code?: string) => {
  qc.invalidateQueries({ queryKey: ['recipes'] })
  if (code) qc.invalidateQueries({ queryKey: ['recipes', code] })
}

// FR-4 · 基于已有版本创建草稿（次版本递增，工艺员+）。
export function useCreateDraft() {
  const qc = useQueryClient()
  return useMutation<DraftResult, Error, DraftVars>({
    mutationFn: ({ code, fromVersion, changeNote, steps }) =>
      request<DraftResult>(`/api/recipes/${encodeURIComponent(code)}/draft`, {
        method: 'POST',
        body: { fromVersion, changeNote, steps },
      }),
    onSuccess: (_d, v) => invalidate(qc, v.code),
  })
}

// FR-5 · 提交审批 draft → pending。
export function useSubmitVersion() {
  const qc = useQueryClient()
  return useMutation<unknown, Error, VersionVars>({
    mutationFn: ({ code, version }) =>
      request(`/api/recipes/${encodeURIComponent(code)}/versions/${encodeURIComponent(version)}/submit`, {
        method: 'POST',
      }),
    onSuccess: (_d, v) => invalidate(qc, v.code),
  })
}

// FR-6 · 审批（管理员）：approved=false 表示驳回。
export function useApproveVersion() {
  const qc = useQueryClient()
  return useMutation<ApproveResult, Error, ApproveVars>({
    mutationFn: ({ code, version, approved = true, comment }) =>
      request<ApproveResult>(`/api/recipes/${encodeURIComponent(code)}/versions/${encodeURIComponent(version)}/approve`, {
        method: 'POST',
        body: { approved, comment },
      }),
    onSuccess: (_d, v) => invalidate(qc, v.code),
  })
}

// NFR-3 · 原子生效切换（管理员）。
export function useActivateVersion() {
  const qc = useQueryClient()
  return useMutation<unknown, Error, ActivateVars>({
    mutationFn: ({ code, version, comment }) =>
      request(`/api/recipes/${encodeURIComponent(code)}/versions/${encodeURIComponent(version)}/activate`, {
        method: 'POST',
        body: { comment },
      }),
    onSuccess: (_d, v) => invalidate(qc, v.code),
  })
}
