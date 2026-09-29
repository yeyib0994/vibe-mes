-- FluxMES Phase E · 食品行业合规能力（PostgreSQL 16，全部幂等）
-- F1 食品安全（HACCP/CCP · 清场 · 环境） / F2 物料谱系与召回 / F3 eBR 与留样效期

-- F3 · 批次效期：生产日期 + 保质期天数 → 到期日
ALTER TABLE batch ADD COLUMN IF NOT EXISTS production_date DATE;
ALTER TABLE batch ADD COLUMN IF NOT EXISTS shelf_life_days INT;
ALTER TABLE batch ADD COLUMN IF NOT EXISTS expiry_date   DATE;
CREATE INDEX IF NOT EXISTS idx_batch_expiry ON batch(expiry_date);

-- ---------------------------------------------------------------------
-- F1 · HACCP 关键控制点（CCP）定义
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ccp_point (
  code              VARCHAR(32) PRIMARY KEY,        -- CCP-01
  name              VARCHAR(128) NOT NULL,          -- 巴氏杀菌温度
  line              VARCHAR(32),                    -- 归属产线
  step_name         VARCHAR(64),                    -- 所在工序
  hazard            VARCHAR(256),                   -- 显著危害
  hazard_type       VARCHAR(32),                    -- BIOLOGICAL / CHEMICAL / PHYSICAL / ALLERGEN
  control_measure   VARCHAR(256),
  cl_min            NUMERIC(12,3),                  -- 关键限值下限
  cl_max            NUMERIC(12,3),                  -- 关键限值上限
  unit              VARCHAR(24),                    -- 摄氏度 / min / ppm
  monitor_freq      VARCHAR(64),                    -- 每批次 / 每 30 分钟 / 连续
  corrective_action VARCHAR(512),                   -- 偏离纠偏措施
  responsible_role  VARCHAR(32) DEFAULT 'OPERATOR',
  enabled           BOOLEAN NOT NULL DEFAULT TRUE
);

