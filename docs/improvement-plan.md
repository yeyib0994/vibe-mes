# FluxMES 完善路线图（Improvement Plan）

> 依据 `specs/constitution.md` 章程与四模块 tasks.md 差距分析制定。
> 勾选框追踪进度；✅ = 已完成，◐ = 进行中，⬜ = 待办。
> 版本：v1.0 · 2026-09-14 · 决策：全量落地 / Spring Boot 4.x + MyBatis-Plus / Docker PostgreSQL / JWT

---

## 现状基线（2026-09-14 体检）

| 维度 | 状态 |
| --- | --- |
| 前端 `fluxmes/` | 7 页面全部经 `src/api/*` + TanStack Query 取数；SSE 订阅、时效降级已接 ✅ |
| 后端 `apps/api-java` | Spring Boot 3.4.1，7 组 REST API；报警 ack/recover 状态机 + SSE + 45s 模拟器 ✅ |
| 数据层 | FixtureStore 内存单例 + mes.json —— **重启丢数据** ❌ |
| 审计追踪（C3） | 无 ❌ |
| RBAC（C4） | 无 ❌ |
| OpenAPI | 无 ❌ |

环境：Java 21.0.12 ✅ · Maven 3.9.16（`~/.m2/wrapper/dists`）✅ · Docker daemon 未启动（需启动 Docker Desktop）◐

---

## 阶段 A · P0 合规基建

### A1 · 后端依赖升级
- ✅ pom 升级 Spring Boot 3.4.1 → **4.1.1**（对齐 vibe-erp）
- ✅ 引入 `mybatis-plus-spring-boot4-starter` 3.5.16 + `mybatis-plus-jsqlparser`
- ✅ 引入 jjwt 0.12.6 / postgresql driver / spring-security-crypto（springdoc 已弃用，见 A5）
- ✅ Jackson 兼容：SB4 用 Jackson 3（tools.jackson.\*），显式保留 jackson-databind 2 给现有代码
- ✅ 编译 + 打包通过（`target/api-java-0.2.0.jar`，29.7MB）

### A2 · PostgreSQL 落地
- ✅ `deploy/run-pg.sh` + docker run postgres:16（fluxmes-pg，5432，库 fluxmes）
- ✅ 建表：`app_user` / `batch` / `alarm` / `audit_log` / `recipe` / `qc_task` / `spc_limit` / `deviation`（8 表，全部 `IF NOT EXISTS`）
- ✅ application.yml 数据源 + MyBatis-Plus 分页插件

### A3 · 持久化改造
- ✅ MyBatis-Plus 实体 + Mapper（7 实体 / 6 Mapper）
- ✅ `DatabaseSeeder`：启动时 fixture seed（mes.json → PG，幂等）
- ✅ FixtureStore 退化为只读展示数据源；报警/批次/审计读写 PG
- ✅ 报警模拟器保留（写入 PG，不再裁剪 60 条，留存 ≥3 年）

### A4 · 认证 / 权限 / 审计
- ✅ `app_user` 四角色种子：工艺员/质检员/值班长/管理员（BCrypt 密码）
- ✅ `POST /api/auth/login` → JWT（HS256，claims: user_id/username/role/exp，12h）
- ✅ `AuthFilter`：`/api/*` 校验 Bearer（白名单：登录/健康检查/OpenAPI）；`Roles` 四级角色校验
- ✅ `audit_log`：who/when/what/entityId/before/after；ack/recover/放行/状态变更全落审计
- ✅ `GET /api/audit` 查询端点（ADMIN）

### A5 · OpenAPI
- ✅ 手写 `/v3/api-docs` 契约端点（`OpenApiController`，零依赖）
- ⬜ ~~springdoc-openapi~~ **已弃用**：springdoc 2.8.17 与 Spring Boot 4.1.1（spring-context 7）不兼容，
      启动期 `SwaggerConfig.swaggerWelcome` 条件装配失败导致应用无法启动；改为手写契约（顺带满足"不引入第三方依赖"约束）
- ✅ 契约覆盖：auth / dashboard / batch / quality / alarm / audit 全部端点

## 阶段 B · P1 业务深化（后端）

### B1 · 批次管理（batch-management tasks T2/T3/T5/T6/T10）
- ✅ 服务端分页（单页 ≤50，`?page=&size=`）
- ✅ 批次状态机：running→waiting→done / running→abnormal（异常阻断推进）
- ✅ `POST /api/batches` 新建批次（校验配方/设备/产量/原料批号，生成 B-yyMMdd-NNN）
- ✅ 放行只读锁定（released=TRUE 禁改，写审计，C2）
- ✅ 批次档案结构化（operator/planYield/materialLots/coaNo/warehouseBin/deviationNo）

### B2 · 质量管理（quality-management tasks T2/T3/T4/T7/T8）
- ✅ `spc_limit` 控制限配置表：按产品/特性读取 UCL/CL/LCL（替换硬编码 99.82/99.18）
- ✅ 判异引擎：Western Electric R1（1 点超 3σ）/ R2（连续 9 点同侧）/ R3（连续 6 点趋势）
- ✅ 超界/判异自动创建 DEV 偏差单
- ✅ 成品放行 + COA：`POST /api/quality/release`（QC+），生成 COA 编号回写批次
- ✅ 偏差工作流：open→investigating→capa→closed；未关闭阻断关联批次放行

