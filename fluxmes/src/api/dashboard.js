import { useQuery } from '@tanstack/react-query'
import {
  PLAN_RATE,
  equipment,
  productionTrend,
  recentAlarms,
  runningBatches,
} from '../data/mes'

const REFRESH_MS = 30_000

// 模拟聚合服务：Phase 0 由前端 Mock 直接计算 KPI，Phase 1 起改由后端 /aggregations 提供（constitution P2/plan.md）。
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

function delay(value, ms = 150) {
  return new Promise((resolve) => setTimeout(() => resolve(value), ms))
}

// 生产驾驶舱聚合数据（T1：页面经此服务层取数，与 mes.js 解耦）。
export function useCockpit(siteId = 'QH-1') {
  return useQuery({
    queryKey: ['cockpit', siteId],
    queryFn: () => delay(buildCockpit()),
    refetchInterval: REFRESH_MS,
  })
}
