# 质量体系 RegTech · 技术计划（plan）

- **模块**：quality-regtech
- **依赖**：`spec.md`、`specs/constitution.md`
- **版本**：v1.0 · 2026-09-14
- **落地阶段**：Phase I（紧随 production-execution 之后）

## 1. 架构与数据模型

新增 `resources/db/schema-p6.sql`（幂等）：

| 表 | 用途 | 关键字段 |
| --- | --- | --- |
| `capa` | CAPA 主记录 | id(PK `CAPA-yyMMdd-seq`)、source_type(DEVIATION/AUDIT_FINDING/ALARM/MANUAL)、source_id、type(CORRECTION/CORRECTIVE/PREVENTIVE)、title、description、root_cause、owner、due_date、status、verification_method、effectiveness、verified_by、verified_at、closed_by、closed_at、site、created_by、created_at、updated_at |
| `capa_task` | CAPA 行动项 | id、capa_id、seq、action、owner、due_date、done、done_at、evidence、remark |
| `capa_event` | CAPA 状态流水 | id、capa_id、from_status、to_status、comment、operator、created_at |
| `internal_audit` | 内审计划 | id(PK `AUDIT-yyyy-nn`)、title、audit_type(SYSTEM/PROCESS/PRODUCT/GMP_SELF)、scope、lead、team(JSON)、plan_start、plan_end、status、closed_by、closed_at、site |
| `audit_finding` | 内审发现项 | id、audit_id、seq、clause、severity(CRITICAL/MAJOR/MINOR/OBSERVATION)、description、area、owner、capa_id、created_by、created_at |
| `signature_policy` | 签名适用范围配置 | action(PK，如 `BATCH_RELEASE`)、record_type、required_meaning、required_role、enabled、note |
| `e_signature` | 电子签名（append-only） | id、record_type、record_id、record_version、meaning、signer、signer_role、signed_at、payload_hash、hash_algo、serialize_rule_version、ip、user_agent、status(VALID/VOID)、voided_at、voided_by、void_reason |
| `signature_attempt` | 签名失败计数（多实例安全） | username(PK)、failed_count、first_failed_at、locked_until、updated_at |

**不新增**认证库：密码二次校验复用 `JwtService` / `AuthFilter` 已有的 BCrypt 校验路径，
哈希使用 JDK 内置 `MessageDigest SHA-256`。

### 状态机

```
capa:  open ──> in_progress ──> pending_verify ──> verified ──> closed
                     ▲                │
                     └──── rejected ──┘   （验证无效退回，行动项重开）

internal_audit: planned ──> in_progress ──> reported ──> closed
```

## 2. 关键决策

| # | 决策 | 理由 |
| --- | --- | --- |
| D1 | CAPA 独立成表，**不扩展** `deviation.capa` 文本字段 | 文本字段无法承载责任人/期限/验证/有效性；保留旧字段仅作兼容展示 |
| D2 | 签名适用范围走 `signature_policy` **配置表** | 避免硬编码在业务代码，企业可按 GMP 要求开关（plan D4 与章程 §7 一致） |
| D3 | `e_signature` **只追加**，作废用 `status=VOID` + 新签 | Part 11 要求签名记录不可删改；更正必须留痕 |
| D4 | 哈希输入采用 **canonical JSON**（字段白名单 + 固定顺序 + 排除 `updated_at` 等易变字段），规则版本化 | 无害更新（如更新时间）不应触发篡改误报（NFR-5） |
| D5 | 失败计数独立表而非内存 | 多实例部署下内存计数无效（NFR-4） |
| D6 | 时间统一用服务端 `Asia/Shanghai`，不接受客户端时间 | 签名时间戳须由系统生成（Part 11 §11.10(e)） |
| D7 | 放行 / 偏差关闭的签名门禁**默认开启**，可用 `signature_policy.enabled=false` 临时关闭 | 演示环境可降级；生产默认合规 |

## 3. 集成点与端点设计

包路径：`com.fluxmes.api.regtech`（`CapaController` / `CapaService` / `AuditProgramController` / `SignatureService`）。

