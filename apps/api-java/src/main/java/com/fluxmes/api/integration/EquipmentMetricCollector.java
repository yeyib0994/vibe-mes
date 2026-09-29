package com.fluxmes.api.integration;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fluxmes.api.entity.EquipmentMetric;
import com.fluxmes.api.integration.dto.MetricSample;
import com.fluxmes.api.integration.port.EquipmentMetricPort;
import com.fluxmes.api.mapper.EquipmentMetricMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 设备参数采集器（equipment-management T12 / FR-3）。
 *
 * <p>职责单一：按周期从 {@link EquipmentMetricPort} 取当前快照，落到
 * {@code equipment_metric}。协议与存储被彻底解耦——
 * 端口不知道有数据库，采集器不知道有 OPC-UA。
 *
 * <p>这是 Phase J 相对此前实现的实质改进：以前 {@code EquipmentService} 直接
 * 生成伪序列返回给前端，**没有任何历史**（刷新一次就换一批随机数）；
 * 现在数据先落库，趋势是真实查询出来的时序。
 */
@Component
public class EquipmentMetricCollector {

  private static final Logger log = LoggerFactory.getLogger(EquipmentMetricCollector.class);

  private final EquipmentMetricPort port;
  private final EquipmentMetricMapper metrics;
  private final IntegrationProperties props;
  private final AtomicLong totalCollected = new AtomicLong();

  private ScheduledExecutorService scheduler;
  private volatile String lastRunStatus = "尚未采集";
  private volatile int lastBatchSize;
  private volatile OffsetDateTime lastRunAt;

  public EquipmentMetricCollector(EquipmentMetricPort port, EquipmentMetricMapper metrics,
      IntegrationProperties props) {
    this.port = port;
    this.metrics = metrics;
    this.props = props;
  }

  @PostConstruct
  void start() {
    int interval = props.getCollectIntervalSeconds();
    if (interval <= 0) {
      lastRunStatus = "定时采集已关闭（collect-interval-seconds<=0）";
      log.info("设备参数定时采集已关闭");
      return;
    }
    scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "metric-collector");
      t.setDaemon(true);
      return t;
    });
    if (props.isCollectOnStartup()) {
      scheduler.execute(this::backfillIfEmpty);
      scheduler.execute(this::collectAndLog);
    }
    scheduler.scheduleWithFixedDelay(this::collectAndLog, interval, interval, TimeUnit.SECONDS);
    log.info("设备参数采集已启动：周期 {}s，来源 {}，原始采样保留 {} 天",
        interval, port.dataSource(), props.getMetricRetentionDays());
  }

  @PreDestroy
  void stop() {
    if (scheduler != null) scheduler.shutdownNow();
  }

  private void collectAndLog() {
    try {
      int n = collectNow();
      if (n > 0) log.debug("设备参数采集入库 {} 条", n);
    } catch (Exception e) {
      log.warn("设备参数采集失败：{}", e.toString());
      lastRunStatus = "采集失败：" + e;
    }
    try {
      purgeExpired();
    } catch (Exception e) {
      log.warn("原始采样清理失败：{}", e.toString());
    }
  }

  /**
   * 冷启动回填：表为空时请求端口构造一段历史，使首屏趋势图有内容。
   *
   * <p>只在表**完全为空**时才做——已有数据说明系统运行过，不该被伪造的历史覆盖。
   * 真实采集源（REAL）的 {@code backfill()} 默认返回空，所以真机上不会写入任何假数据。
   */
  public synchronized int backfillIfEmpty() {
    try {
      long existing = metrics.selectCount(null);
      if (existing > 0) {
        log.info("设备参数表已有 {} 条采样，跳过冷启动回填", existing);
        return 0;
      }
      long stepMs = Math.max(1, props.getBackfillStepMinutes()) * 60_000L;
      List<MetricSample> samples = port.backfill(Math.max(0, props.getBackfillPoints()), stepMs);
      if (samples.isEmpty()) {
        log.info("数据源 {} 不提供历史回填（真实采集无历史可造），跳过", port.dataSource());
        return 0;
      }
      int n = 0;
      for (MetricSample s : samples) {
        metrics.insert(toEntity(s));
        n++;
      }
      log.info("冷启动回填设备参数 {} 点（dataSource={}，纯演示数据）", n, port.dataSource());
      lastRunStatus = "冷启动回填 " + n + " 点（" + port.dataSource() + "）";
      return n;
    } catch (Exception e) {
      log.warn("冷启动回填失败：{}", e.toString());
      return 0;
    }
  }

  /** 立即采集一次并入库，返回入库条数。供定时任务与手工触发接口共用。 */
  public synchronized int collectNow() {
    lastRunAt = OffsetDateTime.now();
    if (!port.available() && port.lastSuccessAt() == null) {
      // 从未成功过：不是错误，只是首次探测前的状态
      lastRunStatus = "数据源尚未就绪：" + port.detail();
    }
    List<MetricSample> samples = port.readAll();
    int n = 0;
    for (MetricSample s : samples) {
      metrics.insert(toEntity(s));
      n++;
    }
    lastBatchSize = n;
    totalCollected.addAndGet(n);
    lastRunStatus = n == 0
        ? "本次采集 0 条（" + port.detail() + "）"
        : "本次采集 " + n + " 条，来源 " + port.dataSource();
    return n;
  }

  private static EquipmentMetric toEntity(MetricSample s) {
    EquipmentMetric m = new EquipmentMetric();
    m.setEquipmentCode(s.equipmentCode());
    m.setMetricKey(s.metricKey());
    m.setMetricName(s.metricName());
    m.setValue(s.value() == null ? null : BigDecimal.valueOf(s.value()));
    m.setUnit(s.unit());
    m.setLowerLimit(s.lowerLimit() == null ? null : BigDecimal.valueOf(s.lowerLimit()));
    m.setUpperLimit(s.upperLimit() == null ? null : BigDecimal.valueOf(s.upperLimit()));
    m.setQuality(s.quality());
    m.setSource(s.dataSource());
    m.setSampledAt(s.sampledAt() == null ? OffsetDateTime.now() : s.sampledAt());
    m.setCreatedAt(OffsetDateTime.now());
    return m;
  }

  /** 按 {@code metric-retention-days} 清理过期原始采样，避免时序表无限增长。 */
  public int purgeExpired() {
    int days = props.getMetricRetentionDays();
    if (days <= 0) return 0;
    OffsetDateTime cutoff = OffsetDateTime.now().minusDays(days);
    int removed = metrics.delete(
        Wrappers.<EquipmentMetric>lambdaQuery().lt(EquipmentMetric::getSampledAt, cutoff));
    if (removed > 0) log.info("清理 {} 天前的设备原始采样 {} 条", days, removed);
    return removed;
  }

  public String lastRunStatus() {
    return lastRunStatus;
  }

  public int lastBatchSize() {
    return lastBatchSize;
  }

  public String lastRunAt() {
    return lastRunAt == null ? null : lastRunAt.toString();
  }

  public long totalCollected() {
    return totalCollected.get();
  }

  /** 表内现有采样条数（健康端点展示，判断链路是否有数据流过）。 */
  public long storedCount() {
    try {
      return metrics.selectCount(null);
    } catch (Exception e) {
      return -1;
    }
  }
}
