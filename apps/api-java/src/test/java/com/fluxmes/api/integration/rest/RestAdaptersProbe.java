package com.fluxmes.api.integration.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fluxmes.api.integration.IntegrationMode;
import com.fluxmes.api.integration.IntegrationProperties;
import com.fluxmes.api.integration.dto.ErpWorkOrder;
import com.fluxmes.api.integration.dto.LabResult;
import com.fluxmes.api.integration.dto.WmsInboundAck;
import com.fluxmes.api.integration.dto.WmsInboundRequest;
import com.fluxmes.api.integration.port.ErpMasterDataPort;
import com.fluxmes.api.integration.port.LabResultPort;
import com.fluxmes.api.integration.port.WmsInboundPort;
import java.util.List;

/**
 * LIMS / ERP / WMS 三个 REST 适配器的联调探针（测试域工具，不参与打包）。
 *
 * <p>与 {@link com.fluxmes.api.integration.opcua.OpcUaProbe} 同理：不起 Spring、
 * 不连数据库，直接把**真适配器**指向 mock 服务（或未来指向真实系统）跑一遍，
 * 用来回答一个具体问题——「适配器序列化/反序列化的字段名，与对面系统真的一致吗」。
 *
 * <p>这类契约错误在 Spring 里排查很隐蔽：适配器 catch 了异常并写进
 * {@code AdapterStatus.detail}，前端只显示一句「取数失败」，真正的
 * {@code UnrecognizedPropertyException} / 空列表原因被吞掉了。
 *
 * <p>用法（先起 mock 服务，见 {@code scripts/sim-up.sh}）：
 * <pre>
 * java -cp "target/classes;target/test-classes;$(cat target/cp.txt)" \
 *      com.fluxmes.api.integration.rest.RestAdaptersProbe
 * </pre>
 *
 * <p>只跑其中一个系统：{@code --only lims}
 */
public final class RestAdaptersProbe {

  public static void main(String[] args) throws Exception {
    String lims = arg(args, "--lims", "http://localhost:9100/lims");
    String erp = arg(args, "--erp", "http://localhost:9100/erp");
    String wms = arg(args, "--wms", "http://localhost:9100/wms");
    String only = arg(args, "--only", "").toLowerCase();

    IntegrationProperties props = new IntegrationProperties();
    props.getLims().setMode(IntegrationMode.REAL);
    props.getLims().setEndpoint(lims);
    props.getErp().setMode(IntegrationMode.REAL);
    props.getErp().setEndpoint(erp);
    props.getWms().setMode(IntegrationMode.REAL);
    props.getWms().setEndpoint(wms);

    // 用与 Spring 一致的 Jackson 2 + JavaTimeModule（OffsetDateTime 字段必需）
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    JsonHttpClient http = new JsonHttpClient(mapper);

    int failures = 0;
    if (only.isEmpty() || "lims".equals(only)) {
      failures += probeLims(new RestLabResultAdapter(props, http), lims);
    }
    if (only.isEmpty() || "erp".equals(only)) {
      failures += probeErp(new RestErpMasterDataAdapter(props, http), erp);
    }
    if (only.isEmpty() || "wms".equals(only)) {
      failures += probeWms(new RestWmsInboundAdapter(props, http), wms);
    }

    System.out.println();
    System.out.println(failures == 0
        ? "== 全部通过 =="
        : "== 有 " + failures + " 项未通过 ==");
    System.exit(failures == 0 ? 0 : 1);
  }

