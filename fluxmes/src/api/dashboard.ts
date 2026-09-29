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

// 本地 Mock 聚合（Phase 0 口径，仅回退模式使用）。
function buildCockpit() {
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
    productionTrend,
    equipment,
    runningBatches,
    recentAlarms,
  }
}

function mockDelay(value, ms = 150) {
  return new Promise((resolve) => setTimeout(() => resolve(value), ms))
}

// 生产驾驶舱聚合数据（T1：页面经此服务层取数）。
// D2：line 为产线代码（LINE-1/2/3），为空表示全部产线汇总。
// G4：site 为厂区代码（SITE-01/02），为空表示集团全厂区汇总。
export function useCockpit(site, line) {
  const params = new URLSearchParams()
  if (site) params.set('site', site)
  if (line) params.set('line', line)
  const qs = params.toString()
  return useQuery({
    queryKey: ['cockpit', site ?? 'ALL', line ?? 'ALL'],
    queryFn: () =>
      USE_MOCK ? mockDelay(buildCockpit()) : request(`/api/dashboard/cockpit${qs ? `?${qs}` : ''}`),
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

export function useSites() {
  return useQuery({
    queryKey: ['sites'],
    queryFn: () => request('/api/dashboard/sites'),
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

export function useLines(site) {
  return useQuery({
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
        : request(`/api/dashboard/lines${site ? `?site=${encodeURIComponent(site)}` : ''}`),
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
  const report = await request(`/api/dashboard/shift-report?${params.toString()}`)
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
