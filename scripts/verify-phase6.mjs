/**
 * FluxMES Phase I（质量体系 RegTech）端到端自检。
 * 前置：PostgreSQL 已启动且后端已运行（java -jar target/api-java-0.2.0.jar）。
 * 用法：node scripts/verify-phase6.mjs
 *
 * 覆盖：CAPA 闭环（创建门禁 / 行动项门禁 / 有效性验证 / 关闭）·
 *       内审管理（分级登记 / 转 CAPA / 关闭门禁 / 年度转化率）·
 *       电子签名（要素完整 / 防共享账号 / 失败锁定 / 篡改检测 / 证据导出）·
 *       受控动作集成（放行 / 偏差关闭 / 配方生效的签名门禁 + FR-7 CAPA 阻断）。
 *
 * 脚本可重复运行：创建的 CAPA / 内审 / 偏差在用例结束时均推进到终态（closed），
 * 不残留会阻断后续运行的门禁数据。
 *
 * 已知未覆盖：SC-10 中「锁定 15 分钟后自动恢复」无法在脚本内构造（不为测试加后门），
 * 脚本只断言锁定可达（423）与锁定期间持续拒绝。
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
const log = (k, v) => out.push(`${String(k).padEnd(38)} ${v}`)
const msg = (r) => (r.data && typeof r.data === 'object' ? r.data.message ?? '' : String(r.data ?? ''))
const day = (offset) => new Date(Date.now() - 8 * 3600 * 1000 + offset * 86400000).toISOString().slice(0, 10)
const sig = (meaning, password) => ({ meaning, password })
let skipped = 0
const skip = (k, why) => { skipped++; log(k, `跳过（${why}）`) }

;(async () => {
  try {
    for (const [u, p] of [
      ['admin', 'admin123'],
      ['supervisor', 'super123'],
      ['qc', 'qc12345'],
      ['operator', 'op12345'],
      ['temp', 'temp123'],
      ['service', 'service123'],
    ]) {
      const r = await call('/api/auth/login', { method: 'POST', body: { username: u, password: p } })
      tokens[u] = r.data?.token ?? ''
    }
    log('0.登录(6 账号·含 service)', `${Object.values(tokens).filter(Boolean).length}/6 成功`)

    /* ================= J1 · CAPA 闭环 ================= */
    let r = await call('/api/capa', { token: tokens.admin })
    const seedItems = r.data?.items ?? []
    const seedOverdue = seedItems.find((c) => c.overdue)
    log('J1.1 CAPA 列表(种子)', `${r.status} 共 ${r.data?.total ?? 0} 条 · 逾期 ${r.data?.overdue ?? 0} 临期 ${r.data?.dueSoon ?? 0}`)
    log('J1.2 种子逾期标记(FR-5)', seedOverdue
      ? `${seedOverdue.id} 逾期 ${Math.abs(seedOverdue.daysLeft)} 天 severity=${seedOverdue.severity}`
      : '— 无逾期种子')

    r = await call('/api/capa/metrics', { token: tokens.admin })
    log('J1.3 指标卡(T22)', `${r.status} 在办=${r.data?.open} 内审=${r.data?.auditCount} 发现项=${r.data?.findingCount} 转化率=${r.data?.capaConversionRate}%`)

    r = await call('/api/capa/overdue', { token: tokens.qc })
    log('J1.4 逾期/临期端点(FR-5)', `${r.status} overdue=${r.data?.overdueCount} dueSoon=${r.data?.dueSoonCount} 责任人=${(r.data?.byOwner ?? []).length} 新报警=${r.data?.alarmRaised}`)

    r = await call('/api/capa', {
      method: 'POST', token: tokens.qc,
      body: { title: '自检缺根因', owner: 'qc', dueDate: day(10), tasks: [{ action: 'x' }] },
    })
    log('J1.5 缺根因拒绝(FR-2)', `${r.status}（期望 400）${msg(r).slice(0, 40)}`)

    r = await call('/api/capa', {
      method: 'POST', token: tokens.qc,
      body: { title: '自检缺行动项', owner: 'qc', dueDate: day(10), rootCause: 'r' },
    })
    log('J1.6 缺行动项拒绝(FR-2)', `${r.status}（期望 400）${msg(r).slice(0, 40)}`)

    r = await call('/api/capa', {
      method: 'POST', token: tokens.qc,
      body: { title: 'x', owner: 'qc', dueDate: day(10), rootCause: 'r', type: 'WRONG', tasks: [{ action: 'x' }] },
    })
    log('J1.7 非法类型拒绝(FR-1)', `${r.status}（期望 400）${msg(r).slice(0, 40)}`)

    r = await call('/api/capa', {
      method: 'POST', token: tokens.qc,
      body: {
        sourceType: 'MANUAL', type: 'PREVENTIVE', title: '自检·预防措施',
        description: '端到端自检创建', rootCause: '自检用根因：点检频次不足',
        owner: 'qc', dueDate: day(12), severity: 'major',
        tasks: [
          { action: '完成整改并留存证据', owner: 'qc', dueDate: day(8) },
          { action: '更新作业指导书', owner: 'qc', dueDate: day(12) },
        ],
      },
    })
    const capaId = r.data?.id
    log('J1.8 创建 CAPA(FR-1/FR-2)', `${r.status} id=${capaId} 编号格式=${/^CAPA-\d{6}-\d{3}$/.test(capaId ?? '')} 行动项=${(r.data?.tasks ?? []).length}`)

    r = await call('/api/capa', {
      method: 'POST', token: tokens.operator,
      body: { title: 'x', owner: 'op', dueDate: day(10), rootCause: 'r', tasks: [{ action: 'x' }] },
    })
    log('J1.9 工艺员创建拒绝(C4)', `${r.status}（期望 403）`)

    r = await call(`/api/capa/${capaId}/transition`, {
      method: 'POST', token: tokens.qc, body: { target: 'in_progress' },
    })
    log('J1.10 启动执行', `${r.status} →${r.data?.status}`)

    r = await call(`/api/capa/${capaId}/transition`, {
      method: 'POST', token: tokens.qc, body: { target: 'pending_verify' },
    })
    const gateMsg = msg(r)
    log('J1.11 未完项阻断验证(FR-4)', `${r.status}（期望 400）列出未完成项=${/#1|#2/.test(gateMsg)}`)

    const tasks = (await call(`/api/capa/${capaId}`, { token: tokens.qc })).data?.tasks ?? []
    let doneErr = 0
    for (const t of tasks) {
      const d = await call(`/api/capa/tasks/${t.id}/done`, {
        method: 'POST', token: tokens.qc, body: { evidence: '自检证据' },
      })
      if (d.status !== 200) doneErr++
    }
    log('J1.12 行动项全部完成', `共 ${tasks.length} 条 · 失败 ${doneErr} 条`)

    r = await call(`/api/capa/${capaId}/transition`, {
      method: 'POST', token: tokens.qc, body: { target: 'pending_verify' },
    })
    log('J1.13 提交验证(FR-4 通过)', `${r.status} →${r.data?.status}`)

    r = await call(`/api/capa/${capaId}/verify`, {
      method: 'POST', token: tokens.admin,
      body: { verificationMethod: 'DOC_REVIEW', effectiveness: 'EFFECTIVE', comment: '自检' },
    })
    log('J1.14 验证缺签名阻断(FR-21)', `${r.status}（期望 409）含含义提示=${/VERIFIED|签名/.test(msg(r))}`)

    r = await call(`/api/capa/${capaId}/verify`, {
      method: 'POST', token: tokens.qc,
      body: { verificationMethod: 'DOC_REVIEW', effectiveness: 'EFFECTIVE', signature: sig('VERIFIED', 'qc12345') },
    })
    log('J1.15 验证角色不足(C4)', `${r.status}（期望 403）`)

    r = await call(`/api/capa/${capaId}/verify`, {
      method: 'POST', token: tokens.admin,
      body: { verificationMethod: 'DOC_REVIEW', effectiveness: 'EFFECTIVE', signature: sig('APPROVED', 'admin123') },
    })
    log('J1.16 签名含义不符(FR-14)', `${r.status}（期望 400）${msg(r).slice(0, 44)}`)

    r = await call('/api/signatures', {
      method: 'POST', token: tokens.service,
      body: { action: 'CAPA_VERIFY', recordId: capaId, meaning: 'VERIFIED', password: 'service123' },
    })
    log('J1.17 系统账号禁签(FR-16)', `${r.status}（期望 403）${msg(r).slice(0, 38)}`)

    r = await call(`/api/capa/${capaId}/verify`, {
      method: 'POST', token: tokens.admin,
      body: {
        verificationMethod: 'DOC_REVIEW', effectiveness: 'EFFECTIVE', comment: '证据齐备，措施有效',
        signature: sig('VERIFIED', 'admin123'),
      },
    })
    log('J1.18 有效验证 + 签名(FR-6)', `${r.status} →${r.data?.status} 结论=${r.data?.effectiveness} 验证人=${r.data?.verifiedBy}`)

    r = await call(`/api/capa/${capaId}/close`, { method: 'POST', token: tokens.admin, body: {} })
    log('J1.19 关闭缺签名阻断(FR-8)', `${r.status}（期望 409）含含义提示=${/APPROVED|签名/.test(msg(r))}`)

    r = await call(`/api/capa/${capaId}/close`, {
      method: 'POST', token: tokens.admin,
      body: { comment: '闭环完成', signature: sig('APPROVED', 'admin123') },
    })
    log('J1.20 关闭成功(FR-8)', `${r.status} →${r.data?.status} closedBy=${r.data?.closedBy}`)

    r = await call(`/api/capa/${capaId}/update`, {
      method: 'POST', token: tokens.qc, body: { title: '关闭后修改' },
    })
    log('J1.21 关闭后禁改', `${r.status}（期望 409）`)

    /* ================= J3 · 电子签名 ================= */
    r = await call('/api/signatures/policy', { token: tokens.qc })
    const policies = r.data?.items ?? []
    log('J3.1 签名策略(6 条/T14)', `${r.status} 共 ${policies.length} 条 · 启用 ${policies.filter((p) => p.enabled).length} · 含义映射=${Object.keys(r.data?.meanings ?? {}).length}`)
    log('J3.2 策略明细', policies.map((p) => `${p.action}:${p.requiredMeaning}/${p.requiredRole}`).join(' '))

    // 另建一条 CAPA 专用于签名 / 篡改 / 锁定用例（上面那条已 closed 不可改）
    r = await call('/api/capa', {
      method: 'POST', token: tokens.qc,
      body: {
        sourceType: 'MANUAL', type: 'CORRECTIVE', title: '自检·签名与篡改检测',
        rootCause: '自检用根因（签名/篡改用例）', owner: 'qc', dueDate: day(20), severity: 'minor',
        tasks: [{ action: '占位行动项', owner: 'qc', dueDate: day(20) }],
      },
    })
    const sigTarget = r.data?.id
    log('J3.3 建签名用 CAPA', `${r.status} id=${sigTarget}`)

    r = await call('/api/signatures', {
      method: 'POST', token: tokens.admin,
      body: { action: 'CAPA_VERIFY', recordId: sigTarget, meaning: 'VERIFIED', password: 'admin123' },
    })
    const s1 = r.data
    log('J3.4 独立签名(FR-15)', `${r.status} id=${s1?.id} 含义=${s1?.meaningLabel} 角色=${s1?.signerRole} 版本=v${s1?.recordVersion}`)
    log('J3.5 要素完整性(SC-7)', `姓名=${!!s1?.signerName} 时间=${!!s1?.signedAt} 哈希64=${(s1?.payloadHash ?? '').length === 64} IP=${!!s1?.ip} UA=${!!s1?.userAgent} 规则=${s1?.serializeRuleVersion}`)

    r = await call(`/api/signatures/${s1?.id}/verify`, { token: tokens.admin })
    log('J3.6 未篡改校验(FR-19)', `${r.status} tampered=${r.data?.tampered} stale=${r.data?.stale} valid=${r.data?.valid}`)

    r = await call(`/api/capa/${sigTarget}/update`, {
      method: 'POST', token: tokens.qc, body: { rootCause: `自检修改根因触发修订 ${Date.now()}` },
    })
    log('J3.7 内容修订(FR-18)', `${r.status} 修订号=v${r.data?.recordRevision} 作废签名=${r.data?.voidedSignatures}`)

    r = await call(`/api/signatures/${s1?.id}/verify`, { token: tokens.admin })
    log('J3.8 篡改检出(SC-8/FR-19)', `tampered=${r.data?.tampered}（期望 true）stale=${r.data?.stale} 状态=${r.data?.status} v${r.data?.recordVersion}→v${r.data?.currentRecordVersion}`)

    r = await call(`/api/signatures?recordType=CAPA&recordId=${encodeURIComponent(sigTarget)}`, { token: tokens.qc })
    const sigRows = r.data?.items ?? []
    log('J3.9 签名清单(FR-20)', `${r.status} 共 ${sigRows.length} 条 · VALID=${sigRows.filter((s) => s.status === 'VALID').length} VOID=${sigRows.filter((s) => s.status === 'VOID').length}`)

    r = await call(`/api/signatures/export?recordType=CAPA&recordId=${encodeURIComponent(sigTarget)}&format=md`, { token: tokens.admin })
    log('J3.10 证据导出 Markdown(SC-12)', `${r.status} 条数=${r.data?.count} 含哈希表=${/SHA-256/.test(r.data?.markdown ?? '')} 文件=${r.data?.filename}`)

    r = await call(`/api/signatures/export?recordType=CAPA&recordId=${encodeURIComponent(sigTarget)}&format=json`, { token: tokens.admin })
    log('J3.11 证据导出 JSON', `${r.status} contentType=${r.data?.contentType} 条数=${r.data?.count}`)

    r = await call('/api/signatures/export?format=md', { token: tokens.qc })
    log('J3.12 导出权限(C4)', `${r.status}（期望 403）`)

    // FR-17 · 失败锁定（用 temp 账号，避免污染 admin/supervisor 的签名能力）
    const seq = []
    for (let i = 0; i < 6; i++) {
      const bad = await call('/api/signatures', {
        method: 'POST', token: tokens.temp,
        body: { action: 'CAPA_VERIFY', recordId: sigTarget, meaning: 'VERIFIED', password: 'wrong-password' },
      })
      seq.push(bad.status)
    }
    log('J3.13 失败锁定(FR-17/SC-10)', `状态序列 [${seq.join(',')}] 出现 423=${seq.includes(423)} 未放行=${!seq.includes(200)}`)

    r = await call('/api/signatures', {
      method: 'POST', token: tokens.temp,
      body: { action: 'CAPA_VERIFY', recordId: sigTarget, meaning: 'VERIFIED', password: 'temp123' },
    })
    log('J3.14 锁定期正确密码仍拒绝', `${r.status}（期望 423）${msg(r).slice(0, 38)}`)

    r = await call('/api/signatures/me/attempts', { token: tokens.temp })
    log('J3.15 账号签名锁定态', `${r.status} locked=${r.data?.locked} until=${r.data?.lockedUntil ?? '—'}`)

    r = await call('/api/audit?entityType=app_user&entityId=temp&size=50', { token: tokens.admin })
    const sigLogs = (r.data?.items ?? []).filter((x) => String(x.action).startsWith('signature.'))
    log('J3.16 锁定写审计(C3)', `${r.status} signature.*=${sigLogs.length} 含 lock=${sigLogs.some((x) => x.action === 'signature.lock')}`)

    /* ================= J2 · 内审管理 ================= */
    r = await call('/api/internal-audit', { token: tokens.admin })
    log('J2.1 内审列表(种子)', `${r.status} 共 ${r.data?.total ?? 0} 次 · 发现项 ${r.data?.summary?.findingCount ?? 0}`)
    log('J2.2 分级分布', `CRITICAL=${r.data?.summary?.bySeverity?.CRITICAL} MAJOR=${r.data?.summary?.bySeverity?.MAJOR} MINOR=${r.data?.summary?.bySeverity?.MINOR} OBS=${r.data?.summary?.bySeverity?.OBSERVATION}`)

    r = await call('/api/internal-audit', {
      method: 'POST', token: tokens.qc,
      body: { title: 'x', auditType: 'SYSTEM', lead: 'qc', planStart: day(0), planEnd: day(5) },
    })
    log('J2.3 创建内审权限(C4)', `${r.status}（期望 403）`)

    r = await call('/api/internal-audit', {
      method: 'POST', token: tokens.admin,
      body: {
        title: `自检·过程内审 ${Date.now()}`, auditType: 'PROCESS', scope: '一车间 精制工段',
        lead: 'admin', team: ['admin', 'qc'], planStart: day(0), planEnd: day(5),
      },
    })
    const auditId = r.data?.id
    log('J2.4 创建内审(FR-9)', `${r.status} id=${auditId} 编号格式=${/^AUDIT-\d{4}-\d{2}$/.test(auditId ?? '')} 状态=${r.data?.status}`)

    r = await call(`/api/internal-audit/${auditId}/findings`, {
      method: 'POST', token: tokens.qc,
      body: { severity: 'MAJOR', description: '自检发现项（缺条款）' },
    })
    log('J2.5 major 缺条款拒绝(C5)', `${r.status}（期望 400）${msg(r).slice(0, 44)}`)

    r = await call(`/api/internal-audit/${auditId}/findings`, {
      method: 'POST', token: tokens.qc,
      body: { severity: 'MAJOR', clause: 'FSSC 22000 8.9.5', description: '自检发现项：清场记录签名不完整', area: '精制工段', owner: 'supervisor' },
    })
    const majorFindingId = (r.data?.findings ?? []).find((f) => f.severity === 'MAJOR' && !f.capaId)?.id
    log('J2.6 登记 major 发现项(FR-10)', `${r.status} 发现项=${r.data?.findingCount} blocking=${r.data?.blockingFindings}`)

    r = await call(`/api/internal-audit/${auditId}/findings`, {
      method: 'POST', token: tokens.qc,
      body: { severity: 'OBSERVATION', description: '自检观察项：归档可再规范' },
    })
    log('J2.7 登记观察项', `${r.status} 发现项=${r.data?.findingCount} blocking=${r.data?.blockingFindings}（观察项不阻断）`)

    for (const t of ['in_progress', 'reported']) {
      r = await call(`/api/internal-audit/${auditId}/transition`, {
        method: 'POST', token: tokens.admin, body: { target: t },
      })
    }
    log('J2.8 推进至已出报告', `${r.status} →${r.data?.status}`)

    r = await call(`/api/internal-audit/${auditId}/close`, {
      method: 'POST', token: tokens.admin, body: { comment: '自检关闭尝试' },
    })
    log('J2.9 关闭门禁(FR-12/SC-6)', `${r.status}（期望 409）列出未处理=${/MAJOR|#\d/.test(msg(r))}`)

    r = await call(`/api/internal-audit/findings/${majorFindingId}/to-capa`, {
      method: 'POST', token: tokens.qc, body: {},
    })
    const derivedCapa = r.data?.capa?.id
    const backfilled = (r.data?.findings ?? []).find((f) => f.id === majorFindingId)?.capaId
    log('J2.10 发现项转 CAPA(FR-11)', `${r.status} CAPA=${derivedCapa} 回写=${backfilled === derivedCapa} 来源=${r.data?.capa?.sourceType} 责任人=${r.data?.capa?.owner}`)

    r = await call(`/api/internal-audit/findings/${majorFindingId}/to-capa`, {
      method: 'POST', token: tokens.qc, body: {},
    })
    log('J2.11 重复转办拒绝', `${r.status}（期望 409）`)

    r = await call(`/api/internal-audit/${auditId}/close`, {
      method: 'POST', token: tokens.admin, body: { comment: '问题项已转办，内审关闭' },
    })
    log('J2.12 关闭内审(FR-12 通过)', `${r.status} →${r.data?.status} closedBy=${r.data?.closedBy}`)

    r = await call('/api/internal-audit/summary', { token: tokens.admin })
    log('J2.13 年度统计(FR-13)', `${r.status} 内审=${r.data?.auditCount} 发现项=${r.data?.findingCount} 转化率=${r.data?.capaConversionRate}% 未转办major+=${r.data?.openMajorFindings}`)

    /* ================= J4 · 受控动作集成 ================= */
    r = await call('/api/batches?size=50', { token: tokens.admin })
    const batch = (r.data?.batches ?? []).find(
      (b) => !b.released && !b.deviationNo && (b.status === 'waiting' || b.status === 'done'),
    )
    if (batch) {
      const noSig = await call('/api/quality/release', {
        method: 'POST', token: tokens.qc, body: { batchId: batch.id },
      })
      log('J4.1 放行缺签名阻断(FR-21)', `${noSig.status}（期望 409）含含义提示=${/APPROVED|签名/.test(msg(noSig))}`)

      const withSig = await call('/api/quality/release', {
        method: 'POST', token: tokens.qc,
        body: { batchId: batch.id, signature: sig('APPROVED', 'qc12345') },
      })
      log('J4.2 放行带签名(SC-11)', `${withSig.status} 批次=${batch.id} COA=${withSig.data?.coaNo ?? '—'} 说明=${msg(withSig).slice(0, 46)}`)
    } else {
      skip('J4.1/J4.2 放行签名门禁', '无「未放行且无偏差」的可用批次')
    }

    // FR-21 · 配方生效签名门禁
    r = await call('/api/recipes', { token: tokens.admin })
    const recipeCode = ((r.data?.recipes ?? r.data?.items) ?? [])[0]?.code
    if (recipeCode) {
      const vres = await call(`/api/recipes/${recipeCode}/versions`, { token: tokens.admin })
      const target = (vres.data ?? []).find((v) => ['pending', 'draft', 'obsolete'].includes(v.status))
      if (target) {
        const noSig = await call(
          `/api/recipes/${recipeCode}/versions/${encodeURIComponent(target.version)}/activate`,
          { method: 'POST', token: tokens.admin, body: { comment: '自检' } },
        )
        log('J4.3 配方生效缺签名(FR-21)', `${noSig.status}（期望 409）含含义提示=${/APPROVED|签名/.test(msg(noSig))}`)
      } else {
        skip('J4.3 配方生效签名门禁', `配方 ${recipeCode} 无可生效版本`)
      }
    } else {
      skip('J4.3 配方生效签名门禁', '未取到配方编号')
    }

    /* ---- FR-7 · 偏差关闭：CAPA 未关闭阻断 ---- */
    const ccp = (await call('/api/haccp/points', { token: tokens.admin })).data?.points?.[0]
    let deviationId = null
    if (ccp) {
      const rec = await call('/api/haccp/records', {
        method: 'POST', token: tokens.operator,
        body: { ccpCode: ccp.code, value: Number(ccp.clMax) + 10 },
      })
      deviationId = rec.data?.deviationId
      log('J4.4 构造偏差单(CCP 偏离)', `${rec.status} 偏差单=${deviationId ?? '—'} 报警=${rec.data?.alarmId ?? '—'}`)
    }

    if (deviationId) {
      for (const t of ['investigating', 'capa']) {
        r = await call(`/api/quality/deviations/${deviationId}/transition`, {
          method: 'POST', token: tokens.supervisor, body: { target: t, rootCause: '自检用根因：仪表漂移' },
        })
      }
      log('J4.5 偏差推进至 capa', `${r.status} →${r.data?.status}`)

      r = await call('/api/capa', {
        method: 'POST', token: tokens.qc,
        body: {
          sourceType: 'DEVIATION', sourceId: deviationId, type: 'CORRECTIVE',
          title: '自检·偏差关联 CAPA', rootCause: '自检用根因', owner: 'supervisor',
          dueDate: day(15), severity: 'major',
          tasks: [{ action: '完成纠正措施', owner: 'supervisor', dueDate: day(10) }],
        },
      })
      const devCapaId = r.data?.id
      log('J4.6 偏差关联 CAPA', `${r.status} id=${devCapaId} 来源=${r.data?.sourceType}/${r.data?.sourceId}`)

      r = await call(`/api/quality/deviations/${deviationId}/transition`, {
        method: 'POST', token: tokens.supervisor,
        body: { target: 'closed', rootCause: '仪表漂移', signature: sig('APPROVED', 'super123') },
      })
      log('J4.7 未关闭 CAPA 阻断偏差(FR-7)', `${r.status}（期望 409）提示含 CAPA 编号=${msg(r).includes(devCapaId ?? '__')}`)

      const dtasks = (await call(`/api/capa/${devCapaId}`, { token: tokens.qc })).data?.tasks ?? []
      for (const t of dtasks) {
        await call(`/api/capa/tasks/${t.id}/done`, { method: 'POST', token: tokens.qc, body: { evidence: '自检' } })
      }
      await call(`/api/capa/${devCapaId}/transition`, { method: 'POST', token: tokens.qc, body: { target: 'in_progress' } })
      await call(`/api/capa/${devCapaId}/transition`, { method: 'POST', token: tokens.qc, body: { target: 'pending_verify' } })
      await call(`/api/capa/${devCapaId}/verify`, {
        method: 'POST', token: tokens.admin,
        body: { verificationMethod: 'DATA_REVIEW', effectiveness: 'EFFECTIVE', comment: '自检', signature: sig('VERIFIED', 'admin123') },
      })
      r = await call(`/api/capa/${devCapaId}/close`, {
        method: 'POST', token: tokens.admin, body: { comment: '自检闭环', signature: sig('APPROVED', 'admin123') },
      })
      log('J4.8 关闭关联 CAPA', `${r.status} →${r.data?.status}`)

      r = await call(`/api/quality/deviations/${deviationId}/transition`, {
        method: 'POST', token: tokens.supervisor,
        body: { target: 'closed', rootCause: '仪表漂移', signature: sig('APPROVED', 'super123') },
      })
      log('J4.9 CAPA 关闭后偏差可关(FR-7)', `${r.status} →${r.data?.status} closedBy=${r.data?.closedBy}`)

      // FR-21 · 偏差关闭缺签名（另建一条偏差，避免第一条已 closed）
      const rec2 = await call('/api/haccp/records', {
        method: 'POST', token: tokens.operator,
        body: { ccpCode: ccp.code, value: Number(ccp.clMax) + 12 },
      })
      const dev2 = rec2.data?.deviationId
      if (dev2) {
        for (const t of ['investigating', 'capa']) {
          await call(`/api/quality/deviations/${dev2}/transition`, {
            method: 'POST', token: tokens.supervisor, body: { target: t, rootCause: '自检根因 2' },
          })
        }
        const noSig = await call(`/api/quality/deviations/${dev2}/transition`, {
          method: 'POST', token: tokens.supervisor, body: { target: 'closed', rootCause: '自检根因 2' },
        })
        log('J4.10 偏差关闭缺签名(FR-21)', `${noSig.status}（期望 409）含含义提示=${/APPROVED|签名/.test(msg(noSig))}`)

        // 带签名收尾：保持终态，避免残留未关闭偏差影响重复运行
        const closed = await call(`/api/quality/deviations/${dev2}/transition`, {
          method: 'POST', token: tokens.supervisor,
          body: { target: 'closed', rootCause: '自检根因 2', signature: sig('APPROVED', 'super123') },
        })
        log('J4.11 带签名关闭收尾', `${closed.status} →${closed.data?.status}`)
      } else {
        skip('J4.10/J4.11 偏差关闭签名门禁', '第二条偏差单未生成')
      }
    } else {
      skip('J4.4 ~ J4.11 偏差/CAPA 联动', 'CCP 点或偏差单不可用')
    }

    /* ================= 汇总 ================= */
    console.log(out.join('\n'))
    const mismatched = out.filter((l) => {
      const m = l.match(/(\d{3})（期望 (\d{3})）/)
      return m && m[1] !== m[2]
    })
    const unverified = out.filter((l) => /（期望 \d{3}）/.test(l)).length
    console.log(
      `\n共 ${out.length} 项检查 · 跳过 ${skipped} 项 · 带「期望」断言 ${unverified} 项 · ` +
        `断言不符 ${mismatched.length} 项`,
    )
    mismatched.forEach((l) => console.log('  ✗ ' + l))
    if (mismatched.length) process.exit(1)
  } catch (e) {
    console.log(out.join('\n'))
    console.error('\n自检异常终止：', e.message)
    process.exit(1)
  }
})()
