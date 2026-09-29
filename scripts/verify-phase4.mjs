/**
 * FluxMES Phase H（三份遗留 Spec 模块落地）端到端自检。
 * 前置：PostgreSQL 已启动且后端已运行（java -jar target/api-java-0.2.0.jar）。
 * 用法：node scripts/verify-phase4.mjs
 *
 * 覆盖：H1 设备管理与校准阻断 / H2 配方版本受控 / H3 追溯闭环。
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
const log = (k, v) => out.push(`${String(k).padEnd(32)} ${v}`)

;(async () => {
  try {
    for (const [u, p] of [
      ['admin', 'admin123'],
      ['supervisor', 'super123'],
      ['qc', 'qc12345'],
      ['operator', 'op12345'],
    ]) {
      const r = await call('/api/auth/login', { method: 'POST', body: { username: u, password: p } })
      tokens[u] = r.data?.token ?? ''
    }
    log('0.登录(4 账号)', Object.values(tokens).filter(Boolean).length + '/4 成功')

    /* ================= H1 · 设备管理 ================= */
    let r = await call('/api/equipment', { token: tokens.admin })
    const fleetAll = r.data?.equipment ?? []
    log('H1.1 设备台账(PG 持久化)', `${r.status} 共 ${fleetAll.length} 台 · ${r.data?.summary?.total} 开票计数`)
    log('H1.2 台账字段已落库', `status=${fleetAll[0]?.status} criticality=${fleetAll[0]?.criticality} category=${fleetAll[0]?.category ?? '—'}`)

    r = await call('/api/equipment?status=RUNNING', { token: tokens.admin })
    log('H1.3 按状态筛选', `${r.status} RUNNING ${(r.data?.equipment ?? []).length} 台`)

    r = await call('/api/equipment?keyword=发酵罐', { token: tokens.admin })
    log('H1.4 关键字检索', `${r.status} 命中 ${(r.data?.equipment ?? []).length} 台`)

    r = await call('/api/equipment/alerts?withinDays=30', { token: tokens.admin })
    log('H1.5 保养/校准预警', `${r.status} 保养逾期=${r.data?.maintenanceOverdue} 到期=${r.data?.maintenanceDue} 校准超期=${r.data?.calibrationExpired}`)

    const expired = (r.data?.calibration ?? [])[0]
    log('H1.6 校准超期样例', expired ? `${expired.code} ${expired.calibrationItem} 超期 ${expired.daysOverdue} 天` : '—')

    // T8 · 校准阻断：以超期设备建批次应被拒绝
    if (expired) {
      r = await call('/api/batches', {
        method: 'POST', token: tokens.supervisor,
        body: {
          product: '一水柠檬酸', recipe: 'R-CA-07', equipment: expired.code,
          planYield: 5, materialLots: ['LOT-001'], line: 'LINE-1', force: true,
        },
      })
      log('H1.7 超期设备建批次', `${r.status}（期望 409 校准阻断）${typeof r.data === 'string' ? '' : ' ' + (r.data?.message ?? '')}`)
    }

    r = await call('/api/equipment/available', { token: tokens.admin })
    const avail = r.data ?? []
    log('H1.8 可用设备清单(FR-10)', `${r.status} ${avail.length} 台（已剔除超期与故障）`)

    const target = avail[0]?.code ?? 'F-101'
    r = await call(`/api/equipment/${target}`, { token: tokens.admin })
    log('H1.9 设备详情聚合', `${r.status} 工单=${(r.data?.maintenanceOrders ?? []).length} 事件=${(r.data?.events ?? []).length} 报警=${(r.data?.alarms ?? []).length} 在制=${(r.data?.activeBatches ?? []).length}`)

    r = await call(`/api/equipment/${target}/oee`, { token: tokens.admin })
    log('H1.10 OEE 三因子(FR-9)', `${r.status} 可用=${r.data?.availability}% 性能=${r.data?.performance}% 良品=${r.data?.quality}% OEE=${r.data?.oee}%`)

    // T5 · 状态变更
    const origStatus = fleetAll.find((e) => e.code === target)?.status
    r = await call(`/api/equipment/${target}/status`, {
      method: 'POST', token: tokens.qc, body: { toStatus: 'IDLE' },
    })
    log('H1.11 质检员改状态', `${r.status}（QC ≥ OPERATOR，允许；期望 200）`)

    r = await call(`/api/equipment/${target}/status`, {
      method: 'POST', token: tokens.operator, body: { toStatus: 'BAD_STATUS' },
    })
    log('H1.12 非法目标状态', `${r.status}（期望 400）`)

    r = await call(`/api/equipment/${target}/status`, {
      method: 'POST', token: tokens.operator,
      body: { toStatus: origStatus === 'MAINTENANCE' ? 'IDLE' : 'MAINTENANCE', reason: '自检：预防性维护' },
    })
    log('H1.13 工艺员改状态', `${r.status} ${r.data?.fromStatus}→${r.data?.toStatus}`)

    r = await call(`/api/equipment/${target}/status`, {
      method: 'POST', token: tokens.operator,
      body: { toStatus: origStatus === 'MAINTENANCE' ? 'IDLE' : 'MAINTENANCE' },
    })
    log('H1.14 重复同状态', `${r.status}（期望 409）`)

    r = await call(`/api/equipment/${target}/status`, {
      method: 'POST', token: tokens.operator, body: { toStatus: origStatus ?? 'RUNNING', reason: '自检还原' },
    })
    log('H1.15 还原设备状态', `${r.status} →${r.data?.toStatus}`)

    // T7 · 维护工单
    r = await call('/api/equipment/maintenance-orders', {
      method: 'POST', token: tokens.operator,
      body: { equipmentCode: target, type: 'PREVENTIVE', remark: '自检工单' },
    })
    log('H1.16 工艺员建工单', `${r.status}（期望 403 需值班长+）`)

    r = await call('/api/equipment/maintenance-orders', {
      method: 'POST', token: tokens.supervisor,
      body: { equipmentCode: target, type: 'PREVENTIVE', remark: '自检：保养' },
    })
    const orderId = r.data?.id
    log('H1.17 值班长建工单', `${r.status} ${orderId} 计划=${r.data?.planDate}`)

    if (orderId) {
      r = await call(`/api/equipment/maintenance-orders/${orderId}/done`, {
        method: 'POST', token: tokens.supervisor, body: { runtimeHours: 6500 },
      })
      log('H1.18 完成工单顺延保养', `${r.status} 下次=${r.data?.equipment?.maint?.next} 运行=${r.data?.equipment?.runHours}h`)

      r = await call(`/api/equipment/maintenance-orders/${orderId}/done`, {
        method: 'POST', token: tokens.supervisor,
      })
      log('H1.19 重复完成工单', `${r.status}（期望 409）`)
    }

    // 校准工单闭环：为超期设备建 CALIBRATION 工单并完成，解除门禁
    if (expired) {
      r = await call('/api/equipment/maintenance-orders', {
        method: 'POST', token: tokens.supervisor,
        body: { equipmentCode: expired.code, type: 'CALIBRATION', remark: '自检：恢复校准' },
      })
      const calId = r.data?.id
      if (calId) {
        r = await call(`/api/equipment/maintenance-orders/${calId}/done`, {
          method: 'POST', token: tokens.supervisor,
        })
        log('H1.20 校准工单解除门禁', `${r.status} 新有效期=${r.data?.equipment?.calibration?.dueAt} 超期=${r.data?.equipment?.calibration?.expired}`)
      }
    }

    r = await call('/api/equipment/maintenance-orders', { token: tokens.admin })
    log('H1.21 工单列表', `${r.status} ${(r.data ?? []).length} 单`)

    /* ================= H2 · 配方版本受控 ================= */
    r = await call('/api/recipes', { token: tokens.admin })
    const recipeList = r.data?.recipes ?? []
    log('H2.1 配方台账', `${r.status} ${recipeList.length} 条 · 待审批=${r.data?.summary?.pendingApproval}`)

    const source = recipeList.find((x) => x.status === 'active') ?? recipeList[0]
    const code = source?.code ?? 'R-CA-07'
    log('H2.2 版本自动锁定', `${code} recipeVersion=${source?.version} versionRole=${source?.versionRole ?? '—'}`)

    r = await call(`/api/recipes/${code}/versions`, { token: tokens.admin })
    const vers = r.data ?? []
    log('H2.3 版本列表(PG)', `${r.status} ${vers.length} 版 · ${vers.map((v) => v.version + ':' + v.status).join(' ')}`)

    r = await call(`/api/recipes/${code}/history`, { token: tokens.admin })
    log('H2.4 版本历史契约', `${r.status} ${(r.data ?? []).length} 条 字段=${Object.keys((r.data ?? [])[0] ?? {}).slice(0, 4).join(',')}`)

    r = await call(`/api/recipes/${code}/steps?version=${encodeURIComponent(source?.version ?? '')}`, { token: tokens.admin })
    log('H2.5 工序参数容差', `${r.status} ${(r.data ?? []).length} 项 CCP=${(r.data ?? []).filter((s) => s.ccp).length}`)

    // FR-4 建草稿
    r = await call(`/api/recipes/${code}/draft`, {
      method: 'POST', token: tokens.qc, body: { changeNote: '自检草稿' },
    })
    log('H2.6 质检员建草稿', `${r.status}（QC ≥ OPERATOR，允许；期望 200）`)

    r = await call(`/api/recipes/${code}/draft`, {
      method: 'POST', token: tokens.operator,
      // 变更 CCP 参数（灭菌温度）以触发「需重评检验方法」判定
      body: { changeNote: '自检：收紧灭菌温度下限', steps: [{ name: '灭菌温度', lo: 122, hi: 124 }] },
    })
    const draftVer = r.data?.version
    log('H2.7 工艺员建草稿', `${r.status} ${draftVer} 源自 ${r.data?.sourceVersion}`)

    if (draftVer) {
      // FR-5 提交
      r = await call(`/api/recipes/${code}/versions/${draftVer}/submit`, {
        method: 'POST', token: tokens.operator,
      })
      log('H2.8 提交审批', `${r.status} ${r.data?.status}`)

      r = await call(`/api/recipes/${code}/versions/${draftVer}/submit`, {
        method: 'POST', token: tokens.operator,
      })
      log('H2.9 重复提交', `${r.status}（期望 409 非法流转）`)

      // FR-6 审批（非管理员应被拒）
      r = await call(`/api/recipes/${code}/versions/${draftVer}/approve`, {
        method: 'POST', token: tokens.supervisor, body: { approved: true },
      })
      log('H2.10 值班长审批', `${r.status}（期望 403 需管理员）`)

      r = await call(`/api/recipes/${code}/versions/${draftVer}/approve`, {
        method: 'POST', token: tokens.admin,
        body: { approved: true, comment: '自检通过', signature: { meaning: 'APPROVED', password: 'admin123' } },
      })
      log('H2.11 管理员审批生效', `${r.status} 版本=${r.data?.version} 停用旧版=${(r.data?.deactivated ?? []).join(',') || '—'}`)

      r = await call(`/api/recipes/${code}/versions`, { token: tokens.admin })
      const effectiveCount = (r.data ?? []).filter((v) => v.status === 'effective').length
      log('H2.12 生效唯一性(NFR-3)', `${r.status} effective=${effectiveCount}（期望 1）`)

      // FR-7 影响面
      r = await call(`/api/recipes/${code}/impact`, { token: tokens.admin })
      log('H2.13 变更影响分析', `${r.status} 受影响批次=${r.data?.affectedBatchCount} 需重评=${r.data?.reviewRequired}`)

      // 还原：把原生效版本再切回（Phase I · FR-21 受控动作需电子签名）
      r = await call(`/api/recipes/${code}/versions/${encodeURIComponent(source?.version ?? '')}/activate`, {
        method: 'POST', token: tokens.admin,
        body: { comment: '自检还原', signature: { meaning: 'APPROVED', password: 'admin123' } },
      })
      log('H2.14 还原生效版本', `${r.status} →${r.data?.version}`)
    }

    // FR-8 批次建单引用失效版本应被拒
    r = await call(`/api/recipes/${code}/versions`, { token: tokens.admin })
    const obsolete = (r.data ?? []).find((v) => v.status === 'obsolete')
    if (obsolete) {
      r = await call('/api/batches', {
        method: 'POST', token: tokens.supervisor,
        body: {
          product: '一水柠檬酸', recipe: code, recipeVersion: obsolete.version,
          equipment: avail[0]?.code ?? 'F-101', planYield: 5,
          materialLots: ['LOT-001'], line: 'LINE-1', force: true,
        },
      })
      log('H2.15 引用停用版本建单', `${r.status}（期望 400 FR-8）`)
    }

    /* ================= H3 · 追溯闭环 ================= */
    r = await call('/api/trace/targets', { token: tokens.admin })
    const traceBatches = r.data?.batches ?? []
    log('H3.1 可追溯批次候选', `${r.status} ${traceBatches.length} 个 · 数据源=${traceBatches[0]?.source ?? '—'}`)

    // 优先选一个已登记投料的批次，才能验证完整链路（而非只有设备/检验节点）
    r = await call('/api/trace/backward?lotNo=LOT-001', { token: tokens.admin })
    const fedBatch = (r.data?.batches ?? [])[0]?.batchId
    const tb = traceBatches.find((b) => b.id === fedBatch) ?? { id: fedBatch }
    if (tb?.id) {
      r = await call(`/api/trace/chain/${encodeURIComponent(tb.id)}`, { token: tokens.admin })
      const chain = r.data?.chain ?? null
      log('H3.2 正向链路(真实数据)', `${r.status} 节点=${(chain?.forward ?? []).length} 层级=${r.data?.meta?.levelCount} 完整=${r.data?.meta?.integrity}`)
      log('H3.3 横向关联(FR-5)', `报警=${(r.data?.issues?.alarms ?? []).length} 偏差=${(r.data?.issues?.deviations ?? []).length} 工序=${r.data?.issues?.stepCount ?? 0}`)

      r = await call(`/api/trace/${encodeURIComponent(tb.id)}/completeness`, { token: tokens.admin })
      log('H3.4 完整性校验(FR-8)', `${r.status} 完整=${r.data?.complete} 缺失=${r.data?.missingCount ?? 0} ${(r.data?.missing ?? []).slice(0, 2).join('；')}`)

      r = await call(`/api/trace/${encodeURIComponent(tb.id)}/export`, { token: tokens.admin })
      const md = typeof r.data?.content === 'string' ? r.data.content : ''
      log('H3.5 报表导出(FR-6)', `${r.status} ${r.data?.fileName ?? '—'} ${md.length} 字符 · 含水印=${md.includes('操作人')}`)
    }

    r = await call('/api/trace/backward?lotNo=LOT-001', { token: tokens.admin })
    log('H3.6 逆向追溯(FR-3)', `${r.status} 原料=${r.data?.material?.name ?? '—'} 消耗批次=${r.data?.batchCount ?? 0}`)

    r = await call('/api/trace/impact?lotNo=LOT-001', { token: tokens.qc })
    log('H3.7 影响面分析(FR-4)', `${r.status} 批次=${r.data?.affectedBatchCount ?? 0} 已放行=${r.data?.releasedCount ?? 0} 产量=${r.data?.totalYieldT ?? 0}t`)

    r = await call('/api/trace/logs?limit=20', { token: tokens.admin })
    log('H3.8 追溯查询审计(FR-7)', `${r.status} ${(r.data ?? []).length} 条 · 最近=${(r.data ?? [])[0]?.queryType ?? '—'} ${(r.data ?? [])[0]?.queryKey ?? ''}`)

    r = await call('/v3/api-docs', { token: tokens.admin })
    log('H3.9 OpenAPI 契约', `${r.status} 版本=${r.data?.info?.version} 路径数=${Object.keys(r.data?.paths ?? {}).length}`)

    console.log(out.join('\n'))
  } catch (e) {
    console.log(out.join('\n'))
    console.error('\n验证中断：', e.message)
    process.exit(1)
  }
})()
