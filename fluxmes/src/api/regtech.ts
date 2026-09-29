import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { request } from './http'

/**
 * Phase I · 质量体系 RegTech 服务层（CAPA 闭环 / 内审管理 / 电子签名）。
 * 与后端 api-java/com/fluxmes/api/regtech 一一对应（CapaController / AuditProgramController /
 * SignatureController）。契约见 specs/quality-regtech/plan.md §3。
 */

const REFRESH_MS = 60_000

/* ---------------- 常量映射（与后端枚举对齐） ---------------- */

export const SOURCE_TYPES = [
  { value: 'DEVIATION', label: '偏差单' },
  { value: 'AUDIT_FINDING', label: '内审发现项' },
  { value: 'ALARM', label: '报警' },
  { value: 'MANUAL', label: '手工创建' },
]

export const CAPA_TYPES = [
  { value: 'CORRECTION', label: '纠正（处置现有不合格）' },
  { value: 'CORRECTIVE', label: '纠正措施（消除已发生原因）' },
  { value: 'PREVENTIVE', label: '预防措施（消除潜在原因）' },
]

export const SEVERITIES = [
  { value: 'critical', label: '严重' },
  { value: 'major', label: '主要' },
  { value: 'minor', label: '次要' },
]

export const CAPA_STATUS: Record<string, { label: string; tone: string }> = {
  open: { label: '待启动', tone: 'muted' },
  in_progress: { label: '执行中', tone: 'primary' },
  pending_verify: { label: '待验证', tone: 'warning' },
  verified: { label: '已验证', tone: 'accent' },
  closed: { label: '已关闭', tone: 'success' },
  rejected: { label: '验证无效退回', tone: 'danger' },
}

export const SEVERITY_TONE: Record<string, string> = { critical: 'danger', major: 'warning', minor: 'muted' }

export const VERIFY_METHODS = [
  { value: 'DOC_REVIEW', label: '文件审查' },
  { value: 'SITE_CHECK', label: '现场确认' },
  { value: 'DATA_REVIEW', label: '数据复核' },
]

export const AUDIT_TYPES = [
  { value: 'SYSTEM', label: '体系审核' },
  { value: 'PROCESS', label: '过程审核' },
  { value: 'PRODUCT', label: '产品审核' },
  { value: 'GMP_SELF', label: 'GMP 自查' },
]

export const FINDING_SEVERITIES = [
  { value: 'CRITICAL', label: '严重' },
  { value: 'MAJOR', label: '主要' },
  { value: 'MINOR', label: '次要' },
  { value: 'OBSERVATION', label: '观察项' },
]

export const FINDING_TONE = {
  CRITICAL: 'danger',
  MAJOR: 'warning',
  MINOR: 'muted',
  OBSERVATION: 'accent',
}

export const MEANING_LABEL = {
  AUTHORED: '编制',
  REVIEWED: '复核',
  APPROVED: '批准',
  VERIFIED: '验证',
  WITNESSED: '见证',
}

/* ---------------- CAPA ---------------- */

/** CAPA 行动项（task）。 */
export type CapaTask = {
  id: string | number
  seq?: number
  action?: string
  owner?: string
  dueDate?: string | null
  done?: boolean
  evidence?: string | null
  doneAt?: string | null
  [key: string]: unknown
}

/** CAPA 状态流水事件（append-only）。 */
export type CapaEvent = {
  fromStatus?: string | null
  toStatus?: string
  operator?: string
  comment?: string | null
  createdAt?: string
  [key: string]: unknown
}

/** CAPA 行 / 详情（列表仅带 taskSummary，详情带 tasks + events）。 */
export type CapaItem = {
  id: string | number
  status?: string
  sourceType?: string
  sourceLabel?: string
  sourceId?: string | number | null
  type?: string
  typeLabel?: string
  title?: string
  description?: string | null
  rootCause?: string | null
  owner?: string
  dueDate?: string | null
  daysLeft?: number
  overdue?: boolean
  dueSoon?: boolean
  severity?: string
  taskSummary?: { done?: number; total?: number; [key: string]: unknown }
  recordRevision?: number
  effectiveness?: string | null
  verificationMethod?: string | null
  verificationMethodLabel?: string | null
  verifyComment?: string | null
  rejectReason?: string | null
  createdAt?: string | null
  closedAt?: string | null
  tasks?: CapaTask[]
  events?: CapaEvent[]
  [key: string]: unknown
}

export type CapaListResponse = { items?: CapaItem[]; [key: string]: unknown }

/** CAPA 指标。 */
export type CapaMetrics = {
  total?: number
  open?: number
  overdue?: number
  dueSoon?: number
  dueSoonDays?: number
  findingCount?: number
  openMajorFindings?: number
  capaConversionRate?: number | null
  auditCount?: number
  [key: string]: unknown
}

