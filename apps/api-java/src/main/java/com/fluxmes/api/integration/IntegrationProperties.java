package com.fluxmes.api.integration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 外部系统接入配置（P0-1）。
 *
 * <pre>
 * fluxmes:
 *   integration:
 *     scada: { mode: mock, endpoint: "opc.tcp://localhost:4840/fluxmes" }
 *     lims:  { mode: mock, endpoint: "http://localhost:9100/lims" }
 *     erp:   { mode: mock, endpoint: "http://localhost:9100/erp" }
 *     wms:   { mode: mock, endpoint: "http://localhost:9100/wms" }
 *     alarm-simulator-enabled: true
 *     collect-interval-seconds: 30
 * </pre>
 *
 * <p>用配置项而非 {@code @Profile} 切换，是为了支持「四个系统部分 mock、部分真实」的
 * 过渡态——现场改造几乎不可能四套同时上线。
 */
@Data
@ConfigurationProperties(prefix = "fluxmes.integration")
public class IntegrationProperties {

  private SystemConfig scada = new SystemConfig(IntegrationMode.MOCK, "opc.tcp://localhost:4840/fluxmes");
  private SystemConfig lims = new SystemConfig(IntegrationMode.MOCK, "http://localhost:9100/lims");
  private SystemConfig erp = new SystemConfig(IntegrationMode.MOCK, "http://localhost:9100/erp");
  private SystemConfig wms = new SystemConfig(IntegrationMode.MOCK, "http://localhost:9100/wms");

  /**
   * 报警模拟器开关（P0-2）。
   *
   * <p>历史上该模拟器在 {@code AlarmService#startSimulator} 中**无条件**启动，每 45s 往
   * PG 写一条 {@code value="模拟器数据"} 的报警。接入真实采集后这些数据会与真实报警混在
   * 同一张表且不可区分，故改为受本开关控制；生产必须置 false。
   */
  private boolean alarmSimulatorEnabled = true;

  /** 设备参数采集周期（秒）。默认 30，对应 NFR-1「一般设备 ≤30s」。≤0 表示关闭定时采集。 */
  private int collectIntervalSeconds = 30;

  /** 启动后是否立即执行一次采集（避免等一个周期前端才有数据）。 */
  private boolean collectOnStartup = true;

  /**
   * 原始采样保留天数（默认 7）。
   *
   * <p>30s 采样 × 24 指标 ≈ 6.9 万行/天，按 C6 保留 3 年不现实（约 7500 万行）。
   * 章程对过程数据的要求是「按合规与性能平衡保留」，故此处只保留近期原始采样，
   * 长期留存依赖后续的汇总/归档（参见 equipment-management plan.md R2）。
   * ≤0 表示不清理（仅用于排障）。
   */
  private int metricRetentionDays = 7;

  /**
   * 冷启动回填点数与点间隔（分钟）。默认 24 点 × 30 分钟 = 12 小时，
   * 使首次启动时趋势图覆盖与趋势窗口一致的时段。仅 mock 数据源会用到。
   */
  private int backfillPoints = 24;
  private int backfillStepMinutes = 30;

  @Data
  public static class SystemConfig {
    private IntegrationMode mode = IntegrationMode.MOCK;
    private String endpoint;
    /** 连接/读取超时（毫秒）。 */
    private int timeoutMs = 3000;
    /** 失败重试次数（首次之外）。 */
    private int retries = 1;

    public SystemConfig() {}

    public SystemConfig(IntegrationMode mode, String endpoint) {
      this.mode = mode;
      this.endpoint = endpoint;
    }

    public boolean isMock() {
      return mode == null || mode == IntegrationMode.MOCK;
    }
  }
}