| 端点 | 说明 | 权限 |
| --- | --- | --- |
| `GET·POST /api/capa` | CAPA 列表（?status=&owner=&overdue=&site=）/ 创建 | 创建需 QC+ |
| `GET /api/capa/{id}` | 详情（行动项 + 状态流水 + 签名） | 登录 |
| `POST /api/capa/{id}/transition` | 状态流转（FR-3/FR-4） | QC+ |
| `POST /api/capa/{id}/tasks` · `POST /api/capa/tasks/{tid}/done` | 行动项新增 / 完成（含证据） | 责任人或 QC+ |
| `POST /api/capa/{id}/verify` | 有效性验证（FR-6），需电子签名 | 管理员 / QC 主管 |
| `POST /api/capa/{id}/close` | 关闭（FR-8），需电子签名 | 管理员 |
| `GET /api/capa/overdue` | 临期与逾期清单（FR-5） | 登录 |
| `GET·POST /api/internal-audit` | 内审计划列表 / 创建 | 创建需管理员 |
| `POST /api/internal-audit/{id}/findings` | 登记发现项（FR-10） | 管理员 / QC |
| `POST /api/internal-audit/findings/{fid}/to-capa` | 发现项转 CAPA（FR-11） | QC+ |
| `POST /api/internal-audit/{id}/close` | 关闭（FR-12 门禁） | 管理员 |
| `POST /api/signatures` | 电子签名（含密码二次确认） | 对应角色（policy 校验） |
| `GET /api/signatures` | 签名清单（?recordType=&recordId=&signer=） | 登录；审计导出需管理员 |
| `GET /api/signatures/{id}/verify` | 哈希复核（FR-19） | 登录 |
| `GET /api/signatures/export` | 签名证据导出（Markdown / JSON） | 管理员 |
| `GET /api/signatures/policy` | 签名适用范围配置 | 登录（只读） |

**既有接口契约变更**（需同步前端与自检脚本）：

- `POST /api/quality/release`（批次放行）→ 若 `signature_policy.BATCH_RELEASE.enabled`，
  请求体需带 `signature: { meaning, password }`，否则 409。
- `POST /api/quality/deviations/{id}/transition`（关闭）→ 校验关联 CAPA 全部 closed（FR-7），
  未满足返回 409 + CAPA 编号列表。

## 4. 合规与安全设计

- **C2 批次档案完整性**：签名绑定记录版本，档案变更后原签名失效（FR-18）。
- **C3 审计追踪**：CAPA 流转、验证、关闭，内审登记与关闭，签名与失败锁定全部写 `audit_log`；
  签名记录本身 append-only。
- **C4 RBAC**：创建 CAPA=QC+；验证/关闭=管理员；内审创建与关闭=管理员；签名按 `required_role` 校验。
- **C5 依据标注**：内审发现项记录条款号（FSSC 22000 / GMP 附录），标准版本变更留痕。
- **C6 数据保留**：CAPA / 内审 / 签名 ≥ 3 年。
- **Part 11 映射**：
  - §11.10(a) 记录完整性 → payload 哈希 + 篡改检测
  - §11.10(b) 记录可读可导出 → `/export`
  - §11.50(a) 签名人身份唯一 → 实名账号、禁共享账号（FR-16）
  - §11.50(b) 签名含姓名/日期时间/含义 → FR-15
  - §11.100(c) 签名与记录绑定 → record_type + record_id + record_version
  - §11.200 识别码 / 密码管理 → 密码二次确认 + 失败锁定（FR-17）

## 5. 风险

| # | 风险 | 缓解 |
| --- | --- | --- |
| R1 | 哈希误报篡改（无害字段变更） | canonical JSON 白名单 + 规则版本化（D4）；升级规则时对历史签名按 `serialize_rule_version` 分别复算 |
| R2 | 签名门禁破坏现有自检脚本 | 自检脚本同步改造；提供 `signature_policy.enabled=false` 的测试旁路 |
| R3 | BCrypt 校验增加放行延迟 | 签名接口独立，仅受控动作调用；P95 < 500ms 目标 |
| R4 | 与既有 `deviation.transition` 顺序冲突 | 固定顺序：行动项完成 → CAPA 验证 → CAPA 关闭 → 偏差关闭 → 批次放行 |
| R5 | 时间源不一致 | 统一服务端 `Asia/Shanghai`（与 `DashboardController.ZONE` 一致），禁止客户端传时间 |
| R6 | 导出格式随前端改版失效 | 导出在**服务端**渲染（Markdown/JSON），与前端无关 |
