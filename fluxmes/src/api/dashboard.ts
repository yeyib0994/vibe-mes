import { useQuery } from '@tanstack/react-query'
import { request } from './http'
import {
  PLAN_RATE,
  equipment,
  productionTrend,
  recentAlarms,
  runningBatches,
} from '../data/mes'

const REFRESH_MS = 30_000
// Phase 1：默认走后端 /api/dashboard/cockpit；置 VITE_USE_MOCK=true 回退本地 Mock。
const USE_MOCK = import.meta.env.VITE_USE_MOCK === 'true'

/**
 * 驾驶舱聚合响应（后端 DashboardController /cockpit）。
 * 页面普遍用可选链读取，因此各 KPI 允许缺省；数值型 KPI 允许 null 表示「数据不足」，
 * 前端必须展示「—」而非兜底值（Phase I OEE 口径，禁止伪造）。
 */
export type CockpitKpi = { value?: number | null; [key: string]: unknown }

/** 罐区/关键设备卡片行（驾驶舱「罐区与设备状态」列表）。 */
export type CockpitEquipmentRow = {
  code: string
  name?: string
  status?: string
  statusText?: string
  params?: string
  [key: string]: unknown
}

/** 在制批次卡片行（驾驶舱「在制批次」列表）。 */
export type CockpitRunningBatch = {
  id: string
  product?: string
  stage?: string
  equipment?: string
  progress?: number
  tone?: 'primary' | 'accent' | 'warn' | 'danger'
  eta?: string
  [key: string]: unknown
}

/** 驾驶舱「最新报警」行。 */
export type CockpitAlarmRow = {
  id: string
  level?: string
  status?: string
  content?: string
  time?: string
  source?: string
  value?: string | number
  threshold?: string | number
  [key: string]: unknown
}

/** 生产趋势点（t 为时刻标签，out 为产出速率 t/h）。 */
export type CockpitTrendPoint = { t?: string; out?: number; [key: string]: unknown }

export type CockpitData = {
  siteId?: string
  generatedAt?: string
  kpis?: {
    dailyOutput?: { actual?: number | null; plan?: number | null; progressPct?: number | null; vsSchedulePct?: number | null; dataSufficient?: boolean; [key: string]: unknown }
    batchPassRate?: { value?: number | null; target?: number | null; [key: string]: unknown }
    oee?: { value?: number | null; availability?: number | null; performance?: number | null; quality?: number | null; dataSufficient?: boolean; [key: string]: unknown }
    activeAlarms?: { total?: number; critical?: number; major?: number; minor?: number; [key: string]: unknown }
    [key: string]: unknown
  }
  planRate?: unknown
  productionTrend?: CockpitTrendPoint[]
  equipment?: CockpitEquipmentRow[]
  runningBatches?: CockpitRunningBatch[]
  recentAlarms?: CockpitAlarmRow[]
  [key: string]: unknown
}

// 本地 Mock 聚合（Phase 0 口径，仅回退模式使用）。
function buildCockpit(): CockpitData {
  const dailyActual = 33.4
  const dailyPlan = 48
  return {
    siteId: 'QH-1',
    generatedAt: new Date().toISOString(),
    kpis: {
      dailyOutput: {
        actual: dailyActual,
        plan: dailyPlan,
        progressPct: Math.round((dailyActual / dailyPlan) * 1000) / 10,
        vsSchedulePct: 4.5,
      },
      batchPassRate: { value: 99.2, target: 98.5 },
      oee: {
        value: 87.7,
        availability: 92.1,
        performance: 96.5,
        quality: 98.7,
      },
      activeAlarms: { total: 3, critical: 1, major: 1, minor: 1 },
    },
    planRate: PLAN_RATE,
    productionTrend: productionTrend as CockpitTrendPoint[],
    equipment: equipment as CockpitEquipmentRow[],
    runningBatches: runningBatches as CockpitRunningBatch[],
    recentAlarms: recentAlarms as CockpitAlarmRow[],
  }
}

function mockDelay<T>(value: T, ms = 150): Promise<T> {
  return new Promise((resolve) => setTimeout(() => resolve(value), ms))
}

