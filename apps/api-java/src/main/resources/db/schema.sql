-- FluxMES Phase 1 schema（PostgreSQL 16，全部幂等：IF NOT EXISTS）
-- 动态/复合字段（stages、meta、params、before/after 等）以 TEXT 存 JSON 字符串，
-- 由应用层 Jackson 编解码，避免 jsonb 与 TypeHandler 的复杂度。

CREATE TABLE IF NOT EXISTS app_user (
  id            BIGSERIAL PRIMARY KEY,
  username      VARCHAR(64)  NOT NULL UNIQUE,
  password_hash VARCHAR(100) NOT NULL,
  display_name  VARCHAR(64)  NOT NULL,
  role          VARCHAR(32)  NOT NULL,          -- ADMIN / SUPERVISOR / QC / OPERATOR
  enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
  created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS batch (
  id             VARCHAR(32) PRIMARY KEY,        -- B-260826-007
  product        VARCHAR(128),
  recipe         VARCHAR(128),
  recipe_version VARCHAR(32),
  equipment      VARCHAR(128),
  stage          VARCHAR(64),                    -- 当前工序
  status         VARCHAR(32)  NOT NULL,          -- running / waiting / done / abnormal
  params         TEXT,                           -- JSON：关键过程参数
  progress       INT          DEFAULT 0,
  started_at     VARCHAR(32),                    -- 展示用时间（HH:mm / 日期）
  ended_at       VARCHAR(32),
  stages         TEXT,                           -- JSON：[["配料","done"],...]
  meta           TEXT,                           -- JSON：结构化批次档案
  operator       VARCHAR(64),
  plan_yield     NUMERIC(12,2),
  material_lots  TEXT,                           -- JSON：原料批号
  coa_no         VARCHAR(64),                    -- COA 编号（放行后回写）
  warehouse_bin  VARCHAR(64),                    -- 入库位
  deviation_no   VARCHAR(64),                    -- 关联偏差单
  released       BOOLEAN      NOT NULL DEFAULT FALSE,  -- 放行锁定：TRUE 后只读
  created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS alarm (
  id                 VARCHAR(32) PRIMARY KEY,     -- A-260826-017
  time               VARCHAR(16),                 -- 展示时间 HH:mm
  level             VARCHAR(16) NOT NULL,         -- critical / major / minor
  source             VARCHAR(128),
  content            VARCHAR(256),
  value              VARCHAR(64),
  threshold          VARCHAR(64),
  status             VARCHAR(16) NOT NULL,        -- unacked / acked / recovered
  ack_by             VARCHAR(64),
  acked_at           TIMESTAMPTZ,
  recovered_at       TIMESTAMPTZ,
  recovered_by       VARCHAR(64),
  responded_minutes INT,
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_alarm_status ON alarm(status);
CREATE INDEX IF NOT EXISTS idx_alarm_created_at ON alarm(created_at);

CREATE TABLE IF NOT EXISTS audit_log (
  id          BIGSERIAL PRIMARY KEY,
  actor       VARCHAR(64)  NOT NULL,              -- who
  action      VARCHAR(64)  NOT NULL,              -- alarm.ack / batch.release / ...
  entity_type VARCHAR(32)  NOT NULL,              -- alarm / batch / deviation / qc
  entity_id   VARCHAR(64),
  before_data TEXT,                              -- JSON：变更前
  after_data  TEXT,                              -- JSON：变更后
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now() -- when
);
CREATE INDEX IF NOT EXISTS idx_audit_entity ON audit_log(entity_type, entity_id);

CREATE TABLE IF NOT EXISTS recipe (
  code         VARCHAR(32) PRIMARY KEY,
  name         VARCHAR(128),
  product      VARCHAR(128),
  version      VARCHAR(32),
  status       VARCHAR(32),
  updated_by   VARCHAR(64),
  updated_at   VARCHAR(32),
  used_batches INT,
  yield_rate   NUMERIC(6,2),
  stages       TEXT,                             -- JSON
  params       TEXT,                             -- JSON
  history      TEXT                              -- JSON
);

CREATE TABLE IF NOT EXISTS qc_task (
  id               VARCHAR(32) PRIMARY KEY,
  type             VARCHAR(32),
  batch            VARCHAR(32),
  item             VARCHAR(128),
  sample           VARCHAR(64),
  pass             BOOLEAN,
  inspector        VARCHAR(64),
  status           VARCHAR(32),
  standard         VARCHAR(128),                 -- 执行标准（C5 依据标注）
  standard_version VARCHAR(64),
  due              VARCHAR(32),
  completed_at     VARCHAR(32)
);

CREATE TABLE IF NOT EXISTS spc_limit (
  id               BIGSERIAL PRIMARY KEY,
  product          VARCHAR(128) NOT NULL,
  feature          VARCHAR(64)  NOT NULL,          -- 质量特性，如「柠檬酸含量」
  ucl              NUMERIC(10,4) NOT NULL,
  cl               NUMERIC(10,4) NOT NULL,
  lcl              NUMERIC(10,4) NOT NULL,
  unit             VARCHAR(16),
  standard_version VARCHAR(64),                   -- GB 1886.25—2016 等，版本变更留痕（C5）
  updated_by       VARCHAR(64),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (product, feature)
);

CREATE TABLE IF NOT EXISTS deviation (
  id           VARCHAR(32) PRIMARY KEY,           -- DEV-260826-002
  batch_id     VARCHAR(32),
  source       VARCHAR(32) NOT NULL,              -- spc / manual / alarm
  description  TEXT,
  status       VARCHAR(32) NOT NULL,              -- open / investigating / capa / closed
  root_cause   TEXT,
  capa         TEXT,
  created_by   VARCHAR(64),
  closed_by    VARCHAR(64),
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  closed_at    TIMESTAMPTZ
);

-- ============================================================
-- Phase D 迁移（幂等 ALTER，兼容已建库）
-- ============================================================

-- D1 · 报警 SLA 与抑制：按级别响应时限 + 超时升级标记 + 抑制键
ALTER TABLE alarm ADD COLUMN IF NOT EXISTS sla_minutes     INT      NOT NULL DEFAULT 15;
ALTER TABLE alarm ADD COLUMN IF NOT EXISTS escalated       BOOLEAN  NOT NULL DEFAULT FALSE;
ALTER TABLE alarm ADD COLUMN IF NOT EXISTS escalated_at    TIMESTAMPTZ;
ALTER TABLE alarm ADD COLUMN IF NOT EXISTS escalation_note VARCHAR(256);
ALTER TABLE alarm ADD COLUMN IF NOT EXISTS suppression_key VARCHAR(128);
CREATE INDEX IF NOT EXISTS idx_alarm_escalated ON alarm(escalated) WHERE escalated = TRUE;

-- D1 · 抑制规则：同 key 在窗口期内重复触发的报警只计数不入库（防抖/闪避）
CREATE TABLE IF NOT EXISTS alarm_suppression (
  id            BIGSERIAL PRIMARY KEY,
  name          VARCHAR(128) NOT NULL,
  source        VARCHAR(128),                     -- 报警源（精确匹配，可空=通配）
  content       VARCHAR(256),                     -- 报警内容关键字（包含匹配，可空=通配）
  level         VARCHAR(16),                      -- 级别（可空=通配）
  window_minutes INT         NOT NULL DEFAULT 10, -- 抑制窗口（分钟）
  enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
  reason        VARCHAR(256),
  created_by    VARCHAR(64),
  created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- D2 · 多产线：批次归属产线
ALTER TABLE batch ADD COLUMN IF NOT EXISTS line VARCHAR(32);
UPDATE batch SET line = 'LINE-1' WHERE line IS NULL;
CREATE INDEX IF NOT EXISTS idx_batch_line ON batch(line);
CREATE INDEX IF NOT EXISTS idx_batch_status ON batch(status);

-- D2 · 产线主数据
CREATE TABLE IF NOT EXISTS production_line (
  code        VARCHAR(32) PRIMARY KEY,            -- LINE-1
  name        VARCHAR(128) NOT NULL,
  workshop    VARCHAR(64),
  capacity_t  NUMERIC(8,2),                       -- 日设计产能（吨）
  enabled     BOOLEAN NOT NULL DEFAULT TRUE
);
INSERT INTO production_line (code, name, workshop, capacity_t)
VALUES ('LINE-1', '柠檬酸发酵一线', '一车间', 48.0),
       ('LINE-2', '柠檬酸发酵二线', '一车间', 36.0),
       ('LINE-3', '精制包装线', '二车间', 24.0)
ON CONFLICT (code) DO NOTHING;
