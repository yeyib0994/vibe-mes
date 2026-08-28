/* FluxMES 示例数据 —— 流程制造（生物发酵 / 食品添加剂）场景
 * 工厂：清禾生物 · 一车间；主产品为柠檬酸系列与食品级柠檬酸钠
 */

/* ---------------- 生产驾驶舱 ---------------- */

// 今日产量趋势（00:00 - 15:00，t/h），累计 33.4 t，日计划 48 t
export const productionTrend = [
  { t: '00:00', out: 1.9 },
  { t: '01:00', out: 1.95 },
  { t: '02:00', out: 2.05 },
  { t: '03:00', out: 2.0 },
  { t: '04:00', out: 1.9 },
  { t: '05:00', out: 2.1 },
  { t: '06:00', out: 2.15 },
  { t: '07:00', out: 2.2 },
  { t: '08:00', out: 2.0 },
  { t: '09:00', out: 2.1 },
  { t: '10:00', out: 2.25 },
  { t: '11:00', out: 2.3 },
  { t: '12:00', out: 2.1 },
  { t: '13:00', out: 2.2 },
  { t: '14:00', out: 2.15 },
  { t: '15:00', out: 2.1 },
]
export const PLAN_RATE = 2.0

// 罐区与关键设备状态
export const equipment = [
  { code: 'F-101', name: '发酵罐 #1', status: 'running', statusText: '运行中', params: '36.8°C · pH 2.05 · 180 rpm' },
  { code: 'F-102', name: '发酵罐 #2', status: 'running', statusText: '运行中', params: '37.2°C · pH 1.98 · 182 rpm' },
  { code: 'F-103', name: '发酵罐 #3', status: 'cleaning', statusText: 'CIP 清洗', params: '85.0°C · 碱洗 2.1 m³/h' },
  { code: 'M-201', name: '配料罐 #1', status: 'running', statusText: '运行中', params: '45.0°C · 62 rpm' },
  { code: 'E-501', name: 'MVR 浓缩器', status: 'alarm', statusText: '报警', params: '蒸汽 0.62 MPa · 真空 -0.052' },
  { code: 'C-601', name: '结晶罐', status: 'running', statusText: '运行中', params: '28.5°C · 42 rpm' },
  { code: 'D-701', name: '流化床干燥机', status: 'running', statusText: '运行中', params: '进风 78.2°C · 出风 61.4°C' },
  { code: 'F-104', name: '发酵罐 #4', status: 'idle', statusText: '待机', params: '已清洗 · 待进批' },
]

// 在制批次（驾驶舱概览）
export const runningBatches = [
  { id: 'B-260826-014', product: '一水柠檬酸', stage: '发酵', equipment: 'F-102', progress: 68, tone: 'primary', eta: '预计 08-27 12:30 放罐' },
  { id: 'B-260826-013', product: '食品级柠檬酸钠', stage: '结晶', equipment: 'C-601', progress: 82, tone: 'primary', eta: '预计 08-26 21:00 转离心' },
  { id: 'B-260826-011', product: '无水柠檬酸', stage: '干燥', equipment: 'D-701', progress: 91, tone: 'accent', eta: '预计 08-26 17:20 完成' },
  { id: 'B-260826-009', product: '食品级柠檬酸钠', stage: '待检', equipment: 'IX-401', progress: 100, tone: 'warn', eta: '等待成品放行检验' },
]

/* ---------------- 批次管理 ---------------- */

