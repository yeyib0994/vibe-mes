# 质量体系 RegTech · 任务清单（tasks）

- **模块**：quality-regtech
- **依赖**：`spec.md`、`plan.md`
- **版本**：v1.1 · 2026-09-15（实现完成，自检脚本已就绪）
- 图例：✅ 已完成 · ◐ 部分完成 · ⬜ 待办；优先级 P0/P1/P2

## P0 · CAPA 闭环

- ✅ **T1** 建表 `capa` / `capa_task` / `capa_event`（schema-p6.sql，幂等）
  - 验收：启动自动建表；重复启动不报错 —— `schema-p6.sql` 全部 `IF NOT EXISTS`，并挂入
    `spring.sql.init.schema-locations` 第 6 项
- ✅ **T2** 实体 + Mapper（`@Mapper` 注解必备）
  - 验收：`bash scripts/mvn.sh compile` 通过 —— `Capa` / `CapaTask` / `CapaEvent` + 3 Mapper
- ✅ **T3** `POST /api/capa`：支持 4 类来源与 3 种类型，必填根因与 ≥1 条行动项（FR-1/FR-2）
  - 验收：无根因或无行动项返回 400；编号形如 `CAPA-260914-001`
  - 实现：`CapaService#create`，编号 `CAPA-<yyMMdd>-<seq>`（三位序号）
- ✅ **T4** 状态机流转 + `capa_event` 流水 + 审计（FR-3）
  - 验收：非法流转 409；`/api/audit` 可查前后值
  - 实现：`open→in_progress→pending_verify`；`rejected→in_progress`；`/verify`、`/close` 走独立端点
- ✅ **T5** 行动项门禁：有未完成项不得进入 `pending_verify`（FR-4）
  - 验收：返回 400 且列出未完成行动项编号
- ✅ **T6** `POST /api/capa/{id}/verify`：验证方式 + 有效性结论；无效退回重开行动项（FR-6）
  - 验收：无效退回后状态为 `in_progress`，原因可查
  - 实现：无效时状态置 `rejected`（可查退回原因）并重开全部已完成的行动项，
    再经 `/transition` 回到 `in_progress`（与 spec §3.1 的 `rejected → in_progress` 一致）
- ✅ **T7** 阻断门禁：偏差存在未关闭 CAPA 时，偏差关闭与批次放行 409（FR-7）
  - 验收：响应含未关闭 CAPA 编号列表
  - 实现：`CapaService#assertDeviationCloseAllowed` / `#assertBatchReleaseAllowed`，
    由 `QualityService#transitionDeviation` / `#release` 调用
- ✅ **T8** 临期 / 逾期预警 + 报警联动（FR-5）
  - 验收：构造到期数据，列表出现临期/逾期标记，major 级产生报警
  - 实现：`GET /api/capa/overdue` 返回 overdue/dueSoon/byOwner，逾期 critical|major 触发报警并
    以「同 source+content 未确认去重」防止刷屏

## P1 · 内审管理

- ✅ **T9** 建表 `internal_audit` / `audit_finding`
  - 验收：表与索引建立
- ✅ **T10** 内审计划 CRUD 与状态流转（FR-9）
  - 验收：创建/推进/关闭可用，非法流转 409
  - 实现：编号 `AUDIT-<yyyy>-<nn>`；`planned→in_progress→reported→closed` 逐级推进
- ✅ **T11** 发现项登记 + 分级 + 条款关联（FR-10）
  - 验收：4 级分级可存；条款号可查
  - 实现：critical / major **强制**要求条款号（C5 依据标注）
- ✅ **T12** 发现项一键转 CAPA，默认责任人 = 区域负责人（FR-11）
  - 验收：生成 CAPA 且 `source_type=AUDIT_FINDING`，回写 `audit_finding.capa_id`
  - 实现：`POST /api/internal-audit/findings/{id}/to-capa`，责任人取 发现项 owner → 审核组长 → 当前用户
- ✅ **T13** 内审关闭门禁：未生成 CAPA 的 critical/major 发现项阻断关闭（FR-12）
  - 验收：返回 409 且列出未处理发现项

## P1 · 电子签名

- ✅ **T14** 建表 `signature_policy` / `e_signature` / `signature_attempt`
  - 验收：表建立；`signature_policy` 预置 6 条策略 —— BATCH_RELEASE / DEVIATION_CLOSE /
    CAPA_CLOSE / CAPA_VERIFY / RECIPE_ACTIVATE / EBR_REVIEW
- ✅ **T15** canonical JSON 序列化 + SHA-256 哈希（规则版本 v1，字段白名单）
  - 验收：仅改 `updated_at` 不触发哈希变化；改业务字段触发
  - 实现：`SignatureService#projection` 按记录类型给出白名单投影（排除 `updated_at` /
    `record_revision` 与受控动作写入的处置字段），`#canonicalJson` 固定顺序、无空白，
    `#sha256Hex` 用 JDK `MessageDigest`（不引第三方哈希库）
