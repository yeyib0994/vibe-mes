import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { request } from './http'

/**
 * 食品行业能力服务层（Phase E）：
 * F1 食品安全（HACCP/CCP、清场、环境） / F2 物料谱系与召回 / F3 eBR 与留样效期。
 * 与后端 apps/api-java/com/fluxmes/api/foodsafety 下的端点一一对应。
 */

const REFRESH_MS = 60_000

/* ============================ F1 · HACCP / CCP ============================ */

export function useCcpPoints(line) {
  return useQuery({
    queryKey: ['haccp', 'points', line ?? 'all'],
    queryFn: () => request(`/api/haccp/points${line ? `?line=${line}` : ''}`),
    refetchInterval: REFRESH_MS,
  })
}

export function useCcpRecords(params = {}) {
  const { batchId, ccpCode, onlyDeviation, limit } = params
  const qs = new URLSearchParams()
  if (batchId) qs.set('batchId', batchId)
  if (ccpCode) qs.set('ccpCode', ccpCode)
  if (onlyDeviation) qs.set('onlyDeviation', 'true')
  if (limit) qs.set('limit', String(limit))
  return useQuery({
    queryKey: ['haccp', 'records', params],
    queryFn: () => request(`/api/haccp/records?${qs}`),
    refetchInterval: REFRESH_MS,
  })
}

export function useCcpSummary() {
  return useQuery({
    queryKey: ['haccp', 'summary'],
    queryFn: () => request('/api/haccp/summary'),
    refetchInterval: REFRESH_MS,
  })
}

export function useRecordCcp() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body) => request('/api/haccp/records', { method: 'POST', body }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['haccp'] })
      qc.invalidateQueries({ queryKey: ['batches'] })
      qc.invalidateQueries({ queryKey: ['alarms'] })
      qc.invalidateQueries({ queryKey: ['quality'] })
    },
  })
}

export function useVerifyCcp() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id) => request(`/api/haccp/records/${id}/verify`, { method: 'POST' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['haccp'] }),
  })
}

/* ============================ F1 · 清场与环境 ============================ */

export function useCleaningRecords(params = {}) {
  const qs = new URLSearchParams()
  if (params.line) qs.set('line', params.line)
  if (params.type) qs.set('type', params.type)
  return useQuery({
    queryKey: ['sanitation', 'records', params],
    queryFn: () => request(`/api/sanitation/records?${qs}`),
    refetchInterval: REFRESH_MS,
  })
}

export function useSanitationStatus(line) {
  return useQuery({
    queryKey: ['sanitation', 'status', line],
    queryFn: () => request(`/api/sanitation/status?line=${line}`),
    enabled: !!line,
  })
}

export function useCreateCleaning() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body) => request('/api/sanitation/records', { method: 'POST', body }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['sanitation'] }),
  })
}

export function useVerifyCleaning() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, result, swabResult }) =>
      request(`/api/sanitation/records/${id}/verify`, { method: 'POST', body: { result, swabResult } }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['sanitation'] }),
  })
}

export function useEnvRecords(params = {}) {
  const qs = new URLSearchParams()
  if (params.area) qs.set('area', params.area)
  if (params.metric) qs.set('metric', params.metric)
  return useQuery({
    queryKey: ['sanitation', 'env', params],
    queryFn: () => request(`/api/sanitation/environment?${qs}`),
    refetchInterval: REFRESH_MS,
  })
}

export function useEnvSummary() {
  return useQuery({
    queryKey: ['sanitation', 'envSummary'],
    queryFn: () => request('/api/sanitation/environment/summary'),
    refetchInterval: REFRESH_MS,
  })
}

export function useRecordEnv() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body) => request('/api/sanitation/environment', { method: 'POST', body }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['sanitation'] })
      qc.invalidateQueries({ queryKey: ['quality'] })
    },
  })
}

/* ============================ F2 · 物料谱系与召回 ============================ */

export function useMaterials() {
  return useQuery({
    queryKey: ['materials'],
    queryFn: () => request('/api/materials'),
    staleTime: 5 * 60_000,
  })
}

export function useMaterialLots(params = {}) {
  const qs = new URLSearchParams()
  if (params.materialCode) qs.set('materialCode', params.materialCode)
  if (params.qcStatus) qs.set('qcStatus', params.qcStatus)
  if (params.expiringSoon) qs.set('expiringSoon', 'true')
  return useQuery({
    queryKey: ['materials', 'lots', params],
    queryFn: () => request(`/api/materials/lots?${qs}`),
    refetchInterval: REFRESH_MS,
  })
}

