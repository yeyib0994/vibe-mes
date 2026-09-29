import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { request } from './http'
import { pareto, qcTasks, spcData } from '../data/mes'

const REFRESH_MS = 30_000
// Phase 1：默认走后端 /api/quality；置 VITE_USE_MOCK=true 回退本地 Mock。
const USE_MOCK = import.meta.env.VITE_USE_MOCK === 'true'

function buildQuality() {
  return {
    generatedAt: new Date().toISOString(),
    spcData,
    pareto,
    qcTasks,
  }
}

function mockDelay(value, ms = 150) {
  return new Promise((resolve) => setTimeout(() => resolve(value), ms))
}

// 质量管理数据（T1：SPC/帕累托/检验任务经此服务层取数）。
// 注：SPC_UCL/CL/LCL 与 qcStatusMap 为显示常量/UI 映射，仍由页面从 mes.js 直接导入。
export function useQuality() {
  return useQuery({
    queryKey: ['quality'],
    queryFn: () => (USE_MOCK ? mockDelay(buildQuality()) : request('/api/quality')),
    refetchInterval: REFRESH_MS,
  })
}

/* ---------------- 判异 / 控制限 / 偏差 / 放行（RBAC 由后端校验） ---------------- */

/** GET /api/quality/spc/detect —— Western Electric 判异结果 + 所用控制限（T2/T3）。 */
export function useQualityDetect() {
  return useQuery({
    queryKey: ['quality-detect'],
    queryFn: () => request('/api/quality/spc/detect'),
    refetchInterval: REFRESH_MS,
  })
}

/** GET /api/quality/deviations —— 偏差单列表（T8）。 */
export function useDeviations() {
  return useQuery({
    queryKey: ['deviations'],
    queryFn: () => request('/api/quality/deviations'),
    refetchInterval: REFRESH_MS,
  })
}

function useQualityInvalidate() {
  const qc = useQueryClient()
  return () => {
    qc.invalidateQueries({ queryKey: ['quality'] })
    qc.invalidateQueries({ queryKey: ['deviations'] })
    qc.invalidateQueries({ queryKey: ['batches'] })
  }
}

/** POST /api/quality/deviations/{id}/transition —— 偏差逐级推进；关闭需值班长及以上。 */
export function useTransitionDeviation() {
  const invalidate = useQualityInvalidate()
  return useMutation({
    mutationFn: (v: { id: string; target: string; rootCause?: string; capa?: string }) =>
      request(`/api/quality/deviations/${encodeURIComponent(v.id)}/transition`, {
        method: 'POST',
        body: { target: v.target, rootCause: v.rootCause, capa: v.capa },
      }),
    onSuccess: invalidate,
  })
}

/** POST /api/quality/release —— 成品放行 + 生成 COA（T7）。需质检员及以上。 */
export function useReleaseBatch() {
  const invalidate = useQualityInvalidate()
  return useMutation({
    mutationFn: (batchId: string) =>
      request('/api/quality/release', { method: 'POST', body: { batchId } }),
    onSuccess: invalidate,
  })
}
