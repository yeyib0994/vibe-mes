# FluxMES · MES 场景功能全景整理

> 用途：以**业务场景**为轴，把散落在 14 个前端页面、20 个 Controller、47 张表、12 份 spec 中的能力
> 归拢成一张可查、可对账的功能地图。作为后续做需求拆分 / 缺口分析 / 新模块设计的基线。
>
> 版本：v1.0 · 2026-09-24 · 编写方式：代码实测对账（非文档转述）

---

## 0. 规模基线（实测）

| 维度 | 数量 | 说明 |
| --- | --- | --- |
| 前端页面 | **14** | 13 个导航页 + `Login.tsx`；分 3 个导航分组 |
| API 模块 | 13 | `fluxmes/src/api/*.ts`（另有 `http.ts` 统一 Bearer 注入） |
| 共享组件 | 13 | `fluxmes/src/components/*`（含 `ui.tsx` 原语与 5 个业务面板） |
| 后端 Controller | **20** | 19 业务 + 1 契约（`OpenApiController`） |
| REST 端点 | **148** | `@*Mapping` 实测计数（含 `/v3/api-docs`） |
| 数据库表 | **47** | 6 个 schema 分片顺序幂等执行 |
| Spec 模块 | 12 | `specs/*/`（另有根约束 `constitution.md`） |
| 自检脚本 | 6 | `scripts/verify-*.mjs`，共约 195 项断言 |

Schema 分片分布：

| 分片 | 表数 | 承载阶段 |
| --- | --- | --- |
| `db/schema.sql` | 10 | 基座（用户/批次/报警/审计/配方/质检/SPC/偏差/产线） |
| `db/schema-food.sql` | 9 | Phase E 食品合规（CCP/清场/环境/物料谱系/eBR/留样） |
| `db/schema-p3.sql` | 6 | Phase G（SLA 政策/人员证书/能力字典/称量） |
| `db/schema-p4.sql` | 8 | Phase H（设备/配方版本/追溯谱系） |
| `db/schema-p5.sql` | 6 | Phase I-1 生产执行（工单/派工/报工/停机/OEE） |
| `db/schema-p6.sql` | 8 | Phase I-2 质量 RegTech（CAPA/内审/电子签名） |

---

## 1. 场景地图（MES 功能域 → 页面 → 后端）

```
┌─ 生产执行域 ─────────────────────────────────────────────┐
│  生产驾驶舱 · 批次管理 · 生产执行(工单/派工/报工/停机/OEE) · 配料称量  │
├─ 质量管控域 ─────────────────────────────────────────────┤
│  质量管理(SPC/COA/偏差) · CAPA闭环 · 内审管理 · 食品安全(HACCP/清场/  │
│  环境) · 物料与追溯(谱系/召回/留样) · 人员资质 · 报警中心               │
├─ 平台主数据域 ───────────────────────────────────────────┤
│  设备监控 · 配方管理 · 追溯查询                                       │
├─ 横切底座 ───────────────────────────────────────────────┤
│  认证JWT · RBAC · 审计追踪 · 电子签名 · OpenAPI契约 · 多厂区隔离        │
└─────────────────────────────────────────────────────────┘
```

---

## 2. 生产执行域

### 2.1 生产驾驶舱 · `pages/Dashboard.jsx`

| 功能点 | 实现 |
| --- | --- |
| KPI 卡（日产量 / 合格率 / OEE） | `GET /api/dashboard/cockpit?site=&line=`，口径真实化 |
| 厂区切换 | `SiteSwitcher`（切换自动清空产线选择） |
| 产线切换 | `LineSwitcher`（LINE-1/2/3/4，含设计产能） |
| 趋势图表 | Recharts `Area/Line/Bar`，数据源自 `FixtureStore`（只读展示层） |
| 数据时效标注 | `StalenessBadge` + 30s 采集周期，超期降级（章程 P4） |
| 班报生成与导出 | `GET /api/dashboard/shift-report?date=&shift=DAY\|NIGHT&line=`，JSON + Markdown 双形态，前端 Blob 下载 |
| **数据不足降级** | OEE 因子缺失返回 `null` + `dataSufficient=false` + `missing`，**不做硬编码兜底** |

