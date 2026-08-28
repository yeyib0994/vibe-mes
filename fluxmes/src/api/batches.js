import { useQuery } from '@tanstack/react-query'
import { batches } from '../data/mes'

const REFRESH_MS = 30_000

function buildBatches() {
  return {
    generatedAt: new Date().toISOString(),
    batches,
  }
}

function delay(value, ms = 150) {
  return new Promise((resolve) => setTimeout(() => resolve(value), ms))
}

// 批次台账数据（T1：批次列表经此服务层取数，支持后续服务端分页/过滤）。
export function useBatches() {
  return useQuery({
    queryKey: ['batches'],
    queryFn: () => delay(buildBatches()),
    refetchInterval: REFRESH_MS,
  })
}
