import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { request } from './http'

/**
 * 食品行业能力服务层（Phase E）：
 * F1 食品安全（HACCP/CCP、清场、环境） / F2 物料谱系与召回 / F3 eBR 与留样效期。
 * 与后端 apps/api-java/com/fluxmes/api/foodsafety 下的端点一一对应。
 */

const REFRESH_MS = 60_000

/* ============================ F1 · HACCP / CCP ============================ */

/** CCP 控制点定义（关键限值 clMin~clMax）。 */
export type CcpPoint = {
  code: string
  name?: string
  line?: string
  stepName?: string
  monitorFreq?: string
  hazardType?: string
  hazard?: string
  controlMeasure?: string
  correctiveAction?: string
  clMin?: number | string
  clMax?: number | string
  unit?: string
  [key: string]: unknown
}

export type CcpPointsResponse = { points?: CcpPoint[]; [key: string]: unknown }

/** CCP 监控记录（含双人复核 verifier）。 */
export type CcpRecord = {
  id: string | number
  ccpCode?: string
  ccpName?: string
  batchId?: string | null
  value?: number | string
  unit?: string
  clMin?: number | string
  clMax?: number | string
  inLimit?: boolean
  deviationId?: string | number | null
  operator?: string
  verifier?: string | null
  recordedAt?: string
  [key: string]: unknown
}

export type CcpRecordsResponse = { records?: CcpRecord[]; [key: string]: unknown }

export type CcpSummary = {
  generatedAt?: string
  complianceRate?: number | null
  recordCount?: number
  pointCount?: number
  deviationCount?: number
  openDeviationCount?: number
  unverifiedCount?: number
  [key: string]: unknown
}

/** 上报 CCP 后的返回：inLimit=false 时会带 deviationId。 */
export type RecordCcpResult = {
  id?: string | number
  ccpName?: string
  ccpCode?: string
  value?: number | string
  unit?: string
  inLimit?: boolean
  deviationId?: string | number | null
  [key: string]: unknown
}

export function useCcpPoints(line?: string) {
  return useQuery<CcpPointsResponse>({
    queryKey: ['haccp', 'points', line ?? 'all'],
    queryFn: () => request<CcpPointsResponse>(`/api/haccp/points${line ? `?line=${line}` : ''}`),
    refetchInterval: REFRESH_MS,
  })
}

export function useCcpRecords(params: { batchId?: string; ccpCode?: string; onlyDeviation?: boolean; limit?: number } = {}) {
  const { batchId, ccpCode, onlyDeviation, limit } = params
  const qs = new URLSearchParams()
  if (batchId) qs.set('batchId', batchId)
  if (ccpCode) qs.set('ccpCode', ccpCode)
  if (onlyDeviation) qs.set('onlyDeviation', 'true')
  if (limit) qs.set('limit', String(limit))
  return useQuery<CcpRecordsResponse>({
    queryKey: ['haccp', 'records', params],
    queryFn: () => request<CcpRecordsResponse>(`/api/haccp/records?${qs}`),
    refetchInterval: REFRESH_MS,
  })
}

export function useCcpSummary() {
  return useQuery<CcpSummary>({
    queryKey: ['haccp', 'summary'],
    queryFn: () => request<CcpSummary>('/api/haccp/summary'),
    refetchInterval: REFRESH_MS,
  })
}