端点 5 个（`DashboardController`）：`/cockpit` `/lines` `/sites` `/shift-report` `/…`

### 2.2 批次管理 · `pages/Batches.jsx` + `components/batch-actions.tsx`

| 功能点 | 实现 |
| --- | --- |
| 列表与筛选 | 服务端分页（单页 ≤50）+ 状态筛选（全部/进行中/待检/已完成/异常）+ 产线/厂区过滤 |
| 状态机 | `running → waiting → done` / `running → abnormal`（异常阻断推进） |
| 新建批次 | 校验配方 / 设备 / 产量 / 原料批号，编号 `B-yyMMdd-NNN` |
| **开工三闸门禁** | 清场有效 + 设备校准有效 + 人员资质健康证，任一不满足拒绝 |
| 强制开工 | `force=true` 且值班长+，写审计 |
| 放行锁定 | `released=TRUE` 禁改，只读 + 审计（C2） |
| 批次档案 | operator / planYield(kg) / materialLots / coaNo / warehouseBin / deviationNo |
| eBR 展开 | `components/ebr-panel.tsx` — 工序设定值 vs 实际值 + 双人复核 |
| 效期字段 | `production_date` / `shelf_life_days` / `expiry_date` |

端点 6 个（`BatchController`）。

### 2.3 生产执行 · `pages/Execution.jsx`

> Phase I 核心：把 OEE 从「算出来的数字」变成可下钻到报工与停机事实的执行结果。

| 分层 | 功能点 |
| --- | --- |
| **I1 工单** | 继承批次产线/厂区/产品/配方版本**快照**（批次后续换版不影响已建工单） |
| | 状态机 `CREATED → RELEASED → RUNNING → FINISHED → CLOSED`，跳级/重复流转 409，全程审计 |
| **I1 派工** | 门禁：账号启用 + 有效健康证 + 岗位资质，缺证 403 并列出缺失项 |
| | 同人同时段冲突提示，`confirmConflict=true` 可强制；撤销为软删留痕 |
| **I2 报工** | 唯一索引 `uq_sr_idem` 幂等（同工单+工序+报工人+开工时间） |
| | 回写 `batch_step`（status=DONE + actualParams JSON）；批次 `progress` **改由报工推导** |
| | 关键工序强制双人复核（复核人 ≠ 报工人）；累计合格超计划 110% 拒绝；已放行批次 409 |
| **I3 停机** | `downtime_reason` 9 条原因码；`planned` **由 category 推导**（CHANGEOVER/CLEANING/NO_ORDER 为计划性损失） |
| | 同设备时间窗重叠自动合并（`merged=true`），不重复扣减可用率 |
| | 超原因码阈值（如 MECH 120min）自动生成 major 报警 |
| **I4 OEE** | 可用率 =(计划−计划外停机)/计划 · 性能率 =标准工时/实际生产工时 · 良品率 =合格/(合格+废次) |
| | `oee_rollup` 预汇总表 + `/oee/recompute`（产线 × 日 × 班次 upsert） |
| | 停机原因帕累托（降序 + 累计百分比，计划/非计划分列） |

端点 **17 个**（`ExecutionController`，最大单控制器）。

### 2.4 配料称量 · `pages/Weighing.jsx`

| 功能点 | 实现 |
| --- | --- |
| 称量任务 | `weighing_task` / `weighing_item`，目标量 ± 容差% / 偏差% / PASS·OVER·UNDER |
| 资质门禁 | 登记称量须持有 `WEIGHING` 能力项证书 |
| 超差处置 | **自动建偏差单 + major 报警**，任务置 `BLOCKED` |
| 解除阻断 | QC 复核（非称量人本人） |
| 看板 | 合格率 / 超差数 / 待复核数 |

端点 6 个（`WeighingController`）。

---

## 3. 质量管控域

### 3.1 质量管理 · `pages/Quality.jsx` + `components/quality-panel.tsx`

