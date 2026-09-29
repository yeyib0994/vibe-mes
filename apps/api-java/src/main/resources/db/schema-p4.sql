-- FluxMES Phase H · 三份遗留 Spec 模块落地（PostgreSQL 16，全部幂等）
-- H1 设备管理 / H2 配方版本受控 / H3 追溯闭环

-- =====================================================================
-- H1 · 设备管理（equipment-management T1）
-- ---------------------------------------------------------------------
-- 主数据：编号为主键，状态与运转计数由本表承载；实时参数与趋势序列仍由
-- FixtureStore 提供（plan D3：真实采集 Phase 2 接入后再迁时序库）。
-- =====================================================================
CREATE TABLE IF NOT EXISTS equipment (
  code                  VARCHAR(32) PRIMARY KEY,          -- F-101
  name                  VARCHAR(128) NOT NULL,            -- 发酵罐 #1
  model                 VARCHAR(128),                     -- 型号
  category              VARCHAR(32),                      -- FERMENTER / MIXING / EVAPORATOR / CRYSTALLIZER / DRYER / PACKING / STERILIZER
  location              VARCHAR(64),                      -- 一车间发酵区
  site_code             VARCHAR(32) DEFAULT 'SITE-01',    -- G4 厂区归属
  line                  VARCHAR(32),                      -- 所属产线
  vol                   VARCHAR(32),                      -- 有效容积/能力描述（展示用）
  status                VARCHAR(16) NOT NULL DEFAULT 'RUNNING',
                                                          -- RUNNING / IDLE / CLEANING / ALARM / MAINTENANCE / STOPPED
  criticality           VARCHAR(8) NOT NULL DEFAULT 'B',   -- A 关键 / B 重要 / C 一般（影响批次开庭与放行门禁力度）
  runtime_hours         NUMERIC(12,1) NOT NULL DEFAULT 0,  -- 累计运行小时
  mtbf_hours            NUMERIC(10,1),                     -- 平均无故障小时
  last_maintenance_at   DATE,
  maintenance_cycle_days INT,                              -- 保养周期（天），完工单时顺延
  next_maintenance_at   DATE,
  calibration_item      VARCHAR(128),                      -- 计量器具名称（温度计 / 压力表 / 衡器）
  calibration_due_at    DATE,                              -- 校准有效期至
  enabled               BOOLEAN NOT NULL DEFAULT TRUE,
  updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_equip_status ON equipment(status);
CREATE INDEX IF NOT EXISTS idx_equip_line   ON equipment(line);

-- 状态事件流水（FR-5）：OEE 可用率需要停机时长证据，审计要求变更留痕（C3）
CREATE TABLE IF NOT EXISTS equipment_event (
  id              BIGSERIAL PRIMARY KEY,
  equipment_code  VARCHAR(32) NOT NULL,
  from_status     VARCHAR(16),
  to_status       VARCHAR(16) NOT NULL,
  reason          VARCHAR(256),
  operator        VARCHAR(64),
  started_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  ended_at        TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_eequip_code   ON equipment_event(equipment_code);
CREATE INDEX IF NOT EXISTS idx_eequip_start  ON equipment_event(started_at);

-- 维护工单（FR-6/FR-7）：PREVENTIVE 预防性保养 / CORRECTIVE 故障维修 / CALIBRATION 校准
CREATE TABLE IF NOT EXISTS maintenance_order (
  id               VARCHAR(32) PRIMARY KEY,               -- MO-260914-001
  equipment_code   VARCHAR(32) NOT NULL,
  type             VARCHAR(16) NOT NULL DEFAULT 'PREVENTIVE',
  status           VARCHAR(16) NOT NULL DEFAULT 'OPEN',    -- OPEN / IN_PROGRESS / DONE / CANCELLED
  plan_date        DATE,
  runtime_before   NUMERIC(12,1),
  runtime_after    NUMERIC(12,1),
  done_at          TIMESTAMPTZ,
  done_by          VARCHAR(64),
  created_by       VARCHAR(64),
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  remark           TEXT
);
CREATE INDEX IF NOT EXISTS idx_mo_equip ON maintenance_order(equipment_code);
CREATE INDEX IF NOT EXISTS idx_mo_stat  ON maintenance_order(status);

-- =====================================================================
-- H2 · 配方版本受控（recipe-management T1）
-- ---------------------------------------------------------------------
-- recipe 主表保留为“默认生效视图”（plan D1），版本受控由 recipe_version 承载。
-- =====================================================================
CREATE TABLE IF NOT EXISTS recipe_version (
  id            BIGSERIAL PRIMARY KEY,
  recipe_code   VARCHAR(32) NOT NULL,                     -- R-CA-07
  version       VARCHAR(16) NOT NULL,                     -- v3.2
  name          VARCHAR(128),
  product       VARCHAR(128),
  yield_rate    VARCHAR(32),
  stages        TEXT,                                     -- JSON 工艺路线（暂 TypedHandler 成本较高，沿用 TEXT 约定）
  status        VARCHAR(16) NOT NULL DEFAULT 'draft',     -- draft / pending / effective / obsolete / rejected
  source_version VARCHAR(16),                             -- 派生自哪个版本
  change_note   TEXT,
  created_by    VARCHAR(64),
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  submitted_by  VARCHAR(64),
  submitted_at  TIMESTAMPTZ,
  approved_by   VARCHAR(64),
  approved_at   TIMESTAMPTZ,
  effective_at  TIMESTAMPTZ,
  obsolete_at   TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_rv_recipe  ON recipe_version(recipe_code, version DESC);
CREATE INDEX IF NOT EXISTS idx_rv_status  ON recipe_version(status);
-- NFR-3 生效唯一性：同配方仅允许一个 effective 版本（部分唯一索引，数据库级强制）
CREATE UNIQUE INDEX IF NOT EXISTS uq_recipe_effective
  ON recipe_version(recipe_code) WHERE status = 'effective';

-- 工序参数与容差（FR-3/FR-9）：结构化数值列便于索引与容差比较（plan D3）
CREATE TABLE IF NOT EXISTS recipe_step (
  id            BIGSERIAL PRIMARY KEY,
  recipe_code   VARCHAR(32) NOT NULL,
  version       VARCHAR(16) NOT NULL,
  seq           INT NOT NULL DEFAULT 0,
  stage         VARCHAR(64),
  param_name    VARCHAR(64) NOT NULL,
  param_key     VARCHAR(64),
  target_value  NUMERIC(14,4),
  lower_limit   NUMERIC(14,4),
  upper_limit   NUMERIC(14,4),
  unit          VARCHAR(24),
  ccp           BOOLEAN NOT NULL DEFAULT FALSE,           -- 是否为关键控制点参数
  equipment_category VARCHAR(32)
);
CREATE INDEX IF NOT EXISTS idx_rstep_ver ON recipe_step(recipe_code, version);

-- 版本变更记录（FR-7）：影响面快照与重评标记
CREATE TABLE IF NOT EXISTS recipe_change (
  id             BIGSERIAL PRIMARY KEY,
  recipe_code    VARCHAR(32) NOT NULL,
  from_version   VARCHAR(16),
  to_version     VARCHAR(16),
  affected_batches TEXT,                                  -- JSON 受影响的在制批次 id 列表
  review_required BOOLEAN NOT NULL DEFAULT FALSE,         -- 是否需重评检验方法
  summary        TEXT,
  created_by     VARCHAR(64),
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_rc_recipe ON recipe_change(recipe_code);

-- =====================================================================
-- H3 · 追溯闭环（traceability T1）
-- ---------------------------------------------------------------------
-- material_lot 与 batch_input 已由 Phase E (F2) 落地且不重建，避免破坏既有数据；
-- 本期只补批间父子关系与追溯查询审计两表。
-- =====================================================================
CREATE TABLE IF NOT EXISTS batch_genealogy (
  id               BIGSERIAL PRIMARY KEY,
  parent_batch_id  VARCHAR(32) NOT NULL,                  -- 上游：中间品/营养盐批次
  child_batch_id   VARCHAR(32) NOT NULL,                  -- 下游：耗用该中间品的成品批次
  relation         VARCHAR(16) NOT NULL DEFAULT 'INPUT',  -- INPUT 投入 / OUTPUT 产出
  qty              NUMERIC(12,2),
  unit             VARCHAR(16),
  stage            VARCHAR(64),
  charged_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  operator         VARCHAR(64)
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_genealogy
  ON batch_genealogy(parent_batch_id, child_batch_id, relation);
CREATE INDEX IF NOT EXISTS idx_gene_parent ON batch_genealogy(parent_batch_id);
CREATE INDEX IF NOT EXISTS idx_gene_child  ON batch_genealogy(child_batch_id);

-- 追溯查询审计（FR-7）：监管核查需要「谁查过什么」的专项统计
CREATE TABLE IF NOT EXISTS trace_query_log (
  id            BIGSERIAL PRIMARY KEY,
  actor         VARCHAR(64) NOT NULL,
  query_type    VARCHAR(16) NOT NULL,                     -- forward / backward / impact / export / completeness
  query_key     VARCHAR(64),
  result_count  INT NOT NULL DEFAULT 0,
  duration_ms   BIGINT,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_tql_actor ON trace_query_log(actor);
CREATE INDEX IF NOT EXISTS idx_tql_type  ON trace_query_log(query_type);