### B3 · 报警中心（alarm-center tasks T6/T9）
- ◐ 响应 SLA：unacked 起算，按级别超时升级 —— **Phase D1 实现**
- ✅ 留存：报警落 PG 不再裁剪（≥3 年合规），按设备/级别/时间检索

### B4 · 驾驶舱（production-cockpit tasks T5）
- ◐ KPI 口径后端真实化 —— 报警 KPI 已实时化；日产量/合格率/OEE 待接真实计量（Phase D3 班报补齐聚合）

## 阶段 C · 前端配套

- ✅ 登录页 `pages/Login.tsx` + `lib/auth.ts` token 存储 + `api/http.ts` 自动注入 Bearer、401 登出
- ✅ 路由守卫 `App.tsx`（未登录跳登录页）+ 角色可见性（导航与操作按钮按角色显隐）
- ✅ 新建批次表单（`components/batch-actions.tsx`）
- ✅ 批次操作面板：推进/标记异常/放行（同组件）
- ✅ 质量页判异规则展示 + 放行操作（`components/quality-panel.tsx`）
- ✅ 偏差单跟踪视图（`quality-panel.tsx` 内偏差推进 open→investigating→capa→closed）

## 阶段 D · P2 增强（2026-09-14 完成 D1–D4）

### D1 · 报警 SLA 升级 + 抑制（alarm-center T6/T7）
<!-- G1 已把时限改为配置表，见「阶段 G」 -->
- ✅ 按级别响应时限：critical 5 / major 15 / minor 30 分钟（`alarm.sla_minutes`）
- ✅ 巡检任务（30s 首检 + 每 60s）：逾期未确认自动 `escalated=true`，写审计 `alarm.escalate` + SSE 推送 `escalated`
- ✅ `alarm_suppression` 表与规则 API：`GET/POST/DELETE /api/alarms/suppressions`（值班长+，后端 RBAC 二次校验）
- ✅ 模拟器入库前过抑制：同 source+content 在窗口期内只计数不入库（防报警洪水）
- ✅ 前端 `components/alarm-suppression.tsx`：SLA 政策说明 + 规则表格 + 新建/删除 + 手动巡检（角色受限）
- ✅ 报警 SLA 时限可配置化 —— **G1 已完成**：移入 `alarm_sla_policy` 配置表，值班长运行时可调

### D2 · 多产线维度
- ✅ `batch.line` + `production_line` 主数据（LINE-1/2/3，含设计产能），历史数据回填 LINE-1
- ✅ 批次列表 `?line=` 过滤，新建批次可指定产线（默认 LINE-1）
- ✅ 驾驶舱 `GET /api/dashboard/cockpit?line=`：`/lines` 主数据 + KPI 按产线聚合
- ✅ KPI 口径真实化：日产量=当日批次按进度折算 / 产线产能；合格率=已完成质检合格占比；OEE=可用性×性能×质量
- ✅ 前端 `components/dashboard-controls.tsx`：`LineSwitcher` 产线切换器

### D3 · 班报生成与导出
- ✅ `GET /api/dashboard/shift-report?date=&shift=DAY|NIGHT&line=`：当班批次/报警/质检/偏差聚合
- ✅ 输出结构化 JSON + Markdown 双形态，前端 Blob 直接下载 `.md`
- ✅ 前端 `ShiftReportActions`：班次选择（白班 08–20 / 夜班 20–次日08）+ 导出按钮

### D4 · 三模块 Spec Kit 文档
- ✅ `specs/equipment-management/`（spec + plan + tasks）：设备主数据/状态事件/维护工单/校准阻断/OEE
- ✅ `specs/recipe-management/`（spec + plan + tasks）：版本受控/审批生命周期/变更影响/容差校验
- ✅ `specs/traceability/`（spec + plan + tasks）：谱系结构化/逆向追溯/影响面/完整性校验/报表导出
- ✅ `specs/constitution.md` 模块清单同步为「已 specify」

### D5 · 端到端验证
- ⬜ **待用户本机执行**：Docker daemon 在 agent 沙箱内无法启动（npipe 不可达）
- ✅ `scripts/dev-up.sh` 一键脚本：起 PG → 起后端 → 跑自检（可选 `--frontend`）
- ✅ `scripts/verify-phase1.mjs` 扩展至 20 项，覆盖 D1 SLA/抑制/越权、D2 产线、D3 班报

### 其他
- ✅ 前端单元测试 —— **G5 已完成**：vitest 2 + `src/lib/mes-math.ts` 纯函数，21 项断言全绿

---

## 阶段 E · 食品行业合规能力（2026-09-14 完成 F1–F4）

> 此前系统是「通用流程制造 MES」，食品行业法定门槛全部缺失。本阶段补齐 HACCP/清场/环境、
> 物料谱系召回、eBR 与留样效期，新增 9 张表、5 组 REST 端点、2 个前端页面。

### F1 · 食品安全（HACCP / 清场 / 环境）
- ✅ `ccp_point` 表：5 个关键控制点（连消灭菌温度、金属检测、干燥出风温度、筛网完整性、过敏原换型 ATP），含关键限值 CL、监控频次、纠偏措施
- ✅ `ccp_record` 表 + `POST /api/haccp/records`：实测值越限 → **自动建偏差单 + critical 报警 + 关联批次置 abnormal 阻断推进**
- ✅ `POST /api/haccp/records/{id}/verify`：QA 双人复核，**复核人不得为记录人本人**
- ✅ `GET /api/haccp/summary`：合规率 / 偏离数 / 待复核数看板
- ✅ `cleaning_record` 表：ROUTINE / CHANGEOVER / ALLERGEN / DEEP 四类，QA 确认后写入 72 小时有效期
- ✅ **清场门禁**：新建批次强制校验目标产线清场有效性，无有效清场拒绝开工；`force=true` 且值班长+可强制作业并写审计
- ✅ `env_monitoring` 表：温湿度 / 压差 / 沉降菌 / ATP，超标自动建偏差单与报警

