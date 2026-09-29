package com.fluxmes.sim;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LIMS / ERP / WMS 的 HTTP 契约模拟服务（外部系统侧的替身，**不属于 MES 应用**）。
 *
 * <p>对应 {@code docs/integration-mock-plan.md} 的 <b>L2 · 独立 Mock 服务</b>：
 * 独立进程暴露 HTTP 端点，MES 侧用**真实 HTTP 客户端**去连——因此超时、重试、
 * 错误码、幂等、序列化这些"集成真正会坏的地方"都被真实覆盖。
 *
 * <p>核心价值在 {@code /_admin/fault}：可以按需注入 timeout / 500 / slow / duplicate，
 * 用来验证 MES 的降级分支。这是"进程内 Fake"做不到的——那种做法永远只返回成功。
 *
 * <p>端点：
 * <pre>
 * GET  /health                 健康
 * GET  /lims/results           LIMS 待回流检验结果
 * POST /lims/coa               LIMS 接收 COA 回传
 * GET  /erp/work-orders        ERP 待下发工单
 * POST /erp/completion         ERP 接收完工回报
 * POST /wms/inbound            WMS 受理入库申请，分配库位
 * GET  /_admin/state           当前故障模式与统计
 * POST /_admin/fault           {"mode":"none|timeout|error500|slow|duplicate"}
 * </pre>
 *
 * <p>用法：
 * <pre>
 * java -cp "target/ext-simulator-0.1.0.jar;target/lib/*" com.fluxmes.sim.ExtMockServiceMain --port 9100
 * </pre>
 */
public final class ExtMockServiceMain {

  private static final int DEFAULT_PORT = 9100;
  /** 注入 TIMEOUT 时的挂起时长，需大于 MES 侧配置的 timeout-ms（默认 3000）。 */
  private static final long TIMEOUT_HANG_MS = 60_000;