export const batches = [
  {
    id: 'B-260826-014',
    product: '一水柠檬酸',
    recipe: 'R-CA-07 v3.2',
    equipment: 'F-102 发酵罐 #2',
    stage: '发酵',
    params: 'pH 2.05 · 36.8°C · DO 34%',
    progress: 68,
    status: 'running',
    start: '08-25 22:10',
    stages: [
      ['配料', 'done'], ['灭菌', 'done'], ['接种', 'done'], ['发酵', 'active'],
      ['过滤', 'todo'], ['精制', 'todo'], ['干燥', 'todo'], ['包装', 'todo'],
    ],
    meta: ['接种物批号 ZJ-0812', '原料批号 RM-20260823（玉米淀粉）', '操作员 张伟', '计划产量 12.0 t'],
  },
  {
    id: 'B-260826-013',
    product: '食品级柠檬酸钠',
    recipe: 'R-TS-12 v2.1',
    equipment: 'C-601 结晶罐',
    stage: '结晶',
    params: '28.4°C · 搅拌 42 rpm',
    progress: 82,
    status: 'running',
    start: '08-26 04:30',
    stages: [
      ['配料', 'done'], ['中和反应', 'done'], ['脱色', 'done'], ['离子交换', 'done'],
      ['浓缩', 'done'], ['结晶', 'active'], ['离心干燥', 'todo'], ['包装', 'todo'],
    ],
    meta: ['中和终点 pH 7.2', '活性炭批号 AC-20260821', '操作员 李倩', '计划产量 8.5 t'],
  },
  {
    id: 'B-260826-011',
    product: '无水柠檬酸',
    recipe: 'R-AC-03 v1.8',
    equipment: 'D-701 流化床干燥机',
    stage: '干燥',
    params: '进风 78.2°C · 水分 0.42%',
    progress: 91,
    status: 'running',
    start: '08-26 02:15',
    stages: [
      ['配料', 'done'], ['灭菌', 'done'], ['发酵', 'done'], ['过滤', 'done'],
      ['精制', 'done'], ['结晶', 'done'], ['离心干燥', 'active'], ['包装', 'todo'],
    ],
    meta: ['结晶母液回用 12%', '操作员 王芳', '计划产量 6.2 t', '干燥终点水分 ≤ 0.5%'],
  },
  {
    id: 'B-260826-009',
    product: '食品级柠檬酸钠',
    recipe: 'R-TS-12 v2.1',
    equipment: 'IX-401 离子交换柱',
    stage: '成品检验',
    params: '全项检验中 · 已取样 8 项',
    progress: 100,
    status: 'waiting',
    start: '08-25 18:40',
    stages: [
      ['配料', 'done'], ['中和反应', 'done'], ['脱色', 'done'], ['离子交换', 'done'],
      ['浓缩', 'done'], ['结晶', 'done'], ['离心干燥', 'done'], ['包装', 'active'],
    ],
    meta: ['检验任务 QC-260826-015', '待放行 8.4 t', '操作员 李倩', '目标客户 华东食品添加剂'],
  },
  {
    id: 'B-260826-007',
    product: '一水柠檬酸',
    recipe: 'R-CA-07 v3.2',
    equipment: '包装线 #2',
    stage: '已入库',
    params: '收率 99.4% · 水分 0.38%',
    progress: 100,
    status: 'done',
    start: '08-24 16:20',
    stages: [
      ['配料', 'done'], ['灭菌', 'done'], ['发酵', 'done'], ['过滤', 'done'],
      ['精制', 'done'], ['结晶', 'done'], ['离心干燥', 'done'], ['包装', 'done'],
    ],
    meta: ['实际产量 11.9 t', '检验报告 COA-260826-07', '入库 WH-A-03-12', '操作员 张伟'],
  },
  {
    id: 'B-260826-005',
    product: '发酵营养盐',
    recipe: 'R-NS-02 v1.1',
    equipment: 'M-201 配料罐 #1',
    stage: '已入库',
    params: '收率 99.8%',
    progress: 100,
    status: 'done',
    start: '08-24 09:00',
    stages: [
      ['配料', 'done'], ['溶解', 'done'], ['过滤', 'done'], ['灭菌', 'done'],
      ['灌装', 'done'], ['贴标', 'done'], ['入库', 'done'], ['—', 'none'],
    ],
    meta: ['实际产量 2.4 t', '内部使用 · 供发酵车间', '操作员 王芳'],
  },
  {
    id: 'B-260825-028',
    product: '一水柠檬酸',
    recipe: 'R-CA-07 v3.1',
    equipment: 'F-103 发酵罐 #3',
    stage: '异常终止',
    params: '第 38 h 染菌 · pH 异常上升',
    progress: 46,
    status: 'abnormal',
    start: '08-25 06:45',
    stages: [
      ['配料', 'done'], ['灭菌', 'done'], ['接种', 'done'], ['发酵', 'fail'],
      ['过滤', 'todo'], ['精制', 'todo'], ['干燥', 'todo'], ['包装', 'todo'],
    ],
    meta: ['异常单 EX-260825-003', '物料转废液处理', '偏差调查进行中', '责任人 工艺组 刘强'],
  },
  {
    id: 'B-260825-031',
    product: '无水柠檬酸',
    recipe: 'R-AC-03 v1.8',
    equipment: '包装线 #1',
    stage: '已入库',
    params: '收率 99.1%',
    progress: 100,
    status: 'done',
    start: '08-23 21:10',
    stages: [
      ['配料', 'done'], ['灭菌', 'done'], ['发酵', 'done'], ['过滤', 'done'],
      ['精制', 'done'], ['结晶', 'done'], ['离心干燥', 'done'], ['包装', 'done'],
    ],
    meta: ['实际产量 6.1 t', '检验报告 COA-260825-31', '入库 WH-A-01-08'],
  },
]

