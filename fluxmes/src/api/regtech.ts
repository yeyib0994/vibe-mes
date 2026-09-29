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

export const CAPA_STATUS = {
  open: { label: '待启动', tone: 'muted' },
  in_progress: { label: '执行中', tone: 'primary' },
  pending_verify: { label: '待验证', tone: 'warning' },
  verified: { label: '已验证', tone: 'accent' },
  closed: { label: '已关闭', tone: 'success' },
  rejected: { label: '验证无效退回', tone: 'danger' },
}

export const SEVERITY_TONE = { critical: 'danger', major: 'warning', minor: 'muted' }

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

export function useCapaList(params = {}) {
  const qs = new URLSearchParams()
  if (params.status) qs.set('status', params.status)
  if (params.owner) qs.set('owner', params.owner)
  if (params.site) qs.set('site', params.site)
  if (params.overdue) qs.set('overdue', 'true')
  const s = qs.toString()
  return useQuery({
    queryKey: ['regtech', 'capa', params],
    queryFn: () => request(`/api/capa${s ? `?${s}` : ''}`),
    refetchInterval: REFRESH_MS,
  })
}

export function useCapaDetail(id) {
  return useQuery({
    queryKey: ['regtech', 'capa', 'detail', id],
    queryFn: () => request(`/api/capa/${id}`),
    enabled: !!id,
  })
}

export function useCapaMetrics() {
  return useQuery({
    queryKey: ['regtech', 'capa', 'metrics'],
    queryFn: () => request('/api/capa/metrics'),
    refetchInterval: REFRESH_MS,
  })
}

export function useCapaOverdue() {
  return useQuery({
    queryKey: ['regtech', 'capa', 'overdue'],
    queryFn: () => request('/api/capa/overdue'),
    refetchInterval: REFRESH_MS,
  })
}

/** CAPA 变更后统一失效缓存（列表 / 指标 / 逾期 / 详情）。 */
function useInvalidateCapa() {
  const qc = useQueryClient()
  return () => qc.invalidateQueries({ queryKey: ['regtech', 'capa'] })
}

export function useCreateCapa() {
  const invalidate = useInvalidateCapa()
  return useMutation({
    mutationFn: (body) => request('/api/capa', { method: 'POST', body }),
    onSuccess: invalidate,
  })
}

export function useTransitionCapa() {
  const invalidate = useInvalidateCapa()
  return useMutation({
    mutationFn: ({ id, target, comment }) =>
      request(`/api/capa/${id}/transition`, { method: 'POST', body: { target, comment } }),
    onSuccess: invalidate,
  })
}

export function useUpdateCapa() {
  const invalidate = useInvalidateCapa()
  return useMutation({
    mutationFn: ({ id, ...body }) => request(`/api/capa/${id}/update`, { method: 'POST', body }),
    onSuccess: invalidate,
  })
}

export function useAddCapaTask() {
  const invalidate = useInvalidateCapa()
  return useMutation({
    mutationFn: ({ id, ...body }) => request(`/api/capa/${id}/tasks`, { method: 'POST', body }),
    onSuccess: invalidate,
  })
}

export function useDoneCapaTask() {
  const invalidate = useInvalidateCapa()
  return useMutation({
    mutationFn: ({ taskId, evidence }) =>
      request(`/api/capa/tasks/${taskId}/done`, { method: 'POST', body: { evidence } }),
    onSuccess: invalidate,
  })
}

/** FR-6 · 有效性验证（需电子签名：含义 VERIFIED / 管理员）。 */
export function useVerifyCapa() {
  const invalidate = useInvalidateCapa()
  return useMutation({
    mutationFn: ({ id, verificationMethod, effectiveness, comment, signature }) =>
      request(`/api/capa/${id}/verify`, {
        method: 'POST',
        body: { verificationMethod, effectiveness, comment, signature },
      }),
    onSuccess: invalidate,
  })
}

/** FR-8 · 关闭（需电子签名：含义 APPROVED / 管理员）。 */
export function useCloseCapa() {
  const invalidate = useInvalidateCapa()
  return useMutation({
    mutationFn: ({ id, comment, signature }) =>
      request(`/api/capa/${id}/close`, { method: 'POST', body: { comment, signature } }),
    onSuccess: invalidate,
  })
}

