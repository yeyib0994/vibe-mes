/**
 * FluxMES Phase E · 食品行业能力端到端自检。
 * 前置：PostgreSQL 已启动且后端已运行（java -jar target/api-java-0.2.0.jar）。
 * 用法：node scripts/verify-food-safety.mjs
 *
 * 覆盖：F1 HACCP/CCP 偏离联动、清场门禁、环境超标；
 *      F2 物料谱系、原料放行、召回分析；
 *      F3 eBR 双人复核、留样、效期预警。
 */
const BASE = 'http://127.0.0.1:8080'
let adminToken = ''
let qcToken = ''
let opToken = ''

async function call(path, { method = 'GET', body, token } = {}) {
  const headers = {}
  if (body) headers['Content-Type'] = 'application/json'
  if (token) headers.Authorization = `Bearer ${token}`
  const res = await fetch(BASE + path, {
    method,
    headers,
    body: body ? JSON.stringify(body) : undefined,
  })
  const text = await res.text()
  let data
  try { data = JSON.parse(text) } catch { data = text }
  return { status: res.status, data }
}

const out = []
const log = (k, v) => out.push(`${String(k).padEnd(28)} ${v}`)

;(async () => {
  try {
    for (const [u, p] of [['admin', 'admin123'], ['qc', 'qc12345'], ['operator', 'op12345']]) {
      const r = await call('/api/auth/login', { method: 'POST', body: { username: u, password: p } })
      if (u === 'admin') adminToken = r.data?.token ?? ''
      if (u === 'qc') qcToken = r.data?.token ?? ''
      if (u === 'operator') opToken = r.data?.token ?? ''
    }
    log('0.登录(admin/qc/operator)', [adminToken, qcToken, opToken].filter(Boolean).length + '/3 成功')

    /* ---------- F1 · HACCP ---------- */
    let r = await call('/api/haccp/points', { token: adminToken })
    log('F1.1 CCP 点', `${r.status} 共 ${r.data?.points?.length ?? 0} 个`)
    const firstCcp = r.data?.points?.[0]

    r = await call('/api/haccp/summary', { token: adminToken })
    log('F1.2 CCP 合规看板', `${r.status} 合规率=${r.data?.complianceRate}% 偏离=${r.data?.deviationCount} 待复核=${r.data?.unverifiedCount}`)

    if (firstCcp) {
      // 正常值
      const ok = await call('/api/haccp/records', {
        method: 'POST', token: opToken,
        body: { ccpCode: firstCcp.code, value: (Number(firstCcp.clMin) + Number(firstCcp.clMax)) / 2 },
      })
      log('F1.3 上报合格值', `${ok.status} inLimit=${ok.data?.inLimit}`)

      // 偏离值 → 应自动建偏差单 + 报警
      const bad = await call('/api/haccp/records', {
        method: 'POST', token: opToken,
        body: { ccpCode: firstCcp.code, value: Number(firstCcp.clMax) + 10 },
      })
      log('F1.4 上报偏离值', `${bad.status} inLimit=${bad.data?.inLimit} 偏差单=${bad.data?.deviationId ?? '—'} 报警=${bad.data?.alarmId ?? '—'}`)

      if (bad.data?.id) {
        const v1 = await call(`/api/haccp/records/${bad.data.id}/verify`, { method: 'POST', token: opToken })
        log('F1.5 工艺员自复核', `${v1.status}（期望 403 角色不足或 409 本人）`)
        const v2 = await call(`/api/haccp/records/${bad.data.id}/verify`, { method: 'POST', token: qcToken })
        log('F1.6 QA 复核', `${v2.status} verifier=${v2.data?.verifier ?? '—'}`)
      }
    }

    /* ---------- F1 · 清场门禁 ---------- */
    r = await call('/api/sanitation/status?line=LINE-1', { token: adminToken })
    log('F1.7 LINE-1 清场状态', `${r.status} cleared=${r.data?.cleared} ${r.data?.reason ?? ''}`)

    r = await call('/api/sanitation/records', { token: adminToken })
    log('F1.8 清场记录', `${r.status} 共 ${r.data?.records?.length ?? 0} 条`)

    const newClean = await call('/api/sanitation/records', {
      method: 'POST', token: opToken,
      body: { line: 'LINE-2', type: 'ALLERGEN', method: '碱洗 → 冲洗 → ATP 验证' },
    })
    log('F1.9 登记过敏原清场', `${newClean.status} id=${newClean.data?.id} result=${newClean.data?.result}`)
    if (newClean.data?.id) {
      const vf = await call(`/api/sanitation/records/${newClean.data.id}/verify`, {
        method: 'POST', token: qcToken, body: { result: 'PASS', swabResult: 'ATP 110 RLU' },
      })
      log('F1.10 QA 确认清场', `${vf.status} result=${vf.data?.result} 有效期=${String(vf.data?.validUntil ?? '').slice(0, 16)}`)
    }

    // 无有效清场 → 新建批次应被拒
    const blocked = await call('/api/batches', {
      method: 'POST', token: opToken,
      body: { product: '一水柠檬酸', recipe: 'R-CA-07', equipment: 'C-601', planYield: 5, materialLots: ['RM-001'], line: 'LINE-9' },
    })
    log('F1.11 无清场开工', `${blocked.status}（期望 409 禁止开工）${typeof blocked.data === 'string' ? '' : ' ' + (blocked.data?.message ?? '')}`)

    /* ---------- F1 · 环境 ---------- */
    r = await call('/api/sanitation/environment/summary', { token: adminToken })
    log('F1.12 环境汇总', `${r.status} 合格率=${r.data?.passRate}% 超标=${r.data?.failCount}`)
    const envBad = await call('/api/sanitation/environment', {
      method: 'POST', token: opToken,
      body: { area: '洁净灌装区', metric: 'MICRO', value: 99, unit: 'CFU/皿', limitMin: 0, limitMax: 30, line: 'LINE-3' },
    })
    log('F1.13 上报环境超标', `${envBad.status} result=${envBad.data?.result} 偏差单=${envBad.data?.deviationId ?? '—'}`)

    /* ---------- F2 · 物料与召回 ---------- */
    r = await call('/api/materials', { token: adminToken })
    log('F2.1 物料主数据', `${r.status} 共 ${r.data?.materials?.length ?? 0} 种，过敏原 ${(r.data?.materials ?? []).filter((m) => m.allergen).length} 种`)

    r = await call('/api/materials/lots', { token: adminToken })
    const lots = r.data?.lots ?? []
    log('F2.2 原料批', `${r.status} 共 ${lots.length} 批（放行 ${lots.filter((l) => l.qcStatus === 'RELEASED').length} / 待检 ${lots.filter((l) => l.qcStatus === 'PENDING').length} / 不合格 ${lots.filter((l) => l.qcStatus === 'FAIL').length}）`)

    const pending = lots.find((l) => l.qcStatus === 'PENDING')
    if (pending) {
      const insp = await call(`/api/materials/lots/${pending.id}/inspect`, {
        method: 'POST', token: qcToken, body: { result: 'PASS', coaNo: 'COA-TEST-001' },
      })
      log('F2.3 原料检验放行', `${insp.status} ${pending.id} → ${insp.data?.qcStatus}`)
    }
    const failed = lots.find((l) => l.qcStatus === 'FAIL')
    if (failed) {
      const feed = await call('/api/materials/B-TEST/inputs', {
        method: 'POST', token: opToken, body: { materialLotId: failed.id, qty: 10 },
      })
      log('F2.4 不合格原料投料', `${feed.status}（期望 404/409 被拒）`)
    }

    // 取一个真实存在且已登记投料的批次（种子为前 6 个批次建谱系）
    const bl = await call('/api/batches', { token: adminToken })
    const target = (bl.data?.batches ?? [])[0]?.id ?? 'B-260826-007'

    r = await call(`/api/materials/genealogy/${target}`, { token: adminToken })
    log('F2.5 真实谱系', `${r.status} ${target} 上游=${r.data?.upstreamCount ?? '—'} 下游=${r.data?.downstreamCount ?? '—'} 完整性=${r.data?.integrity ?? '—'}`)

    const usedLot = (r.data?.upstream ?? [])[0]?.lotId
    if (usedLot) {
      const rec = await call(`/api/materials/recall/${usedLot}`, { token: adminToken })
      log('F2.6 召回影响分析', `${rec.status} 原料批=${usedLot} 受影响批次=${rec.data?.affectedBatchCount ?? '—'} 已放行=${rec.data?.releasedBatchCount ?? '—'} 产量=${rec.data?.totalYieldT ?? '—'}t`)
    }

    /* ---------- F3 · eBR / 留样 / 效期 ---------- */
    r = await call(`/api/ebr/${target}`, { token: adminToken })
    log('F3.1 电子批记录', `${r.status} ${target} 工序=${r.data?.stepCount ?? 0} 完成=${r.data?.doneCount ?? 0} 待复核=${r.data?.unreviewedCount ?? 0}`)
    const step = (r.data?.steps ?? []).find((s) => s.status === 'DONE' && !s.reviewer)
    if (step) {
      const rv = await call(`/api/ebr/steps/${step.id}/review`, { method: 'POST', token: qcToken })
      log('F3.2 工序双人复核', `${rv.status} reviewer=${rv.data?.reviewer ?? '—'}`)
    } else {
      log('F3.2 工序双人复核', '跳过（无待复核工序）')
    }

    r = await call('/api/ebr/retention', { token: adminToken })
    log('F3.3 留样台账', `${r.status} 共 ${r.data?.samples?.length ?? 0} 件`)

    r = await call('/api/ebr/expiry-alerts?warnDays=30', { token: adminToken })
    log('F3.4 效期预警', `${r.status} 近效期=${r.data?.expiringCount ?? 0} 已过期=${r.data?.expiredCount ?? 0}`)

    r = await call('/v3/api-docs', { token: adminToken })
    const tags = [...new Set(Object.values(r.data?.paths ?? {}).flatMap((p) => Object.values(p).flatMap((o) => o.tags ?? [])))]
    log('Z.OpenAPI 契约', `${r.status} 路径=${Object.keys(r.data?.paths ?? {}).length} 标签=${tags.join(',')}`)

    console.log('\n=== FluxMES Phase E 食品行业能力自检 ===')
    console.log(out.join('\n'))
    console.log('\n完成。逐项检查：状态非 2xx 的项需结合期望值判断（部分为故意的越权/拦截用例）。')
  } catch (e) {
    console.error('自检失败：后端是否已启动？', e.message)
    process.exit(1)
  }
})()
