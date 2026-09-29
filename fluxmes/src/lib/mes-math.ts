/**
 * MES 纯计算函数集合（可单元测试、无副作用、不依赖 React / 网络）。
 *
 * 这些口径同时存在于后端（Java）与前端展示层，抽到此处保证两侧一致并可被测试锁定：
 * - 帕累托累计百分比
 * - SPC Western Electric 判异规则 R1 / R2 / R3（与 QualityService.detectRules 同口径）
 * - 报警级别排序权重（中国区习惯：严重 red 优先）
 * - 证书到期判定（G2 人员资质门禁）
 */

export type ParetoItem = { name: string; n: number }

/**
 * 帕累托累计：按 n 降序（不改变传入数组），输出累计占比（保留 1 位小数）。
 * 空数组或总量为 0 时返回空数组，避免除零。
 */
export function paretoCumulative<T extends ParetoItem>(items: T[]): (T & { cum: number })[] {
  const sorted = [...items].sort((a, b) => b.n - a.n)
  const total = sorted.reduce((s, d) => s + d.n, 0)
  if (!sorted.length || total <= 0) return []
  let cum = 0
  return sorted.map((d) => {
    cum += d.n
    return { ...d, cum: Math.round((cum / total) * 1000) / 10 }
  })
}

/** 累计占比达到阈值（如 80%）所需的首个下标；-1 表示不可达。 */
export function paretoCutIndex(items: ParetoItem[], threshold = 80): number {
  const withCum = paretoCumulative(items)
  const idx = withCum.findIndex((d) => d.cum >= threshold)
  return idx
}

export type SpcHit = { index: number; rule: 'R1' | 'R2' | 'R3'; desc: string }

/**
 * Western Electric 判异（与后端 QualityService.detectRules 完全同口径）：
 * R1 单点超出 UCL/LCL；R2 连续 9 点位于 CL 同侧；R3 连续 6 点单调上升/下降。
 */
export function detectSpcRules(v: number[], ucl: number, cl: number, lcl: number): SpcHit[] {
  const hits: SpcHit[] = []
  const n = v.length
  for (let i = 0; i < n; i++) {
    const x = v[i]
    if (x > ucl || x < lcl) {
      hits.push({ index: i, rule: 'R1', desc: `1 点超出控制限（${x} ∉ [${lcl}, ${ucl}]）` })
    }
  }
  for (let i = 8; i < n; i++) {
    let allAbove = true
    let allBelow = true
    for (let j = i - 8; j <= i; j++) {
      if (v[j] <= cl) allAbove = false
      if (v[j] >= cl) allBelow = false
    }
    if (allAbove || allBelow) {
      hits.push({ index: i, rule: 'R2', desc: `连续 9 点位于中心线${allAbove ? '上方' : '下方'}` })
    }
  }
  for (let i = 5; i < n; i++) {
    let up = true
    let down = true
    for (let j = i - 5; j < i; j++) {
      if (!(v[j] < v[j + 1])) up = false
      if (!(v[j] > v[j + 1])) down = false
    }
    if (up || down) {
      hits.push({ index: i, rule: 'R3', desc: `连续 6 点持续${up ? '上升' : '下降'}` })
    }
  }
  return hits
}

/** 报警级别排序权重：critical > major > minor；未知级别排最后。 */
export function alarmLevelWeight(level?: string | null): number {
  switch (level) {
    case 'critical':
      return 3
    case 'major':
      return 2
    case 'minor':
      return 1
    default:
      return 0
  }
}

/** 按级别降序、再按时间降序排序报警（不改动原数组）。 */
export function sortAlarms<T extends { level?: string | null; createdAt?: string | null }>(list: T[]): T[] {
  return [...list].sort((a, b) => {
    const d = alarmLevelWeight(b.level) - alarmLevelWeight(a.level)
    if (d !== 0) return d
    return String(b.createdAt ?? '').localeCompare(String(a.createdAt ?? ''))
  })
}

export type CertLike = { validUntil?: string | null; status?: string | null }

/** 距到期天数：无有效期返回 null（终身有效）。 */
export function certDaysLeft(cert: CertLike, today: Date = new Date()): number | null {
  if (!cert?.validUntil) return null
  const target = new Date(`${cert.validUntil}T00:00:00`)
  if (Number.isNaN(target.getTime())) return null
  const ms = target.getTime() - new Date(today.toDateString()).getTime()
  return Math.round(ms / 86_400_000)
}

/**
 * 证书是否有效：已吊销一律无效；过期（validUntil < 今天）无效；无有效期则只要未吊销即有效。
 * 用于 G2 人员资质门禁的前端即时提示（后端仍会二次校验）。
 */
export function certValid(cert: CertLike, today: Date = new Date()): boolean {
  if (!cert) return false
  if (cert.status === 'REVOKED') return false
  if (cert.status === 'EXPIRED') return false
  const left = certDaysLeft(cert, today)
  return left === null || left >= 0
}

/**
 * G3 · 称量容差判定：偏差百分比 =（实际 − 目标）/ 目标 × 100，保留 3 位。
 * 目标量 ≤ 0 时返回 null（非法输入）。
 */
export function weighingDeviationPct(actual: number, target: number): number | null {
  if (!Number.isFinite(actual) || !Number.isFinite(target) || target === 0) return null
  return Math.round(((actual - target) / target) * 100 * 1000) / 1000
}

/** 称量结果：|偏差| ≤ 容差 → PASS；否则 OVER（超出）/ UNDER（不足）。 */
export function weighingResult(
  actual: number,
  target: number,
  tolerancePct: number,
): 'PASS' | 'OVER' | 'UNDER' | null {
  const dev = weighingDeviationPct(actual, target)
  if (dev === null) return null
  if (Math.abs(dev) <= tolerancePct) return 'PASS'
  return dev > 0 ? 'OVER' : 'UNDER'
}
