/**
 * FluxMES 端到端自检脚本（Phase 1 + Phase D）。
 * 前置：PostgreSQL 已启动（fluxmes-pg 容器）且后端已运行（java -jar target/api-java-0.2.0.jar）。
 * 用法：node scripts/verify-phase1.mjs
 */
const BASE = 'http://127.0.0.1:8080'
let token = ''

async function call(path, { method = 'GET', body, auth = true, token: tk } = {}) {
  const headers = {}
  if (body) headers['Content-Type'] = 'application/json'
  const t = tk ?? token
  if (auth && t) headers.Authorization = `Bearer ${t}`
  const res = await fetch(BASE + path, { method, headers, body: body ? JSON.stringify(body) : undefined })
  const text = await res.text()
  let data
  try { data = JSON.parse(text) } catch { data = text }
  return { status: res.status, data }
}

const out = []
const log = (k, v) => out.push(`${k.padEnd(24)} ${v}`)

;(async () => {
  try {
    /* ---------- Phase 1 基础链路 ---------- */
    let r = await call('/api/auth/login', { method: 'POST', body: { username: 'admin', password: 'admin123' }, auth: false })
    log('1.login', `${r.status} role=${r.data?.user?.role} user=${r.data?.user?.username}`)
    token = r.data?.token || ''
    log('1.token', token ? token.slice(0, 24) + '…(有)' : '无')

    const noAuth = await fetch(BASE + '/api/batches')
    log('2.无token访问', String(noAuth.status) + '（期望 401）')

    r = await call('/api/batches')
    log('3.batches', `${r.status} total=${r.data?.total}`)

    r = await call('/api/alarms')
    log('4.alarms', `${r.status} unacked=${r.data?.unackedCount} 逾期=${r.data?.overdueCount} 条数=${r.data?.alarms?.length}`)

    r = await call('/api/quality/spc/detect')
    const v = r.data?.violations || []
    log('5.spc判异', `${r.status} 命中=${v.length} 规则=${[...new Set(v.map(x => x.rule))].join('/') || '无'}`)
    log('5.控制限', JSON.stringify(r.data?.limit || {}))

    r = await call('/api/batches', { method: 'POST', body: { product: '一水柠檬酸', recipe: 'R-CA-07', equipment: 'F-101 发酵罐 #1', planYield: 12, materialLots: ['RM-玉米粉-0724'], line: 'LINE-2' } })
    log('6.新建批次(LINE-2)', `${r.status} id=${r.data?.id} status=${r.data?.status} line=${r.data?.line}`)

    if (r.data?.id) {
      const id = r.data.id
      const adv = await call(`/api/batches/${encodeURIComponent(id)}/advance`, { method: 'POST', body: {} })
      log('7.工序推进', `${adv.status} stage=${adv.data?.stage} progress=${adv.data?.progress}`)
      // Phase I · FR-21：放行属受控动作，无电子签名应被阻断
      const relNoSig = await call('/api/quality/release', { method: 'POST', body: { batchId: id } })
      log('8a.放行签名门禁', `${relNoSig.status}（期望 409）${relNoSig.data?.message ? String(relNoSig.data.message).slice(0, 36) : ''}`)
      const rel = await call('/api/quality/release', {
        method: 'POST',
        body: { batchId: id, signature: { meaning: 'APPROVED', password: 'admin123' } },
      })
      log('8b.放行COA', `${rel.status} ${rel.data?.coaNo || rel.data?.message || ''}`)
    }

    r = await call('/api/audit')
    log('9.审计日志', `${r.status} total=${r.data?.total}`)

    r = await call('/v3/api-docs', { auth: false })
    log('10.OpenAPI', `${r.status} paths=${Object.keys(r.data?.paths || {}).length}`)

    /* ---------- D2 · 多产线 ---------- */
    r = await call('/api/dashboard/lines')
    log('11.产线列表', `${r.status} lines=${(r.data?.lines || []).map(l => `${l.code}(${l.batchCount ?? 0})`).join(' ')}`)

    r = await call('/api/dashboard/cockpit?line=LINE-2')
    log('12.驾驶舱(LINE-2)', `${r.status} 日产量=${JSON.stringify(r.data?.kpis?.dailyOutput)} OEE=${r.data?.kpis?.oee?.value}`)

    /* ---------- D3 · 班报 ---------- */
    r = await call('/api/dashboard/shift-report?shift=DAY')
    const md = r.data?.markdown || ''
    log('13.班报', `${r.status} 批次=${r.data?.summary?.batchTotal} 报警=${r.data?.summary?.alarmTotal} md长度=${md.length}`)

    /* ---------- D1 · SLA 与抑制 ---------- */
    r = await call('/api/alarms/stats')
    log('14.报警统计', `${r.status} SLA=${JSON.stringify(r.data?.slaPolicy)} 逾期=${r.data?.overdueUnacked} 抑制规则=${r.data?.activeSuppressionRules}`)

    const sweep = await call('/api/alarms/sla/sweep', { method: 'POST', body: {} })
    log('15.SLA巡检', `${sweep.status} ${JSON.stringify(sweep.data)}`)

    const created = await call('/api/alarms/suppressions', {
      method: 'POST',
      body: { name: '自检-罐温抖动抑制', source: 'F-101 发酵罐 #1', content: '罐温偏高', level: 'minor', windowMinutes: 10 },
    })
    const sid = created.data?.id
    log('16.新建抑制规则', `${created.status} id=${sid} 窗口=${created.data?.windowMinutes}min`)

    r = await call('/api/alarms/suppressions')
    log('17.抑制规则列表', `${r.status} 条数=${(r.data || []).length}`)

    // 越权校验：工艺员不得维护抑制规则（期望 403）
    const op = await call('/api/auth/login', { method: 'POST', body: { username: 'operator', password: 'op12345' }, auth: false })
    const opToken = op.data?.token || ''
    const denied = await call('/api/alarms/suppressions', {
      method: 'POST', token: opToken,
      body: { name: '越权测试', source: 'X', windowMinutes: 5 },
    })
    log('18.越权建规则', `${denied.status}（期望 403）`)

    if (sid) {
      const del = await call(`/api/alarms/suppressions/${sid}`, { method: 'DELETE' })
      log('19.删除抑制规则', `${del.status} ${JSON.stringify(del.data)}`)
    }

    // 报警台账应含 SLA 字段
    r = await call('/api/alarms')
    const sample = r.data?.alarms?.[0] || {}
    log('20.报警SLA字段', `slaMinutes=${sample.slaMinutes} elapsed=${sample.elapsedMinutes} overdue=${sample.overdue} escalated=${sample.escalated}`)
  } catch (e) {
    log('ERROR', e.message)
  }
  console.log(out.join('\n'))
})()
