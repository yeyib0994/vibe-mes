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

/** 参数是否越限（供页面高亮降级）。 */
function paramState(p: EquipParam): 'ok' | 'near' | 'ooc' {
  if (Number(p.value) > Number(p.hi) || Number(p.value) < Number(p.lo)) return 'ooc'
  const margin = (Number(p.hi) - Number(p.lo)) * 0.12
  if (Number(p.value) >= Number(p.hi) - margin || Number(p.value) <= Number(p.lo) + margin) return 'near'
  return 'ok'
}

function mockDelay<T>(value: T, ms = 150): Promise<T> {
  return new Promise((resolve) => setTimeout(() => resolve(value), ms))
}

/** 设备实时参数（含采集来源与越限状态）。 */
export type EquipParam = {
  name: string
  unit?: string
  value: number | string
  lo: number | string
  hi: number | string
  state?: 'ok' | 'near' | 'ooc'
  dataSource?: string
  [key: string]: unknown
}

/** 设备趋势通道（series 空表示无采集数据 → 页面渲染空态）。 */
export type EquipTrendChannel = {
  name: string
  unit?: string
  lo?: number
  hi?: number
  dataSource?: string
  series?: { t?: string; v?: number }[]
  [key: string]: unknown
}

/** 设备集群条目。 */
export type EquipmentItem = {
  code: string
  name?: string
  line?: string
  health?: string
  params?: EquipParam[]
  paramsDataSource?: string
  metrics?: { oee?: number; [key: string]: unknown }
  maint?: { next?: string; [key: string]: unknown }
  [key: string]: unknown
}

/** 设备集群总览响应（GET /api/equipment）。 */
export type EquipmentFleetResponse = {
  generatedAt?: string
  equipment?: EquipmentItem[]
  summary?: {
    total?: number
    running?: number
    idle?: number
    alarm?: number
    oeeAvg?: number
    maintToday?: number
    [key: string]: unknown
  }
  [key: string]: unknown
}

/** 设备关联报警行（fixture / 后端同口径，字段杂故开放扩展）。 */
export type EquipmentAlarmRow = {
  id: string | number
  level?: string
  status?: string
  content?: string
  time?: string
  value?: string | number | null
  threshold?: string | number | null
  ackBy?: string | null
  source?: string
  [key: string]: unknown
}