- ✅ **T16** `POST /api/signatures`：密码二次确认 + 含义声明 + 角色校验 + 要素落库（FR-14~FR-16）
  - 验收：service 账号签名 403；要素齐全
  - 实现：落库含 签名人 / **姓名**（§11.50(b)）/ 角色 / 服务器时间 / 含义 / 记录类型 / 记录 ID /
    记录修订号 / SHA-256 / IP / User-Agent / 规则版本
  - 顺序说明：**先验身份（密码）后判授权（角色）**，与登录语义一致，使失败锁定对任意账号一致生效
- ✅ **T17** 失败计数与锁定：5 次失败锁 15 分钟，持久化（FR-17）
  - 验收：第 5 次失败后返回 423；锁定期间拒绝；15 分钟后恢复
  - 实现：`signature_attempt` 持久化（多实例安全，plan D5）；第 5 次写 `signature.lock` 审计。
    **「15 分钟后恢复」未纳入脚本断言**（不为测试开后门），由代码评审覆盖
- ✅ **T18** 篡改检测 `GET /api/signatures/{id}/verify`（FR-19）
  - 验收：修改已签名记录后返回 `tampered=true`，未修改返回 `false`
  - 实现：额外返回 `stale`（当前修订号 ≠ 签名时修订号）与 `valid` 综合判定
- ✅ **T19** 受控动作签名门禁：放行 / 偏差关闭 / CAPA 关闭 / 配方生效（FR-21）
  - 验收：缺签名时返回 409 并说明所需含义与角色；带合法签名可通过
  - 实现：`SignatureService#requireSignature`，策略未启用则旁路（plan D7 降级开关）。
    EBR_REVIEW 策略仅配置留档，实际双人复核由既有 `step_report` / `ccp_record` 复核逻辑承载

## P2 · 前端、导出与验证

- ✅ **T20** 导出：签名证据包（服务端渲染 Markdown/JSON）+ CAPA 清单（FR-20）
  - 验收：导出内容与页面一致，含哈希与记录版本 —— `GET /api/signatures/export`（管理员），
    服务端渲染，与前端改版解耦（R6）
- ✅ **T21** 前端 `Capa.jsx` / `Audit.jsx` + 签名条组件（密码 + 含义下拉）
  - 验收：无权限角色不可签名；签名后动作完成
  - 实现：`pages/Capa.jsx`、`pages/Audit.jsx`、`components/signature-bar.tsx`
    （`SignatureDialog` 二次身份确认 + `SignatureList` 哈希复核/导出）、`api/regtech.ts`
- ✅ **T22** 驾驶舱新增「CAPA 逾期 / 内审发现项」指标卡
  - 验收：与后端 `/api/capa/overdue` 一致
  - 实现：`GET /api/capa/metrics` 汇总在办 / 逾期 / 临期 / 内审次数 / 发现项 /
    未转办 major+ / 转化率；CAPA 页顶部指标卡已消费
- ✅ **T23** `scripts/verify-phase6.mjs` 端到端自检（≥ 30 项）+ 回归 phase1/3/4/5
  - 验收：全部通过；现有自检脚本因签名门禁变更而失败项已同步改造
  - 实现：**47 项检查**；已同步改造 `verify-phase1.mjs`（放行改为「先验门禁 → 再带签名放行」）
    与 `verify-phase4.mjs`（审批 / 生效带签名）；`dev-up.sh` 串联 phase1 + phase5 + phase6

## 需求之外的小增补（已在两处文件注明）

- `POST /api/capa/{id}/update`：CAPA 内容修订端点。spec 未列，但 FR-18「已签名记录被修改 →
  原签名失效」需要一个**可通过 HTTP 触发的内容变更入口**，否则该需求无法验证；
  同时也是真实 QMS 的必要操作（CAPA 内容随调查深入而修订）。
- `GET /api/capa/metrics`：T22 的指标卡聚合端点（避免前端多次请求后自行拼装）。
- 前端基础设施修复（Phase I 顺带发现）：
  - `tailwind.config.js` 的 `content` 遗漏 `ts/tsx`，`.tsx` 内的类名会被 purge（已补全）
  - `Badge` / `Dot` 缺少 `success` 与 `warning`（历史沿用名）两个 tone，导致全仓约 25 处
    Badge 落到 `undefined` 样式 —— 已补 `--success` 令牌与两个 tone 别名

## 现状说明

- 已有：`deviation` 表（状态机 open→investigating→capa→closed）、`audit_log` 审计、
  `JwtService`/`AuthFilter` 的 BCrypt 校验路径。本模块复用全部基础设施。
- 已解决缺陷：CAPA 不再只是一段文本（独立成表 + 行动项 + 验证 + 有效性 + 闭环门禁）；
  关键动作具备 Part 11 要素的电子签名与篡改检测。
- 依赖红线：未引入新认证库、未引入新 ORM、未引第三方哈希/CA 库（SHA-256 用 JDK 内置）。
- **注意**：`Deviation.capa`（TEXT）字段保留仅作兼容展示，新逻辑不再写入该字段；
  偏差与 CAPA 的关联以 `capa.source_type=DEVIATION` + `capa.source_id=<偏差单号>` 为准。
