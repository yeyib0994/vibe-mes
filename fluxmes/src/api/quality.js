import { useQuery } from '@tanstack/react-query'
import { pareto, qcTasks, spcData } from '../data/mes'

const REFRESH_MS = 30_000

function buildQuality() {
  return {
    generatedAt: new Date().toISOString(),
    spcData,
    pareto,
    qcTasks,
  }
}

function delay(value, ms = 150) {
  return new Promise((resolve) => setTimeout(() => resolve(value), ms))
}

// 质量管理数据（T1：SPC/帕累托/检验任务经此服务层取数）。
// 注：SPC_UCL/CL/LCL 与 qcStatusMap 为显示常量/UI 映射，仍由页面从 mes.js 直接导入。
export function useQuality() {
  return useQuery({
    queryKey: ['quality'],
    queryFn: () => delay(buildQuality()),
    refetchInterval: REFRESH_MS,
  })
}
