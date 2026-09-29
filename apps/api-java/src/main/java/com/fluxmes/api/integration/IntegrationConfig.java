package com.fluxmes.api.integration;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 集成层装配入口。
 *
 * <p>启动期把四个系统的模式与端点**醒目打印**到日志：mock 数据流入生产是这套方案
 * 最容易发生的事故（风险 R2），日志是第一道人工防线。
 */
@Configuration
@EnableConfigurationProperties(IntegrationProperties.class)
public class IntegrationConfig {

  private static final Logger log = LoggerFactory.getLogger(IntegrationConfig.class);

  private final IntegrationProperties props;

  public IntegrationConfig(IntegrationProperties props) {
    this.props = props;
  }

  @PostConstruct
  void logModes() {
    log.warn("""
        ════════════════════ FluxMES 外部系统接入模式 ════════════════════
          SCADA/OPC-UA : {}  {}
          LIMS         : {}  {}
          ERP          : {}  {}
          WMS          : {}  {}
          报警模拟器    : {}
          采集周期      : {}s
        ==================================================================""",
        props.getScada().getMode(), nvl(props.getScada().getEndpoint()),
        props.getLims().getMode(), nvl(props.getLims().getEndpoint()),
        props.getErp().getMode(), nvl(props.getErp().getEndpoint()),
        props.getWms().getMode(), nvl(props.getWms().getEndpoint()),
        props.isAlarmSimulatorEnabled() ? "启用（仅演示，生产须关闭）" : "已关闭",
        props.getCollectIntervalSeconds());

    boolean anyMock = props.getScada().isMock() || props.getLims().isMock()
        || props.getErp().isMock() || props.getWms().isMock();
    if (anyMock) {
      log.warn("存在以 MOCK 模式运行的集成项：相关数据标记为 dataSource=MOCK，不代表真实生产数据");
    }
  }

  private static String nvl(String s) {
    return s == null ? "(未配置)" : s;
  }
}
