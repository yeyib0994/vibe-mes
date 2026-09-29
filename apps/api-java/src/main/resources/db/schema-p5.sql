-- FluxMES Phase I · 生产执行深化（PostgreSQL 16，全部幂等）
-- I1 工单与派工 / I2 工序报工 / I3 停机原因码 / I4 OEE 重算
-- 口径约定：数量统一存 kg（R5），时长统一存分钟，时间戳统一 TIMESTAMPTZ（Asia/Shanghai 写入）

-- ---------------------------------------------------------------------
-- I1 · 工单（批次 N:1，工单继承批次产线/厂区/产品/配方版本快照）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS work_order (
  id             VARCHAR(32) PRIMARY KEY,        -- WO-260915-001
  batch_id       VARCHAR(32) NOT NULL,
  line           VARCHAR(32),
  site           VARCHAR(32),
  product        VARCHAR(128),
  recipe_code    VARCHAR(64),
  recipe_version VARCHAR(32),
  plan_qty       NUMERIC(14,3) NOT NULL,         -- 计划数量（kg）
  unit           VARCHAR(16) NOT NULL DEFAULT 'kg',
  plan_minutes   INT,                            -- 计划生产工时（分钟，可用率分母）
  plan_start     TIMESTAMPTZ,
  plan_end       TIMESTAMPTZ,
  shift          VARCHAR(16),                    -- DAY / NIGHT
  status         VARCHAR(16) NOT NULL DEFAULT 'CREATED',
                                                 -- CREATED / RELEASED / RUNNING / FINISHED / CLOSED
  input_qty      NUMERIC(14,3) NOT NULL DEFAULT 0,  -- 报工汇总（投料）
  good_qty       NUMERIC(14,3) NOT NULL DEFAULT 0,  -- 报工汇总（合格）
  scrap_qty      NUMERIC(14,3) NOT NULL DEFAULT 0,  -- 报工汇总（废次品）
  actual_minutes INT,                            -- 实际生产工时（性能率分母）
  source         VARCHAR(16) NOT NULL DEFAULT 'MANUAL',  -- MANUAL / BACKFILL（历史迁移）
  created_by     VARCHAR(64),
  released_at    TIMESTAMPTZ,
  finished_at    TIMESTAMPTZ,
  closed_at      TIMESTAMPTZ,
  remark         TEXT,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_wo_batch  ON work_order(batch_id);
CREATE INDEX IF NOT EXISTS idx_wo_status ON work_order(status);
CREATE INDEX IF NOT EXISTS idx_wo_site   ON work_order(site);
CREATE INDEX IF NOT EXISTS idx_wo_plan   ON work_order(plan_start);

-- ---------------------------------------------------------------------
-- I1 · 派工（撤销为软删，保留留痕）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS work_order_assignment (
  id            BIGSERIAL PRIMARY KEY,
  order_id      VARCHAR(32) NOT NULL,
  username      VARCHAR(64) NOT NULL,
  display_name  VARCHAR(64),
  role_in_order VARCHAR(16) NOT NULL DEFAULT 'OPERATOR',  -- OPERATOR / REVIEWER
  capability    VARCHAR(48),                              -- 派工时校验的能力项
  assigned_by   VARCHAR(64),
  assigned_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  revoked_by    VARCHAR(64),
  revoked_at    TIMESTAMPTZ,
  revoke_reason VARCHAR(256)
);
CREATE INDEX IF NOT EXISTS idx_woa_order ON work_order_assignment(order_id);
CREATE INDEX IF NOT EXISTS idx_woa_user  ON work_order_assignment(username);

-- ---------------------------------------------------------------------
-- I2 · 工序报工（幂等：同工单+工序+报工人+开工时间唯一）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS step_report (
  id           BIGSERIAL PRIMARY KEY,
  order_id     VARCHAR(32) NOT NULL,
  batch_id     VARCHAR(32),
  step_no      INT,
  step_name    VARCHAR(128),
  equipment    VARCHAR(64),
  username     VARCHAR(64),
  reviewer     VARCHAR(64),
  reviewed_at  TIMESTAMPTZ,
  started_at   TIMESTAMPTZ,
  finished_at  TIMESTAMPTZ,
  duration_min NUMERIC(10,1),
  input_qty    NUMERIC(14,3) NOT NULL DEFAULT 0,
  good_qty     NUMERIC(14,3) NOT NULL DEFAULT 0,
  scrap_qty    NUMERIC(14,3) NOT NULL DEFAULT 0,
  std_minutes  NUMERIC(10,1),                    -- 标准工时（性能率分子）
  critical     BOOLEAN NOT NULL DEFAULT FALSE,   -- 关键工序需双人复核
  source       VARCHAR(16) NOT NULL DEFAULT 'MANUAL',  -- MANUAL / BACKFILL
  remark       TEXT,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_sr_order ON step_report(order_id);
CREATE INDEX IF NOT EXISTS idx_sr_batch ON step_report(batch_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_sr_idem
  ON step_report(order_id, COALESCE(step_no, -1), username, COALESCE(started_at, to_timestamp(0)));

-- ---------------------------------------------------------------------
-- I3 · 停机原因码字典（计划 / 非计划分类决定是否计入可用率损失）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS downtime_reason (
  code                VARCHAR(24) PRIMARY KEY,
  name                VARCHAR(64) NOT NULL,
  category            VARCHAR(16) NOT NULL,      -- PLANNED / UNPLANNED
  alarm_threshold_min INT DEFAULT 0,             -- ≥ 该时长自动报警（0 = 不报警）
  enabled             BOOLEAN NOT NULL DEFAULT TRUE,
  sort_no             INT NOT NULL DEFAULT 100,
  note                VARCHAR(256)
);
INSERT INTO downtime_reason (code, name, category, alarm_threshold_min, sort_no, note) VALUES
  ('MECH',       '机械故障',   'UNPLANNED', 120, 10, '搅拌/泵/阀等机械失效'),
  ('ELEC',       '电气故障',   'UNPLANNED', 120, 20, '仪表/变频/供电异常'),
  ('MATERIAL',   '待料',       'UNPLANNED', 180, 30, '上道工序或仓库未及时供料'),
  ('QUALITY',    '质量问题',   'UNPLANNED',  60, 40, '过程指标异常需停机处置'),
  ('UTILITY',    '公用工程',   'UNPLANNED', 120, 50, '蒸汽/压缩空气/循环水异常'),
  ('OTHER',      '其他',       'UNPLANNED', 240, 60, '未归类原因，须填写说明'),
  ('CHANGEOVER', '换型',       'PLANNED',     0, 70, '换品种/规格（食品行业属计划性损失）'),
  ('CLEANING',   '清洗清场',   'PLANNED',     0, 80, 'CIP / 换型清场 / 过敏原清场'),
  ('NO_ORDER',   '无订单',     'PLANNED',     0, 90, '计划性停产待单')
ON CONFLICT (code) DO NOTHING;

-- ---------------------------------------------------------------------
-- I3 · 停机事件（plan D6：与 equipment_event 时间窗合并，避免重复扣减）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS downtime_event (
  id           BIGSERIAL PRIMARY KEY,
  order_id     VARCHAR(32),
  equipment    VARCHAR(64),
  line         VARCHAR(32),
  site         VARCHAR(32),
  shift        VARCHAR(16),
  reason_code  VARCHAR(24) NOT NULL,
  planned      BOOLEAN NOT NULL DEFAULT FALSE,   -- 由原因码 category 推导
  started_at   TIMESTAMPTZ NOT NULL,
  ended_at     TIMESTAMPTZ,
  duration_min NUMERIC(10,1),
  description  TEXT,
  source       VARCHAR(16) NOT NULL DEFAULT 'MANUAL',  -- MANUAL / EVENT（设备状态事件推导）
  merged_from  VARCHAR(128),                     -- 合并来源说明（FR-18）
  created_by   VARCHAR(64),
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_dt_order  ON downtime_event(order_id);
CREATE INDEX IF NOT EXISTS idx_dt_equip  ON downtime_event(equipment, started_at);
CREATE INDEX IF NOT EXISTS idx_dt_window ON downtime_event(started_at, ended_at);

-- ---------------------------------------------------------------------
-- I4 · OEE 预汇总（按 范围 × 日期 × 班次，重算 upsert）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS oee_rollup (
  id                     BIGSERIAL PRIMARY KEY,
  scope_type             VARCHAR(16) NOT NULL,   -- LINE / EQUIPMENT
  scope_key              VARCHAR(64) NOT NULL,
  stat_date              DATE        NOT NULL,
  shift                  VARCHAR(16) NOT NULL DEFAULT 'ALL',
  site                   VARCHAR(32),
  planned_minutes        NUMERIC(12,1) NOT NULL DEFAULT 0,
  unplanned_stop_minutes NUMERIC(12,1) NOT NULL DEFAULT 0,
  run_minutes            NUMERIC(12,1) NOT NULL DEFAULT 0,
  std_minutes            NUMERIC(12,1) NOT NULL DEFAULT 0,
  good_qty               NUMERIC(14,3)  NOT NULL DEFAULT 0,
  scrap_qty              NUMERIC(14,3)  NOT NULL DEFAULT 0,
  availability           NUMERIC(6,2),
  performance            NUMERIC(6,2),
  quality                NUMERIC(6,2),
  oee                    NUMERIC(6,2),
  data_sufficient        BOOLEAN NOT NULL DEFAULT FALSE,
  missing                VARCHAR(256),
  computed_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_oee_scope
  ON oee_rollup(scope_type, scope_key, stat_date, shift);