export const batchStatusMap = {
  running: { label: '进行中', tone: 'primary' },
  waiting: { label: '待检', tone: 'warn' },
  done: { label: '已完成', tone: 'accent' },
  abnormal: { label: '异常', tone: 'danger' },
}

/* ---------------- 质量管理 ---------------- */

// SPC：成品柠檬酸含量（滴定法），批次 B-260826-007
export const SPC_UCL = 99.82
export const SPC_CL = 99.5
export const SPC_LCL = 99.18
export const spcData = [
  ['08:00', 99.46], ['08:30', 99.52], ['09:00', 99.48], ['09:30', 99.55],
  ['10:00', 99.6], ['10:30', 99.92], ['11:00', 99.66], ['11:30', 99.58],
  ['12:00', 99.42], ['12:30', 99.5], ['13:00', 99.47], ['13:30', 99.61],
  ['14:00', 99.56], ['14:30', 99.49], ['15:00', 99.53],
].map(([t, v]) => ({ t, v, ooc: v > SPC_UCL || v < SPC_LCL }))

// 近 30 日不合格帕累托
export const paretoRaw = [
  { name: '色度偏差', n: 14 },
  { name: '水分超标', n: 9 },
  { name: 'pH 超标', n: 7 },
  { name: '粒度超标', n: 4 },
  { name: '异物', n: 2 },
  { name: '可见杂质', n: 1 },
]
const paretoTotal = paretoRaw.reduce((s, d) => s + d.n, 0)
let cum = 0
export const pareto = paretoRaw.map((d) => {
  cum += d.n
  return { ...d, cum: Math.round((cum / paretoTotal) * 1000) / 10 }
})

export const qcTasks = [
  { id: 'QC-260826-018', type: '成品检验', batch: 'B-260826-007', item: '柠檬酸含量 / 水分 / 色度', sample: 12, pass: 12, inspector: '林晓芸', status: 'done' },
  { id: 'QC-260826-017', type: '过程巡检', batch: 'B-260826-014', item: '发酵液 pH / 溶氧 / 菌浓', sample: 6, pass: 6, inspector: '赵敏', status: 'done' },
  { id: 'QC-260826-016', type: '首件检验', batch: 'B-260826-011', item: '干燥水分 / 粒度分布', sample: 3, pass: 2, inspector: '林晓芸', status: 'review' },
  { id: 'QC-260826-015', type: '成品检验', batch: 'B-260826-009', item: '全项检验（8 项）', sample: 8, pass: 5, inspector: '赵敏', status: 'progress' },
  { id: 'QC-260825-041', type: '成品检验', batch: 'B-260825-031', item: '柠檬酸含量 / 重金属', sample: 12, pass: 12, inspector: '林晓芸', status: 'done' },
]

