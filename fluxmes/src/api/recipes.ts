import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { request } from './http'
import { batches, recipes } from '../data/mes'

const REFRESH_MS = 30_000
// Phase 1：默认走后端 /api/recipes；置 VITE_USE_MOCK=true 回退本地 Mock。
const USE_MOCK = import.meta.env.VITE_USE_MOCK === 'true'

function mockDelay(value, ms = 150) {
  return new Promise((resolve) => setTimeout(() => resolve(value), ms))
}

function buildRecipes() {
  // 统计每个「配方号」当前正在跑的批次数（配方号 = code，忽略版本）。
  const activeBatchCount = {}
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

// 配方台账与版本数据（T：配方管理页经此服务层取数）。
export function useRecipes() {
  return useQuery({
    queryKey: ['recipes'],
    queryFn: () => (USE_MOCK ? mockDelay(buildRecipes()) : request('/api/recipes')),
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
export function useRecipeVersions(code, enabled = true) {
  return useQuery({
    queryKey: ['recipes', code, 'versions'],
    queryFn: () => request(`/api/recipes/${encodeURIComponent(code)}/versions`),
    enabled: !!code && enabled,
    staleTime: 30_000,
  })
}

// 版本历史（兼容原抽屉契约）。
export function useRecipeHistory(code) {
  return useQuery({
    queryKey: ['recipes', code, 'history'],
    queryFn: () => request(`/api/recipes/${encodeURIComponent(code)}/history`),
    enabled: !!code,
    staleTime: 30_000,
  })
}

// 指定版本的工序参数与容差（FR-3）。
export function useRecipeSteps(code, version) {
  return useQuery({
    queryKey: ['recipes', code, 'steps', version ?? 'LATEST'],
    queryFn: () =>
      request(`/api/recipes/${encodeURIComponent(code)}/steps${version ? `?version=${encodeURIComponent(version)}` : ''}`),
    enabled: !!code,
    staleTime: 30_000,
  })
}

// 变更影响分析（FR-7）。
export function useRecipeImpact(code, fromVersion) {
  const qs = fromVersion ? `?fromVersion=${encodeURIComponent(fromVersion)}` : ''
  return useQuery({
    queryKey: ['recipes', code, 'impact', fromVersion ?? 'AUTO'],
    queryFn: () => request(`/api/recipes/${encodeURIComponent(code)}/impact${qs}`),
    enabled: !!code,
  })
}

const invalidate = (qc, code) => {
  qc.invalidateQueries({ queryKey: ['recipes'] })
  if (code) qc.invalidateQueries({ queryKey: ['recipes', code] })
}

// FR-4 · 基于已有版本创建草稿（次版本递增，工艺员+）。
export function useCreateDraft() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ code, fromVersion, changeNote, steps }) =>
      request(`/api/recipes/${encodeURIComponent(code)}/draft`, {
        method: 'POST',
        body: { fromVersion, changeNote, steps },
      }),
    onSuccess: (_d, v) => invalidate(qc, v.code),
  })
}

// FR-5 · 提交审批 draft → pending。
export function useSubmitVersion() {
  const qc = useQueryClient()
  return useMutation({
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
  return useMutation({
    mutationFn: ({ code, version, approved = true, comment }) =>
      request(`/api/recipes/${encodeURIComponent(code)}/versions/${encodeURIComponent(version)}/approve`, {
        method: 'POST',
        body: { approved, comment },
      }),
    onSuccess: (_d, v) => invalidate(qc, v.code),
  })
}

// NFR-3 · 原子生效切换（管理员）。
export function useActivateVersion() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ code, version, comment }) =>
      request(`/api/recipes/${encodeURIComponent(code)}/versions/${encodeURIComponent(version)}/activate`, {
        method: 'POST',
        body: { comment },
      }),
    onSuccess: (_d, v) => invalidate(qc, v.code),
  })
}
