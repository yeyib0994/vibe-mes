import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { request } from './http'
import { alarmData, equipmentSpec } from '../data/mes'

const REFRESH_MS = 30_000
// Phase 1：默认走后端 /api/equipment；置 VITE_USE_MOCK=true 回退本地 Mock。
const USE_MOCK = import.meta.env.VITE_USE_MOCK === 'true'

// 注意：这里**不再生成伪趋势序列**。
// 趋势的形状（seed/amp/末值收敛）此前在前端与后端各有一份，属于重复实现——
// 接真采集时前端那份无法剥离，且两条曲线的形状会不一致。
// 现在伪序列唯一来源是后端适配器（integration.mock.MockEquipmentMetricAdapter.backfill，
// 采样带 dataSource=MOCK），前端只负责画。VITE_USE_MOCK 下无后端可取，故 trend 为空。

// 参数是否越限（供页面高亮降级）。
function paramState(p) {
  if (p.value > p.hi || p.value < p.lo) return 'ooc'
  const margin = (p.hi - p.lo) * 0.12
  if (p.value >= p.hi - margin || p.value <= p.lo + margin) return 'near'
  return 'ok'
}

function mockDelay(value, ms = 150) {
  return new Promise((resolve) => setTimeout(() => resolve(value), ms))
}

function buildFleet() {
  const running = equipmentSpec.filter((e) => e.metrics.oee > 0)
  const oeeAvg =
    running.length > 0
      ? Math.round((running.reduce((s, e) => s + e.metrics.oee, 0) / running.length) * 10) / 10
      : 0
  const alarm = equipmentSpec.filter((e) => e.health === 'fault').length
  const maintToday = equipmentSpec.filter((e) => e.maint.next <= '2026-08-29').length
  return {
    generatedAt: new Date().toISOString(),
    equipment: equipmentSpec.map((e) => ({
      ...e,
      // 本地 mock 无采集链路，显式标注来源，避免被误当成真实读数（constitution P4）
      paramsDataSource: 'MOCK',
      params: e.params.map((p) => ({ ...p, state: paramState(p), dataSource: 'MOCK' })),
    })),
    summary: {
      total: equipmentSpec.length,
      running: running.length,
      idle: equipmentSpec.filter((e) => e.health === 'idle').length,
      alarm,
      oeeAvg,
      maintToday,
    },
  }
}

function buildDetail(code) {
  const spec = equipmentSpec.find((e) => e.code === code) || equipmentSpec[0]
  return {
    generatedAt: new Date().toISOString(),
    code: spec.code,
    dataSource: 'MOCK',
    // series 为空：本地 mock 不造曲线，由页面渲染「无趋势数据」空态
    trend: spec.params.map((p) => ({
      name: p.name,
      unit: p.unit,
      lo: p.lo,
      hi: p.hi,
      dataSource: 'MOCK',
      series: [],
    })),
    alarms: alarmData.filter((a) => a.source.startsWith(`${spec.code} `)),
  }
}

// 设备集群总览 + KPI（T：设备监控页经此服务层取数）。
export function useEquipmentFleet() {
  return useQuery({
    queryKey: ['equipment', 'fleet'],
    queryFn: () => (USE_MOCK ? mockDelay(buildFleet()) : request('/api/equipment')),
    refetchInterval: REFRESH_MS,
  })
}

// 单设备实时趋势与关联报警（随选中设备变化）。
export function useEquipmentDetail(code) {
  return useQuery({
    queryKey: ['equipment', 'detail', code],
    queryFn: () =>
      USE_MOCK ? mockDelay(buildDetail(code)) : request(`/api/equipment/${encodeURIComponent(code)}`),
    refetchInterval: REFRESH_MS,
    enabled: !!code,
  })
}

/* ------------------------- H1 · 设备保全（PG 持久化） ------------------------- */

export const EQUIPMENT_STATUSES = [
  { code: 'RUNNING', label: '运行中' },
  { code: 'IDLE', label: '待机' },
  { code: 'CLEANING', label: '清洗中' },
  { code: 'MAINTENANCE', label: '维护中' },
  { code: 'ALARM', label: '故障' },
  { code: 'STOPPED', label: '停机' },
]

export const ORDER_TYPES = [
  { code: 'PREVENTIVE', label: '预防性保养' },
  { code: 'CORRECTIVE', label: '故障维修' },
  { code: 'CALIBRATION', label: '计量校准' },
]

// 保养到期与校准超期预警（?withinDays=7）。
export function useEquipmentAlerts(withinDays = 7) {
  return useQuery({
    queryKey: ['equipment', 'alerts', withinDays],
    queryFn: () => request(`/api/equipment/alerts?withinDays=${withinDays}`),
    refetchInterval: 60_000,
  })
}

// 批次建单可用设备（启用 + 校准有效，FR-10）。
export function useAvailableEquipment(line) {
  return useQuery({
    queryKey: ['equipment', 'available', line ?? 'ALL'],
    queryFn: () => request(`/api/equipment/available${line ? `?line=${encodeURIComponent(line)}` : ''}`),
    staleTime: 30_000,
  })
}

// 维护工单列表。
export function useMaintenanceOrders({ equipmentCode, status } = {}) {
  const params = new URLSearchParams()
  if (equipmentCode) params.set('equipmentCode', equipmentCode)
  if (status) params.set('status', status)
  const qs = params.toString()
  return useQuery({
    queryKey: ['equipment', 'orders', equipmentCode ?? 'ALL', status ?? 'ALL'],
    queryFn: () => request(`/api/equipment/maintenance-orders${qs ? `?${qs}` : ''}`),
    refetchInterval: 60_000,
  })
}

// 设备 OEE 三因子（可用率取自状态事件停机时长，FR-9）。
export function useEquipmentOee(code) {
  return useQuery({
    queryKey: ['equipment', 'oee', code],
    queryFn: () => request(`/api/equipment/${encodeURIComponent(code)}/oee`),
    enabled: !!code,
  })
}

const invalidateEquipment = (qc) => {
  qc.invalidateQueries({ queryKey: ['equipment'] })
}

// 设备状态变更（工艺员及以上，写事件流水与审计）。
export function useChangeEquipmentStatus() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ code, toStatus, reason }) =>
      request(`/api/equipment/${encodeURIComponent(code)}/status`, {
        method: 'POST',
        body: { toStatus, reason },
      }),
    onSuccess: () => invalidateEquipment(qc),
  })
}

// 新建维护工单（值班长及以上）。
export function useCreateMaintenanceOrder() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body) => request('/api/equipment/maintenance-orders', { method: 'POST', body }),
    onSuccess: () => invalidateEquipment(qc),
  })
}

// 完成维护工单：顺延保养周期，CALIBRATION 类同时续期校准。
export function useCompleteMaintenanceOrder() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, runtimeHours, remark }) =>
      request(`/api/equipment/maintenance-orders/${encodeURIComponent(id)}/done`, {
        method: 'POST',
        body: { runtimeHours, remark },
      }),
    onSuccess: () => invalidateEquipment(qc),
  })
}
