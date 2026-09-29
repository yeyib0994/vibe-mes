-- FluxMES Phase I · 质量体系 RegTech（PostgreSQL 16，全部幂等）
-- J1 CAPA 闭环 / J2 内审管理 / J3 电子签名（21 CFR Part 11 · EU GMP 附录 11 对齐）
-- 口径约定：时间戳统一 TIMESTAMPTZ（服务端 Asia/Shanghai 写入，D6 不接受客户端时间）。
-- record_revision：记录内容修订号，电子签名绑定该值（FR-15/FR-18），
--                  内容实质变更时 +1 并作废原签名（NFR-2 append-only，更正 = 作废 + 新签）。

-- ---------------------------------------------------------------------
-- J1 · CAPA 主记录（不扩展 deviation.capa 文本字段，plan D1）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS capa (
  id                   VARCHAR(32) PRIMARY KEY,          -- CAPA-260915-001
  source_type          VARCHAR(24) NOT NULL,             -- DEVIATION / AUDIT_FINDING / ALARM / MANUAL
  source_id            VARCHAR(64),
  type                 VARCHAR(16) NOT NULL,             -- CORRECTION / CORRECTIVE / PREVENTIVE
  title                VARCHAR(256) NOT NULL,
  description          TEXT,
  root_cause           TEXT NOT NULL,                    -- FR-2 根因必填
  owner                VARCHAR(64) NOT NULL,
  due_date             DATE NOT NULL,
  severity             VARCHAR(16) NOT NULL DEFAULT 'major',   -- critical / major / minor
  status               VARCHAR(20) NOT NULL DEFAULT 'open',
                       -- open / in_progress / pending_verify / verified / closed / rejected
  verification_method  VARCHAR(24),                      -- DOC_REVIEW / SITE_CHECK / DATA_REVIEW
  effectiveness        VARCHAR(16),                      -- EFFECTIVE / INEFFECTIVE
  verify_comment       TEXT,
  reject_reason        TEXT,
  verified_by          VARCHAR(64),
  verified_at          TIMESTAMPTZ,
  closed_by            VARCHAR(64),
  closed_at            TIMESTAMPTZ,
  site                 VARCHAR(32),
  record_revision      INT NOT NULL DEFAULT 1,           -- 签名绑定的记录修订号
  created_by           VARCHAR(64),
  created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_capa_status ON capa(status);
CREATE INDEX IF NOT EXISTS idx_capa_owner  ON capa(owner);
CREATE INDEX IF NOT EXISTS idx_capa_source ON capa(source_type, source_id);
CREATE INDEX IF NOT EXISTS idx_capa_due    ON capa(due_date);
CREATE INDEX IF NOT EXISTS idx_capa_site   ON capa(site);

-- ---------------------------------------------------------------------
-- J1 · CAPA 行动项（FR-2 至少一条；FR-4 未完成项阻断进入 pending_verify）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS capa_task (
  id          BIGSERIAL PRIMARY KEY,
  capa_id     VARCHAR(32) NOT NULL,
  seq         INT NOT NULL,
  action      TEXT NOT NULL,
  owner       VARCHAR(64) NOT NULL,
  due_date    DATE,
  done        BOOLEAN NOT NULL DEFAULT FALSE,
  done_at     TIMESTAMPTZ,
  done_by     VARCHAR(64),
  evidence    TEXT,                                      -- 证据说明 / 附件引用
  remark      TEXT,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_capa_task_capa ON capa_task(capa_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_capa_task_seq ON capa_task(capa_id, seq);

-- ---------------------------------------------------------------------
-- J1 · CAPA 状态流水（审计留痕，配合 audit_log 使用）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS capa_event (
  id          BIGSERIAL PRIMARY KEY,
  capa_id     VARCHAR(32) NOT NULL,
  from_status VARCHAR(20),
  to_status   VARCHAR(20) NOT NULL,
  comment     TEXT,
  operator    VARCHAR(64),
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_capa_event_capa ON capa_event(capa_id, created_at);

-- ---------------------------------------------------------------------
-- J2 · 内审计划（FR-9）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS internal_audit (
  id          VARCHAR(32) PRIMARY KEY,                   -- AUDIT-2026-01
  title       VARCHAR(256) NOT NULL,
  audit_type  VARCHAR(16) NOT NULL,                      -- SYSTEM / PROCESS / PRODUCT / GMP_SELF
  scope       TEXT,
  lead        VARCHAR(64) NOT NULL,
  team        TEXT,                                      -- JSON 数组（TEXT 约定，见项目数据分层）
  plan_start  DATE,
  plan_end    DATE,
  status      VARCHAR(16) NOT NULL DEFAULT 'planned',    -- planned / in_progress / reported / closed
  report_note TEXT,
  closed_by   VARCHAR(64),
  closed_at   TIMESTAMPTZ,
  site        VARCHAR(32),
  created_by  VARCHAR(64),
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_ia_status ON internal_audit(status);
CREATE INDEX IF NOT EXISTS idx_ia_site   ON internal_audit(site);

-- ---------------------------------------------------------------------
-- J2 · 内审发现项（FR-10 四级分级 + 条款关联；FR-11 转 CAPA 后回写 capa_id）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS audit_finding (
  id          BIGSERIAL PRIMARY KEY,
  audit_id    VARCHAR(32) NOT NULL,
  seq         INT NOT NULL,
  clause      VARCHAR(64),                               -- FSSC 22000 8.9.5 / GMP 附录1 §4.3
  severity    VARCHAR(16) NOT NULL,                      -- CRITICAL / MAJOR / MINOR / OBSERVATION
  description TEXT NOT NULL,
  area        VARCHAR(64),
  owner       VARCHAR(64),
  capa_id     VARCHAR(32),
  created_by  VARCHAR(64),
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_af_audit ON audit_finding(audit_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_af_seq ON audit_finding(audit_id, seq);

-- ---------------------------------------------------------------------
-- J3 · 签名适用范围配置（plan D2/D7：门禁不硬编码，企业可开关）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS signature_policy (
  action           VARCHAR(32) PRIMARY KEY,              -- BATCH_RELEASE
  name             VARCHAR(64) NOT NULL,
  record_type      VARCHAR(24) NOT NULL,                 -- BATCH / DEVIATION / CAPA / RECIPE_VERSION
  required_meaning VARCHAR(16) NOT NULL,                 -- AUTHORED/REVIEWED/APPROVED/VERIFIED/WITNESSED
  required_role    VARCHAR(32) NOT NULL,
  enabled          BOOLEAN NOT NULL DEFAULT TRUE,
  note             VARCHAR(256)
);
INSERT INTO signature_policy (action, name, record_type, required_meaning, required_role, enabled, note) VALUES
  ('BATCH_RELEASE',   '成品放行',     'BATCH',          'APPROVED', 'QC',         TRUE, 'C2 批次档案完整性：签名绑定放行时档案内容'),
  ('DEVIATION_CLOSE', '偏差关闭',     'DEVIATION',      'APPROVED', 'SUPERVISOR', TRUE, '偏差单关闭须签名声明「批准关闭」'),
  ('CAPA_CLOSE',      'CAPA 关闭',    'CAPA',           'APPROVED', 'ADMIN',      TRUE, 'CAPA 闭环终点，管理员批准'),
  ('CAPA_VERIFY',     'CAPA 有效性验证', 'CAPA',        'VERIFIED', 'ADMIN',      TRUE, 'FR-6 验证方式与有效性结论须签名确认'),
  ('RECIPE_ACTIVATE', '配方版本生效', 'RECIPE_VERSION', 'APPROVED', 'SUPERVISOR', TRUE, 'NFR-3 生效唯一性由管理员级签名批准'),
  ('EBR_REVIEW',      '批记录复核',   'BATCH',          'REVIEWED', 'QC',         TRUE, '与既有双人复核衔接（policy 配置留档）')
ON CONFLICT (action) DO NOTHING;

-- ---------------------------------------------------------------------
-- J3 · 电子签名（append-only，NFR-2：无 UPDATE/DELETE 语义接口，更正 = 作废 + 新签）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS e_signature (
  id                     BIGSERIAL PRIMARY KEY,
  action                 VARCHAR(32) NOT NULL,
  record_type            VARCHAR(24) NOT NULL,
  record_id              VARCHAR(64) NOT NULL,
  record_version         INT NOT NULL,                   -- 签名时记录的 record_revision
  meaning                VARCHAR(16) NOT NULL,
  signer                 VARCHAR(64) NOT NULL,
  signer_name            VARCHAR(64),                    -- Part 11 §11.50(b) 签名须含姓名
  signer_role            VARCHAR(32) NOT NULL,
  signed_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
  payload_hash           VARCHAR(64) NOT NULL,           -- SHA-256 hex
  hash_algo              VARCHAR(24) NOT NULL DEFAULT 'SHA-256',
  serialize_rule_version VARCHAR(16) NOT NULL DEFAULT 'v1',
  ip                     VARCHAR(64),
  user_agent             VARCHAR(256),
  status                 VARCHAR(12) NOT NULL DEFAULT 'VALID',   -- VALID / VOID
  voided_at              TIMESTAMPTZ,
  voided_by              VARCHAR(64),
  void_reason            VARCHAR(256)
);
CREATE INDEX IF NOT EXISTS idx_sig_record ON e_signature(record_type, record_id);
CREATE INDEX IF NOT EXISTS idx_sig_signer ON e_signature(signer, signed_at);
CREATE INDEX IF NOT EXISTS idx_sig_action ON e_signature(action, status);

-- ---------------------------------------------------------------------
-- J3 · 签名失败计数（plan D5：独立表而非内存，多实例部署安全；与登录失败隔离）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS signature_attempt (
  username        VARCHAR(64) PRIMARY KEY,
  failed_count    INT NOT NULL DEFAULT 0,
  first_failed_at TIMESTAMPTZ,
  locked_until    TIMESTAMPTZ,
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------
-- J3 · 记录修订号（签名绑定的版本来源，FR-15/FR-18）
-- 说明：recipe_version 已有业务列 version(VARCHAR)，故统一取名 record_revision 避免歧义。
-- ---------------------------------------------------------------------
ALTER TABLE batch          ADD COLUMN IF NOT EXISTS record_revision INT NOT NULL DEFAULT 1;
ALTER TABLE deviation      ADD COLUMN IF NOT EXISTS record_revision INT NOT NULL DEFAULT 1;
ALTER TABLE recipe_version ADD COLUMN IF NOT EXISTS record_revision INT NOT NULL DEFAULT 1;
