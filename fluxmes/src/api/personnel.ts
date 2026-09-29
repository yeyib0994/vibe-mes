import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { request } from './http'

/**
 * G2 · 人员资质与健康证服务层。
 * 与后端 api-java/com/fluxmes/api/personnel/PersonnelController 一一对应。
 *
 * 食品行业法定门槛：从业人员须持年度健康证上岗；CCP 监控、配料称量、成品放行、
 * 批记录复核等关键动作由后端 RequireCapability 门禁校验资质，缺失直接 403。
 */

const REFRESH_MS = 120_000

export const CERT_TYPE_TEXT = { HEALTH: '健康证', QUALIFICATION: '岗位资质' }
export const STATUS_TONE = { VALID: 'success', EXPIRED: 'danger', REVOKED: 'muted' }
export const STATUS_TEXT = { VALID: '有效', EXPIRED: '已过期', REVOKED: '已吊销' }

/** 能力项中文名（与后端 capability_dict 对齐）。 */
export const CAPABILITY_TEXT = {
  WEIGHING: '配料称量',
  CCP_MONITOR: 'CCP 监控',
  RELEASE: '成品放行',
  BATCH_REVIEW: '批记录复核',
  SANITATION: '清场作业',
  LAB_TEST: '理化检验',
}

/** 能力项字典项。 */
export type CapabilityItem = {
  code: string
  name?: string
  description?: string
  enforced?: boolean
  holders?: number
  requiredRole?: string
  [key: string]: unknown
}

/** 人员证书行。 */
export type CertificateItem = {
  id: number | string
  username?: string
  displayName?: string | null
  certType?: string
  certName?: string
  capability?: string | null
  certNo?: string | null
  issuedBy?: string | null
  validUntil?: string | null
  status?: string
  /** 到期预警：已过期天数 */
  overdueDays?: number
  /** 到期预警：剩余天数 */
  daysLeft?: number
  [key: string]: unknown
}

/** 到期预警响应（GET /api/personnel/alerts）。 */
export type PersonnelAlertsResponse = {
  days?: number
  expiredCount?: number
  expiringCount?: number
  expired?: CertificateItem[]
  expiring?: CertificateItem[]
  [key: string]: unknown
}

/** 人员资质汇总（GET /api/personnel/summary）。 */
export type PersonnelSummaryResponse = {
  totalCertificates?: number
  healthCertificates?: number
  qualifications?: number
  healthCertEnforced?: boolean
  [key: string]: unknown
}

export function useCertificates(params: { username?: string; certType?: string } = {}) {
  const qs = new URLSearchParams()
  if (params.username) qs.set('username', params.username)
  if (params.certType) qs.set('certType', params.certType)
  const s = qs.toString()
  return useQuery<CertificateItem[]>({
    queryKey: ['personnel', 'certificates', params],
    queryFn: () => request<CertificateItem[]>(`/api/personnel/certificates${s ? `?${s}` : ''}`),
    refetchInterval: REFRESH_MS,
  })
}

export function useCapabilities() {
  return useQuery<CapabilityItem[]>({
    queryKey: ['personnel', 'capabilities'],
    queryFn: () => request<CapabilityItem[]>('/api/personnel/capabilities'),
    refetchInterval: REFRESH_MS,
  })
}

export function usePersonnelAlerts(days = 30) {
  return useQuery<PersonnelAlertsResponse>({
    queryKey: ['personnel', 'alerts', days],
    queryFn: () => request<PersonnelAlertsResponse>(`/api/personnel/alerts?days=${days}`),
    refetchInterval: REFRESH_MS,
  })
}

export function usePersonnelSummary() {
  return useQuery<PersonnelSummaryResponse>({
    queryKey: ['personnel', 'summary'],
    queryFn: () => request<PersonnelSummaryResponse>('/api/personnel/summary'),
    refetchInterval: REFRESH_MS,
  })
}

export function useIssueCertificate() {
  const qc = useQueryClient()
  return useMutation<unknown, Error, Record<string, unknown>>({
    mutationFn: (body) => request('/api/personnel/certificates', { method: 'POST', body }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['personnel'] }),
  })
}

export function useRevokeCertificate() {
  const qc = useQueryClient()
  return useMutation<unknown, Error, { id: string | number; reason?: string }>({
    mutationFn: ({ id, reason }) =>
      request(`/api/personnel/certificates/${id}/revoke`, { method: 'POST', body: { reason } }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['personnel'] }),
  })
}