### F2 · 物料谱系与召回
- ✅ `material` / `material_lot` / `batch_input` 三表，过敏原标识贯穿物料主数据
- ✅ 原料到货登记（默认待检验）→ `POST /lots/{id}/inspect` 检验放行（QC+）
- ✅ 投料登记强校验：**原料批未放行或已过期一律拒绝投料**
- ✅ `GET /materials/genealogy/{batchId}` 真实正反向谱系（此前为 mes.json 硬编码假链）
- ✅ `GET /materials/recall/{materialLotId}` 召回影响分析：受影响成品批次 + 已放行数量 + 处置建议

### F3 · 电子批记录 eBR / 留样 / 效期
- ✅ `batch_step` 表：工序级工艺设定值 vs 实际值 + 双人复核（`reviewer ≠ operator`）
- ✅ `retention_sample` 表：留样到期日 = 成品效期 + 180 天（保质期后 6 个月）
- ✅ `batch` 扩展 `production_date` / `shelf_life_days` / `expiry_date`
- ✅ `GET /api/ebr/expiry-alerts` 近效期（默认 30 天）与已过期成品预警

### F4 · 前端与种子
- ✅ `pages/FoodSafety.jsx`：CCP / 清场 / 环境 三 Tab 页面（侧栏「食品安全」）
- ✅ `pages/Materials.jsx`：物料批 / 谱系召回 / 留样效期 三 Tab 页面（侧栏「物料与追溯」）
- ✅ `components/ebr-panel.tsx`：批次详情页展开显示电子批记录
- ✅ 新建批次表单增强：产线选择 + 保质期 + 清场门禁实时状态 + 值班长强制开工勾选
- ✅ `Trace.jsx` 增加「真实投料谱系」卡片
- ✅ `FoodSafetySeeder`：CCP 点/监控记录（含 1 条偏离）、5 条清场记录（3 有效/1 不合格/1 过期）、
     8 条环境记录（含 1 条超标）、6 种物料、7 个原料批（含待检/不合格）、投料谱系、8 道工序、3 件留样、
     1 个近效期 + 1 个过期成品（效期预警演示）
- ✅ `scripts/verify-food-safety.mjs`：Phase E 专项自检（26 项，含越权与拦截用例）
- ✅ **端到端已跑通**（19:35）：Phase E 26/26 通过 + Phase 1/D 回归 20 项通过；
  实测 CCP 偏离自动建 `DEV-260914-001` + 报警 `A-260914-101`、QA 复核生效、无清场开工 409、
  召回 LOT-001 影响 6 批（2 批已放行 / 72 吨）、eBR 8 工序、效期预警 1 近效期 + 1 过期

### 本轮修复的隐藏缺陷

- **Spring Boot 4 不装配 Jackson 2 的 `ObjectMapper` bean**（只装配 Jackson 3 `tools.jackson.*`），
  导致 `AuditService` 构造失败 —— 应用此前**从未真正启动成功过**。
  新增 `config/Jackson2Config`（`@Primary` Jackson 2 + JavaTimeModule）+ `jackson-datatype-jsr310` 依赖。
- **BIGSERIAL 主键 JSON 返 Long 丢精度**（雪花 ID > `Number.MAX_SAFE_INTEGER`）→ 前端拿到错 id，
  `/verify` 404。改为视图层输出 `String.valueOf(id)`。
- PostgreSQL 端口由 5432 改为 **5433**（5432 被本机 `vibe-erp-postgres` 占用）；
  `application.yml` / `scripts/dev-up.sh` / `deploy/run-pg.sh` 已同步。
- 沙箱注入 `SERVER__PORT=60761`，启动需显式 `--server.port=8080`。

---

---

## 阶段 G · P3 剩余能力（2026-09-14 完成 G1–G5）

> 承接 D1 遗留项与 P3 增强清单：SLA 可配置化、人员资质与健康证、称量配料容差、
> 多厂区、前端单元测试。新增 6 张表、3 组 REST 端点、2 个前端页面、21 项单元测试。

### G1 · 报警响应 SLA 可配置化（D1 遗留）
- ✅ `alarm_sla_policy` 表（level / minutes / enabled / note / updatedBy），种子写入 5 / 15 / 30
- ✅ `AlarmSlaPolicyService`：`effectiveMinutes`（停用 → 0 表示豁免考核）、`policyMap`、`list`、`update`
- ✅ `GET /api/alarms/sla/policy` · `PUT /api/alarms/sla/policy/{level}`（值班长+，1–1440 分钟校验）
- ✅ `AlarmService` 常量降级为 `defaultSla(level)` 兜底；新报警写入当期政策，在途报警保留创建时
      时限（审计口径），政策变更不追溯
- ✅ 前端 `SuppressionPanel` 支持值班长就地修改各级别时限与启停

### G2 · 人员资质与健康证
- ✅ `person_certificate` 表（HEALTH 健康证 / QUALIFICATION 岗位资质 + capability 能力项）
      与 `capability_dict` 能力项字典（WEIGHING / CCP_MONITOR / RELEASE / BATCH_REVIEW / SANITATION / LAB_TEST）