/** 按责任人的在办汇总。 */
export type CapaOwnerSummary = {
  owner?: string
  overdue?: number
  dueSoon?: number
  onTrack?: number
  total?: number
  [key: string]: unknown
}

/** 逾期 / 临期清单。 */
export type CapaOverdueResponse = {
  overdueCount?: number
  dueSoonCount?: number
  dueSoonDays?: number
  alarmRaised?: number
  overdue?: CapaItem[]
  dueSoon?: CapaItem[]
  byOwner?: CapaOwnerSummary[]
  [key: string]: unknown
}

export function useCapaList(params: { status?: string; owner?: string; site?: string; overdue?: boolean } = {}) {
  const qs = new URLSearchParams()
  if (params.status) qs.set('status', params.status)
  if (params.owner) qs.set('owner', params.owner)
  if (params.site) qs.set('site', params.site)
  if (params.overdue) qs.set('overdue', 'true')
  const s = qs.toString()
  return useQuery<CapaListResponse>({
    queryKey: ['regtech', 'capa', params],
    queryFn: () => request<CapaListResponse>(`/api/capa${s ? `?${s}` : ''}`),
    refetchInterval: REFRESH_MS,
  })
}

export function useCapaDetail(id?: string | number) {
  return useQuery<CapaItem>({
    queryKey: ['regtech', 'capa', 'detail', id],
    queryFn: () => request<CapaItem>(`/api/capa/${id}`),
    enabled: !!id,
  })
}

export function useCapaMetrics() {
  return useQuery<CapaMetrics>({
    queryKey: ['regtech', 'capa', 'metrics'],
    queryFn: () => request<CapaMetrics>('/api/capa/metrics'),
    refetchInterval: REFRESH_MS,
  })
}

export function useCapaOverdue() {
  return useQuery<CapaOverdueResponse>({
    queryKey: ['regtech', 'capa', 'overdue'],
    queryFn: () => request<CapaOverdueResponse>('/api/capa/overdue'),
    refetchInterval: REFRESH_MS,
  })
}

/** CAPA 变更后统一失效缓存（列表 / 指标 / 逾期 / 详情）。 */
function useInvalidateCapa() {
  const qc = useQueryClient()
  return () => qc.invalidateQueries({ queryKey: ['regtech', 'capa'] })
}

/** 创建结果（新建后自动选中该 CAPA）。 */
export type CreateCapaResult = { id?: string | number; [key: string]: unknown }

export function useCreateCapa() {
  const invalidate = useInvalidateCapa()
  return useMutation<CreateCapaResult, Error, Record<string, unknown>>({
    mutationFn: (body: Record<string, unknown>) => request<CreateCapaResult>('/api/capa', { method: 'POST', body }),
    onSuccess: invalidate,
  })
}

export function useTransitionCapa() {
  const invalidate = useInvalidateCapa()
  return useMutation<unknown, Error, { id: string | number; target: string; comment?: string }>({
    mutationFn: ({ id, target, comment }: { id: string | number; target: string; comment?: string }) =>
      request(`/api/capa/${id}/transition`, { method: 'POST', body: { target, comment } }),
    onSuccess: invalidate,
  })
}

export function useUpdateCapa() {
  const invalidate = useInvalidateCapa()
  return useMutation<unknown, Error, { id: string | number } & Record<string, unknown>>({
    mutationFn: ({ id, ...body }: { id: string | number } & Record<string, unknown>) => request(`/api/capa/${id}/update`, { method: 'POST', body }),
    onSuccess: invalidate,
  })
}

export function useAddCapaTask() {
  const invalidate = useInvalidateCapa()
  return useMutation<unknown, Error, { id: string | number } & Record<string, unknown>>({
    mutationFn: ({ id, ...body }: { id: string | number } & Record<string, unknown>) => request(`/api/capa/${id}/tasks`, { method: 'POST', body }),
    onSuccess: invalidate,
  })
}

export function useDoneCapaTask() {
  const invalidate = useInvalidateCapa()
  return useMutation<unknown, Error, { taskId: string | number; evidence?: unknown }>({
    mutationFn: ({ taskId, evidence }: { taskId: string | number; evidence?: unknown }) =>
      request(`/api/capa/tasks/${taskId}/done`, { method: 'POST', body: { evidence } }),
    onSuccess: invalidate,
  })
}

