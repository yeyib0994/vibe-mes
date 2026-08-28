import { useEffect, useState } from 'react'

// 周期性返回当前时间戳（默认 1s 一跳），用于驱动「数据更新于 Ns 前」与降级判定。
export function useNow(intervalMs = 1000) {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), intervalMs)
    return () => clearInterval(t)
  }, [intervalMs])
  return now
}

// 依据数据生成时间判断是否已经超期（默认 30s），实现 spec FR-8 时效降级。
// 返回 { stale, ageSec }：stale 为 true 时页面须明显降级提示且不展示可能误导的陈旧值。
export function useStale(generatedAt, ttlMs = 30_000) {
  const now = useNow(1000)
  if (!generatedAt) return { stale: false, ageSec: 0 }
  const ageSec = Math.max(0, Math.round((now - new Date(generatedAt).getTime()) / 1000))
  return { stale: ageSec * 1000 > ttlMs, ageSec }
}