| 功能点 | 实现 |
| --- | --- |
| SPC 控制限 | `spc_limit` 表按产品/特性读取 UCL/CL/LCL（**已替换硬编码 99.82/99.18**） |
| 判异引擎 | Western Electric R1（1 点超 3σ）/ R2（连续 9 点同侧）/ R3（连续 6 点趋势） |
| 自动偏差 | 超界或判异 → 自动创建 DEV 偏差单 |
| 成品放行 + COA | `POST /api/quality/release`（QC+），生成 COA 编号回写批次 |
| 偏差工作流 | `open → investigating → capa → closed`；未关闭阻断关联批次放行 |
| 依据标注 | 判定标准版本留痕（如 GB 1886.25—2016，C5） |
| 前沿渲染 | `frontend/src/lib/mes-math.ts` 与后端 `QualityService.detectRules` **同口径**（单测覆盖） |

端点 9 个（`QualityController`）。

### 3.2 CAPA 闭环 · `pages/Capa.jsx`

| 功能点 | 实现 |
| --- | --- |
| CAPA 清单 / 临期与逾期 / 新建 | `GET /api/capa`、`/capa/overdue`、`/capa/metrics` |
| 生命周期 | 启动执行 → 提交验证 → 验证通过/重新执行 → 关闭（非法流转 409） |
| 任务分解 | `POST /capa/{id}/tasks` + `POST /capa/tasks/{taskId}/done` |
| 有效性评价 | `POST /capa/{id}/verify`，验证人 ≠ 执行人 |
| **放行联动** | 批次放行额外校验 **CAPA 已闭环**（Phase I-2 / FR-7），未闭环 409 |

端点 11 个（`CapaController`）。

### 3.3 内审管理 · `pages/Audit.jsx`

| 功能点 | 实现 |
| --- | --- |
| 内审计划 | 状态流转 `已计划 → 审核中 → 已出报告 → 已关闭` |
| 发现项 | `POST /api/internal-audit/{id}/findings` |
| **发现项转 CAPA** | `POST /api/internal-audit/findings/{findingId}/to-capa` — 与 CAPA 模块打通 |
| 关闭 | `POST /api/internal-audit/{id}/close` |

端点 8 个（`AuditProgramController`）。

### 3.4 食品安全 · `pages/FoodSafety.jsx`（三 Tab）

| Tab | 功能点 |
| --- | --- |
| **HACCP 关键控制点** | 5 个 CCP（连消灭菌温度 / 金属检测 / 干燥出风温度 / 筛网完整性 / 过敏原换型 ATP），含关键限值 CL、监控频次、纠偏措施 |
| | 实测越限 → **自动建偏差单 + critical 报警 + 关联批次置 abnormal 阻断推进** |
| | `POST /haccp/records/{id}/verify` QA 双人复核，**复核人 ≠ 记录人**（409） |
| | `GET /haccp/summary` 合规率 / 偏离数 / 待复核数看板 |
| **清场与过敏原** | 四类清场 ROUTINE / CHANGEOVER / ALLERGEN / DEEP；QA 确认后写入 **72 小时有效期** |
| | 清场门禁：无有效清场拒绝开工（见 2.2） |
| **环境与卫生** | 温湿度 / 压差 / 沉降菌 / ATP，超标自动建偏差单与报警 |

端点 21 个（`HaccpController` 6 + `SanitationController` 7 + `MaterialController` 8）。

### 3.5 物料与追溯 · `pages/Materials.jsx`（三 Tab）

| Tab | 功能点 |
| --- | --- |
| **物料与原料批** | `material` / `material_lot` 主数据，过敏原标识贯穿；到货登记默认待检 → 检验放行（QC+） |
| | **投料强校验**：原料批必须 `RELEASED` 且未过期，否则拒绝投料 |
| **投料谱系与召回** | `GET /materials/genealogy/{batchId}` 真实正反向谱系（**已替换 mes.json 硬编码假链**） |
| | `GET /materials/recall/{materialLotId}` 召回影响分析：受影响成品批 + 已放行数量 + 处置建议 |
| **留样与效期** | `retention_sample`：留样到期 = 成品效期 + **180 天** |
| | `GET /api/ebr/expiry-alerts` 近效期（默认 30 天）与已过期成品预警 |

