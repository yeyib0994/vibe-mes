package com.fluxmes.api.integration.mock;

import static com.fluxmes.api.common.ApiSupport.num;
import static com.fluxmes.api.common.ApiSupport.r3;

import com.fluxmes.api.core.FixtureStore;
import com.fluxmes.api.integration.DataSourceTag;
import com.fluxmes.api.integration.dto.MetricSample;
import com.fluxmes.api.integration.port.EquipmentMetricPort;
import com.fluxmes.api.integration.support.AdapterStatus;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * L1 · 进程内 Fake 的设备参数源（{@code fluxmes.integration.scada.mode=mock}）。
 *
 * <p>本地没有 PLC / SCADA 时使用。数据以 fixture 的设备台账参数为中心（那些值是
 * 「当前读数」的语义），叠加慢波 + 快抖动的**确定性**漂移，使多次轮询之间数值
 * 会变化但形状稳定——既像实时数据，又不会让自检脚本无法复现。
 *
 * <p>这是 P0 阶段「假数据」的规范化归宿：此前伪序列生成在
 * {@code EquipmentService.genSeries()} 里，属于**业务服务内的 mock 逻辑**，
 * 接真采集时无法剥离；现在它被关在适配器里，业务服务只认识端口。
 */
@Component
@ConditionalOnProperty(name = "fluxmes.integration.scada.mode", havingValue = "mock",
    matchIfMissing = true)
public class MockEquipmentMetricAdapter implements EquipmentMetricPort {

  /** 质量码劣化的指标：F-103 电导率探头易结垢，模拟为 UNCERTAIN 以验证质量码链路。 */
  private static final String DEGRADED_METRIC = "F-103.cond";

  private final FixtureStore fixtures;
  private final AdapterStatus status = new AdapterStatus();

  public MockEquipmentMetricAdapter(FixtureStore fixtures) {
    this.fixtures = fixtures;
  }

  @Override
  public String dataSource() {
    return DataSourceTag.MOCK;
  }

  @Override
  public boolean available() {
    return status.available();
  }

  @Override
  public String detail() {
    return status.detail();
  }

  @Override
  public String lastSuccessAt() {
    return status.lastSuccessAt();
  }

  @Override
  public List<MetricSample> readAll() {
    try {
      OffsetDateTime now = OffsetDateTime.now();
      List<MetricSample> out = new ArrayList<>();
      for (Map<String, Object> spec : fixtures.equipmentSpec()) {
        String code = str(spec.get("code"));
        Object rawParams = spec.get("params");
        if (code.isBlank() || !(rawParams instanceof List<?> params)) continue;
        for (Object raw : params) {
          if (!(raw instanceof Map<?, ?> p)) continue;
          @SuppressWarnings("unchecked")
          Map<String, Object> param = (Map<String, Object>) p;
          String key = str(param.get("key"));
          if (key.isBlank()) continue;
          double seed = num(param.get("seed"), num(param.get("value"), 0));
          double amp = num(param.get("amp"), 0.5);
          boolean degraded = DEGRADED_METRIC.equals(code + "." + key);
          out.add(new MetricSample(
              code,
              key,
              str(param.get("name")),
              liveValue(seed, amp, now),
              str(param.get("unit")),
              nullableNum(param.get("lo")),
              nullableNum(param.get("hi")),
              degraded ? MetricSample.QUALITY_UNCERTAIN : MetricSample.QUALITY_GOOD,
              DataSourceTag.MOCK,
              now));
        }
      }
      status.success("mock 设备参数源，共 " + out.size() + " 个指标（fixture 派生，非真实采集）");
      return out;
    } catch (Exception e) {
      status.failure("mock 取数失败：" + e);
      return List.of();
    }
  }

  /**
   * 冷启动回填：按原有伪序列公式（wave + noise，末值收敛实时读数）构造一段历史，
   * 使首次启动时趋势图不是空白。所有回填采样带 {@code dataSource=MOCK}，可识别、可清理。
   *
   * <p>真实 SCADA 接入后本方法不会被调用（REAL 模式不装配本类；且基类默认返回空）。
   */
  @Override
  public List<MetricSample> backfill(int points, long stepMs) {
    List<MetricSample> out = new ArrayList<>();
    OffsetDateTime now = OffsetDateTime.now();
    for (Map<String, Object> spec : fixtures.equipmentSpec()) {
      String code = str(spec.get("code"));
      Object rawParams = spec.get("params");
      if (code.isBlank() || !(rawParams instanceof List<?> params)) continue;
      for (Object raw : params) {
        if (!(raw instanceof Map<?, ?> p)) continue;
        @SuppressWarnings("unchecked")
        Map<String, Object> param = (Map<String, Object>) p;
        String key = str(param.get("key"));
        if (key.isBlank()) continue;
        double seed = num(param.get("seed"), num(param.get("value"), 0));
        double amp = num(param.get("amp"), 0.5);
        double live = num(param.get("value"), seed);
        boolean degraded = DEGRADED_METRIC.equals(code + "." + key);
        for (int i = 0; i < points; i++) {
          double wave = Math.sin((double) i / points * Math.PI * 2 + seed) * amp;
          double noise = Math.sin(i * 12.9898 + seed * 7.13) * amp * 0.25;
          double v = (i == points - 1) ? live : seed + wave + noise;
          out.add(new MetricSample(
              code, key, str(param.get("name")), r3(v), str(param.get("unit")),
              nullableNum(param.get("lo")), nullableNum(param.get("hi")),
              degraded ? MetricSample.QUALITY_UNCERTAIN : MetricSample.QUALITY_GOOD,
              DataSourceTag.MOCK,
              now.minus((long) (points - 1 - i) * stepMs, ChronoUnit.MILLIS)));
        }
      }
    }
    status.success("mock 设备参数源：回填历史 " + out.size() + " 点（dataSource=MOCK）");
    return out;
  }

  /**
   * 确定性活值：以 fixture 记录值为中心，叠加 90s 慢波与快抖动。
   * 同一秒内多次调用结果一致，跨秒变化——像实时数据，但可复现。
   */
  private static double liveValue(double seed, double amp, OffsetDateTime now) {
    long t = now.toEpochSecond();
    double slow = Math.sin(t / 90.0) * amp * 0.35;
    double fast = Math.sin(t * 12.9898) * amp * 0.15;
    return r3(seed + slow + fast);
  }

  private static Double nullableNum(Object v) {
    if (v == null) return null;
    double d = num(v, Double.NaN);
    return Double.isNaN(d) ? null : d;
  }

  private static String str(Object v) {
    return v == null ? "" : String.valueOf(v);
  }
}