export function useRecordCcp() {
  const qc = useQueryClient()
  return useMutation<RecordCcpResult, Error, Record<string, unknown>>({
    mutationFn: (body: Record<string, unknown>) =>
      request<RecordCcpResult>('/api/haccp/records', { method: 'POST', body }),
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
  return useMutation<unknown, Error, string | number>({
    mutationFn: (id: string | number) => request(`/api/haccp/records/${id}/verify`, { method: 'POST' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['haccp'] }),
  })
}

/* ============================ F1 · 清场与环境 ============================ */

/** 清场记录（PASS 且 QA 确认且在 72h 有效期内方可开工）。 */
export type CleaningRecord = {
  id: string | number
  line?: string
  equipmentCode?: string
  type?: string
  method?: string
  executedBy?: string
  verifiedBy?: string | null
  swabResult?: string | null
  result?: 'PASS' | 'FAIL' | 'PENDING' | string
  validUntil?: string | null
  [key: string]: unknown
}

export type CleaningRecordsResponse = { records?: CleaningRecord[]; [key: string]: unknown }

export function useCleaningRecords(params: { line?: string; type?: string } = {}) {
  const qs = new URLSearchParams()
  if (params.line) qs.set('line', params.line)
  if (params.type) qs.set('type', params.type)
  return useQuery<CleaningRecordsResponse>({
    queryKey: ['sanitation', 'records', params],
    queryFn: () => request<CleaningRecordsResponse>(`/api/sanitation/records?${qs}`),
    refetchInterval: REFRESH_MS,
  })
}

/** 清场门禁状态（F1 开工三闸之一）。 */
export type SanitationStatus = {
  line?: string
  cleared?: boolean
  reason?: string | null
  latest?: { id?: string | number; method?: string; validUntil?: string | null; [key: string]: unknown } | null
  lastCleaningAt?: string | null
  expiresAt?: string | null
  qaConfirmed?: boolean
  [key: string]: unknown
}

export function useSanitationStatus(line?: string) {
  return useQuery<SanitationStatus>({
    queryKey: ['sanitation', 'status', line],
    queryFn: () => request<SanitationStatus>(`/api/sanitation/status?line=${line}`),
    enabled: !!line,
  })
}

export function useCreateCleaning() {
  const qc = useQueryClient()
  return useMutation<unknown, Error, Record<string, unknown>>({
    mutationFn: (body: Record<string, unknown>) => request('/api/sanitation/records', { method: 'POST', body }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['sanitation'] }),
  })
}

export function useVerifyCleaning() {
  const qc = useQueryClient()
  return useMutation<unknown, Error, { id: string | number; result?: string; swabResult?: string }>({
    mutationFn: ({ id, result, swabResult }: { id: string | number; result?: string; swabResult?: string }) =>
      request(`/api/sanitation/records/${id}/verify`, { method: 'POST', body: { result, swabResult } }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['sanitation'] }),
  })
}

/** 环境监测记录。 */
export type EnvRecord = {
  id: string | number
  area?: string
  metric?: string
  value?: number | string
  unit?: string
  limitMin?: number | string | null
  limitMax?: number | string | null
  result?: 'PASS' | 'FAIL' | string
  deviationId?: string | number | null
  sampledBy?: string
  sampledAt?: string
  [key: string]: unknown
}

export type EnvRecordsResponse = { records?: EnvRecord[]; [key: string]: unknown }

export type EnvAreaStat = { area?: string; passRate?: number | null; recordCount?: number; failCount?: number; [key: string]: unknown }

export type EnvSummary = {
  generatedAt?: string
  passRate?: number | null
  recordCount?: number
  failCount?: number
  areas?: EnvAreaStat[]
  [key: string]: unknown
}

/** 上报环境指标后的返回：FAIL 时会带 deviationId。 */
export type RecordEnvResult = {
  id?: string | number
  result?: string
  deviationId?: string | number | null
  [key: string]: unknown
}

export function useEnvRecords(params: { area?: string; metric?: string } = {}) {
  const qs = new URLSearchParams()
  if (params.area) qs.set('area', params.area)
  if (params.metric) qs.set('metric', params.metric)
  return useQuery<EnvRecordsResponse>({
    queryKey: ['sanitation', 'env', params],
    queryFn: () => request<EnvRecordsResponse>(`/api/sanitation/environment?${qs}`),
    refetchInterval: REFRESH_MS,
  })
}