### 3.6 人员资质 · `pages/Personnel.jsx`（三 Tab）

| Tab | 功能点 |
| --- | --- |
| 到期预警 | 已过期 / 临期（默认 30 天） |
| 证书台账 | HEALTH 健康证 / QUALIFICATION 岗位资质；发证 / 吊销 |
| 能力项矩阵 | 六类能力项：WEIGHING / CCP_MONITOR / RELEASE / BATCH_REVIEW / SANITATION / LAB_TEST |
| **关键动作门禁** | CCP 监控 · CCP 复核 · eBR 工序复核 · 成品放行 · 清场登记与确认 · 配料称量 |
| 兼容策略 | 某能力项无人持证时不强制；有人持证后自动启用（健康证同理） |
| 过期巡检 | `sweepExpired` |

端点 7 个（`PersonnelController`）。

### 3.7 质量体系 RegTech（横切在 Quality/Capa/Audit 内）· `components/signature-bar.tsx`

| 功能点 | 实现 |
| --- | --- |
| **电子签名 append-only** | `e_signature` 只 INSERT，纠错 = VOID + 重签 |
| 变更令旧签失效 | `record_revision` 变更即令旧签失效（FR-18） |
| 失败锁定 | 失败 5 次锁 15 分钟（`signature_attempt`） |
| 策略降级 | `signature_policy.enabled=false` 时签名门禁整体降级放行 |
| 系统账号禁签 | `service` 账号禁签（FR-16 演示） |

端点 6 个（`SignatureController`）。

### 3.8 报警中心 · `pages/Alarms.jsx` + `components/alarm-suppression.tsx`

| 功能点 | 实现 |
| --- | --- |
| 状态机 | 未确认 → 已确认 → 已恢复（列表四 Tab：未确认/已确认/已恢复/全部） |
| SSE 实时推送 | 报警流 ≤30s，含 `escalated` 事件 |
| **SLA 可配置** | `alarm_sla_policy` 表（默认 critical 5 / major 15 / minor 30 分钟），**值班长运行时可调**，0 = 豁免考核 |
| SLA 巡检 | 30s 首检 + 每 60s，逾期未确认自动 `escalated=true` + 审计 + SSE |
| **抑制规则** | `alarm_suppression` 表 CRUD；模拟器入库前过抑制，同 source+content 窗口期内只计数不入库（防洪水） |
| 留存合规 | 落 PG 不裁剪，按设备/级别/时间检索，保留 ≥ 3 年（C6） |

端点 14 个（`AlarmController`，含 SLA 政策与抑制规则）。

---

## 4. 平台主数据域

### 4.1 设备监控 · `pages/Equipment.jsx` + `components/equipment-panel.tsx`

| 功能点 | 实现 |
| --- | --- |
| 台账 | `equipment` / `equipment_event` / `maintenance_order` 三表；PG 主数据与 fixture 展示数据服务层缝合，**前端契约零变更** |
| **状态机** | RUNNING / IDLE / CLEANING / ALARM / MAINTENANCE / STOPPED；变更写事件流水 + 审计；上一段自动补 `ended_at` |
| 保养 | 到期与逾期预警；工单完成后回填运行小时并按 `cycleDays` 顺延 |
| **校准阻断** | 超期设备被移出 `available`（FR-10），以其建批次 409；批次放行时该批检验数据同样被拒（FR-8/C5）；CALIBRATION 工单完成后自动续期一年 |
| 详情聚合 | 关联报警 / 在制批次 / 维护工单 / 状态事件 |
| OEE | 可用率取自事件停机时长；无事件时回落采集值并标注「数据不足」（不伪造数值） |

端点 9 个（`EquipmentController`）。

### 4.2 配方管理 · `pages/Recipes.jsx` + `components/recipe-panel.tsx`

