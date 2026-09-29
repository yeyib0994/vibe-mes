package com.fluxmes.sim;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.eclipse.milo.opcua.sdk.core.AccessLevel;
import org.eclipse.milo.opcua.sdk.core.Reference;
import org.eclipse.milo.opcua.sdk.server.OpcUaServer;
import org.eclipse.milo.opcua.sdk.server.api.DataItem;
import org.eclipse.milo.opcua.sdk.server.api.ManagedNamespaceWithLifecycle;
import org.eclipse.milo.opcua.sdk.server.api.MonitoredItem;
import org.eclipse.milo.opcua.sdk.server.api.config.OpcUaServerConfig;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaVariableNode;
import org.eclipse.milo.opcua.sdk.server.util.SubscriptionModel;
import org.eclipse.milo.opcua.stack.core.Identifiers;
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.DateTime;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.eclipse.milo.opcua.stack.core.types.enumerated.MessageSecurityMode;
import org.eclipse.milo.opcua.stack.server.EndpointConfiguration;

/**
 * SCADA / OPC-UA 协议模拟器（外部系统侧的替身，**不属于 MES 应用**）。
 *
 * <p>为什么不用"进程内返回假数组"：OPC-UA 的风险全在协议层——NodeId 寻址、订阅与
 * 会话生命周期、断线重连、数据类型映射、StatusCode 质量码、SourceTimestamp 语义。
 * 这些用假数组一个都验不到。本模拟器起一个**真的 OPC-UA Server**，MES 侧写的是
 * **真的 OPC-UA 客户端代码**；将来接真 SCADA 时只改 {@code opc.tcp://host:port}。
 *
 * <p>地址空间（三层，扁平寻址，模拟常见的 SCADA 网关组态）：
 * <pre>
 * Objects/
 * └── FluxMES                          （根文件夹）
 *     ├── F-101                       （设备文件夹）
 *     │   ├── F-101.temp              Double  值节点（订阅对象）
 *     │   ├── F-101.temp.unit         String  单位
 *     │   ├── F-101.temp.lo           Double  工艺下限
 *     │   ├── F-101.temp.hi           Double  工艺上限
 *     │   └── F-101.temp.name         String  指标展示名
 *     └── F-102 ...
 * </pre>
 *
 * <p>安全策略为 None + 匿名令牌：这是**本地演示模拟器**，不要暴露到网络上。
 *
 * <p>用法：
 * <pre>
 * bash scripts/mvn-sim.sh package
 * java -cp "target/ext-simulator-0.1.0.jar;target/lib/*" com.fluxmes.sim.ScadaSimulatorMain --port 4840
 * </pre>
 */
public final class ScadaSimulatorMain {

  private static final int DEFAULT_PORT = 4840;
  private static final String ENDPOINT_PATH = "/fluxmes";
  private static final String NAMESPACE_URI = "urn:fluxmes:scada";
  /** 质量码劣化的指标：F-103 电导率探头易结垢（与 MES 侧 mock 口径一致）。 */
  private static final String DEGRADED = "F-103.cond";