/** FR-6 · 有效性验证（需电子签名：含义 VERIFIED / 管理员）。 */
export function useVerifyCapa() {
  const invalidate = useInvalidateCapa()
  return useMutation<CreateCapaResult, Error, { id: string | number; verificationMethod?: string; effectiveness?: string; comment?: string; signature?: unknown }>({
    mutationFn: ({ id, verificationMethod, effectiveness, comment, signature }: { id: string | number; verificationMethod?: string; effectiveness?: string; comment?: string; signature?: unknown }) =>
      request<CreateCapaResult>(`/api/capa/${id}/verify`, {
        method: 'POST',
        body: { verificationMethod, effectiveness, comment, signature },
      }),
    onSuccess: invalidate,
  })
}

/** FR-8 · 关闭（需电子签名：含义 APPROVED / 管理员）。 */
export function useCloseCapa() {
  const invalidate = useInvalidateCapa()
  return useMutation<unknown, Error, { id: string | number; comment?: string; signature?: unknown }>({
    mutationFn: ({ id, comment, signature }: { id: string | number; comment?: string; signature?: unknown }) =>
      request(`/api/capa/${id}/close`, { method: 'POST', body: { comment, signature } }),
    onSuccess: invalidate,
  })
}

/* ---------------- 内审 ---------------- */

/** 内审发现项。 */
export type AuditFinding = {
  id: number | string
  seq?: number
  severity?: string
  severityLabel?: string
  clause?: string | null
  description?: string
  area?: string | null
  owner?: string | null
  capaId?: number | string | null
  [key: string]: unknown
}

/** 内审计划行。 */
export type AuditListItem = {
  id: number | string
  title?: string
  auditType?: string
  auditTypeLabel?: string
  scope?: string | null
  lead?: string | null
  planStart?: string | null
  planEnd?: string | null
  status?: string
  findingCount?: number
  blockingFindings?: number
  [key: string]: unknown
}

/** 内审列表响应（GET /api/internal-audit）。 */
export type AuditListResponse = {
  items?: AuditListItem[]
  summary?: {
    auditCount?: number
    findingCount?: number
    openMajorFindings?: number
    capaConversionRate?: number | null
    capaCreated?: number
    [key: string]: unknown
  }
  [key: string]: unknown
}

/** 内审详情（含发现项）。 */
export type AuditDetailResponse = AuditListItem & { findings?: AuditFinding[] }

// 内审计划列表/详情（Phase I · FR-9 ~ FR-13）。
export function useAuditList(params: { year?: string | number; site?: string } = {}) {
  const qs = new URLSearchParams()
  if (params.year) qs.set('year', String(params.year))
  if (params.site) qs.set('site', params.site)
  const s = qs.toString()
  return useQuery<AuditListResponse>({
    queryKey: ['regtech', 'audit', params],
    queryFn: () => request<AuditListResponse>(`/api/internal-audit${s ? `?${s}` : ''}`),
    refetchInterval: REFRESH_MS,
  })
}

export function useAuditDetail(id?: string | number) {
  return useQuery<AuditDetailResponse>({
    queryKey: ['regtech', 'audit', 'detail', id],
    queryFn: () => request<AuditDetailResponse>(`/api/internal-audit/${id}`),
    enabled: !!id,
  })
}

export function useAuditSummary(year?: string | number) {
  return useQuery({
    queryKey: ['regtech', 'audit', 'summary', year],
    queryFn: () => request(`/api/internal-audit/summary${year ? `?year=${year}` : ''}`),
    refetchInterval: REFRESH_MS,
  })
}

function useInvalidateAudit() {
  const qc = useQueryClient()
  return () => {
    qc.invalidateQueries({ queryKey: ['regtech', 'audit'] })
    qc.invalidateQueries({ queryKey: ['regtech', 'capa'] })
  }
}

/** 建内审响应（回传新建 id）。 */
export type CreateAuditResult = { id?: number | string; [key: string]: unknown }

export function useCreateAudit() {
  const invalidate = useInvalidateAudit()
  return useMutation<CreateAuditResult, Error, Record<string, unknown>>({
    mutationFn: (body) => request<CreateAuditResult>('/api/internal-audit', { method: 'POST', body }),
    onSuccess: invalidate,
  })
}

export function useTransitionAudit() {
  const invalidate = useInvalidateAudit()
  return useMutation<unknown, Error, { id: string | number; target: string; comment?: string }>({
    mutationFn: ({ id, target, comment }) =>
      request(`/api/internal-audit/${id}/transition`, { method: 'POST', body: { target, comment } }),
    onSuccess: invalidate,
  })
}

export function useAddFinding() {
  const invalidate = useInvalidateAudit()
  return useMutation<unknown, Error, { id: string | number } & Record<string, unknown>>({
    mutationFn: ({ id, ...body }) => request(`/api/internal-audit/${id}/findings`, { method: 'POST', body }),
    onSuccess: invalidate,
  })
}