- ✅ `PersonnelService`：台账 / 发证 / 吊销 / 能力项矩阵 / 到期预警 / `sweepExpired` 过期巡检
- ✅ **关键动作门禁**：CCP 监控记录（CCP_MONITOR）、CCP 复核（BATCH_REVIEW）、eBR 工序复核
      （BATCH_REVIEW）、成品放行（RELEASE）、清场登记与确认（SANITATION）、配料称量（WEIGHING）
- ✅ 兼容策略：某能力项无人持证时不强制（未纳入管理），有人持证后自动启用；健康证同理
- ✅ 接口：`GET /api/personnel/{certificates|capabilities|alerts|summary|{username}}`、
      `POST /api/personnel/certificates`、`POST .../certificates/{id}/revoke`
- ✅ `pages/Personnel.jsx`：到期预警 / 证书台账 / 能力项矩阵三 Tab
- ✅ `P3Seeder`：四角色发证（健康证 5 + 岗位资质 18）+ 演示账号 `temp/temp123`
      （SITE-02，健康证已过期，用于门禁演示）

### G3 · 称量 / 配料容差校验
- ✅ `weighing_task` / `weighing_item` 两表（目标量 ± 容差% / 偏差% / PASS·OVER·UNDER）
- ✅ `WeighingService`：创建任务、登记称量（须 WEIGHING 资质）、超差自动建偏差单 + major 报警
      并把任务置 BLOCKED；QC 复核（非称量人本人）后解除阻断
- ✅ 接口：`GET|POST /api/weighing/tasks`、`GET /api/weighing/tasks/{id}/items`、
      `POST /api/weighing/tasks/{id}/weigh`、`POST /api/weighing/items/{id}/review`、`GET /api/weighing/summary`
- ✅ `pages/Weighing.jsx`：合格率 / 超差 / 待复核看板 + 任务卡片与称量明细

### G4 · 多厂区
- ✅ `site` 主数据表（城东工厂 SITE-01 / 滨海工厂 SITE-02）；`production_line`、`batch`、
      `app_user`、`ccp_point` 增加厂区归属；滨海新增 LINE-4
- ✅ JWT 增加 `site` 声明；`CurrentUser.site()`；登录返回与 `/api/auth/me` 携带厂区
- ✅ `GET /api/dashboard/sites`、`GET /api/dashboard/lines?site=`、
      `GET /api/dashboard/cockpit?site=&line=`、`GET /api/batches?site=`、`/shift-report?site=`
- ✅ 新建批次厂区归属：显式指定 > 当前用户厂区 > 默认 SITE-01
- ✅ 前端 `SiteSwitcher`（切换厂区自动清空产线选择）

### G5 · 前端单元测试
- ✅ 引入 `vitest@2`（兼容 vite 5），新增 `npm test` / `npm run test:watch`
- ✅ `src/lib/mes-math.ts` 抽出纯函数：帕累托累计与 80% 截断、SPC Western Electric
      R1/R2/R3（与后端 `QualityService.detectRules` 同口径）、报警分级排序、证书到期判定、称量容差判定
- ✅ `src/lib/mes-math.test.ts` **21 项断言全部通过**

### 新增端点速查

| 端点 | 说明 | 权限 |
| --- | --- | --- |
| `GET /api/alarms/sla/policy` | 各级别响应时限（0=豁免考核） | 登录 |
| `PUT /api/alarms/sla/policy/{level}` | 调整时限 / 启停 | 值班长+ |
| `GET /api/personnel/certificates` | 证书台账 | 登录 |
| `POST /api/personnel/certificates` | 登记发证 | 值班长+ |
| `POST /api/personnel/certificates/{id}/revoke` | 吊销证书 | 值班长+ |
| `GET /api/personnel/capabilities` | 能力项字典与持证人数 | 登录 |
| `GET /api/personnel/alerts?days=30` | 已过期 / 临期预警 | 登录 |
| `GET /api/personnel/summary` | 资质合规看板 | 登录 |
| `GET /api/weighing/tasks` · `POST /api/weighing/tasks` | 称量任务列表 / 创建 | 登录 |
| `POST /api/weighing/tasks/{id}/weigh` | 登记称量（容差判定） | 称量资质 |
| `POST /api/weighing/items/{id}/review` | 超差复核 | 质检员+ |
| `GET /api/weighing/summary` | 称量合规看板 | 登录 |
| `GET /api/dashboard/sites` | 厂区主数据 | 登录 |
| `GET /api/batches?site=SITE-01` | 批次按厂区过滤 | 登录 |

演示账号：admin/admin123（管理员）· supervisor/super123（值班长）· qc/qc12345（质检员）·
operator/op12345（工艺员）· **temp/temp123（滨海工厂外包操作工，健康证已过期 → 资质门禁演示）**

自检：`node scripts/verify-phase3.mjs`（P3 专项，~24 项）

---

---

## 阶段 H · 三份遗留 Spec 模块落地（2026-09-14 完成 H1–H3）

> 背景：`specs/equipment-management`、`recipe-management`、`traceability` 三份 tasks.md 共 36 项任务
> 此前只有文档、没有代码，三个 Controller 仍在读 FixtureStore（重启丢数据、版本无法受控、追溯链为硬编码示例）。
> 本阶段补齐：新增 8 张表、3 组 REST 端点、3 个前端组件。

