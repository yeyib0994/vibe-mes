/**
 * FluxMES Phase I（生产执行深化）端到端自检。
 * 前置：PostgreSQL 已启动且后端已运行（java -jar target/api-java-0.2.0.jar）。
 * 用法：node scripts/verify-phase5.mjs
 *
 * 覆盖：工单状态机与快照 / 派工资质门禁 / 报工驱动进度与双人复核 / 停机原因码与合并 /
 *       真实 OEE 口径与「数据不足不伪造」/ 停机帕累托 / 驾驶舱与设备页口径切换。
 */
const BASE = 'http://127.0.0.1:8080'
const tokens = {}

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
const log = (k, v) => out.push(`${String(k).padEnd(34)} ${v}`)
const pad = (n) => String(n).padStart(2, '0')
const nowLocal = (h) => {
  const d = new Date(Date.now() - 8 * 3600 * 1000)
  return `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}T${pad(h)}:00:00+08:00`
}

;(async () => {
  try {
    for (const [u, p] of [
      ['admin', 'admin123'],
      ['supervisor', 'super123'],
      ['qc', 'qc12345'],
      ['operator', 'op12345'],
      ['temp', 'temp123'],
    ]) {
      const r = await call('/api/auth/login', { method: 'POST', body: { username: u, password: p } })
      tokens[u] = r.data?.token ?? ''
    }
    log('0.登录(5 账号)', `${Object.values(tokens).filter(Boolean).length}/5 成功`)

    /* ================= I1 · 工单与快照 ================= */
    let r = await call('/api/execution/orders', { token: tokens.admin })
    const seeded = r.data?.orders ?? []
    log('I1.1 工单列表(种子回填)', `${r.status} 共 ${seeded.length} 张 · 首张=${seeded[0]?.id ?? '—'} 状态=${seeded[0]?.status ?? '—'}`)
    log('I1.2 快照字段(FR-1)', `配方=${seeded[0]?.recipeCode ?? '—'}@${seeded[0]?.recipeVersion ?? '—'} 产线=${seeded[0]?.line ?? '—'} 厂区=${seeded[0]?.site ?? '—'}`)

    r = await call('/api/execution/downtime/reasons', { token: tokens.admin })
    const reasons = r.data ?? []
    log('I1.3 停机原因码字典(FR-16)', `${r.status} ${reasons.length} 条 · 计划类=${reasons.filter((x) => x.planned).length}`)

    // 选一个未放行批次建工单
    r = await call('/api/batches?size=50', { token: tokens.admin })
    const batch = (r.data?.batches ?? []).find((b) => !b.released) ?? (r.data?.batches ?? [])[0]
    log('I1.4 选用在建批次', `${batch?.id ?? '—'} released=${batch?.released}`)

    // 非法入参校验（FR-3）
    r = await call('/api/execution/orders', {
      method: 'POST', token: tokens.supervisor,
      body: { batchId: batch?.id, planQty: 0 },
    })
    log('I1.5 计划量≤0 拒绝(FR-3)', `${r.status}（期望 400）${typeof r.data === 'string' ? '' : ' ' + (r.data?.message ?? '')}`)

    r = await call('/api/execution/orders', {
      method: 'POST', token: tokens.supervisor,
      body: { batchId: batch?.id, planQty: 1000, planStart: nowLocal(12), planEnd: nowLocal(8) },
    })
    log('I1.6 完工早于开工 拒绝', `${r.status}（期望 400）`)

    // 正常建单
    r = await call('/api/execution/orders', {
      method: 'POST', token: tokens.supervisor,
      body: { batchId: batch?.id, planQty: 1000, shift: 'DAY', planStart: nowLocal(8), planEnd: nowLocal(20) },
    })
    const order = r.data
    log('I1.7 建工单(FR-1/FR-2)', `${r.status} ${order?.id} 计划=${order?.planQty}${order?.unit} 计划工时=${order?.planMinutes}min 状态=${order?.status}`)
    log('I1.8 厂区/产线继承', `site=${order?.site} line=${order?.line} recipe=${order?.recipeCode}@${order?.recipeVersion}`)

    // 权限：工艺员不可建单
    r = await call('/api/execution/orders', {
      method: 'POST', token: tokens.operator, body: { batchId: batch?.id, planQty: 100 },
    })
    log('I1.9 工艺员建单拒绝(C4)', `${r.status}（期望 403）`)

    /* ================= I1 · 状态机 ================= */
    r = await call(`/api/execution/orders/${order.id}/start`, { method: 'POST', token: tokens.operator })
    log('I1.10 跳级开工拒绝(FR-4)', `${r.status}（期望 409，须先下达）`)

    r = await call(`/api/execution/orders/${order.id}/release`, { method: 'POST', token: tokens.supervisor })
    log('I1.11 下达工单', `${r.status} 状态=${r.data?.order?.status} next=${r.data?.nextStatus}`)

    r = await call(`/api/execution/orders/${order.id}/release`, { method: 'POST', token: tokens.supervisor })
    log('I1.12 重复下达拒绝', `${r.status}（期望 409）`)

    r = await call(`/api/execution/orders/${order.id}/start`, { method: 'POST', token: tokens.operator })
    log('I1.13 开工', `${r.status} 状态=${r.data?.order?.status}`)

    /* ================= I1 · 派工门禁 ================= */
    r = await call(`/api/execution/orders/${order.id}/dispatch`, {
      method: 'POST', token: tokens.supervisor,
      body: { username: 'temp', capability: 'CCP_MONITOR' },
    })
    log('I1.14 缺证人员派工拒绝(FR-6/7)', `${r.status}（期望 403）${typeof r.data === 'string' ? '' : ' ' + String(r.data?.message ?? '').slice(0, 60)}`)

    r = await call(`/api/execution/orders/${order.id}/dispatch`, {
      method: 'POST', token: tokens.operator, body: { username: 'operator' },
    })
    log('I1.15 工艺员派工拒绝(C4)', `${r.status}（期望 403）`)

    r = await call(`/api/execution/orders/${order.id}/dispatch`, {
      method: 'POST', token: tokens.supervisor,
      body: { username: 'operator', roleInOrder: 'OPERATOR', capability: 'WEIGHING' },
    })
    const asg = r.data?.created?.[0]
    log('I1.16 派工成功', `${r.status} ${asg?.username} ${asg?.roleInOrder} 能力=${asg?.capability}`)

    if (asg?.id) {
      r = await call(`/api/execution/orders/${order.id}/dispatch/${asg.id}?reason=验证撤销`, {
        method: 'DELETE', token: tokens.supervisor,
      })
      log('I1.17 撤销派工(软删 FR-9)', `${r.status} 状态=${r.data?.status} 原因=${r.data?.revokeReason}`)
    }

    /* ================= I2 · 报工 ================= */
    const t1 = nowLocal(9)
    const t2 = nowLocal(12)
    r = await call(`/api/execution/orders/${order.id}/reports`, {
      method: 'POST', token: tokens.operator,
      body: { stepNo: 1, stepName: '配料称量', equipment: 'M-201', startedAt: t1, finishedAt: t2,
        inputQty: 520, goodQty: 500, scrapQty: 20, stdMinutes: 150 },
    })
    const rep = r.data?.report
    log('I2.1 报工(FR-10)', `${r.status} #${rep?.stepNo} 合格=${rep?.goodQty} 良品率=${rep?.yieldPct}% 幂等=${r.data?.idempotent}`)
    log('I2.2 批次进度由报工推导(D2)', `progressPct=${r.data?.order?.progressPct}% 合格累计=${r.data?.order?.goodQty}`)

    r = await call(`/api/execution/orders/${order.id}/reports`, {
      method: 'POST', token: tokens.operator,
      body: { stepNo: 1, stepName: '配料称量', equipment: 'M-201', startedAt: t1, finishedAt: t2,
        inputQty: 520, goodQty: 500, scrapQty: 20, stdMinutes: 150 },
    })
    log('I2.3 报工幂等(NFR-2)', `${r.status} idempotent=${r.data?.idempotent}（期望 true）`)

    r = await call(`/api/execution/orders/${order.id}/reports`, {
      method: 'POST', token: tokens.operator,
      body: { stepNo: 2, stepName: '发酵', startedAt: nowLocal(13), finishedAt: nowLocal(15),
        inputQty: 900, goodQty: 900, scrapQty: 0, stdMinutes: 100, critical: true, reviewer: 'operator' },
    })
    log('I2.4 关键工序自复核拒绝(FR-13)', `${r.status}（期望 400）${typeof r.data === 'string' ? '' : ' ' + (r.data?.message ?? '')}`)

    r = await call(`/api/execution/orders/${order.id}/reports`, {
      method: 'POST', token: tokens.operator,
      body: { stepNo: 2, stepName: '发酵', startedAt: nowLocal(13), finishedAt: nowLocal(15),
        inputQty: 900, goodQty: 700, scrapQty: 0, stdMinutes: 100, critical: true, reviewer: 'qc' },
    })
    log('I2.5 关键工序双人复核通过', `${r.status} reviewer=${r.data?.report?.reviewer} critical=${r.data?.report?.critical}`)

    r = await call(`/api/execution/orders/${order.id}/reports`, {
      method: 'POST', token: tokens.operator,
      body: { stepNo: 3, stepName: '提取', startedAt: nowLocal(16), finishedAt: nowLocal(17),
        inputQty: 200, goodQty: 200, scrapQty: 0, stdMinutes: 40 },
    })
    log('I2.6 累计超计划110%拒绝(FR-12)', `${r.status}（期望 400，累计 1400/1000）`)

    r = await call(`/api/execution/orders/${order.id}`, { token: tokens.admin })
    const detail = r.data
    const doneStep = (detail?.reports ?? []).find((x) => x.stepNo === 1)
    log('I2.7 报工明细与工单汇总', `${r.status} 报工=${(detail?.reports ?? []).length} 条 工单合格=${detail?.order?.goodQty} 实际工时=${detail?.order?.actualMinutes}min`)
    log('I2.8 batch_step 回写', `步骤=${doneStep?.stepName} 设备=${doneStep?.equipment}`)

    /* ================= I3 · 停机 ================= */
    const s1 = nowLocal(6)
    const s2 = nowLocal(8)
    r = await call('/api/execution/downtime', {
      method: 'POST', token: tokens.operator,
      body: { orderId: order.id, equipment: 'C-601', reasonCode: 'MECH', startedAt: s1, endedAt: s2,
        description: '结晶罐搅拌电机过热跳停' },
    })
    const dt1 = r.data?.downtime
    log('I3.1 停机录入(MECH)', `${r.status} ${dt1?.durationMin}min planned=${dt1?.planned}（期望 false）报警=${r.data?.alarmId ?? '—'}`)

    r = await call('/api/execution/downtime', {
      method: 'POST', token: tokens.operator,
      body: { orderId: order.id, equipment: 'C-601', reasonCode: 'MECH', startedAt: nowLocal(7), endedAt: nowLocal(9) },
    })
    log('I3.2 时间窗合并(D6/FR-18)', `${r.status} merged=${r.data?.merged}（期望 true）时长=${r.data?.downtime?.durationMin}min 来源=${r.data?.downtime?.mergedFrom ?? '—'}`)

    r = await call('/api/execution/downtime', {
      method: 'POST', token: tokens.operator,
      body: { orderId: order.id, equipment: 'C-601', reasonCode: 'CHANGEOVER', startedAt: nowLocal(2), endedAt: nowLocal(3),
        description: '换规格清场' },
    })
    log('I3.3 计划停机分类(FR-17)', `${r.status} planned=${r.data?.downtime?.planned}（期望 true）category=${r.data?.downtime?.category}`)

    r = await call('/api/execution/downtime', {
      method: 'POST', token: tokens.operator,
      body: { equipment: 'C-601', reasonCode: 'MECH', startedAt: nowLocal(10), endedAt: nowLocal(13) },
    })
    log('I3.4 长时停机自动报警(FR-19)', `${r.status} 报警=${r.data?.alarmId ?? '—'}（阈值 120min）`)

    r = await call('/api/execution/downtime', {
      method: 'POST', token: tokens.operator, body: { equipment: 'C-601', reasonCode: 'NOPE' },
    })
    log('I3.5 未知原因码拒绝', `${r.status}（期望 400）`)

    r = await call('/api/execution/downtime?equipment=C-601', { token: tokens.admin })
    log('I3.6 停机列表', `${r.status} ${(r.data ?? []).length} 条`)

    /* ================= I4 · 真实 OEE ================= */
    r = await call('/api/execution/oee', { token: tokens.admin })
    const oee = r.data
    log('I4.1 真实 OEE(FR-20)', `${r.status} OEE=${oee?.oee}% 可用率=${oee?.availability} 性能率=${oee?.performance} 良品率=${oee?.quality}`)
    log('I4.2 数据充足性标记(FR-21)', `dataSufficient=${oee?.dataSufficient} missing=${oee?.missing ? String(oee.missing).slice(0, 50) : '无'}`)
    log('I4.3 原始事实可下钻', `计划=${oee?.plannedMinutes}min 非计划停机=${oee?.unplannedStopMinutes}min 作业=${oee?.runMinutes}min 标准=${oee?.stdMinutes}min`)
    log('I4.4 口径自述', `${String(oee?.formula ?? '').slice(0, 60)}…`)

    r = await call('/api/execution/oee?from=2030-01-01&to=2030-01-02', { token: tokens.admin })
    log('I4.5 无数据不伪造(FR-21)', `${r.status} OEE=${r.data?.oee} dataSufficient=${r.data?.dataSufficient}（期望 null/false）`)

    r = await call('/api/execution/oee?equipment=C-601', { token: tokens.admin })
    log('I4.6 设备维度 OEE', `${r.status} OEE=${r.data?.oee} 可用率=${r.data?.availability} 计划工时推导=${r.data?.plannedMinutesDerived}`)

    r = await call('/api/execution/oee/pareto', { token: tokens.admin })
    const items = r.data?.items ?? []
    const last = items[items.length - 1]
    log('I4.7 停机帕累托(FR-23)', `${r.status} ${items.length} 类 合计=${r.data?.totalMinutes}min 首=${items[0]?.reasonName}:${items[0]?.sharePct}% 累计末=${last?.cumPct}%`)
    log('I4.8 计划/非计划拆分', `计划=${r.data?.plannedMinutes}min 非计划=${r.data?.unplannedMinutes}min`)

    r = await call('/api/execution/oee/recompute', { method: 'POST', token: tokens.operator })
    log('I4.9 重算权限(C4)', `${r.status}（期望 403）`)

    r = await call('/api/execution/oee/recompute', { method: 'POST', token: tokens.supervisor })
    log('I4.10 重算产线日汇总(FR-22)', `${r.status} 行数=${(r.data?.rows ?? []).length} 日期=${r.data?.date}`)

    /* ================= 口径切换回归 ================= */
    r = await call('/api/dashboard/cockpit?line=LINE-1', { token: tokens.admin })
    const cock = r.data?.kpi?.oee ?? r.data?.oee
    log('I4.11 驾驶舱 OEE 换源', `${r.status} source=${cock?.source} OEE=${cock?.value} dataSufficient=${cock?.dataSufficient}`)
    log('I4.12 驾驶舱日产量去兜底(T15)', `actual=${r.data?.kpi?.dailyOutput?.actual} dataSufficient=${r.data?.kpi?.dailyOutput?.dataSufficient}`)

    r = await call('/api/equipment/F-101/oee', { token: tokens.admin })
    log('I4.13 设备页 OEE 换源(T15)', `${r.status} OEE=${r.data?.oee} 可用率=${r.data?.availability} dataSufficient=${r.data?.dataSufficient}`)
    log('I4.14 设备 OEE 缺失说明', `${String(r.data?.missing ?? r.data?.note ?? '—').slice(0, 60)}`)

    r = await call('/v3/api-docs', { token: tokens.admin })
    const paths = Object.keys(r.data?.paths ?? {})
    log('I4.15 OpenAPI 契约', `${r.status} 版本=${r.data?.info?.version} 路径=${paths.length} 含 execution=${paths.some((p) => p.includes('/execution'))}`)

    console.log(out.join('\n'))
  } catch (e) {
    console.log(out.join('\n'))
    console.error('\n验证中断：', e.message)
    process.exit(1)
  }
})()