| 功能点 | 实现 |
| --- | --- |
| 受控版本 | `recipe_version` / `recipe_step` / `recipe_change`；6 个版本含 effective/draft/obsolete 三态 |
| **生效唯一性** | 数据库部分唯一索引 `uq_recipe_effective` + 事务内「先置旧版 obsolete 再抬新版 effective」双保险（NFR-3） |
| 生命周期 | 建草稿（v3.2→v3.3）→ 提交（draft→pending）→ 审批（→effective/rejected）→ 原子生效；非法流转 409；审批与生效限管理员（C4） |
| **变更影响面** | 列出执行旧版本的在制批次；CCP 参数上下限变更时自动标记「需重评检验方法」 |
| **版本快照** | `BatchService.resolveVersionForBatch` 自动锁定生效版本；简写（`v3`）可解析；显式引用失效版本建单 400 |
| 契约兼容 | `GET /api/recipes` 的 `history` 保持 `{v,date,by,note}` 不变，受控版本走并列 `versions` |

端点 10 个（`RecipeController`）。

### 4.3 追溯查询 · `pages/Trace.jsx` + `components/trace-panel.tsx`

| 功能点 | 实现 |
| --- | --- |
| **正向链路** | 中间品 → 原料投入 → 工序 → 主设备 → 检验放行 → 入库，真实数据组装，兼容 `traceChains` 节点契约 |
| 逆向追溯 | `GET /api/trace/backward?lotNo=` |
| 影响面分析 | `GET /api/trace/impact?lotNo=`，下游展开 ≤5 层 + 处置建议 |
| 横向关联 | 节点聚合关联设备报警与批次偏差单 |
| 完整性校验 | 缺投料/设备/操作员/配方版本快照/COA 即标记「档案不完整」并列出缺失项（FR-8） |
| 导出 | `GET /api/trace/{batchId}/export` Markdown 报表，含生成时间与操作人水印 |
| 查询审计 | 查询与导出均写 `trace_query_log` + `audit_log`（FR-7） |

端点 8 个（`TraceController`）。

---

## 5. 横切底座

| 能力 | 实现要点 |
| --- | --- |
| **认证** | JWT 无状态（HS256，12h），claims `user_id/username/role/site`；自研轻量 `AuthFilter`，`/api/*` 白名单外需 Bearer |
| **RBAC** | `CurrentUser`(ThreadLocal) + `Roles.atLeast()` 四级角色：放行 ≥ QC · 关闭偏差 ≥ SUPERVISOR · 新建/推进/异常 ≥ OPERATOR · 审计查询 = ADMIN |
| **审计追踪** | `audit_log` 记 who/when/what/entityId/before/after；放行/确认/关闭/状态变更全落；`GET /api/audit`（ADMIN）；保留 ≥3 年（C3） |
| **多厂区** | `site` 主数据（SITE-01 城东 / SITE-02 滨海）；JWT 带 `site`；批次/产线/CCP/用户带厂区归属；查询维度已落地，**强制数据隔离待办（T8）** |
| **OpenAPI** | 手写 `/v3/api-docs`（`OpenApiController`），零依赖；**禁用 springdoc**（2.x 与 SB4 不兼容 → 启动失败） |
| **登录页** | `pages/Login.tsx` + `lib/auth.ts`；`api/http.ts` 自动注入 Bearer、401 登出；`App.tsx` 路由守卫 |

端点：`AuthController` 2 + `AuditController` 1 + `OpenApiController` 1。

---

## 6. 门禁矩阵（跨模块硬约束汇总）

**开工三闸**（新建批次须同时满足）：

| 闸 | 依据 | 失败返回 |
| --- | --- | --- |
| 清场有效 | F1：PASS + QA 确认 + 未过 72h | 409 |
| 设备校准有效 | H1/T8 | 409 |
| 人员资质与健康证 | G2 | 403 |

**放行额外校验**（在开工三闸之外）：

| 校验 | 依据 |
| --- | --- |
| 偏差单已关闭 | B2 |
| 主设备校准有效 | H1/FR-8 |
| **CAPA 已闭环** | I-2/FR-7 |
| 电子签名有效 | I-2/FR-18 |

任一不满足 → 拒绝（409/403），并**回传缺失清单**。

**双人复核**（`reviewer != operator`，否则 409）：CCP 记录 · eBR 工序 · 关键工序报工 · 称量超差复核。

---

## 7. 不可回退的业务语义（改动前必须确认）