/* ---------------- 内审 ---------------- */

export function useAuditList(params = {}) {
  const qs = new URLSearchParams()
  if (params.year) qs.set('year', params.year)
  if (params.site) qs.set('site', params.site)
  const s = qs.toString()
  return useQuery({
    queryKey: ['regtech', 'audit', params],
    queryFn: () => request(`/api/internal-audit${s ? `?${s}` : ''}`),
    refetchInterval: REFRESH_MS,
  })
}

export function useAuditDetail(id) {
  return useQuery({
    queryKey: ['regtech', 'audit', 'detail', id],
    queryFn: () => request(`/api/internal-audit/${id}`),
    enabled: !!id,
  })
}

export function useAuditSummary(year) {
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

export function useCreateAudit() {
  const invalidate = useInvalidateAudit()
  return useMutation({
    mutationFn: (body) => request('/api/internal-audit', { method: 'POST', body }),
    onSuccess: invalidate,
  })
}

export function useTransitionAudit() {
  const invalidate = useInvalidateAudit()
  return useMutation({
    mutationFn: ({ id, target, comment }) =>
      request(`/api/internal-audit/${id}/transition`, { method: 'POST', body: { target, comment } }),
    onSuccess: invalidate,
  })
}

export function useAddFinding() {
  const invalidate = useInvalidateAudit()
  return useMutation({
    mutationFn: ({ id, ...body }) =>
      request(`/api/internal-audit/${id}/findings`, { method: 'POST', body }),
    onSuccess: invalidate,
  })
}

/** FR-11 · 发现项一键转 CAPA。 */
export function useFindingToCapa() {
  const invalidate = useInvalidateAudit()
  return useMutation({
    mutationFn: ({ findingId, ...body }) =>
      request(`/api/internal-audit/findings/${findingId}/to-capa`, { method: 'POST', body }),
    onSuccess: invalidate,
  })
}

/** FR-12 · 关闭内审（critical / major 未生成 CAPA 则 409）。 */
export function useCloseAudit() {
  const invalidate = useInvalidateAudit()
  return useMutation({
    mutationFn: ({ id, comment }) =>
      request(`/api/internal-audit/${id}/close`, { method: 'POST', body: { comment } }),
    onSuccess: invalidate,
  })
}

/* ---------------- 电子签名 ---------------- */

export function useSignaturePolicy() {
  return useQuery({
    queryKey: ['regtech', 'signatures', 'policy'],
    queryFn: () => request('/api/signatures/policy'),
    staleTime: 5 * 60_000,
  })
}

export function useSignatureList(params = {}) {
  const qs = new URLSearchParams()
  if (params.recordType) qs.set('recordType', params.recordType)
  if (params.recordId) qs.set('recordId', params.recordId)
  if (params.signer) qs.set('signer', params.signer)
  const s = qs.toString()
  return useQuery({
    queryKey: ['regtech', 'signatures', params],
    queryFn: () => request(`/api/signatures${s ? `?${s}` : ''}`),
    refetchInterval: REFRESH_MS,
  })
}

/** FR-17 · 当前账号签名失败/锁定状态。 */
export function useMySignatureAttempts(options = {}) {
  return useQuery({
    queryKey: ['regtech', 'signatures', 'attempts'],
    queryFn: () => request('/api/signatures/me/attempts'),
    enabled: options.enabled !== false,
    refetchInterval: options.enabled === false ? false : 30_000,
  })
}

/** FR-19 · 篡改检测。 */
export function useVerifySignature() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id) => request(`/api/signatures/${id}/verify`),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['regtech', 'signatures'] }),
  })
}

/** FR-20 · 服务端渲染的证据包（Markdown / JSON）。 */
export function useExportSignatures() {
  return useMutation({
    mutationFn: ({ recordType, recordId, format = 'md' }) => {
      const qs = new URLSearchParams()
      if (recordType) qs.set('recordType', recordType)
      if (recordId) qs.set('recordId', recordId)
      qs.set('format', format)
      return request(`/api/signatures/export?${qs.toString()}`)
    },
  })
}

/** 触发浏览器下载（Blob，与班报 / 追溯导出保持一致的交互）。 */
export function downloadText(filename, content, contentType = 'text/markdown') {
  const blob = new Blob([content], { type: `${contentType};charset=utf-8` })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}