export function useEnvSummary() {
  return useQuery<EnvSummary>({
    queryKey: ['sanitation', 'envSummary'],
    queryFn: () => request<EnvSummary>('/api/sanitation/environment/summary'),
    refetchInterval: REFRESH_MS,
  })
}

export function useRecordEnv() {
  const qc = useQueryClient()
  return useMutation<RecordEnvResult, Error, Record<string, unknown>>({
    mutationFn: (body: Record<string, unknown>) =>
      request<RecordEnvResult>('/api/sanitation/environment', { method: 'POST', body }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['sanitation'] })
      qc.invalidateQueries({ queryKey: ['quality'] })
    },
  })
}

/* ============================ F2 · 物料谱系与召回 ============================ */

/** 物料主数据（含过敏原标记）。 */
export type MaterialItem = {
  code: string
  name?: string
  allergen?: boolean
  allergenName?: string | null
  unit?: string
  [key: string]: unknown
}

export type MaterialsResponse = { materials?: MaterialItem[]; [key: string]: unknown }

export function useMaterials() {
  return useQuery<MaterialsResponse>({
    queryKey: ['materials'],
    queryFn: () => request<MaterialsResponse>('/api/materials'),
    staleTime: 5 * 60_000,
  })
}

/** 原料批（material_lot）行。 */
export type MaterialLot = {
  id: string | number
  materialCode?: string
  materialName?: string
  supplier?: string
  supplierLot?: string
  qty?: number
  unit?: string
  /** 是否为过敏原物料（页面据此显示过敏原徽标）。 */
  allergen?: boolean
  allergenName?: string | null
  qcStatus?: 'PENDING' | 'RELEASED' | 'FAIL' | string
  coaNo?: string | null
  expiryDate?: string | null
  daysToExpiry?: number | null
  daysLeft?: number | null
  warehouseBin?: string | null
  [key: string]: unknown
}

export type MaterialLotsResponse = { lots?: MaterialLot[]; [key: string]: unknown }

export function useMaterialLots(params: { materialCode?: string; qcStatus?: string; expiringSoon?: boolean } = {}) {
  const qs = new URLSearchParams()
  if (params.materialCode) qs.set('materialCode', params.materialCode)
  if (params.qcStatus) qs.set('qcStatus', params.qcStatus)
  if (params.expiringSoon) qs.set('expiringSoon', 'true')
  return useQuery<MaterialLotsResponse>({
    queryKey: ['materials', 'lots', params],
    queryFn: () => request<MaterialLotsResponse>(`/api/materials/lots?${qs}`),
    refetchInterval: REFRESH_MS,
  })
}

export function useCreateLot() {
  const qc = useQueryClient()
  return useMutation<unknown, Error, Record<string, unknown>>({
    mutationFn: (body: Record<string, unknown>) => request('/api/materials/lots', { method: 'POST', body }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['materials'] }),
  })
}

export function useInspectLot() {
  const qc = useQueryClient()
  return useMutation<unknown, Error, { id: string | number; result?: string; coaNo?: string }>({
    mutationFn: ({ id, result, coaNo }: { id: string | number; result?: string; coaNo?: string }) =>
      request(`/api/materials/lots/${id}/inspect`, { method: 'POST', body: { result, coaNo } }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['materials'] }),
  })
}

/** 投料记录行（batch_input）。 */
export type BatchInput = {
  id?: string | number
  lotId?: string
  materialName?: string
  qty?: number
  unit?: string
  fedBy?: string
  fedAt?: string
  [key: string]: unknown
}

export type BatchInputsResponse = { inputs?: BatchInput[]; [key: string]: unknown }

export function useBatchInputs(batchId?: string) {
  return useQuery<BatchInputsResponse>({
    queryKey: ['materials', 'inputs', batchId],
    queryFn: () => request<BatchInputsResponse>(`/api/materials/${batchId}/inputs`),
    enabled: !!batchId,
  })
}