  public static void main(String[] args) throws Exception {
    int port = intArg(args, "--port", DEFAULT_PORT);
    long updateMs = intArg(args, "--update-ms", 2000);

    OpcUaServerConfig config = OpcUaServerConfig.builder()
        .setApplicationUri("urn:fluxmes:scada-simulator")
        .setProductUri("urn:fluxmes:scada-simulator")
        .setApplicationName(LocalizedText.english("FluxMES SCADA Simulator"))
        .setEndpoints(Set.of(
            EndpointConfiguration.newBuilder()
                .setBindAddress("0.0.0.0")
                .setBindPort(port)
                .setPath(ENDPOINT_PATH)
                .setSecurityPolicy(SecurityPolicy.None)
                .setSecurityMode(MessageSecurityMode.None)
                .addTokenPolicies(OpcUaServerConfig.USER_TOKEN_POLICY_ANONYMOUS)
                .build()))
        .build();

    OpcUaServer server = new OpcUaServer(config);
    FluxMesNamespace namespace = new FluxMesNamespace(server);
    namespace.startup();
    server.startup().get();

    String url = "opc.tcp://localhost:" + port + ENDPOINT_PATH;
    System.out.println("[SCADA-SIM] OPC-UA Server 已启动：" + url);
    System.out.println("[SCADA-SIM] 地址空间：" + NodeCatalog.METRICS.size()
        + " 个指标 / 命名空间 " + NAMESPACE_URI);
    System.out.println("[SCADA-SIM] 值刷新周期 " + updateMs + "ms（SecurityPolicy=None，仅限本地演示）");
    // ASCII 就绪标记：脚本据此判断服务可用。中文在 Windows 控制台常被按 GBK 落盘，
    // 用中文串做 grep 会因编码不一致而永远等不到。
    System.out.println("[SCADA-SIM] READY " + url);
    System.out.flush();

    ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "value-updater");
      t.setDaemon(true);
      return t;
    });
    exec.scheduleWithFixedDelay(namespace::updateValues, updateMs, updateMs, TimeUnit.MILLISECONDS);

    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      System.out.println("[SCADA-SIM] 正在关闭...");
      exec.shutdownNow();
      try {
        server.shutdown().get(5, TimeUnit.SECONDS);
      } catch (Exception ignored) {
        // 关闭失败不影响退出
      }
    }));

    // 必须阻塞主线程：Milo 的 netty 事件循环与我们的更新线程都是 daemon，
    // main 一旦返回 JVM 会立刻退出（shutdown hook 被触发），表现为「刚起来就关闭」。
    Thread.currentThread().join();
  }

  /** FluxMES 命名空间：按 {@link NodeCatalog} 建节点，并周期性刷新值。 */
  static final class FluxMesNamespace extends ManagedNamespaceWithLifecycle {

    private record MetricNode(NodeCatalog.Metric spec, UaVariableNode node) {}

    private final List<MetricNode> nodes = new ArrayList<>();

    /**
     * Mono/Milo 把「订阅与监视项」的生命周期回调独立成 {@code MonitoredItemServices}。
     * SDK 提供了 {@link SubscriptionModel} 作为标准实现——它负责采样定时器、
     * 死区（deadband）判定、队列与 Publish 投递。命名空间只需把它挂进生命周期并转发回调，
     * 不要自己写空实现，否则客户端建了订阅也永远收不到数据（静默失败）。
     */
    private final SubscriptionModel subscriptionModel;

    FluxMesNamespace(OpcUaServer server) {
      super(server, NAMESPACE_URI);
      subscriptionModel = new SubscriptionModel(server, this);
      getLifecycleManager().addLifecycle(subscriptionModel);
      getLifecycleManager().addStartupTask(this::createAddressSpace);
      getLifecycleManager().addShutdownTask(nodes::clear);
    }

    // ---- MonitoredItemServices：全部委托给 SubscriptionModel ----

    @Override
    public void onDataItemsCreated(List<DataItem> dataItems) {
      subscriptionModel.onDataItemsCreated(dataItems);
    }

    @Override
    public void onDataItemsModified(List<DataItem> dataItems) {
      subscriptionModel.onDataItemsModified(dataItems);
    }

    @Override
    public void onDataItemsDeleted(List<DataItem> dataItems) {
      subscriptionModel.onDataItemsDeleted(dataItems);
    }

    @Override
    public void onMonitoringModeChanged(List<MonitoredItem> monitoredItems) {
      subscriptionModel.onMonitoringModeChanged(monitoredItems);
    }

    private void createAddressSpace() {
      UaFolderNode root = new UaFolderNode(getNodeContext(),
          newNodeId(NodeCatalog.ROOT_NAME), newQualifiedName(NodeCatalog.ROOT_NAME),
          LocalizedText.english(NodeCatalog.ROOT_NAME));
      getNodeManager().addNode(root);
      // 把根文件夹挂到标准 Objects 文件夹下（否则客户端浏览不到）
      getNodeManager().addReference(new Reference(
          Identifiers.ObjectsFolder,
          Identifiers.Organizes,
          root.getNodeId().expanded(),
          true));

      Map<String, UaFolderNode> folders = new LinkedHashMap<>();
      for (NodeCatalog.Metric m : NodeCatalog.METRICS) {
        UaFolderNode folder = folders.computeIfAbsent(m.equipmentCode(), code -> {
          UaFolderNode f = new UaFolderNode(getNodeContext(), newNodeId(code),
              newQualifiedName(code), LocalizedText.english(m.equipmentName()));
          getNodeManager().addNode(f);
          root.addOrganizes(f);
          return f;
        });

        String browse = m.equipmentCode() + NodeCatalog.SEPARATOR + m.key();
        UaVariableNode valueNode = new UaVariableNode.UaVariableNodeBuilder(getNodeContext())
            .setNodeId(newNodeId(browse))
            .setBrowseName(newQualifiedName(browse))
            .setDisplayName(LocalizedText.english(browse))
            .setDataType(Identifiers.Double)
            .setTypeDefinition(Identifiers.BaseDataVariableType)
            .setValueRank(-1)
            .setAccessLevel(AccessLevel.READ_ONLY)
            .setUserAccessLevel(AccessLevel.READ_ONLY)
            .setValue(goodValue(m.liveValue()))
            .build();
        getNodeManager().addNode(valueNode);
        folder.addOrganizes(valueNode);
        nodes.add(new MetricNode(m, valueNode));

        // 元数据伴随节点：客户端浏览后按后缀归组，因此不需要额外的组态文件
        folder.addOrganizes(stringMeta(folder, browse, NodeCatalog.META_UNIT, m.unit()));
        folder.addOrganizes(stringMeta(folder, browse, NodeCatalog.META_NAME, m.metricName()));
        folder.addOrganizes(numberMeta(folder, browse, NodeCatalog.META_LO, m.lowerLimit()));
        folder.addOrganizes(numberMeta(folder, browse, NodeCatalog.META_HI, m.upperLimit()));
      }

      System.out.println("[SCADA-SIM] 地址空间构建完成：" + folders.size() + " 台设备，"
          + nodes.size() + " 个值节点，元数据节点 " + (nodes.size() * 4) + " 个");
    }

    private UaVariableNode stringMeta(UaFolderNode folder, String base, String suffix, String v) {
      String id = base + NodeCatalog.SEPARATOR + suffix;
      UaVariableNode n = new UaVariableNode.UaVariableNodeBuilder(getNodeContext())
          .setNodeId(newNodeId(id))
          .setBrowseName(newQualifiedName(id))
          .setDisplayName(LocalizedText.english(id))
          .setDataType(Identifiers.String)
          .setTypeDefinition(Identifiers.BaseDataVariableType)
          .setValueRank(-1)
          .setAccessLevel(AccessLevel.READ_ONLY)
          .setUserAccessLevel(AccessLevel.READ_ONLY)
          .setValue(new DataValue(new Variant(v == null ? "" : v), StatusCode.GOOD, DateTime.now()))
          .build();
      getNodeManager().addNode(n);
      return n;
    }

    private UaVariableNode numberMeta(UaFolderNode folder, String base, String suffix, double v) {
      String id = base + NodeCatalog.SEPARATOR + suffix;
      UaVariableNode n = new UaVariableNode.UaVariableNodeBuilder(getNodeContext())
          .setNodeId(newNodeId(id))
          .setBrowseName(newQualifiedName(id))
          .setDisplayName(LocalizedText.english(id))
          .setDataType(Identifiers.Double)
          .setTypeDefinition(Identifiers.BaseDataVariableType)
          .setValueRank(-1)
          .setAccessLevel(AccessLevel.READ_ONLY)
          .setUserAccessLevel(AccessLevel.READ_ONLY)
          .setValue(goodValue(v))
          .build();
      getNodeManager().addNode(n);
      return n;
    }

    /**
     * 周期性刷新所有值节点。
     *
     * <p>读数为「fixture 记录值（=现场基准）+ 慢波 + 快抖动」，确定性可复现：
     * 便于反复演示同一现象，也便于自检脚本断言。
     */
    void updateValues() {
      double t = System.currentTimeMillis() / 1000.0;
      for (MetricNode mn : nodes) {
        NodeCatalog.Metric m = mn.spec();
        double phase = m.seed();
        double slow = Math.sin(t / 90.0 + phase) * m.amplitude() * 0.40;
        double fast = Math.sin(t * 3.7 + phase * 7.13) * m.amplitude() * 0.15;
        double value = round3(m.liveValue() + slow + fast);

        String browse = m.equipmentCode() + NodeCatalog.SEPARATOR + m.key();
        boolean degraded = DEGRADED.equals(browse);
        mn.node().setValue(new DataValue(
            new Variant(value),
            degraded ? StatusCode.UNCERTAIN : StatusCode.GOOD,
            DateTime.now()));
      }
    }

    private static DataValue goodValue(double v) {
      return new DataValue(new Variant(v), StatusCode.GOOD, DateTime.now());
    }

    private static double round3(double v) {
      return Math.round(v * 1000.0) / 1000.0;
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

  private ScadaSimulatorMain() {}
}
