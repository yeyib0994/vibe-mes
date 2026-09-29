import { describe, expect, it } from 'vitest'
import {
  alarmLevelWeight,
  certDaysLeft,
  certValid,
  detectSpcRules,
  paretoCumulative,
  paretoCutIndex,
  sortAlarms,
  weighingDeviationPct,
  weighingResult,
} from './mes-math'

/* 与后端 mes.json 一致的帕累托基线 */
const PARETO_RAW = [
  { name: '色度偏差', n: 14 },
  { name: '水分超标', n: 9 },
  { name: 'pH 超标', n: 7 },
  { name: '粒度超标', n: 4 },
  { name: '异物', n: 2 },
  { name: '可见杂质', n: 1 },
]

describe('帕累托累计', () => {
  it('按数量降序且累计占比末项为 100', () => {
    const rows = paretoCumulative(PARETO_RAW)
    expect(rows.map((r) => r.n)).toEqual([14, 9, 7, 4, 2, 1])
    expect(rows.at(-1)?.cum).toBe(100)
  })

  it('累计百分比与后端口径一致（保留 1 位）', () => {
    const rows = paretoCumulative(PARETO_RAW)
    expect(rows.map((r) => r.cum)).toEqual([37.8, 62.2, 81.1, 91.9, 97.3, 100])
  })

  it('80% 截断落在第 3 项（关键少数）', () => {
    expect(paretoCutIndex(PARETO_RAW, 80)).toBe(2)
  })

  it('空数组与全 0 不产生除零', () => {
    expect(paretoCumulative([])).toEqual([])
    expect(paretoCumulative([{ name: 'a', n: 0 }])).toEqual([])
  })

  it('不修改传入数组', () => {
    const src = [{ name: 'b', n: 1 }, { name: 'a', n: 5 }]
    paretoCumulative(src)
    expect(src[0].name).toBe('b')
  })
})

describe('SPC 判异（Western Electric）', () => {
  const UCL = 99.82
  const CL = 99.5
  const LCL = 99.18
  const steady = [99.4, 99.6, 99.5, 99.55, 99.45, 99.5, 99.52, 99.48]

  it('稳定序列无判异', () => {
    expect(detectSpcRules(steady, UCL, CL, LCL)).toEqual([])
  })

  it('R1：单点超出控制限', () => {
    const hits = detectSpcRules([...steady, 99.95], UCL, CL, LCL)
    expect(hits.some((h) => h.rule === 'R1' && h.index === 8)).toBe(true)
  })

  it('R1：低于下控限同样命中', () => {
    const hits = detectSpcRules([...steady, 99.0], UCL, CL, LCL)
    expect(hits.filter((h) => h.rule === 'R1')).toHaveLength(1)
  })

  it('R2：连续 9 点位于中心线同侧', () => {
    const above = Array.from({ length: 9 }, (_, i) => CL + 0.05 + i * 0.001)
    const hits = detectSpcRules(above, UCL, CL, LCL)
    expect(hits.some((h) => h.rule === 'R2' && h.index === 8)).toBe(true)
  })

  it('R3：连续 6 点单调上升', () => {
    const trend = [99.2, 99.3, 99.4, 99.5, 99.6, 99.7]
    const hits = detectSpcRules(trend, UCL, CL, LCL)
    expect(hits.some((h) => h.rule === 'R3' && h.index === 5)).toBe(true)
  })

  it('R3：连续 6 点单调下降', () => {
    const trend = [99.8, 99.7, 99.6, 99.5, 99.4, 99.3]
    expect(detectSpcRules(trend, UCL, CL, LCL).some((h) => h.rule === 'R3')).toBe(true)
  })
})

describe('报警分级与排序', () => {
  it('级别权重 critical > major > minor > 未知', () => {
    expect(alarmLevelWeight('critical')).toBeGreaterThan(alarmLevelWeight('major'))
    expect(alarmLevelWeight('major')).toBeGreaterThan(alarmLevelWeight('minor'))
    expect(alarmLevelWeight(null)).toBe(0)
  })

  it('同级别内按时间倒序，且不改动原数组', () => {
    const list = [
      { level: 'minor', createdAt: '2026-09-14T08:00:00Z' },
      { level: 'critical', createdAt: '2026-09-14T09:00:00Z' },
      { level: 'major', createdAt: '2026-09-14T10:00:00Z' },
      { level: 'major', createdAt: '2026-09-14T11:00:00Z' },
    ]
    const sorted = sortAlarms(list)
    expect(sorted.map((a) => a.level)).toEqual(['critical', 'major', 'major', 'minor'])
    expect(sorted[1].createdAt).toBe('2026-09-14T11:00:00Z')
    expect(list[0].level).toBe('minor')
  })
})

describe('人员资质到期判定（G2）', () => {
  const today = new Date('2026-09-14T00:00:00')

  it('未过期证书剩余天数正确', () => {
    expect(certDaysLeft({ validUntil: '2026-09-24' }, today)).toBe(10)
    expect(certDaysLeft({ validUntil: '2026-09-14' }, today)).toBe(0)
  })

  it('已过期证书剩余天数为负', () => {
    expect(certDaysLeft({ validUntil: '2026-08-05' }, today)).toBe(-40)
  })

  it('无有效期视为长期有效', () => {
    expect(certDaysLeft({ validUntil: null }, today)).toBeNull()
  })

  it('有效 / 过期 / 吊销判定', () => {
    expect(certValid({ status: 'VALID', validUntil: '2026-12-31' }, today)).toBe(true)
    expect(certValid({ status: 'VALID', validUntil: '2026-08-05' }, today)).toBe(false)
    expect(certValid({ status: 'EXPIRED', validUntil: '2026-12-31' }, today)).toBe(false)
    expect(certValid({ status: 'REVOKED', validUntil: '2026-12-31' }, today)).toBe(false)
  })
})

describe('称量容差判定（G3）', () => {
  it('偏差百分比计算保留 3 位', () => {
    expect(weighingDeviationPct(501.5, 500)).toBe(0.3)
    expect(weighingDeviationPct(530, 500)).toBe(6)
    expect(weighingDeviationPct(495, 500)).toBe(-1)
  })

  it('目标量为 0 时返回 null', () => {
    expect(weighingDeviationPct(100, 0)).toBeNull()
  })

  it('容差内判 PASS（含边界）', () => {
    expect(weighingResult(500, 500, 1)).toBe('PASS')
    expect(weighingResult(505, 500, 1)).toBe('PASS')
    expect(weighingResult(495, 500, 1)).toBe('PASS')
  })

  it('超出上/下限分别判 OVER / UNDER', () => {
    expect(weighingResult(530, 500, 1)).toBe('OVER')
    expect(weighingResult(480, 500, 1)).toBe('UNDER')
  })
})