### H1 · 设备管理（T1–T11）
- ✅ `equipment` / `equipment_event` / `maintenance_order` 三表；fixture 设备台账幂等导入，状态分布覆盖运行/清洗/故障
- ✅ `GET /api/equipment` 改读 PG，与 fixture 展示数据（实时参数与趋势）在服务层缝合，前端契约零变更
- ✅ **T5 状态机**：RUNNING/IDLE/CLEANING/ALARM/MAINTENANCE/STOPPED，变更写事件流水 + 审计；
      上一段事件自动补 `ended_at` 以支撑停机时长统计
- ✅ **T6/T7 保养**：`GET /api/equipment/alerts?withinDays=` 到期与逾期预警；维护工单完成后回填运行小时并按 `cycleDays` 顺延下次保养
- ✅ **T8 校准阻断**：超期设备被移出 `GET /api/equipment/available`（FR-10），以其建批次返回 409；
      批次放行时该批检验数据同样被拒（FR-8 / C5）；`CALIBRATION` 类工单完成后自动续期一年解除门禁
- ✅ **T9/T10**：设备详情聚合关联报警 / 在制批次 / 维护工单 / 状态事件；OEE 三因子可用率取自事件停机时长，
      无事件时回落采集值并标注「数据不足」（plan R1，不伪造数值）
- ✅ 前端 `components/equipment-panel.tsx`：合规总览条 + 保养校准卡 + OEE 卡 + 工单列表 + 状态变更

### H2 · 配方版本受控（T1–T11）
- ✅ `recipe_version` / `recipe_step` / `recipe_change` 三表；NFR-3 生效唯一性由数据库部分唯一索引
      `uq_recipe_effective` 强制 + 事务内「先置旧版 obsolete 再抬新版 effective」双保险
- ✅ 种子从 fixture 导入 6 个版本（含 effective/draft/obsolete 三态）及其工序参数容差
- ✅ `GET /api/recipes` 保留原契约（`history` 仍为 `{v,date,by,note}`），并行提供 `versions` / `versionRole` 字段
- ✅ **FR-4~FR-6 生命周期**：建草稿（次版本递增 v3.2→v3.3）→提交（draft→pending）→审批（pending→effective/rejected）
      →原子生效；非法流转返回 409，审批与生效限定管理员（C4）
- ✅ **FR-7 影响面**：列出执行旧版本的在制批次；CCP 参数上下限变更时自动标记「需重评检验方法」
- ✅ **FR-8 版本快照**：`BatchService` 通过 `resolveVersionForBatch` 自动锁定生效版本；表单传简写（如 `v3`）也能解析到实际版本；
      显式引用失效版本建单返回 400
- ✅ 前端 `components/recipe-panel.tsx`：版本表（带生命周期操作按钮）+ 建草稿表单 + 影响面卡片

### H3 · 追溯闭环（traceability 剩余项）
- ✅ 复用 Phase E 已落地的 `material_lot` / `batch_input`（不重建同名表，见「已知风险」），
      新增 `batch_genealogy`（批间父子）与 `trace_query_log`（查询审计）两表
- ✅ **FR-1 正向链路**改由真实数据组装：中间品 → 原料投入 → 工序 → 主设备 → 检验放行 → 入库，
      兼容前端 `traceChains` 节点契约 `{type,label,title,value,meta}`
- ✅ **FR-3 逆向** `GET /api/trace/backward?lotNo=`；**FR-4 影响面** `GET /api/trace/impact?lotNo=`（含处置建议，下游展开 ≤5 层）
- ✅ **FR-5 横向关联**：节点上聚合关联设备报警与批次偏差单
- ✅ **FR-8 完整性**：缺投料/设备/操作员/配方版本快照/COA 即标记「档案不完整」并列出缺失项
- ✅ **FR-6 导出**：`GET /api/trace/{batchId}/export` 生成 Markdown 报表，含生成时间与操作人水印
- ✅ **FR-7 审计**：查询与导出均写 `trace_query_log` + `audit_log`；`GET /api/trace/logs` 可查
- ✅ 前端 `components/trace-panel.tsx`：完整性卡 + 按料批反查 + 影响面 + 导出按钮 + 审计流水

### 新增端点速查

| 端点 | 说明 | 权限 |
| --- | --- | --- |
| `GET /api/equipment` · `/{code}` | 台账（?status=&keyword=）/ 详情聚合 | 登录 |
| `POST /api/equipment/{code}/status` | 状态变更，写事件与审计 | 工艺员+ |
| `GET /api/equipment/{code}/oee` | OEE 三因子分解 | 登录 |
| `GET /api/equipment/alerts` | 保养到期 + 校准超期预警 | 登录 |
| `GET /api/equipment/available` | 批次建单可用设备（FR-10） | 登录 |
| `GET·POST /api/equipment/maintenance-orders` | 工单列表 / 创建 | 创建需值班长+ |
| `POST /api/equipment/maintenance-orders/{id}/done` | 完成工单，顺延保养与校准 | 值班长+ |
| `GET /api/recipes/{code}/versions` · `/history` · `/steps` | 受控版本 / 历史 / 工序容差 | 登录 |
| `POST /api/recipes/{code}/draft` | 建草稿（次版本递增） | 工艺员+ |
| `POST /api/recipes/{code}/versions/{v}/submit` | 提交审批 | 工艺员+ |
| `POST /api/recipes/{code}/versions/{v}/approve` | 审批通过 / 驳回 | 管理员 |
| `POST /api/recipes/{code}/versions/{v}/activate` | 原子生效切换 | 管理员 |
| `GET /api/recipes/{code}/impact` | 变更影响分析 | 登录 |
| `GET /api/trace/chain/{batchId}` | 真实数据正向 / 逆向链路 | 登录 |
| `GET /api/trace/backward?lotNo=` · `/impact?lotNo=` | 逆向追溯 / 影响面 | 登录 |
| `GET /api/trace/{batchId}/completeness` · `/export` | 完整性校验 / 报表导出 | 登录 |
| `GET /api/trace/logs` · `POST /api/trace/genealogy` | 查询审计 / 登记流转关系 | 登录 |