export function useFeed() {
  const qc = useQueryClient()
  return useMutation<unknown, Error, { batchId: string } & Record<string, unknown>>({
    mutationFn: ({ batchId, ...body }: { batchId: string } & Record<string, unknown>) =>
      request(`/api/materials/${batchId}/inputs`, { method: 'POST', body }),
    onSuccess: (_d, v) => {
      qc.invalidateQueries({ queryKey: ['materials', 'inputs', v.batchId] })
      qc.invalidateQueries({ queryKey: ['materials', 'genealogy'] })
    },
  })
}

/** 谱系上游原料批行（F2 · 真实投料谱系）。 */
export type GenealogyUpstream = {
  lotId: string
  materialName?: string
  qcStatus?: string
  allergen?: boolean
  allergenName?: string | null
  supplier?: string
  supplierLot?: string
  qty?: number
  unit?: string
  coaNo?: string | null
  expiryDate?: string | null
  [key: string]: unknown
}

/** 谱系下游用途行。 */
export type GenealogyDownstream = { lotId?: string; batchId?: string; [key: string]: unknown }

/** 投料谱系响应（GET /api/materials/genealogy/{batchId}）。 */
export type GenealogyResponse = {
  batchId?: string
  product?: string
  upstream?: GenealogyUpstream[]
  downstream?: GenealogyDownstream[]
  upstreamCount?: number
  downstreamCount?: number
  [key: string]: unknown
}

export function useGenealogy(batchId?: string) {
  return useQuery<GenealogyResponse>({
    queryKey: ['materials', 'genealogy', batchId],
    queryFn: () => request<GenealogyResponse>(`/api/materials/genealogy/${batchId}`),
    enabled: !!batchId,
  })
}

/** 召回分析命中的成品批次。 */
export type RecallAffectedBatch = {
  batchId?: string
  product?: string
  status?: string
  released?: boolean
  warehouseBin?: string | null
  planYieldT?: number | null
  [key: string]: unknown
}

/** 召回影响分析响应（GET /api/materials/recall/{lotId}）。 */
export type RecallResponse = {
  materialLotId?: string
  materialName?: string
  supplier?: string
  supplierLot?: string
  affectedBatchCount?: number
  releasedBatchCount?: number
  totalYieldT?: number | null
  affectedBatches?: RecallAffectedBatch[]
  suggestions?: string[]
  [key: string]: unknown
}

export function useRecall(materialLotId?: string, enabled = false) {
  return useQuery<RecallResponse>({
    queryKey: ['materials', 'recall', materialLotId],
    queryFn: () => request<RecallResponse>(`/api/materials/recall/${materialLotId}`),
    enabled: !!materialLotId && enabled,
  })
}

/* ============================ F3 · eBR / 留样 / 效期 ============================ */

/** eBR 工序行。 */
export type EbrStep = {
  id: number
  stepNo?: number
  stepName?: string
  name?: string
  status?: string
  /** 工艺设定值快照。 */
  targetParams?: Record<string, unknown> | null
  /** 实际操作值快照。 */
  actualParams?: Record<string, unknown> | null
  operator?: string | null
  reviewer?: string | null
  reviewed?: boolean
  [key: string]: unknown
}
/** 电子批记录（F3）。 */
export type EbrResponse = {
  batchId?: string
  complete?: boolean
  unreviewedCount?: number
  doneCount?: number
  stepCount?: number
  steps?: EbrStep[]
  [key: string]: unknown
}

export function useEbr(batchId?: string) {
  return useQuery<EbrResponse>({
    queryKey: ['ebr', batchId],
    queryFn: () => request<EbrResponse>(`/api/ebr/${batchId}`),
    enabled: !!batchId,
  })
}

export function useRecordStep() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ batchId, ...body }: { batchId: string } & Record<string, unknown>) =>
      request(`/api/ebr/${batchId}/steps`, { method: 'POST', body }),
    onSuccess: (_d, v) => qc.invalidateQueries({ queryKey: ['ebr', v.batchId] }),
  })
}