1. CCP 实测越出关键限值 → 自动建偏差 + critical 报警 + 关联批次置 `abnormal` 阻断推进
2. 投料强校验：原料批必须 `RELEASED` 且未过期
3. 留样到期 = 成品效期 + 180 天；谱系基于真实 `batch_input`（非 mes.json 假链）
4. **OEE 三因子必须真实** —— 数据缺失返回 `null + dataSufficient=false + missing`，**绝不硬编码兜底**
5. 电子签名 append-only（纠错 = VOID + 重签）；`record_revision` 变更令旧签失效
6. `GET /api/recipes` 的 `history` 结构必须保持 `{v,date,by,note}`
7. 追溯复用 Phase E 的 `material_lot` / `batch_input`，**绝不重建同名表**
8. BIGSERIAL 主键在 JSON 视图必须输出为 **String**（防前端丢精度）

---

## 8. 文档与代码一致性缺口（本次整理发现）

| # | 问题 | 严重度 | 建议 |
| --- | --- | --- | --- |
| 1 | **Phase I-2（质量 RegTech）代码已全量落地**（`schema-p6.sql` 8 表 / `RegtechSeeder` / 3 个 Controller 25 端点 / `Capa.jsx`+`Audit.jsx`），但 `docs/improvement-plan.md` **无对应章节**（该文档止于 Phase I） | P1 | 补写「阶段 J · 质量体系 RegTech」章节，与 Phase E/G/H/I 同格式 |
| 2 | `specs/constitution.md` 仍写「Phase I 规划中」、`production-execution` 与 `quality-regtech` 标注 ⬜ 待实现，实际两者均已实现 | P1 | 更新章程第 7 节模块拆分与第 9 节模块索引状态 |
| 3 | `specs/production-execution/spec.md`、`quality-regtech/spec.md` 状态仍为「待实现」 | P2 | 按章程「回填纪律」在文首标注「回填」+ plan 标注实现状态 + tasks 用 ✅/⬜ 区分 |
| 4 | `quality-regtech` 的 CAPA/内审/签名三块能力分散在 Quality/Capa/Audit 三页，**无统一入口** | P2 | 若后续要做「合规看板」，需先定聚合口径 |
| 5 | 多厂区强制数据隔离（T8）未落地，当前仅查询维度隔离 | P2 | 已在章程标注 ◐，保持可见 |
| 6 | **`equipment_metric` 时序表「设计但未创建」**：`specs/equipment-management/plan.md` 第 14 行已定义该表（metric_key/value/unit/sampled_at），但 6 个 schema 分片里**没有它**，也无 Entity/Mapper。设备实时参数与趋势实际由 `FixtureStore`(mes.json) + `EquipmentService.genSeries()`（正弦+噪声确定性伪序列）产出 | P1 | 要么落地该表并接 T12 采集，要么在 plan 中明确标注「本期不建」以消除文档与代码的错位 |
| 7 | **章程要求的 4 个外部集成全部未落地**（constitution §5 第 62 行：SCADA/OPC-UA、LIMS、ERP、WMS）。设备模块是唯一在 spec 中显式预留采集层的模块（`spec.md` §7 第 62 行「Phase 2，本期用模拟器」） | P2 | 属 Phase 2 范围，当前不影响合规与 OEE 正确性（见 7.1） |

> 注：`docs/improvement-plan.md` 中「已知风险与解决」标题**重复出现两次**（第 408、414 行），可顺手合并。

---

## 9. 后续可整理方向（供选择）

- **A. 补齐文档**：按第 8 节 #1~#3 回填 Phase I-2 章节与章程状态，让文档追上代码。
- **B. 能力矩阵对账**：把 12 份 spec 的 tasks.md 逐条与代码对照，产出「已实现 / 部分 / 未实现」三态清单。
- **C. 门禁全景固化**：把第 6 节门禁矩阵做成可执行自检（现分散在 `verify-*.mjs` 中）。
- **D. 新模块设计**：若目标是扩展 MES 场景（如设备 OPC-UA 采集、WMS 入库、时序库接入），基于本图做场景缺口分析。

---

*本文件为功能整理基线，随代码演进更新。*