自检：`node scripts/verify-phase4.mjs`（Phase H 专项，~40 项）

---

## 阶段 I · 生产执行深化（2026-09-15 完成 I1–I4）

目标：让 OEE 从「替代口径算出来的数字」变成**可下钻到报工与停机事实**的执行结果，
并补齐「工单 → 派工 → 报工 → 停机」四层执行证据链（对应 `specs/production-execution/`）。

### I1 · 工单与派工

- 建表 `work_order` / `work_order_assignment`（`db/schema-p5.sql`，随启动幂等执行）
- 工单继承批次**产线/厂区/产品/配方版本快照**（批次后续换版不影响已建工单）
- 状态机 `CREATED → RELEASED → RUNNING → FINISHED → CLOSED`，跳级与重复流转返回 409，全程写审计
- 派工门禁：账号启用 + 有效健康证 + 岗位资质（复用 G2 `capability_dict`），缺证 403 并列出缺失项；
  同一人同时段重复派工提示冲突（`confirmConflict=true` 可强制）；撤销为软删留痕

### I2 · 工序报工

- 建表 `step_report`，唯一索引 `uq_sr_idem` 保证幂等（同工单+工序+报工人+开工时间）
- 报工回写 `batch_step`（status=DONE + actualParams JSON + operator/reviewer）
- **批次 `progress` 改由报工推导**：`max(工序推推进度, 累计合格 ÷ 计划 × 100)`，上限 100
- 关键工序强制双人复核（复核人 ≠ 报工人）；累计合格超计划 110% 拒绝；已放行批次 409

### I3 · 停机与原因码

- 建表 `downtime_reason`（9 条种子）与 `downtime_event`
- `planned` **由原因码 category 推导**，不由调用方指定：`CHANGEOVER / CLEANING / NO_ORDER` 属计划性损失
- 同设备时间窗重叠自动**合并**为一条（`merged=true`），不重复扣减可用率
- 与 `equipment_event` 重叠段标注 `mergedFrom`（OEE 以停机事件为准）
- 超过原因码阈值（如 `MECH` 120min）自动生成 major 报警

### I4 · 真实 OEE 与兜底清除

```
可用率 =（计划生产工时 − 计划外停机工时）÷ 计划生产工时
性能率 = 标准工时 ÷ 实际生产工时        （报工事实，非「产量 ÷ 产能」）
良品率 = 合格量 ÷（合格量 + 废次品量）
OEE   = 可用率 × 性能率 × 良品率
```

- 新增 `oee_rollup` 预汇总表 + `/api/execution/oee/recompute`（按产线 × 日 × 班次 upsert）
- **已删除的硬编码兜底**：驾驶舱 `92.1 / 96.5 / 33.4 / Math.min(120, …)`、设备页回落 fixture 采集值；
  数据不足时因子为 `null` 且 `dataSufficient=false` + `missing` 说明，前端展示「数据不足」
- 设备维度可用率 = 作业 ÷ (作业 + 计划外停机)，计划外停机优先取 `downtime_event`，
  未覆盖段回落 `equipment_event`（FR-18 不重复计）
- 停机原因帕累托（降序 + 累计百分比，计划/非计划分列）

### 新增端点速查

| 端点 | 说明 | 权限 |
| --- | --- | --- |
| `GET /api/execution/orders` | 工单列表（?batchId=&line=&site=&status=&shift=&from=&to=） | 登录 |
| `POST /api/execution/orders` | 建工单（继承批次快照） | 值班长+ |
| `GET /api/execution/orders/{id}` | 详情（派工 + 报工 + 停机聚合 + nextStatus） | 登录 |
| `POST /api/execution/orders/{id}/release` · `/start` · `/finish` · `/close` | 状态流转 | 值班长+ / 工艺员+ / 值班长+ / 管理员 |
| `POST /api/execution/orders/{id}/dispatch` | 派工（资质 + 健康证门禁） | 值班长+ |
| `DELETE /api/execution/orders/{id}/dispatch/{aid}` | 撤销派工（软删） | 值班长+ |
| `GET·POST /api/execution/orders/{id}/reports` | 报工明细 / 提交报工（幂等） | 提交需工艺员+ |
| `GET·POST /api/execution/downtime` | 停机列表 / 录入（自动合并 + 超阈值报警） | 录入需工艺员+ |
| `GET /api/execution/downtime/reasons` | 原因码字典（含是否计划） | 登录 |
| `GET /api/execution/oee` | 真实 OEE（?site=&line=&equipment=&shift=&from=&to=） | 登录 |
| `POST /api/execution/oee/recompute` | 重算产线日汇总 | 值班长+ |
| `GET /api/execution/oee/pareto` | 停机原因帕累托 | 登录 |

### 种子与前端

- `core/P5Seeder`（@Order 140，幂等）：按现有批次回填 12 张工单（`source=BACKFILL`）+ 报工 + 停机，
  使移除兜底后驾驶舱仍有真实且非极端的 OEE 可展示；二次启动自动跳过