-- F1 · CCP 监控记录：一次实测一条，偏离自动联动偏差单与报警
CREATE TABLE IF NOT EXISTS ccp_record (
  id             BIGSERIAL PRIMARY KEY,
  ccp_code       VARCHAR(32) NOT NULL,
  batch_id       VARCHAR(32),
  line           VARCHAR(32),
  value          NUMERIC(12,3) NOT NULL,
  in_limit       BOOLEAN NOT NULL,
  deviation_id   VARCHAR(32),
  alarm_id       VARCHAR(32),
  corrective     TEXT,
  operator       VARCHAR(64),
  verifier       VARCHAR(64),
  verified_at    TIMESTAMPTZ,
  remark         TEXT,
  recorded_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_ccp_record_batch ON ccp_record(batch_id);
CREATE INDEX IF NOT EXISTS idx_ccp_record_code  ON ccp_record(ccp_code);

-- ---------------------------------------------------------------------
-- F1 · 清场与卫生（CIP / 换型 / 过敏原）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS cleaning_record (
  id             VARCHAR(32) PRIMARY KEY,           -- CLN-260914-001
  line           VARCHAR(32) NOT NULL,
  equipment_code VARCHAR(64),
  type           VARCHAR(24) NOT NULL,              -- ROUTINE / CHANGEOVER / ALLERGEN / DEEP
  method         VARCHAR(128),
  allergen_from  VARCHAR(64),
  allergen_to    VARCHAR(64),
  started_at     TIMESTAMPTZ,
  finished_at    TIMESTAMPTZ,
  executed_by    VARCHAR(64),
  verified_by    VARCHAR(64),                       -- QA / 值班长确认
  verified_at    TIMESTAMPTZ,
  result         VARCHAR(16) NOT NULL DEFAULT 'PENDING',  -- PENDING / PASS / FAIL
  swab_result    VARCHAR(64),                       -- ATP 涂抹检测结果
  valid_until    TIMESTAMPTZ,                       -- 清场有效期
  next_batch_id  VARCHAR(32),
  remark         TEXT,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_clean_line ON cleaning_record(line);

-- ---------------------------------------------------------------------
-- F1 · 环境与卫生监测
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS env_monitoring (
  id           BIGSERIAL PRIMARY KEY,
  area         VARCHAR(64) NOT NULL,                -- 发酵间 / 洁净灌装区 / 原料暂存
  line         VARCHAR(32),
  metric       VARCHAR(32) NOT NULL,                -- TEMP / HUMIDITY / PRESSURE_DIFF / MICRO / ATP
  value        NUMERIC(12,3) NOT NULL,
  unit         VARCHAR(24),
  limit_min    NUMERIC(12,3),
  limit_max    NUMERIC(12,3),
  result       VARCHAR(16) NOT NULL,                -- PASS / FAIL
  deviation_id VARCHAR(32),
  sampled_by   VARCHAR(64),
  sampled_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  remark       TEXT
);
CREATE INDEX IF NOT EXISTS idx_env_area ON env_monitoring(area);

-- ---------------------------------------------------------------------
-- F2 · 物料主数据与原料批（谱系基础）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS material (
  code            VARCHAR(32) PRIMARY KEY,          -- RM-001
  name            VARCHAR(128) NOT NULL,            -- 玉米淀粉
  category        VARCHAR(24) NOT NULL,             -- RAW / EXCIPIENT / PACKAGING
  unit            VARCHAR(16) DEFAULT 'kg',
  allergen        BOOLEAN NOT NULL DEFAULT FALSE,
  allergen_name   VARCHAR(64),
  shelf_life_days INT,
  spec            VARCHAR(256),
  enabled         BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE IF NOT EXISTS material_lot (
  id              VARCHAR(32) PRIMARY KEY,          -- LOT-260901-01
  material_code   VARCHAR(32) NOT NULL,
  supplier        VARCHAR(128),
  supplier_lot    VARCHAR(64),
  received_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  qty             NUMERIC(12,2),
  unit            VARCHAR(16),
  qc_status       VARCHAR(16) NOT NULL DEFAULT 'PENDING',  -- PENDING / PASS / FAIL / RELEASED
  qc_by           VARCHAR(64),
  qc_at           TIMESTAMPTZ,
  production_date DATE,
  expiry_date     DATE,
  warehouse_bin   VARCHAR(64),
  coa_no          VARCHAR(64),
  remark          TEXT
);
CREATE INDEX IF NOT EXISTS idx_mlot_material ON material_lot(material_code);
CREATE INDEX IF NOT EXISTS idx_mlot_qc       ON material_lot(qc_status);

-- F2 · 投料记录：成品批次 ← 原料批（真实谱系边）
CREATE TABLE IF NOT EXISTS batch_input (
  id              BIGSERIAL PRIMARY KEY,
  batch_id        VARCHAR(32) NOT NULL,
  material_lot_id VARCHAR(32) NOT NULL,
  material_code   VARCHAR(32),
  qty             NUMERIC(12,2),
  unit            VARCHAR(16),
  fed_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  operator        VARCHAR(64),
  remark          TEXT
);
CREATE INDEX IF NOT EXISTS idx_binput_batch ON batch_input(batch_id);
CREATE INDEX IF NOT EXISTS idx_binput_lot   ON batch_input(material_lot_id);

-- ---------------------------------------------------------------------
-- F3 · 电子批记录 eBR（工序级执行 + 双人复核）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS batch_step (
  id            BIGSERIAL PRIMARY KEY,
  batch_id      VARCHAR(32) NOT NULL,
  step_no       INT NOT NULL,
  step_name     VARCHAR(64) NOT NULL,
  status        VARCHAR(16) NOT NULL DEFAULT 'PENDING',  -- PENDING / RUNNING / DONE / SKIPPED
  target_params TEXT,                                    -- JSON：工艺设定值
  actual_params TEXT,                                    -- JSON：实测值
  operator      VARCHAR(64),
  reviewer      VARCHAR(64),                             -- 复核人（不得为操作人本人）
  started_at    TIMESTAMPTZ,
  finished_at   TIMESTAMPTZ,
  reviewed_at   TIMESTAMPTZ,
  remark        TEXT
);
CREATE INDEX IF NOT EXISTS idx_bstep_batch ON batch_step(batch_id);

-- F3 · 留样管理（法定：留至保质期后 6 个月）
CREATE TABLE IF NOT EXISTS retention_sample (
  id          VARCHAR(32) PRIMARY KEY,              -- RS-260826-007
  batch_id    VARCHAR(32) NOT NULL,
  qty         NUMERIC(10,2),
  unit        VARCHAR(16) DEFAULT 'g',
  location    VARCHAR(64),
  retained_by VARCHAR(64),
  retained_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  expiry_date DATE,                                 -- 成品效期 + 180 天
  status      VARCHAR(16) NOT NULL DEFAULT 'RETAINED',   -- RETAINED / TESTED / DISCARDED
  disposed_at TIMESTAMPTZ,
  disposed_by VARCHAR(64),
  remark      TEXT
);
CREATE INDEX IF NOT EXISTS idx_rs_batch  ON retention_sample(batch_id);
CREATE INDEX IF NOT EXISTS idx_rs_expiry ON retention_sample(expiry_date);