  public static void main(String[] args) throws Exception {
    int port = intArg(args, "--port", DEFAULT_PORT);

    HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
    Handler handler = new Handler();
    server.createContext("/", handler);
    ExecutorService executor = Executors.newFixedThreadPool(8);
    server.setExecutor(executor);
    server.start();

    System.out.println("[EXT-MOCK] LIMS/ERP/WMS mock 服务已启动：http://localhost:" + port);
    System.out.println("[EXT-MOCK]   LIMS : http://localhost:" + port + "/lims/results");
    System.out.println("[EXT-MOCK]   ERP  : http://localhost:" + port + "/erp/work-orders");
    System.out.println("[EXT-MOCK]   WMS  : http://localhost:" + port + "/wms/inbound");
    System.out.println("[EXT-MOCK]   故障注入：POST http://localhost:" + port
        + "/_admin/fault  {\"mode\":\"timeout|error500|slow|duplicate|none\"}");
    System.out.println("[EXT-MOCK]   待回流检验结果 " + ExtMockData.LAB_RESULTS.size()
        + " 条 / ERP 工单 " + ExtMockData.WORK_ORDERS.size() + " 张");
    // ASCII 就绪标记：脚本据此判断服务可用（中文在 Windows 控制台可能被按 GBK 落盘）
    System.out.println("[EXT-MOCK] READY http://localhost:" + port);
    System.out.flush();

    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      System.out.println("[EXT-MOCK] 正在关闭...");
      server.stop(0);
      executor.shutdownNow();
    }));

    // 显式阻塞主线程：不依赖 HttpServer 内部线程是否 daemon，
    // 也避免以后给线程池加 daemon 开关时出现「服务刚起就退出」的诡异现象。
    Thread.currentThread().join();
  }

  /** 可注入的故障模式。 */
  enum Fault {
    NONE, TIMEOUT, ERROR500, SLOW, DUPLICATE;

    static Fault parse(String s) {
      if (s == null) return NONE;
      for (Fault f : values()) {
        if (f.name().equalsIgnoreCase(s.replace("-", "").replace("_", ""))) return f;
      }
      return NONE;
    }
  }

  static final class Handler implements HttpHandler {

    private volatile Fault fault = Fault.NONE;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicInteger injections = new AtomicInteger();
    /** duplicate 模式下的幂等记录：同一批次只应得到同一个库位。 */
    private final Set<String> issuedBins = ConcurrentHashMap.newKeySet();
    private final List<String> log = new ArrayList<>();

    @Override
    public void handle(HttpExchange ex) throws IOException {
      String path = ex.getRequestURI().getPath();
      String method = ex.getRequestMethod();
      requests.incrementAndGet();
      try {
        // 故障注入点：所有业务端点都受同一个开关控制，便于一次性演练
        Fault f = fault;
        if (f == Fault.TIMEOUT && !path.startsWith("/_admin")) {
          injections.incrementAndGet();
          note(method + " " + path + " → 注入 TIMEOUT（挂起 " + TIMEOUT_HANG_MS + "ms）");
          sleep(TIMEOUT_HANG_MS);
          respond(ex, 504, json("error", "simulated timeout"));
          return;
        }
        if (f == Fault.SLOW && !path.startsWith("/_admin")) {
          injections.incrementAndGet();
          sleep(2000);
          note(method + " " + path + " → 注入 SLOW（延迟 2000ms）");
        }
        if (f == Fault.ERROR500 && !path.startsWith("/_admin")) {
          injections.incrementAndGet();
          note(method + " " + path + " → 注入 HTTP 500");
          respond(ex, 500, json("error", "simulated internal error"));
          return;
        }

        if ("/health".equals(path)) {
          respond(ex, 200, json("ok", true, "fault", fault.name(),
              "requests", requests.get()));
          return;
        }
        if (path.startsWith("/lims")) {
          handleLims(ex, path, method);
          return;
        }
        if (path.startsWith("/erp")) {
          handleErp(ex, path, method);
          return;
        }
        if (path.startsWith("/wms")) {
          handleWms(ex, path, method);
          return;
        }
        if (path.startsWith("/_admin")) {
          handleAdmin(ex, path, method);
          return;
        }
        respond(ex, 404, json("error", "no route: " + path));
      } catch (Exception e) {
        respond(ex, 500, json("error", String.valueOf(e)));
      } finally {
        ex.close();
      }
    }

    /* ---------------- LIMS ---------------- */

    private void handleLims(HttpExchange ex, String path, String method) throws IOException {
      if (path.endsWith("/results") && "GET".equals(method)) {
        OffsetDateTime now = OffsetDateTime.now();
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < ExtMockData.LAB_RESULTS.size(); i++) {
          ExtMockData.LabResultRow r = ExtMockData.LAB_RESULTS.get(i);
          if (i > 0) sb.append(',');
          sb.append('{')
              .append(field("taskNo", r.taskNo())).append(',')
              .append(field("batchId", r.batchId())).append(',')
              .append(field("item", r.item())).append(',')
              .append("\"value\":null,\"unit\":null,")
              .append(field("standard", "GB 1886.25—2016")).append(',')
              .append(field("conclusion", r.pass() ? "PASS" : "PENDING")).append(',')
              .append(r.pass()
                  ? field("coaNo", coaOf(r.batchId())) + ","
                  : "\"coaNo\":null,")
              .append(field("inspector", r.inspector())).append(',')
              .append(field("reportedAt", now.minusMinutes(30).toString()))
              .append('}');
        }
        sb.append(']');
        note("GET " + path + " → 回流 " + ExtMockData.LAB_RESULTS.size() + " 条检验结果");
        respond(ex, 200, sb.toString());
        return;
      }
      if (path.endsWith("/coa") && "POST".equals(method)) {
        String body = readBody(ex);
        note("POST " + path + " → 接收 COA：" + body);
        respond(ex, 200, json("ok", true));
        return;
      }
      respond(ex, 404, json("error", "no LIMS route: " + path));
    }

    /* ---------------- ERP ---------------- */

    private void handleErp(HttpExchange ex, String path, String method) throws IOException {
      if (path.endsWith("/work-orders") && "GET".equals(method)) {
        OffsetDateTime now = OffsetDateTime.now();
        String today = now.toLocalDate().toString();
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < ExtMockData.WORK_ORDERS.size(); i++) {
          ExtMockData.WorkOrderRow w = ExtMockData.WORK_ORDERS.get(i);
          if (i > 0) sb.append(',');
          sb.append('{')
              .append(field("orderNo", w.orderNo())).append(',')
              .append(field("product", w.product())).append(',')
              .append("\"planQty\":12.0,\"unit\":\"t\",\"line\":null,")
              .append(field("siteCode", w.siteCode())).append(',')
              .append(field("recipeCode", w.recipeCode())).append(',')
              .append(field("planStart", today)).append(',')
              .append(field("planEnd", now.toLocalDate().plusDays(3).toString())).append(',')
              .append(field("source", "ERP"))
              .append('}');
        }
        sb.append(']');
        note("GET " + path + " → 下发 " + ExtMockData.WORK_ORDERS.size() + " 张工单");
        respond(ex, 200, sb.toString());
        return;
      }
      if (path.endsWith("/completion") && "POST".equals(method)) {
        String body = readBody(ex);
        note("POST " + path + " → 接收完工回报：" + body);
        respond(ex, 200, json("ok", true));
        return;
      }
      respond(ex, 404, json("error", "no ERP route: " + path));
    }

    /* ---------------- WMS ---------------- */

    private void handleWms(HttpExchange ex, String path, String method) throws IOException {
      if (!path.endsWith("/inbound") || !"POST".equals(method)) {
        respond(ex, 404, json("error", "no WMS route: " + path));
        return;
      }
      String body = readBody(ex);
      String batchId = extractString(body, "batchId");
      Double qty = extractNumber(body, "qty");

      if (batchId == null || batchId.isBlank()) {
        respond(ex, 200, wmsAck(false, null, null, "缺少 batchId，拒绝入库"));
        return;
      }
      if (qty == null || qty <= 0) {
        respond(ex, 200, wmsAck(false, null, null, "数量缺失或非正，拒绝入库"));
        return;
      }

      String bin = allocateBin(batchId);
      boolean dup = fault == Fault.DUPLICATE && !issuedBins.add(batchId);
      note("POST " + path + " → 受理 " + batchId + " / " + qty + " → 库位 " + bin
          + (dup ? "（duplicate 模式：重复受理，库位保持一致以验证幂等）" : ""));
      respond(ex, 200, wmsAck(true, "WMS-ACK-" + batchId, bin,
          dup ? "重复受理（幂等返回同一库位）" : "库位已分配"));
    }

    private String wmsAck(boolean accepted, String ackNo, String bin, String message) {
      return "{"
          + "\"accepted\":" + accepted + ","
          + (ackNo == null ? "\"ackNo\":null," : field("ackNo", ackNo) + ",")
          + (bin == null ? "\"bin\":null," : field("bin", bin) + ",")
          + field("message", message) + ","
          + field("ackedAt", OffsetDateTime.now().toString()) + ","
          + field("dataSource", "WMS")
          + "}";
    }

    /** 成品库 A 区 18 个库位循环，按批次号哈希——确定性，便于复现与断言。 */
    private static String allocateBin(String batchId) {
      int h = Math.abs(batchId.hashCode());
      return String.format("CP-A-%02d", (h % 18) + 1);
    }

    /* ---------------- 管理 ---------------- */

    private void handleAdmin(HttpExchange ex, String path, String method) throws IOException {
      if (path.endsWith("/state") && "GET".equals(method)) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < log.size(); i++) {
          if (i > 0) sb.append(',');
          sb.append('"').append(escape(log.get(i))).append('"');
        }
        sb.append(']');
        respond(ex, 200, "{"
            + "\"fault\":" + quote(fault.name()) + ","
            + "\"requests\":" + requests.get() + ","
            + "\"injections\":" + injections.get() + ","
            + "\"log\":" + sb
            + "}");
        return;
      }
      if (path.endsWith("/fault") && "POST".equals(method)) {
        String body = readBody(ex);
        Fault f = Fault.parse(extractString(body, "mode"));
        fault = f;
        note("故障模式切换为 " + f.name());
        respond(ex, 200, json("ok", true, "fault", f.name()));
        return;
      }
      respond(ex, 404, json("error", "no admin route: " + path));
    }

    /* ---------------- helpers ---------------- */

    private void note(String line) {
      synchronized (log) {
        log.add(OffsetDateTime.now().toLocalTime() + " " + line);
        if (log.size() > 200) log.remove(0);
      }
      System.out.println("[EXT-MOCK] " + line);
    }

    private static String coaOf(String batchId) {
      String tail = batchId.length() >= 3 ? batchId.substring(batchId.length() - 3) : batchId;
      return "COA-" + OffsetDateTime.now().format(
          java.time.format.DateTimeFormatter.ofPattern("yyMMdd")) + "-" + tail;
    }

    private static String readBody(HttpExchange ex) throws IOException {
      return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
      ex.sendResponseHeaders(status, bytes.length);
      try (OutputStream os = ex.getResponseBody()) {
        os.write(bytes);
      }
    }

    private static void sleep(long ms) {
      try {
        Thread.sleep(ms);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    private static String json(Object... kv) {
      StringBuilder sb = new StringBuilder("{");
      for (int i = 0; i + 1 < kv.length; i += 2) {
        if (i > 0) sb.append(',');
        sb.append(quote(String.valueOf(kv[i]))).append(':');
        Object v = kv[i + 1];
        if (v instanceof Boolean || v instanceof Number) {
          sb.append(v);
        } else {
          sb.append(quote(String.valueOf(v)));
        }
      }
      return sb.append('}').toString();
    }

    private static String field(String key, String value) {
      return quote(key) + ":" + quote(value);
    }

    private static String quote(String s) {
      return "\"" + escape(s) + "\"";
    }

    private static String escape(String s) {
      if (s == null) return "";
      StringBuilder sb = new StringBuilder();
      for (char c : s.toCharArray()) {
        switch (c) {
          case '"' -> sb.append("\\\"");
          case '\\' -> sb.append("\\\\");
          case '\n' -> sb.append("\\n");
          case '\r' -> sb.append("\\r");
          case '\t' -> sb.append("\\t");
          default -> sb.append(c);
        }
      }
      return sb.toString();
    }

    private static String extractString(String json, String key) {
      Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"")
          .matcher(json == null ? "" : json);
      return m.find() ? m.group(1) : null;
    }

    private static Double extractNumber(String json, String key) {
      Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?[0-9.]+)")
          .matcher(json == null ? "" : json);
      if (!m.find()) return null;
      try {
        return Double.parseDouble(m.group(1));
      } catch (NumberFormatException e) {
        return null;
      }
    }
  }

  private static int intArg(String[] args, String name, int fallback) {
    for (int i = 0; i < args.length - 1; i++) {
      if (name.equals(args[i])) {
        try {
          return Integer.parseInt(args[i + 1]);
        } catch (NumberFormatException ignored) {
          return fallback;
        }
      }
    }
    return fallback;
  }

  private ExtMockServiceMain() {}
}