/** FR-11 · 发现项一键转 CAPA。 */
export function useFindingToCapa() {
  const invalidate = useInvalidateAudit()
  return useMutation<unknown, Error, { findingId: string | number } & Record<string, unknown>>({
    mutationFn: ({ findingId, ...body }) =>
      request(`/api/internal-audit/findings/${findingId}/to-capa`, { method: 'POST', body }),
    onSuccess: invalidate,
  })
}

/** FR-12 · 关闭内审（critical / major 未生成 CAPA 则 409）。 */
export function useCloseAudit() {
  const invalidate = useInvalidateAudit()
  return useMutation<unknown, Error, { id: string | number; comment?: string }>({
    mutationFn: ({ id, comment }) =>
      request(`/api/internal-audit/${id}/close`, { method: 'POST', body: { comment } }),
    onSuccess: invalidate,
  })
}

/* ---------------- 电子签名 ---------------- */

/** 签名策略行（FR-16）。enabled=false 时签名门禁整体降级放行。 */
export type SignaturePolicyItem = {
  action?: string
  requiredMeaning?: string
  requiredRole?: string
  required?: boolean
  enabled?: boolean
  [key: string]: unknown
}
export type SignaturePolicyResponse = { items?: SignaturePolicyItem[]; enabled?: boolean; [key: string]: unknown }

/** 当前账号签名失败/锁定状态（FR-17）。 */
export type SignatureAttemptState = {
  locked?: boolean
  lockedUntil?: string | null
  failedCount?: number
  maxFailures?: number
  lockMinutes?: number
  [key: string]: unknown
}

/** 签名记录行。 */
export type SignatureRecord = {
  id: number
  signer?: string
  signerName?: string
  signerRole?: string
  signedAt?: string
  meaning?: string
  meaningLabel?: string
  action?: string
  recordType?: string
  recordId?: string
  recordVersion?: number
  payloadHash?: string
  status?: 'VALID' | 'VOID' | string
  [key: string]: unknown
}
export type SignatureListResponse = { items?: SignatureRecord[]; [key: string]: unknown }

/** 哈希复核结果（FR-19）。 */
export type SignatureVerifyResult = {
  tampered?: boolean
  stale?: boolean
  payloadHash?: string
  currentHash?: string | null
  recordVersion?: number
  currentRecordVersion?: number
  [key: string]: unknown
}

/** 证据包导出结果（FR-20）。 */
export type SignatureExportResult = { filename?: string; markdown?: string; [key: string]: unknown }

export function useSignaturePolicy() {
  return useQuery<SignaturePolicyResponse>({
    queryKey: ['regtech', 'signatures', 'policy'],
    queryFn: () => request<SignaturePolicyResponse>('/api/signatures/policy'),
    staleTime: 5 * 60_000,
  })
}

export function useSignatureList(params: { recordType?: string; recordId?: string | number; signer?: string } = {}) {
  const qs = new URLSearchParams()
  if (params.recordType) qs.set('recordType', params.recordType)
  if (params.recordId) qs.set('recordId', String(params.recordId))
  if (params.signer) qs.set('signer', params.signer)
  const s = qs.toString()
  return useQuery<SignatureListResponse>({
    queryKey: ['regtech', 'signatures', params],
    queryFn: () => request<SignatureListResponse>(`/api/signatures${s ? `?${s}` : ''}`),
    refetchInterval: REFRESH_MS,
  })
}

/** FR-17 · 当前账号签名失败/锁定状态。 */
export function useMySignatureAttempts(options: { enabled?: boolean } = {}) {
  return useQuery<SignatureAttemptState>({
    queryKey: ['regtech', 'signatures', 'attempts'],
    queryFn: () => request<SignatureAttemptState>('/api/signatures/me/attempts'),
    enabled: options.enabled !== false,
    refetchInterval: options.enabled === false ? false : 30_000,
  })
}

/** FR-19 · 篡改检测。 */
export function useVerifySignature() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: string | number) => request<SignatureVerifyResult>(`/api/signatures/${id}/verify`),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['regtech', 'signatures'] }),
  })
}

/** FR-20 · 服务端渲染的证据包（Markdown / JSON）。 */
export function useExportSignatures() {
  return useMutation({
    mutationFn: ({ recordType, recordId, format = 'md' }: { recordType?: string; recordId?: string | number; format?: string }) => {
      const qs = new URLSearchParams()
      if (recordType) qs.set('recordType', recordType)
      if (recordId) qs.set('recordId', String(recordId))
      qs.set('format', format)
      return request<SignatureExportResult>(`/api/signatures/export?${qs.toString()}`)
    },
  })
}

/** 触发浏览器下载（Blob，与班报 / 追溯导出保持一致的交互）。 */
export function downloadText(filename: string, content: string | BlobPart, contentType = 'text/markdown') {
  const blob = new Blob([content], { type: `${contentType};charset=utf-8` })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}
