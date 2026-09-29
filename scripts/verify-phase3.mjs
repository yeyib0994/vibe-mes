/**
 * FluxMES Phase G（P3 增强）端到端自检。
 * 前置：PostgreSQL 已启动且后端已运行（java -jar target/api-java-0.2.0.jar）。
 * 用法：node scripts/verify-phase3.mjs
 *
 * 覆盖：G1 报警 SLA 可配置 / G2 人员资质与健康证门禁 / G3 称量容差校验 / G4 多厂区。
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
const log = (k, v) => out.push(`${String(k).padEnd(30)} ${v}`)

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
    log('0.登录(5 账号)', Object.values(tokens).filter(Boolean).length + '/5 成功')

    /* ================= G1 · 报警 SLA 可配置 ================= */
    let r = await call('/api/alarms/sla/policy', { token: tokens.admin })
    const policy = r.data ?? []
    log('G1.1 SLA 政策列表', `${r.status} ${policy.map((p) => `${p.level}=${p.minutes}min`).join(' ')}`)

    const before = policy.find((p) => p.level === 'minor')
    r = await call('/api/alarms/sla/policy/minor', {
      method: 'PUT', token: tokens.operator, body: { minutes: 45 },
    })
    log('G1.2 工艺员改政策', `${r.status}（期望 403 角色不足）`)

    r = await call('/api/alarms/sla/policy/minor', {
      method: 'PUT', token: tokens.supervisor, body: { minutes: 45, note: '自检临时调整' },
    })
    log('G1.3 值班长改政策', `${r.status} minor=${r.data?.minutes} 生效=${r.data?.effectiveMinutes}`)

    r = await call('/api/alarms/stats', { token: tokens.admin })
    log('G1.4 统计反映新政策', `${r.status} slaPolicy.minor=${r.data?.slaPolicy?.minor}`)

    r = await call('/api/alarms/sla/policy/minor', {
      method: 'PUT', token: tokens.supervisor,
      body: { minutes: before?.minutes ?? 30, enabled: true },
    })
    log('G1.5 还原 minor 政策', `${r.status} minor=${r.data?.minutes}`)

    /* ================= G2 · 人员资质与健康证 ================= */
    r = await call('/api/personnel/summary', { token: tokens.admin })
    log('G2.1 资质看板', `${r.status} 证书=${r.data?.totalCertificates} 健康证=${r.data?.healthCertificates} 资质=${r.data?.qualifications} 健康证强制=${r.data?.healthCertEnforced}`)

    r = await call('/api/personnel/capabilities', { token: tokens.admin })
    const caps = r.data ?? []
    log('G2.2 能力项字典', `${r.status} 共 ${caps.length} 项，强制=${caps.filter((c) => c.enforced).length}`)

    r = await call('/api/personnel/alerts?days=30', { token: tokens.admin })
    log('G2.3 到期预警', `${r.status} 已过期=${r.data?.expiredCount} 临期=${r.data?.expiringCount}`)

    r = await call('/api/haccp/points', { token: tokens.admin })
    const ccp = r.data?.points?.[0]
    if (ccp) {
      const ok = await call('/api/haccp/records', {
        method: 'POST', token: tokens.operator,
        body: { ccpCode: ccp.code, value: (Number(ccp.clMin) + Number(ccp.clMax)) / 2 },
      })
      log('G2.4 有资质人员录 CCP', `${ok.status} inLimit=${ok.data?.inLimit}`)

      const denied = await call('/api/haccp/records', {
        method: 'POST', token: tokens.temp,
        body: { ccpCode: ccp.code, value: (Number(ccp.clMin) + Number(ccp.clMax)) / 2 },
      })
      log('G2.5 无资质人员录 CCP', `${denied.status}（期望 403 资质门禁）${denied.data?.message ? ' · ' + denied.data.message : ''}`)
    }

    r = await call('/api/personnel/certificates', {
      method: 'POST', token: tokens.qc,
      body: { username: 'operator', certType: 'HEALTH', certName: '食品从业人员健康证' },
    })
    log('G2.6 质检员发证', `${r.status}（期望 403 需值班长）`)

    /* ================= G3 · 称量容差校验 ================= */
    const bl = await call('/api/batches?size=5', { token: tokens.admin })
    const target = bl.data?.batches?.[0]?.id
    r = await call('/api/weighing/summary', { token: tokens.admin })
    log('G3.1 称量看板', `${r.status} 任务=${r.data?.taskCount} 称量=${r.data?.weighCount} 合格率=${r.data?.passRatePercent}% 超差=${r.data?.outOfToleranceCount} 待复核=${r.data?.pendingReviewCount}`)

    if (target) {
      const t = await call('/api/weighing/tasks', {
        method: 'POST', token: tokens.operator,
        body: { batchId: target, materialCode: 'RM-001', materialName: '玉米淀粉', targetQty: 500, tolerancePct: 1 },
      })
      log('G3.2 创建称量任务', `${t.status} ${t.data?.id ?? '—'} 目标=${t.data?.targetQty}±${t.data?.tolerancePct}%`)
      const taskId = t.data?.id

      if (taskId) {
        // 先做超差称量（触发偏差单 + 报警 + 任务阻断），再复核，最后补一次合格称量收尾
        const over = await call(`/api/weighing/tasks/${taskId}/weigh`, {
          method: 'POST', token: tokens.operator, body: { actualQty: 530 },
        })
        log('G3.3 超差称量(+6%)', `${over.status} result=${over.data?.result} 偏差=${over.data?.deviationPct}% 偏差单=${over.data?.deviationId ?? '—'} 报警=${over.data?.alarmId ?? '—'}`)

        r = await call(`/api/weighing/tasks?batchId=${target}`, { token: tokens.admin })
        log('G3.4 超差后任务状态', `${r.status} status=${r.data?.[0]?.status}（期望 BLOCKED）`)

        if (over.data?.id) {
          const self = await call(`/api/weighing/items/${over.data.id}/review`, {
            method: 'POST', token: tokens.operator,
          })
          log('G3.5 称量人自复核', `${self.status}（期望 403 角色不足）`)
          const rev = await call(`/api/weighing/items/${over.data.id}/review`, {
            method: 'POST', token: tokens.qc,
          })
          log('G3.6 QC 复核超差', `${rev.status} reviewer=${rev.data?.reviewer ?? '—'}`)
        }

        const pass = await call(`/api/weighing/tasks/${taskId}/weigh`, {
          method: 'POST', token: tokens.operator, body: { actualQty: 501.5, equipment: 'SCALE-M201' },
        })
        log('G3.7 复核后合格称量', `${pass.status} result=${pass.data?.result} 偏差=${pass.data?.deviationPct}%`)

        r = await call(`/api/weighing/tasks?batchId=${target}`, { token: tokens.admin })
        log('G3.8 达标后任务状态', `${r.status} status=${r.data?.[0]?.status}（期望 DONE）`)
      }
    }

    const denied = await call('/api/weighing/tasks', {
      method: 'POST', token: tokens.temp,
      body: { batchId: target, targetQty: 100 },
    })
    log('G3.9 建任务不校验称量资质', `${denied.status}（期望 200，称量登记时才校验）`)

    /* ================= G4 · 多厂区 ================= */
    r = await call('/api/dashboard/sites', { token: tokens.admin })
    const sites = r.data?.sites ?? []
    log('G4.1 厂区主数据', `${r.status} 共 ${sites.length} 个：${sites.map((s) => s.code).join(', ')}`)

    r = await call('/api/batches?site=SITE-01&size=5', { token: tokens.admin })
    log('G4.2 批次按厂区过滤', `${r.status} SITE-01 批次 ${r.data?.total ?? 0} 个`)

    r = await call('/api/batches?site=SITE-02&size=5', { token: tokens.admin })
    log('G4.3 滨海工厂批次', `${r.status} SITE-02 批次 ${r.data?.total ?? 0} 个`)

    r = await call('/api/dashboard/cockpit?site=SITE-01', { token: tokens.admin })
    log('G4.4 驾驶舱按厂区', `${r.status} site=${r.data?.site} 日产量=${r.data?.kpis?.dailyOutput?.actual} OEE=${r.data?.kpis?.oee?.value}`)

    r = await call('/api/dashboard/lines?site=SITE-02', { token: tokens.admin })
    log('G4.5 滨海产线', `${r.status} 共 ${r.data?.lines?.length ?? 0} 条`)

    r = await call('/api/auth/me', { token: tokens.temp })
    log('G4.6 token 携带厂区', `${r.status} user=${r.data?.username} site=${r.data?.site ?? '—'}`)

    r = await call('/v3/api-docs', { token: tokens.admin })
    const paths = Object.keys(r.data?.paths ?? {})
    log('G6 OpenAPI 路径数', `${r.status} 共 ${paths.length} 条`)
  } catch (e) {
    log('异常', e?.message ?? String(e))
  }

  console.log('\n=== FluxMES Phase G（P3）自检 ===')
  out.forEach((l) => console.log(l))
})()