  private static int probeLims(LabResultPort port, String endpoint) {
    System.out.println("---- LIMS ----  " + endpoint);
    List<LabResult> results = port.poll();
    System.out.println("available : " + port.available());
    System.out.println("detail    : " + port.detail());
    System.out.println("回流条数  : " + results.size());
    for (LabResult r : results) {
      System.out.printf("  %-18s batch=%-14s conclusion=%-8s coa=%-16s 项目=%s%n",
          r.taskNo(), r.batchId(), r.conclusion(),
          r.coaNo() == null ? "-" : r.coaNo(), r.item());
    }
    // coaNo / conclusion / reportedAt 是回流链路的关键字段，缺任一即视为契约不符
    int broken = (int) results.stream()
        .filter(r -> r.taskNo() == null || r.batchId() == null || r.reportedAt() == null)
        .count();
    if (broken > 0) {
      System.out.println("  !! " + broken + " 条的 taskNo/batchId/reportedAt 为空——字段名不匹配");
    }
    System.out.println();
    return (port.available() && !results.isEmpty() && broken == 0) ? 0 : 1;
  }

  private static int probeErp(ErpMasterDataPort port, String endpoint) {
    System.out.println("---- ERP  ----  " + endpoint);
    List<ErpWorkOrder> orders = port.pullWorkOrders();
    System.out.println("available : " + port.available());
    System.out.println("detail    : " + port.detail());
    System.out.println("工单数    : " + orders.size());
    for (ErpWorkOrder o : orders) {
      System.out.printf("  %-22s %-14s plan=%-8s %-4s site=%-8s 计划 %s → %s%n",
          o.orderNo(), o.product(), o.planQty(), o.unit(), o.siteCode(),
          o.planStart(), o.planEnd());
    }
    int broken = (int) orders.stream()
        .filter(o -> o.orderNo() == null || o.product() == null || o.planQty() == null)
        .count();
    if (broken > 0) {
      System.out.println("  !! " + broken + " 张工单的 orderNo/product/planQty 为空——字段名不匹配");
    }
    System.out.println();
    return (port.available() && !orders.isEmpty() && broken == 0) ? 0 : 1;
  }

  private static int probeWms(WmsInboundPort port, String endpoint) {
    System.out.println("---- WMS  ----  " + endpoint);
    String batchId = "B-PROBE-" + System.currentTimeMillis();
    WmsInboundRequest req = new WmsInboundRequest(
        batchId, "一水柠檬酸", 25000.0, "kg", "SITE-01", "probe");
    WmsInboundAck first = port.requestInbound(req);
    System.out.println("available : " + port.available());
    System.out.println("detail    : " + port.detail());
    System.out.println("首次申请  : accepted=" + first.accepted() + " bin=" + first.bin()
        + " ackNo=" + first.ackNo() + " msg=" + first.message());

    // 幂等：同一 batchId 再发一次，库位必须一致（否则重放会把同一批货分到两个库位）
    WmsInboundAck again = port.requestInbound(req);
    System.out.println("重复申请  : accepted=" + again.accepted() + " bin=" + again.bin()
        + " ackNo=" + again.ackNo());
    boolean idempotent = first.accepted() && again.accepted()
        && first.bin() != null && first.bin().equals(again.bin());

    // 反例：缺 batchId 必须被拒（验证对端真的在校验，而不是照单全收）
    WmsInboundAck rejected = port.requestInbound(
        new WmsInboundRequest(null, "一水柠檬酸", 1.0, "kg", "SITE-01", "probe"));
    System.out.println("缺 batchId: accepted=" + rejected.accepted() + " msg=" + rejected.message());
    boolean rejectsBadInput = !rejected.accepted();

    if (!idempotent) {
      System.out.println("  !! 幂等失效：同一 batchId 两次申请拿到不同库位");
    }
    if (!rejectsBadInput) {
      System.out.println("  !! 校验缺失：缺 batchId 的请求被受理了");
    }
    System.out.println();
    return (port.available() && idempotent && rejectsBadInput) ? 0 : 1;
  }

  private static String arg(String[] args, String name, String fallback) {
    for (int i = 0; i < args.length - 1; i++) {
      if (name.equals(args[i])) return args[i + 1];
    }
    return fallback;
  }

  private RestAdaptersProbe() {}
}
