package com.fluxmes.api.integration.opcua;

import com.fluxmes.api.integration.IntegrationMode;
import com.fluxmes.api.integration.IntegrationProperties;
import com.fluxmes.api.integration.dto.MetricSample;
import com.fluxmes.api.integration.port.EquipmentMetricPort;
import java.util.List;

/**
 * OPC-UA 采集链路探针（测试域工具，不参与打包）。
 *
 * <p>存在理由：设备参数链路的故障点几乎全在协议层（端点 URL 不匹配、命名空间索引错、
 * 浏览名结构与网关组态不一致、证书/安全策略被拒），而走 Spring 全栈排查这些要先起
 * PostgreSQL、登录拿 JWT、再翻日志，反馈太慢。这个探针不求助于数据库与 Spring，
 * **直接构造真适配器并采集一次**，把结果打到控制台。
 *
 * <p>本地自检（先起模拟器，见 {@code scripts/sim-up.sh}）：
 * <pre>
 * bash scripts/mvn.sh test-compile
 * bash scripts/mvn.sh dependency:build-classpath -Dmdep.outputFile=target/cp.txt -Dmdep.includeScope=test
 * java -cp "target/classes;target/test-classes;$(cat target/cp.txt)" \
 *      com.fluxmes.api.integration.opcua.OpcUaProbe --endpoint opc.tcp://localhost:4840/fluxmes
 * </pre>
 *
 * <p>现场联调时先把 {@code --endpoint} 换成真实网关地址跑通本探针，再切
 * {@code fluxmes.integration.scada.mode=real}——先证明协议通，再谈业务。
 */
public final class OpcUaProbe {

  public static void main(String[] args) throws Exception {
    String endpoint = arg(args, "--endpoint", "opc.tcp://localhost:4840/fluxmes");
    int timeoutMs = intArg(args, "--timeout-ms", 3000);

    IntegrationProperties props = new IntegrationProperties();
    props.getScada().setMode(IntegrationMode.REAL);
    props.getScada().setEndpoint(endpoint);
    props.getScada().setTimeoutMs(timeoutMs);

    EquipmentMetricPort port = new OpcUaEquipmentMetricAdapter(props);

    System.out.println("=== OPC-UA 采集探针 ===");
    System.out.println("endpoint  : " + endpoint);
    System.out.println("timeout   : " + timeoutMs + "ms");
    long t0 = System.nanoTime();
    List<MetricSample> samples = port.readAll();
    long ms = (System.nanoTime() - t0) / 1_000_000;

    System.out.println("available : " + port.available() + "   (耗时 " + ms + "ms)");
    System.out.println("dataSource: " + port.dataSource());
    System.out.println("detail    : " + port.detail());
    System.out.println("lastOkAt  : " + port.lastSuccessAt());
    System.out.println("样本数    : " + samples.size());
    System.out.println();

    if (samples.isEmpty()) {
      System.out.println("!! 采集为空——协议或地址空间契约有问题，请看上面的 detail");
      System.exit(1);
    }

    System.out.printf("%-8s %-8s %-12s %10s %-6s %10s %10s %-10s %s%n",
        "设备", "指标", "名称", "值", "单位", "下限", "上限", "质量码", "采样时间");
    for (MetricSample s : samples) {
      System.out.printf("%-8s %-8s %-12s %10s %-6s %10s %10s %-10s %s%s%n",
          s.equipmentCode(), s.metricKey(), s.metricName(),
          s.value() == null ? "-" : String.valueOf(s.value()),
          s.unit(),
          s.lowerLimit() == null ? "-" : String.valueOf(s.lowerLimit()),
          s.upperLimit() == null ? "-" : String.valueOf(s.upperLimit()),
          s.quality(),
          s.sampledAt(),
          s.outOfControl() ? "   <== 越限" : "");
    }

    long good = samples.stream().filter(s -> MetricSample.QUALITY_GOOD.equals(s.quality())).count();
    long uncertain = samples.stream()
        .filter(s -> MetricSample.QUALITY_UNCERTAIN.equals(s.quality())).count();
    long bad = samples.stream().filter(s -> MetricSample.QUALITY_BAD.equals(s.quality())).count();
    long ooc = samples.stream().filter(MetricSample::outOfControl).count();
    long devices = samples.stream().map(MetricSample::equipmentCode).distinct().count();

    System.out.println();
    System.out.println("质量码分布：GOOD=" + good + " UNCERTAIN=" + uncertain + " BAD=" + bad);
    System.out.println("设备台数  ：" + devices);
    System.out.println("越限指标  ：" + ooc + "（outOfControl()：仅 GOOD 且超出 lo/hi 才算）");

    if (uncertain == 0) {
      System.out.println();
      System.out.println("提示：未观察到 UNCERTAIN 质量码——若地址空间包含 F-103.cond，"
          + "说明 StatusCode 劣化链路没生效");
    }
    System.exit(0);
  }

  private static String arg(String[] args, String name, String fallback) {
    for (int i = 0; i < args.length - 1; i++) {
      if (name.equals(args[i])) return args[i + 1];
    }
    return fallback;
  }

  private static int intArg(String[] args, String name, int fallback) {
    String v = arg(args, name, null);
    if (v == null) return fallback;
    try {
      return Integer.parseInt(v);
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  private OpcUaProbe() {}
}