- 前端新增「生产执行」页（`pages/Execution.jsx` + `api/execution.ts`）：工单看板、派工、报工、
  停机录入、OEE 三因子卡（数据不足降级）、停机帕累托
- `Dashboard.jsx` 同步整改：OEE 与日产量移除前端兜底（`87.7` / `33.4`），改为 `—` + 成因说明

### 自检

```bash
bash scripts/dev-up.sh             # 起 PG + 后端 + Phase 1 & Phase I 自检
bash scripts/dev-up.sh --all       # 追加 Phase E / G / H 全量回归
node scripts/verify-phase5.mjs     # 单独运行 Phase I 自检（37 项）
```

---

## 阶段 J · 外部系统集成（2026-09-24 完成 P0 + P1）

**问题**：章程要求集成 SCADA/OPC-UA、LIMS、ERP、WMS 四个外部系统，但本地没有任何一台真设备，
接口一直没落地——四个系统在代码里是零。同时「设备参数趋势」是前端现场编造的伪序列
（`genSeries()`），刷新一次换一批随机数，**没有任何历史**。

**结论**：本地没有设备，恰恰是**必须先把 Mock 做好**的理由——规范驱动（Spec Kit）的
价值就在这里：契约先立，Mock 与真实是同一接口的两个实现。详细方案见
`docs/integration-mock-plan.md`（含三层 Mock 口径、实施偏差、实测记录）。

### J1 · 契约与开关基建（P0）

- `fluxmes.integration.{scada,lims,erp,wms}.{mode,endpoint,timeout-ms,retries}` +
  `alarm-simulator-enabled` / `collect-interval-seconds` 等，**用配置项而非 `@Profile`**——
  现场改造几乎不可能四套同时上线，必须支持「部分 mock、部分 real」的过渡态
- 4 个 Port 接口 + 6 个 DTO（契约先行）；mock 与 real 通过
  `@ConditionalOnProperty(havingValue="mock"|"real")` 二选一装配
- `db/schema-p7.sql`：建 `equipment_metric` 时序表（设计早已存在但从未创建）+
  `alarm.data_source` 列
- **收口伪序列**：删前端 `genSeries()` 与后端 `EquipmentService.genSeries()`，
  伪序列唯一来源变为 `MockEquipmentMetricAdapter.backfill()`，采样带 `dataSource=MOCK`

### J2 · 三层 Mock（P1）

| 层 | 形态 | 覆盖的风险 |
| --- | --- | --- |
| L1 | 进程内 Fake（4 个 `Mock*Adapter`） | 业务逻辑与契约 |
| L2 | 独立 HTTP 服务 `tools/ext-simulator`（9100） | HTTP 契约、超时、重试、故障注入、幂等 |
| L3 | **真 OPC-UA Server**（Eclipse Milo 0.6.16，4840） | NodeId 寻址、浏览、类型映射、StatusCode 质量码、SourceTimestamp |

L3 是本阶段最值钱的部分：OPC-UA 的风险全在协议层，用假数组一个都验不到。
MES 侧写的是**真的 OPC-UA 客户端代码**（`OpcUaEquipmentMetricAdapter`），
将来接真 SCADA 只改 `endpoint` 与（必要时）地址空间映射的那一处。

### J3 · 数据来源必须可辨识（章程 P4）

所有来自外部系统的数据在 API 响应里带 `dataSource`（`MOCK`/`OPCUA`/`LIMS`/`ERP`/`WMS`/`MANUAL`）。
前端设备页新增「外部系统集成」状态条 + 来源徽标 + 参数表来源列；
**采集不到时显示「暂无趋势 / 来源未知」，不再用伪曲线填充**——
把「链路不通」暴露出来，比让它看起来正常更有价值。

### J4 · 新增端点

| 端点 | 说明 | 权限 |
| --- | --- | --- |
| `GET /api/integration/health` | 四系统模式 + 连通状态 + 来源标识（只读最近已知状态，无副作用） | 登录 |
| `GET /api/integration/summary` | 集成总览 + 采集器统计 | 登录 |
| `POST /api/integration/{system}/probe` | 主动探测（有副作用：真读一次） | 值班长+ |
| `POST /api/integration/scada/collect` | 立即采集一次设备参数 | 值班长+ |
| `POST /api/integration/lims/results` · `/lims/reset` | 拉取 / 重置 LIMS 回流（消费式） | 管理员 |
| `POST /api/integration/erp/work-orders` | 拉取 ERP 待开工工单 | 管理员 |
| `POST /api/integration/wms/test` | 试一次入库申请（不落库） | 管理员 |

### J5 · 自检与联调工具

```bash
bash scripts/sim-up.sh            # 一键起两个模拟器（OPC-UA 4840 / HTTP 9100）
bash scripts/sim-up.sh --stop     # 停掉 → 演练「外部系统不可用」时的降级
node scripts/verify-integration.mjs   # Phase J 端到端自检（J1–J7，需 PG + 后端）

# 不起 Spring / 不连库，单独验协议（排查链路问题最快的方式）
cd apps/api-java
bash ../scripts/mvn.sh test-compile
bash ../scripts/mvn.sh dependency:build-classpath -Dmdep.outputFile=target/cp.txt -Dmdep.includeScope=test
java -cp "target/classes;target/test-classes;$(cat target/cp.txt)" \
     com.fluxmes.api.integration.opcua.OpcUaProbe
java -cp "target/classes;target/test-classes;$(cat target/cp.txt)" \
     com.fluxmes.api.integration.rest.RestAdaptersProbe
```

