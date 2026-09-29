-- FluxMES Phase J · 外部系统集成（PostgreSQL 16，全部幂等）
-- J1 设备参数时序落点
--
-- 背景：specs/equipment-management/plan.md 第 14 行早已设计 equipment_metric 表，
-- 但此前从未创建，导致「实时参数与趋势」只能由 FixtureStore + genSeries() 伪序列兜住。
-- 本分片补齐该表，作为外部系统（SCADA/OPC-UA）采集数据的唯一落点。
--
-- 只建表与索引；采集器与适配器见 com.fluxmes.api.integration。

-- =====================================================================
-- J1 · 设备实时工艺参数（equipment-management T12 / FR-3）
-- ---------------------------------------------------------------------
-- 写入方：EquipmentMetricCollector（经 EquipmentMetricPort 从 OPC-UA / Mock 取数）
-- 读取方：EquipmentService 详情页趋势序列、OEE 与越限判定
-- 保留策略：按 sampled_at 归档，≥3 年（C6）；高频采样下增长快，见 plan.md R2
-- =====================================================================
CREATE TABLE IF NOT EXISTS equipment_metric (
  id              BIGSERIAL PRIMARY KEY,
  equipment_code  VARCHAR(32) NOT NULL,                   -- F-102
  metric_key      VARCHAR(64) NOT NULL,                   -- temp / pressure / level / rpm
  metric_name     VARCHAR(64),                            -- 罐温 / 罐压 / 液位 / 搅拌转速
  value           NUMERIC(14,4),                          -- 采集值（质量码非 GOOD 时可能为空）
  unit            VARCHAR(24),                            -- ℃ / MPa / % / rpm
  lower_limit     NUMERIC(14,4),                          -- 工艺下限（越限判定用）
  upper_limit     NUMERIC(14,4),                          -- 工艺上限
  quality         VARCHAR(16) NOT NULL DEFAULT 'GOOD',    -- GOOD / BAD / UNCERTAIN（OPC-UA StatusCode 归一）
  source          VARCHAR(16) NOT NULL DEFAULT 'MANUAL',  -- MOCK / OPCUA / MANUAL
  sampled_at      TIMESTAMPTZ NOT NULL DEFAULT now(),     -- 数据源时间戳（非入库时间）
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 趋势查询：按设备+指标取最近 N 点
CREATE INDEX IF NOT EXISTS idx_em_code_key_time
  ON equipment_metric(equipment_code, metric_key, sampled_at DESC);
-- 保留策略/归档扫描
CREATE INDEX IF NOT EXISTS idx_em_sampled ON equipment_metric(sampled_at);

-- =====================================================================
-- J2 · 报警来源标识（数据源可辨识，P0-2 配套）
-- ---------------------------------------------------------------------
-- 报警模拟器（AlarmService#simulateNewAlarm）造的演示报警此前与业务真实触发的
-- 报警**完全无法区分**（同样写 alarm 表、同样进审计），接入真实采集后会造成
-- 「演示数据被当成真实报警」的误判。补一列来源标识。
-- 取值：MOCK / OPCUA / MANUAL / null（历史数据与业务规则触发）
-- =====================================================================
ALTER TABLE alarm ADD COLUMN IF NOT EXISTS data_source VARCHAR(16);
CREATE INDEX IF NOT EXISTS idx_alarm_source ON alarm(data_source);