export function useReviewStep() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: string | number) => request(`/api/ebr/steps/${id}/review`, { method: 'POST' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['ebr'] }),
  })
}

/** 留样记录（到期 = 成品效期 + 180 天）。 */
export type RetentionSample = {
  id: string | number
  batchId?: string
  product?: string
  qty?: number
  unit?: string
  location?: string
  status?: 'RETAINED' | 'TESTED' | 'DISCARDED' | string
  expiryDate?: string | null
  daysLeft?: number | null
  disposedBy?: string | null
  [key: string]: unknown
}

export type RetentionSamplesResponse = { samples?: RetentionSample[]; [key: string]: unknown }

export function useRetentionSamples(params: { status?: string; expiringSoon?: boolean } = {}) {
  const qs = new URLSearchParams()
  if (params.status) qs.set('status', params.status)
  if (params.expiringSoon) qs.set('expiringSoon', 'true')
  return useQuery<RetentionSamplesResponse>({
    queryKey: ['ebr', 'retention', params],
    queryFn: () => request<RetentionSamplesResponse>(`/api/ebr/retention?${qs}`),
    refetchInterval: REFRESH_MS,
  })
}

export function useRetainSample() {
  const qc = useQueryClient()
  return useMutation<unknown, Error, Record<string, unknown>>({
    mutationFn: (body: Record<string, unknown>) => request('/api/ebr/retention', { method: 'POST', body }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['ebr', 'retention'] }),
  })
}

export function useDisposeSample() {
  const qc = useQueryClient()
  return useMutation<unknown, Error, { id: string | number; remark?: string }>({
    mutationFn: ({ id, remark }: { id: string | number; remark?: string }) =>
      request(`/api/ebr/retention/${id}/dispose`, { method: 'POST', body: { remark } }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['ebr', 'retention'] }),
  })
}

/** 近效期/已过期成品行。 */
export type ExpiryAlertBatch = {
  batchId?: string
  product?: string
  expiryDate?: string | null
  daysLeft?: number
  [key: string]: unknown
}

export type ExpiryAlertsResponse = {
  warnDays?: number
  expiringCount?: number
  expiredCount?: number
  expiring?: ExpiryAlertBatch[]
  expired?: ExpiryAlertBatch[]
  [key: string]: unknown
}

export function useExpiryAlerts(warnDays: number = 30) {
  return useQuery<ExpiryAlertsResponse>({
    queryKey: ['ebr', 'expiry', warnDays],
    queryFn: () => request<ExpiryAlertsResponse>(`/api/ebr/expiry-alerts?warnDays=${warnDays}`),
    refetchInterval: REFRESH_MS,
  })
}

/* ============================ 展示用常量 ============================ */

export const CLEANING_TYPE_LABEL: Record<string, { text: string; tone: string }> = {
  ROUTINE: { text: '日常清场', tone: 'muted' },
  CHANGEOVER: { text: '换型清场', tone: 'info' },
  ALLERGEN: { text: '过敏原换型', tone: 'warning' },
  DEEP: { text: '深度清洁', tone: 'primary' },
}

export const HAZARD_LABEL: Record<string, { text: string; tone: string }> = {
  BIOLOGICAL: { text: '生物性', tone: 'danger' },
  CHEMICAL: { text: '化学性', tone: 'warning' },
  PHYSICAL: { text: '物理性', tone: 'info' },
  ALLERGEN: { text: '过敏原', tone: 'warning' },
}

export const METRIC_LABEL: Record<string, string> = {
  TEMP: '温度',
  HUMIDITY: '湿度',
  PRESSURE_DIFF: '压差',
  MICRO: '沉降菌',
  ATP: 'ATP',
  PARTICLE: '悬浮粒子',
}

export const RESULT_TONE: Record<string, string> = { PASS: 'success', FAIL: 'danger', PENDING: 'warning' }