export const qcStatusMap = {
  done: { label: '已完成', tone: 'accent' },
  review: { label: '待复核', tone: 'warn' },
  progress: { label: '检验中', tone: 'primary' },
}

/* ---------------- 报警中心 ---------------- */

export const alarmData = [
  { id: 'A-260826-017', time: '15:32', level: 'critical', source: 'E-501 MVR 浓缩器', content: '加热蒸汽压力高高', value: '0.62 MPa', threshold: '≥ 0.60 MPa', status: 'unacked' },
  { id: 'A-260826-016', time: '15:18', level: 'major', source: 'F-102 发酵罐 #2', content: '溶氧浓度偏低', value: 'DO 18%', threshold: '< 25%', status: 'acked', ackBy: '张伟' },
  { id: 'A-260826-015', time: '14:56', level: 'minor', source: 'M-201 配料罐 #1', content: '搅拌电流波动', value: '32.5 → 38.1 A', threshold: 'Δ > 5 A', status: 'acked', ackBy: '王芳' },
  { id: 'A-260826-014', time: '14:32', level: 'major', source: 'C-601 结晶罐', content: '冷却水流量低', value: '14.2 m³/h', threshold: '< 16 m³/h', status: 'acked', ackBy: '李倩' },
  { id: 'A-260826-013', time: '13:47', level: 'minor', source: 'D-701 流化床干燥机', content: '出风温度偏差', value: '63.8°C', threshold: '> 63°C', status: 'recovered' },
  { id: 'A-260826-012', time: '11:20', level: 'major', source: 'F-103 发酵罐 #3', content: '温度变送器信号丢失', value: '—', threshold: '信号超时 30 s', status: 'recovered' },
  { id: 'A-260826-011', time: '10:05', level: 'minor', source: 'IX-401 离子交换柱', content: '柱压差偏高', value: '0.14 MPa', threshold: '> 0.12 MPa', status: 'recovered' },
  { id: 'A-260826-010', time: '09:12', level: 'minor', source: 'S-201 连消机', content: '灭菌温度瞬时波动', value: '119.6°C', threshold: '< 121°C', status: 'recovered' },
]

export const alarmLevelMap = {
  critical: { label: '严重', tone: 'danger' },
  major: { label: '重要', tone: 'warn' },
  minor: { label: '一般', tone: 'muted' },
}

// 报警趋势（按小时，按级别堆叠）
export const alarmTrend = [
  { t: '08:00', critical: 0, major: 1, minor: 2 },
  { t: '09:00', critical: 0, major: 0, minor: 1 },
  { t: '10:00', critical: 0, major: 1, minor: 1 },
  { t: '11:00', critical: 0, major: 1, minor: 0 },
  { t: '12:00', critical: 0, major: 0, minor: 0 },
  { t: '13:00', critical: 0, major: 0, minor: 1 },
  { t: '14:00', critical: 0, major: 1, minor: 1 },
  { t: '15:00', critical: 1, major: 1, minor: 1 },
]

export const alarmTopSources = [
  { source: 'E-501 MVR 浓缩器', count: 5 },
  { source: 'F-102 发酵罐 #2', count: 4 },
  { source: 'M-201 配料罐 #1', count: 3 },
  { source: 'D-701 流化床干燥机', count: 2 },
  { source: 'C-601 结晶罐', count: 2 },
]

// 驾驶舱引用的最新报警（取列表前 4 条）
export const recentAlarms = alarmData.slice(0, 4)
