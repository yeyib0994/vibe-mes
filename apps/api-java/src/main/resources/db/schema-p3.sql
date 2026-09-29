-- FluxMES Phase G · P3 增强能力（PostgreSQL 16，全部幂等）
-- G1 报警 SLA 可配置 / G2 人员资质与健康证 / G3 称量配料容差 / G4 多厂区

-- ---------------------------------------------------------------------
-- G1 · 报警响应 SLA 政策表（原为代码常量，改为运行时可配置）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS alarm_sla_policy (
  level       VARCHAR(16) PRIMARY KEY,           -- critical / major / minor
  minutes     INT          NOT NULL,             -- 响应时限（分钟）
  enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
  note        VARCHAR(256),
  updated_by  VARCHAR(64),
  updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
INSERT INTO alarm_sla_policy (level, minutes, note) VALUES
  ('critical', 5,  '关键报警：5 分钟内必须确认'),
  ('major',    15, '重要报警：15 分钟内确认'),
  ('minor',    30, '一般报警：30 分钟内确认')
ON CONFLICT (level) DO NOTHING;

-- ---------------------------------------------------------------------
-- G2 · 人员资质与健康证（食品行业法定：从业人员持证上岗 + 年度健康体检）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS person_certificate (
  id           BIGSERIAL PRIMARY KEY,
  username     VARCHAR(64)  NOT NULL,
  display_name VARCHAR(64),
  cert_type    VARCHAR(24)  NOT NULL,            -- HEALTH 健康证 / QUALIFICATION 岗位资质
  cert_name    VARCHAR(128) NOT NULL,            -- 食品从业人员健康证 / 化验员资格证
  capability   VARCHAR(64),                      -- 资质对应的能力项（QUALIFICATION 时必填）
  cert_no      VARCHAR(64),
  issued_by    VARCHAR(128),
  issued_at    DATE,
  valid_until  DATE,
  status       VARCHAR(16)  NOT NULL DEFAULT 'VALID',   -- VALID / EXPIRED / REVOKED
  remark       TEXT,
  created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_pcert_user ON person_certificate(username);
CREATE INDEX IF NOT EXISTS idx_pcert_cap  ON person_certificate(capability);
CREATE INDEX IF NOT EXISTS idx_pcert_due  ON person_certificate(valid_until);

-- 能力项字典（前端下拉与校验提示的数据源）
CREATE TABLE IF NOT EXISTS capability_dict (
  code        VARCHAR(48) PRIMARY KEY,           -- WEIGHING / CCP_MONITOR / RELEASE / BATCH_REVIEW
  name        VARCHAR(128) NOT NULL,
  description VARCHAR(256),
  required_role VARCHAR(16) DEFAULT 'OPERATOR'
);
INSERT INTO capability_dict (code, name, description, required_role) VALUES
  ('WEIGHING',      '配料称量',     '原料配料称量作业（含容差判定）',          'OPERATOR'),
  ('CCP_MONITOR',   'CCP 监控',     'HACCP 关键控制点监控值记录',              'OPERATOR'),
  ('RELEASE',       '成品放行',     '成品质量放行与 COA 签发',                 'QC'),
  ('BATCH_REVIEW',  '批记录复核',   '电子批记录（eBR）工序双人复核',           'QC'),
  ('SANITATION',    '清场作业',     'CIP / 换型 / 过敏原清场执行与确认',       'OPERATOR'),
  ('LAB_TEST',      '理化检验',     '原料与成品理化微生物检验',                'QC')
ON CONFLICT (code) DO NOTHING;

-- ---------------------------------------------------------------------
-- G3 · 称量 / 配料容差校验
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS weighing_task (
  id            VARCHAR(32) PRIMARY KEY,         -- WT-260914-001
  batch_id      VARCHAR(32) NOT NULL,
  material_code VARCHAR(32),
  material_name VARCHAR(128),
  target_qty    NUMERIC(12,3) NOT NULL,          -- 配方目标量
  unit          VARCHAR(16)  DEFAULT 'kg',
  tolerance_pct NUMERIC(6,3)  NOT NULL DEFAULT 1.0,  -- 允许偏差百分比（±）
  total_weighed NUMERIC(12,3) NOT NULL DEFAULT 0,    -- 累计已称量
  status        VARCHAR(16)  NOT NULL DEFAULT 'OPEN', -- OPEN / DONE / BLOCKED
  created_by    VARCHAR(64),
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_wtask_batch ON weighing_task(batch_id);

CREATE TABLE IF NOT EXISTS weighing_item (
  id            BIGSERIAL PRIMARY KEY,
  task_id       VARCHAR(32) NOT NULL,
  seq           INT         NOT NULL DEFAULT 1,
  actual_qty    NUMERIC(12,3) NOT NULL,
  deviation_pct NUMERIC(8,3)  NOT NULL,          -- (实际-目标)/目标 ×100
  result        VARCHAR(16) NOT NULL,            -- PASS / OVER / UNDER
  deviation_id  VARCHAR(32),                     -- 超差联动偏差单
  alarm_id      VARCHAR(32),                     -- 超差联动报警
  operator      VARCHAR(64),
  reviewer      VARCHAR(64),                     -- 超差强制复核人（不得为称量人本人）
  reviewed_at   TIMESTAMPTZ,
  equipment     VARCHAR(64),                     -- 衡器编号
  weighed_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  remark        TEXT
);
CREATE INDEX IF NOT EXISTS idx_witem_task ON weighing_item(task_id);

-- ---------------------------------------------------------------------
-- G4 · 多厂区
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS site (
  code        VARCHAR(32) PRIMARY KEY,           -- SITE-01
  name        VARCHAR(128) NOT NULL,
  address     VARCHAR(256),
  enabled     BOOLEAN NOT NULL DEFAULT TRUE,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO site (code, name, address) VALUES
  ('SITE-01', '清禾生物 · 城东工厂', '江苏省泰州市城东工业园区兴丰路 8 号'),
  ('SITE-02', '清禾生物 · 滨海工厂', '江苏省连云港市滨海新区海港大道 66 号')
ON CONFLICT (code) DO NOTHING;

-- 产线归属厂区（既有三条线归城东工厂；滨海工厂新增线）
ALTER TABLE production_line ADD COLUMN IF NOT EXISTS site_code VARCHAR(32);
UPDATE production_line SET site_code = 'SITE-01' WHERE site_code IS NULL;
INSERT INTO production_line (code, name, workshop, capacity_t, site_code)
VALUES ('LINE-4', '柠檬酸发酵四线', '滨海一车间', 42.0, 'SITE-02')
ON CONFLICT (code) DO NOTHING;

-- 批次归属厂区
ALTER TABLE batch ADD COLUMN IF NOT EXISTS site VARCHAR(32);
UPDATE batch SET site = 'SITE-01' WHERE site IS NULL;
CREATE INDEX IF NOT EXISTS idx_batch_site ON batch(site);

-- 用户归属厂区（NULL = 全厂区可见，用于集团级账号）
ALTER TABLE app_user ADD COLUMN IF NOT EXISTS site_code VARCHAR(32);
UPDATE app_user SET site_code = 'SITE-01' WHERE site_code IS NULL AND username <> 'admin';
CREATE INDEX IF NOT EXISTS idx_user_site ON app_user(site_code);

-- CCP 点归属厂区
ALTER TABLE ccp_point ADD COLUMN IF NOT EXISTS site_code VARCHAR(32);
UPDATE ccp_point SET site_code = 'SITE-01' WHERE site_code IS NULL;
