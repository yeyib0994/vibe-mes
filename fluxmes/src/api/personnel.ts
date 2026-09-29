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

export function useCertificates(params = {}) {
  const qs = new URLSearchParams()
  if (params.username) qs.set('username', params.username)
  if (params.certType) qs.set('certType', params.certType)
  const s = qs.toString()
  return useQuery({
    queryKey: ['personnel', 'certificates', params],
    queryFn: () => request(`/api/personnel/certificates${s ? `?${s}` : ''}`),
    refetchInterval: REFRESH_MS,
  })
}

export function useCapabilities() {
  return useQuery({
    queryKey: ['personnel', 'capabilities'],
    queryFn: () => request('/api/personnel/capabilities'),
    refetchInterval: REFRESH_MS,
  })
}

export function usePersonnelAlerts(days = 30) {
  return useQuery({
    queryKey: ['personnel', 'alerts', days],
    queryFn: () => request(`/api/personnel/alerts?days=${days}`),
    refetchInterval: REFRESH_MS,
  })
}

export function usePersonnelSummary() {
  return useQuery({
    queryKey: ['personnel', 'summary'],
    queryFn: () => request('/api/personnel/summary'),
    refetchInterval: REFRESH_MS,
  })
}

export function useIssueCertificate() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body) => request('/api/personnel/certificates', { method: 'POST', body }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['personnel'] }),
  })
}

export function useRevokeCertificate() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, reason }) =>
      request(`/api/personnel/certificates/${id}/revoke`, { method: 'POST', body: { reason } }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['personnel'] }),
  })
}