/** 单设备详情响应（趋势 + 关联报警）。 */
export type EquipmentDetailResponse = {
  generatedAt?: string
  code?: string
  dataSource?: string
  trend?: EquipTrendChannel[]
  alarms?: EquipmentAlarmRow[]
  [key: string]: unknown
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

function buildDetail(code: string) {
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
  return useQuery<EquipmentFleetResponse>({
    queryKey: ['equipment', 'fleet'],
    queryFn: () => (USE_MOCK ? mockDelay(buildFleet()) : request<EquipmentFleetResponse>('/api/equipment')),
    refetchInterval: REFRESH_MS,
  })
}

// 单设备实时趋势与关联报警（随选中设备变化）。
export function useEquipmentDetail(code?: string) {
  return useQuery<EquipmentDetailResponse>({
    queryKey: ['equipment', 'detail', code],
    queryFn: () =>
      USE_MOCK
        ? mockDelay(buildDetail(code ?? ''))
        : request<EquipmentDetailResponse>(`/api/equipment/${encodeURIComponent(code ?? '')}`),
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


/* ---------------- H1 设备保全响应模型 ---------------- */

/** 保养/校准预警行。 */
export type EquipmentAlertItem = {
  code: string
  /** 保养：下次保养时间 */
  nextMaintenanceAt?: string | null
  daysRemaining?: number
  cycleDays?: number
  overdue?: boolean
  /** 校准：应校准时间 */
  calibrationDueAt?: string | null
  daysOverdue?: number
  calibrationItem?: string
  [key: string]: unknown
}

/** 设备合规预警汇总（?withinDays=7）。 */
export type EquipmentAlertsResponse = {
  withinDays?: number
  maintenance?: EquipmentAlertItem[]
  calibration?: EquipmentAlertItem[]
  /** 校准超期设备数 */
  calibrationExpired?: number
  /** 保养逾期设备数 */
  maintenanceOverdue?: number
  /** 窗口内即将到期数 */
  maintenanceDue?: number
  [key: string]: unknown
}

/** 维护工单行。 */
export type MaintenanceOrder = {
  id: number
  code?: string
  type?: string
  status?: 'OPEN' | 'IN_PROGRESS' | 'DONE' | 'CANCELLED' | string
  planDate?: string | null
  createdBy?: string | null
  doneAt?: string | null
  runtimeAfter?: number | null
  remark?: string | null
  [key: string]: unknown
}

/** 设备 OEE 三因子（FR-9）。数据不足时各值为 null + dataSufficient=false，禁止兜底。 */
export type EquipmentOee = {
  equipmentCode?: string
  availability?: number | null
  performance?: number | null
  quality?: number | null
  oee?: number | null
  downtimeHours?: number | null
  eventCount?: number | null
  dataSufficient?: boolean
  missing?: string[]
  note?: string | null
  [key: string]: unknown
}

/** 完工响应：回传顺延后的保养/校准信息。 */
export type MaintenanceDoneResult = {
  equipment?: { code?: string; maint?: { next?: string } }
  [key: string]: unknown
}

// 保养到期与校准超期预警（?withinDays=7）。
export function useEquipmentAlerts(withinDays: number = 7) {
  return useQuery<EquipmentAlertsResponse>({
    queryKey: ['equipment', 'alerts', withinDays],
    queryFn: () => request<EquipmentAlertsResponse>(`/api/equipment/alerts?withinDays=${withinDays}`),
    refetchInterval: 60_000,
  })
}

// 批次建单可用设备（启用 + 校准有效，FR-10）。
export function useAvailableEquipment(line?: string) {
  return useQuery<unknown>({
    queryKey: ['equipment', 'available', line ?? 'ALL'],
    queryFn: () => request(`/api/equipment/available${line ? `?line=${encodeURIComponent(line)}` : ''}`),
    staleTime: 30_000,
  })
}

// 维护工单列表。
export function useMaintenanceOrders({ equipmentCode, status }: { equipmentCode?: string; status?: string } = {}) {
  const params = new URLSearchParams()
  if (equipmentCode) params.set('equipmentCode', equipmentCode)
  if (status) params.set('status', status)
  const qs = params.toString()
  return useQuery<MaintenanceOrder[]>({
    queryKey: ['equipment', 'orders', equipmentCode ?? 'ALL', status ?? 'ALL'],
    queryFn: () => request<MaintenanceOrder[]>(`/api/equipment/maintenance-orders${qs ? `?${qs}` : ''}`),
    refetchInterval: 60_000,
  })
}

// 设备 OEE 三因子（可用率取自状态事件停机时长，FR-9）。
export function useEquipmentOee(code?: string) {
  return useQuery<EquipmentOee>({
    queryKey: ['equipment', 'oee', code],
    queryFn: () => request<EquipmentOee>(`/api/equipment/${encodeURIComponent(code ?? '')}/oee`),
    enabled: !!code,
  })
}

const invalidateEquipment = (qc: ReturnType<typeof useQueryClient>) => {
  qc.invalidateQueries({ queryKey: ['equipment'] })
}

// 设备状态变更（工艺员及以上，写事件流水与审计）。
export function useChangeEquipmentStatus() {
  const qc = useQueryClient()
  return useMutation<unknown, Error, { code: string; toStatus: string; reason?: string }>({
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
  return useMutation<unknown, Error, Record<string, unknown>>({
    mutationFn: (body) => request('/api/equipment/maintenance-orders', { method: 'POST', body }),
    onSuccess: () => invalidateEquipment(qc),
  })
}

// 完成维护工单：顺延保养周期，CALIBRATION 类同时续期校准。
export function useCompleteMaintenanceOrder() {
  const qc = useQueryClient()
  return useMutation<MaintenanceDoneResult, Error, { id: string | number; runtimeHours?: number; remark?: string }>({
    mutationFn: ({ id, runtimeHours, remark }) =>
      request<MaintenanceDoneResult>(`/api/equipment/maintenance-orders/${encodeURIComponent(String(id))}/done`, {
        method: 'POST',
        body: { runtimeHours, remark },
      }),
    onSuccess: () => invalidateEquipment(qc),
  })
}