// 生产驾驶舱聚合数据（T1：页面经此服务层取数）。
// D2：line 为产线代码（LINE-1/2/3），为空表示全部产线汇总。
// G4：site 为厂区代码（SITE-01/02），为空表示集团全厂区汇总。
export function useCockpit(site?: string, line?: string) {
  const params = new URLSearchParams()
  if (site) params.set('site', site)
  if (line) params.set('line', line)
  const qs = params.toString()
  return useQuery<CockpitData>({
    queryKey: ['cockpit', site ?? 'ALL', line ?? 'ALL'],
    queryFn: (): Promise<CockpitData> =>
      USE_MOCK
        ? mockDelay(buildCockpit())
        : request<CockpitData>(`/api/dashboard/cockpit${qs ? `?${qs}` : ''}`),
    refetchInterval: REFRESH_MS,
  })
}

/* ------------------------- G4 · 厂区主数据 ------------------------- */

export type SiteItem = {
  code: string
  name?: string
  address?: string
  lineCount?: number
  batchCount?: number
}

export type SiteListResponse = { generatedAt?: string; sites?: SiteItem[] }

export function useSites() {
  return useQuery<SiteListResponse>({
    queryKey: ['sites'],
    queryFn: () => request<SiteListResponse>('/api/dashboard/sites'),
    staleTime: 5 * 60_000,
  })
}

/* ------------------------- D2 · 产线主数据 ------------------------- */

export type LineItem = {
  code: string
  name?: string
  workshop?: string
  capacityT?: number
  batchCount?: number
  activeBatches?: number
}

export type LineListResponse = { generatedAt?: string; lines?: LineItem[] }

export function useLines(site?: string) {
  return useQuery<LineListResponse>({
    queryKey: ['production-lines', site ?? 'ALL'],
    queryFn: () =>
      USE_MOCK
        ? mockDelay({
            generatedAt: new Date().toISOString(),
            lines: [
              { code: 'LINE-1', name: '柠檬酸发酵一线', workshop: '一车间', capacityT: 48, batchCount: 6, activeBatches: 3 },
              { code: 'LINE-2', name: '柠檬酸发酵二线', workshop: '一车间', capacityT: 36, batchCount: 4, activeBatches: 2 },
              { code: 'LINE-3', name: '精制包装线', workshop: '二车间', capacityT: 24, batchCount: 3, activeBatches: 1 },
            ],
          })
        : request<LineListResponse>(`/api/dashboard/lines${site ? `?site=${encodeURIComponent(site)}` : ''}`),
    staleTime: 5 * 60_000,
  })
}

/* ------------------------- D3 · 班报 ------------------------- */

export type ShiftReportParams = { date?: string; shift?: 'DAY' | 'NIGHT'; line?: string; site?: string }

export function useShiftReport({ date, shift = 'DAY', line, site }: ShiftReportParams, enabled = false) {
  const params = new URLSearchParams()
  if (date) params.set('date', date)
  params.set('shift', shift)
  if (line) params.set('line', line)
  if (site) params.set('site', site)
  return useQuery({
    queryKey: ['shift-report', date ?? '', shift, line ?? 'ALL'],
    queryFn: () => request(`/api/dashboard/shift-report?${params.toString()}`),
    enabled: !USE_MOCK && enabled,
    staleTime: 60_000,
  })
}

/** 拉取班报并以 Markdown 文件下载（前端 Blob 导出，无需后端文件落盘）。 */
export async function downloadShiftReport({ date, shift = 'DAY', line, site }: ShiftReportParams) {
  const params = new URLSearchParams()
  if (date) params.set('date', date)
  params.set('shift', shift)
  if (line) params.set('line', line)
  if (site) params.set('site', site)
  const report = await request<{ markdown?: string; date?: string } | null>(
    `/api/dashboard/shift-report?${params.toString()}`,
  )
  const md = report?.markdown ?? JSON.stringify(report, null, 2)
  const name = `班报_${report?.date ?? date ?? ''}_${shift}${site ? '_' + site : ''}${line ? '_' + line : ''}.md`
  const blob = new Blob([md], { type: 'text/markdown;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = name
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
  return report
}