export function useCreateLot() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body) => request('/api/materials/lots', { method: 'POST', body }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['materials'] }),
  })
}

export function useInspectLot() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, result, coaNo }) =>
      request(`/api/materials/lots/${id}/inspect`, { method: 'POST', body: { result, coaNo } }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['materials'] }),
  })
}

export function useBatchInputs(batchId) {
  return useQuery({
    queryKey: ['materials', 'inputs', batchId],
    queryFn: () => request(`/api/materials/${batchId}/inputs`),
    enabled: !!batchId,
  })
}

export function useFeed() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ batchId, ...body }) =>
      request(`/api/materials/${batchId}/inputs`, { method: 'POST', body }),
    onSuccess: (_d, v) => {
      qc.invalidateQueries({ queryKey: ['materials', 'inputs', v.batchId] })
      qc.invalidateQueries({ queryKey: ['materials', 'genealogy'] })
    },
  })
}

export function useGenealogy(batchId) {
  return useQuery({
    queryKey: ['materials', 'genealogy', batchId],
    queryFn: () => request(`/api/materials/genealogy/${batchId}`),
    enabled: !!batchId,
  })
}

export function useRecall(materialLotId, enabled = false) {
  return useQuery({
    queryKey: ['materials', 'recall', materialLotId],
    queryFn: () => request(`/api/materials/recall/${materialLotId}`),
    enabled: !!materialLotId && enabled,
  })
}

/* ============================ F3 · eBR / 留样 / 效期 ============================ */

export function useEbr(batchId) {
  return useQuery({
    queryKey: ['ebr', batchId],
    queryFn: () => request(`/api/ebr/${batchId}`),
    enabled: !!batchId,
  })
}

export function useRecordStep() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ batchId, ...body }) =>
      request(`/api/ebr/${batchId}/steps`, { method: 'POST', body }),
    onSuccess: (_d, v) => qc.invalidateQueries({ queryKey: ['ebr', v.batchId] }),
  })
}

export function useReviewStep() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id) => request(`/api/ebr/steps/${id}/review`, { method: 'POST' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['ebr'] }),
  })
}

export function useRetentionSamples(params = {}) {
  const qs = new URLSearchParams()
  if (params.status) qs.set('status', params.status)
  if (params.expiringSoon) qs.set('expiringSoon', 'true')
  return useQuery({
    queryKey: ['ebr', 'retention', params],
    queryFn: () => request(`/api/ebr/retention?${qs}`),
    refetchInterval: REFRESH_MS,
  })
}

export function useRetainSample() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body) => request('/api/ebr/retention', { method: 'POST', body }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['ebr', 'retention'] }),
  })
}

export function useDisposeSample() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, remark }) =>
      request(`/api/ebr/retention/${id}/dispose`, { method: 'POST', body: { remark } }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['ebr', 'retention'] }),
  })
}

export function useExpiryAlerts(warnDays = 30) {
  return useQuery({
    queryKey: ['ebr', 'expiry', warnDays],
    queryFn: () => request(`/api/ebr/expiry-alerts?warnDays=${warnDays}`),
    refetchInterval: REFRESH_MS,
  })
}

/* ============================ 展示用常量 ============================ */

export const CLEANING_TYPE_LABEL = {
  ROUTINE: { text: '日常清场', tone: 'muted' },
  CHANGEOVER: { text: '换型清场', tone: 'info' },
  ALLERGEN: { text: '过敏原换型', tone: 'warning' },
  DEEP: { text: '深度清洁', tone: 'primary' },
}

export const HAZARD_LABEL = {
  BIOLOGICAL: { text: '生物性', tone: 'danger' },
  CHEMICAL: { text: '化学性', tone: 'warning' },
  PHYSICAL: { text: '物理性', tone: 'info' },
  ALLERGEN: { text: '过敏原', tone: 'warning' },
}

export const METRIC_LABEL = {
  TEMP: '温度',
  HUMIDITY: '湿度',
  PRESSURE_DIFF: '压差',
  MICRO: '沉降菌',
  ATP: 'ATP',
  PARTICLE: '悬浮粒子',
}

export const RESULT_TONE = { PASS: 'success', FAIL: 'danger', PENDING: 'warning' }