### J6 · 遗留（P2）

- **故障演练的人工观察**：`sim-up.sh --stop` 停掉 SCADA 后，设备页应显示来源未知 / 暂无趋势，
  需在本机看着页面确认（代码路径与断言已就位）
- **真实 SCADA 的地址空间差异**：现场网关组态大概率与模拟器不同，
  需改 `OpcUaEquipmentMetricAdapter` 的浏览映射逻辑（那是唯一的设计耦合点）
- **签名/加密**：当前只支持匿名 + SecurityPolicy=None；现场若要求证书需扩展
  `selectEndpoint` 与客户端证书配置
- **订阅 vs 轮询**：当前用批量 Read（周期 30s 级）；若出现「需秒级捕捉瞬时越限」的需求
  再引入 OPC-UA Subscription
- **长期留存**：`equipment_metric` 默认只留 7 天（30s × 24 指标 ≈ 6.9 万行/天），
  长期留存依赖后续汇总/归档

---

## 已知风险与解决

1. **追溯 plan 与已落地 F2 表冲突**：`specs/traceability/plan.md` 原计划新建 `material_lot` / `batch_material`，
   但 Phase E (F2) 已落地同名/近义表（`material_lot` / `batch_input`）。重建会破坏既有数据，
   故本期**复用 F2 表**，仅新增 `batch_genealogy` 与 `trace_query_log` 两表，同时消除 plan R1 的双写风险。

## 已知风险与解决

1. **SB4 Jackson 3 迁移**：Spring Boot 4 默认 tools.jackson.*；现有代码用 com.fasterxml.*。✅ 已解决：显式保留 jackson-databind 2.x 依赖（vibe-erp 同款做法）。
2. **springdoc 与 SB4 不兼容**：✅ 已解决：移除 springdoc，改手写 `/v3/api-docs`（见 A5）。
3. **Docker daemon**：⚠️ 沙箱安全策略阻止（wsl.exe 黑名单 + 拒绝对 `~/.docker` 访问），PG 容器需**用户本机手动启动**（命令见下）。
4. **构建沙箱**：mvn 命令需加 `CODEBUDDY_SAFE_DELETE_ENABLED=0` 前缀；Git Bash 里 mvn 脚本失效时改用
   `java -cp .../plexus-classworlds-2.11.0.jar -Dclassworlds.conf=...\m2.conf -Dmaven.home=... -Dmaven.multiModuleProjectDirectory=<项目绝对路径> org.codehaus.plexus.classworlds.launcher.Launcher package`
5. **前端 @types/react 19 与 react 18 混装**：devDependencies 是 ^19.2.18，运行时 react 18.3——不影响构建（vite build 已通过）。

## 启动方式（用户本机执行）

```bash
# 推荐：一键启动（起 PG → 起后端 → 跑 20 项自检；加 --frontend 附带 Vite）
bash scripts/dev-up.sh
bash scripts/dev-up.sh --frontend

# 等价的分步命令：
# 1) PostgreSQL 容器（首次）
docker run -d --name fluxmes-pg -e POSTGRES_USER=fluxmes -e POSTGRES_PASSWORD=fluxmes \
  -e POSTGRES_DB=fluxmes -p 5433:5432 postgres:16     # 5432 已被 vibe-erp 占用
# 或：bash deploy/run-pg.sh

# 2) 后端（自动建表 + 种子数据；schema.sql 幂等，含 Phase D 迁移）
cd apps/api-java && java -jar target/api-java-0.2.0.jar     # 端口 8080
# 契约：http://localhost:8080/v3/api-docs

# 3) 前端
cd fluxmes && npm run dev                                    # 端口 5173，/api 代理到 8080

# 4) 端到端自检脚本（需后端已启动）
node scripts/verify-phase1.mjs          # Phase 1/D 回归（20 项）
node scripts/verify-food-safety.mjs     # Phase E 食品行业（26 项）
node scripts/verify-phase3.mjs          # Phase G · P3 增强（24 项）
```

后端构建：`bash scripts/mvn.sh compile | package`（封装了 Maven Launcher 启动与
`CODEBUDDY_SAFE_DELETE_ENABLED=0` 前缀）。前端测试：`cd fluxmes && npm test`。

Phase D 新增端点速查：

| 端点 | 说明 | 权限 |
| --- | --- | --- |
| `GET /api/dashboard/lines` | 产线主数据与各线批次统计 | 登录 |
| `GET /api/dashboard/cockpit?line=LINE-1` | 按产线聚合 KPI | 登录 |
| `GET /api/dashboard/shift-report?date=&shift=&line=` | 班报（JSON + Markdown） | 登录 |
| `GET /api/batches/lines`、`GET /api/batches?line=` | 产线列表 / 批次按产线过滤 | 登录 |
| `GET /api/alarms/suppressions` | 抑制规则列表 | 登录 |
| `POST /api/alarms/suppressions` | 新建抑制规则 | 值班长+ |
| `DELETE /api/alarms/suppressions/{id}` | 删除抑制规则 | 值班长+ |
| `POST /api/alarms/sla/sweep` | 手动触发 SLA 巡检 | 值班长+ |

演示账号：admin/admin123（管理员）· supervisor/super123（值班长）· qc/qc12345（质检员）· operator/op12345（工艺员）
