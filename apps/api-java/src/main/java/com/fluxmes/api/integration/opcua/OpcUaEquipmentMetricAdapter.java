package com.fluxmes.api.integration.opcua;

import com.fluxmes.api.integration.DataSourceTag;
import com.fluxmes.api.integration.IntegrationProperties;
import com.fluxmes.api.integration.dto.MetricSample;
import com.fluxmes.api.integration.port.EquipmentMetricPort;
import com.fluxmes.api.integration.support.AdapterStatus;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.eclipse.milo.opcua.sdk.client.AddressSpace;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.api.config.OpcUaClientConfig;
import org.eclipse.milo.opcua.sdk.client.api.config.OpcUaClientConfigBuilder;
import org.eclipse.milo.opcua.sdk.client.api.identity.AnonymousProvider;
import org.eclipse.milo.opcua.stack.core.AttributeId;
import org.eclipse.milo.opcua.stack.core.Identifiers;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.DateTime;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.QualifiedName;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned;
import org.eclipse.milo.opcua.stack.core.types.enumerated.MessageSecurityMode;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;
import org.eclipse.milo.opcua.stack.core.types.structured.EndpointDescription;
import org.eclipse.milo.opcua.stack.core.types.structured.ReadResponse;
import org.eclipse.milo.opcua.stack.core.types.structured.ReadValueId;
import org.eclipse.milo.opcua.stack.core.types.structured.ReferenceDescription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * REAL · 真 OPC-UA 客户端（{@code fluxmes.integration.scada.mode=real}）。
 *
 * <p>这是 MES 侧唯一与现场 SCADA **协议耦合**的地方，也是本类存在的全部理由：
 * 业务层（{@code EquipmentService} / {@code EquipmentMetricCollector}）只认识
 * {@link EquipmentMetricPort}，切到真实采集时它们一行都不用改。
 *
 * <h3>期望的地址空间契约（接入现场前需在 SCADA 网关侧组态成此形状）</h3>
 * <pre>
 * Objects/
 * └── FluxMES                 （根文件夹，名字固定）
 *     └── &lt;设备编号&gt;          （如 F-101）
 *         ├── F-101.temp      Double  值节点
 *         ├── F-101.temp.unit String  单位
 *         ├── F-101.temp.name String  展示名
 *         ├── F-101.temp.lo   Double  下限
 *         └── F-101.temp.hi   Double  上限
 * </pre>
 *
 * <p>即「设备文件夹 / {@code 设备.指标} 值节点 / {@code 设备.指标.元数据后缀} 伴随节点」。
 * 浏览名（BrowseName）承载语义，而不是靠 NodeId 硬编码——这样 SCADA 侧换编号规则时
 * 只需改这里的 {@link #ROOT_FOLDER} 与分隔符，不必改 NodeId 表。
 *
 * <p>现场网关组态不同怎么办：**改这一处**（本类的 {@code browse} 与映射逻辑），
 * 不要去改端口、采集器或业务服务——那正是把它们拆开的收益。
 *
 * <h3>为什么用批量 Read 而不是 Subscription</h3>
 * <p>采集周期是 30s 量级（NFR-1「一般设备 ≤30s」），订阅（Subscriptions）的优势在于
 * 亚秒级变化与减少往返，对本场景是过度设计；而订阅会引入会话保持、重连后重建监视项、
 * 队列溢出等一堆状态。批量 Read 无状态、天然幂等、失败可见——先用它，
 * 等确实出现「需要秒级捕捉瞬时越限」的需求再引入订阅。
 *
 * <h3>已知限制</h3>
 * <ul>
 *   <li>只支持匿名 + SecurityPolicy=None。现场若要求签名/加密，需要为客户端配置证书
 *       （{@code setKeyPair/setCertificate/setCertificateValidator}）并在
 *       {@link #selectEndpoint} 中放开对应的安全策略白名单。</li>
 *   <li>不做历史回填：真实设备没连上 MES 的那段时间，数据就是不存在，
 *       不该由 MES 编造。故 {@code backfill()} 沿用基类的「返回空」。</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "fluxmes.integration.scada.mode", havingValue = "real")
public class OpcUaEquipmentMetricAdapter implements EquipmentMetricPort {

  private static final Logger log = LoggerFactory.getLogger(OpcUaEquipmentMetricAdapter.class);

  /** 根文件夹浏览名；<b>接入现场时最可能需要改的就是这一行</b>。 */
  private static final String ROOT_FOLDER = "FluxMES";

  /** 浏览名分隔符：{@code 设备.指标}。 */
  private static final String SEP = ".";

  private static final String META_NAME = "name";
  private static final String META_UNIT = "unit";
  private static final String META_LO = "lo";
  private static final String META_HI = "hi";

  /** SecurityPolicy=None 的标准 URI 后缀。 */
  private static final String POLICY_NONE_SUFFIX = "#None";

  private final IntegrationProperties props;
  private final AdapterStatus status = new AdapterStatus();
  private final Object connectLock = new Object();

  private volatile OpcUaClient client;
  private volatile boolean connected;

  public OpcUaEquipmentMetricAdapter(IntegrationProperties props) {
    this.props = props;
  }

  @Override
  public String dataSource() {
    return DataSourceTag.OPCUA;
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

  /* ---------------- 采集 ---------------- */

  @Override
  public List<MetricSample> readAll() {
    long startedAt = System.nanoTime();
    try {
      OpcUaClient c = currentClient();
      AddressSpace as = c.getAddressSpace();

      NodeId rootId = findRootFolder(as);
      if (rootId == null) {
        status.failure("OPC-UA 地址空间里找不到根文件夹 " + ROOT_FOLDER + "（现场网关组态与 MES 契约不一致）");
        return List.of();
      }

      // 1) 把「设备 → 叶子节点浏览名 → NodeId」拍平成一个索引，后续全部按浏览名寻址
      Map<String, NodeId> leaves = new LinkedHashMap<>();
      int deviceCount = 0;
      for (ReferenceDescription deviceRef : as.browse(rootId)) {
        NodeId deviceId = as.toNodeId(deviceRef.getNodeId());
        if (deviceId == null) continue;
        deviceCount++;
        for (ReferenceDescription leafRef : as.browse(deviceId)) {
          String browseName = leafRef.getBrowseName() == null
              ? null : leafRef.getBrowseName().getName();
          NodeId leafId = as.toNodeId(leafRef.getNodeId());
          if (browseName != null && !browseName.isBlank() && leafId != null) {
            leaves.put(browseName, leafId);
          }
        }
      }

      // 2) 值节点 = 浏览名恰好两段（设备.指标）；三段的是元数据伴随节点
      List<String> valueKeys = leaves.keySet().stream()
          .filter(k -> k.split("\\" + SEP).length == 2)
          .sorted()
          .toList();
      if (valueKeys.isEmpty()) {
        status.failure("OPC-UA 根文件夹下没有 '设备.指标' 形式的值节点（发现 " + deviceCount
            + " 台设备 / " + leaves.size() + " 个叶子节点）");
        return List.of();
      }

      // 3) 一次批量读把值节点与全部元数据节点取回（避免 24×5 次往返）
      List<NodeId> ids = new ArrayList<>();
      List<String> names = new ArrayList<>();
      for (String key : valueKeys) {
        ids.add(leaves.get(key));
        names.add(key);
      }
      for (String key : valueKeys) {
        for (String meta : List.of(META_NAME, META_UNIT, META_LO, META_HI)) {
          NodeId id = leaves.get(key + SEP + meta);
          if (id != null) {
            ids.add(id);
            names.add(key + SEP + meta);
          }
        }
      }

      Map<String, DataValue> byName = batchRead(c, ids, names);

      // 4) 组装：元数据缺失时降级为 null/空串，而不是丢样本——
      //    「读数拿到了但单位没配」比「整条读数消失」更接近现场真实情况
      List<MetricSample> out = new ArrayList<>(valueKeys.size());
      for (String key : valueKeys) {
        String[] parts = key.split("\\" + SEP, 2);
        DataValue value = byName.get(key);
        Double v = asDouble(value);
        if (v == null && qualityOf(value).equals(MetricSample.QUALITY_BAD)) {
          log.debug("跳过无读数且质量码为 BAD 的指标 {}", key);
          continue;
        }
        out.add(new MetricSample(
            parts[0],
            parts[1],
            orDefault(asString(byName.get(key + SEP + META_NAME)), parts[1]),
            v == null ? null : r3(v),
            orDefault(asString(byName.get(key + SEP + META_UNIT)), ""),
            asDouble(byName.get(key + SEP + META_LO)),
            asDouble(byName.get(key + SEP + META_HI)),
            qualityOf(value),
            DataSourceTag.OPCUA,
            sampleTime(value)));
      }

      long ms = (System.nanoTime() - startedAt) / 1_000_000;
      status.success("OPC-UA 采集成功：" + out.size() + " 个指标 / " + deviceCount + " 台设备，"
          + ms + "ms @" + props.getScada().getEndpoint());
      return out;
    } catch (Exception e) {
      // 采集失败一律「断开并在下次调用重连」：OPC-UA 会话失效后不能复用
      markDown(e);
      return List.of();
    }
  }

  private Map<String, DataValue> batchRead(OpcUaClient c, List<NodeId> ids, List<String> names)
      throws Exception {
    List<ReadValueId> requests = ids.stream()
        .map(id -> new ReadValueId(id, AttributeId.Value.uid(), null, QualifiedName.NULL_VALUE))
        .toList();
    long timeoutMs = Math.max(1000, props.getScada().getTimeoutMs()) * 2L;
    ReadResponse response = c.read(0.0, TimestampsToReturn.Both, requests)
        .get(timeoutMs, TimeUnit.MILLISECONDS);
    List<DataValue> results = List.of(response.getResults());
    Map<String, DataValue> byName = new LinkedHashMap<>();
    for (int i = 0; i < names.size() && i < results.size(); i++) {
      byName.put(names.get(i), results.get(i));
    }
    return byName;
  }

  private static NodeId findRootFolder(AddressSpace as) throws Exception {
    for (ReferenceDescription ref : as.browse(Identifiers.ObjectsFolder)) {
      String name = ref.getBrowseName() == null ? null : ref.getBrowseName().getName();
      if (ROOT_FOLDER.equals(name)) {
        return as.toNodeId(ref.getNodeId());
      }
    }
    return null;
  }

  /* ---------------- 连接管理 ---------------- */

  /**
   * 取一个已连接的客户端；没有就建一个。
   *
   * <p>懒连接 + 失败即弃：连接建立成本（发现端点、建会话、读命名空间表）远高于一次
   * 批量读，因此成功后就复用；一旦读失败则整体丢弃，下次调用重建——
   * 比在会话上做复杂的断线重连状态机更简单，也更容易观测。
   */
  private OpcUaClient currentClient() throws Exception {
    OpcUaClient existing = client;
    if (existing != null && connected) {
      return existing;
    }
    synchronized (connectLock) {
      if (client != null && connected) {
        return client;
      }
      closeQuietly();
      String endpoint = props.getScada().getEndpoint();
      OpcUaClient created = OpcUaClient.create(endpoint,
          OpcUaEquipmentMetricAdapter::selectEndpoint, this::buildConfig);
      long timeoutMs = Math.max(1000, props.getScada().getTimeoutMs()) * 2L;
      created.connect().get(timeoutMs, TimeUnit.MILLISECONDS);
      client = created;
      connected = true;
      log.info("OPC-UA 客户端已连接：{}", endpoint);
      return created;
    }
  }

  /**
   * 端点选择：优先 None/None（本地模拟器与多数内网网关），否则退回第一个可用端点。
   *
   * <p>现场若启用签名/加密，这里要改成按 {@code SecurityPolicy} 白名单筛选，
   * 并同时给客户端配好证书——见类注释「已知限制」。
   */
  private static Optional<EndpointDescription> selectEndpoint(List<EndpointDescription> endpoints) {
    if (endpoints == null || endpoints.isEmpty()) {
      return Optional.empty();
    }
    Optional<EndpointDescription> none = endpoints.stream()
        .filter(e -> e.getSecurityMode() == MessageSecurityMode.None)
        .filter(e -> e.getSecurityPolicyUri() == null
            || e.getSecurityPolicyUri().endsWith(POLICY_NONE_SUFFIX))
        .findFirst();
    return none.or(() -> endpoints.stream().findFirst());
  }

  private OpcUaClientConfig buildConfig(OpcUaClientConfigBuilder builder) {
    int timeoutMs = Math.max(1000, props.getScada().getTimeoutMs());
    return builder
        .setApplicationName(LocalizedText.english("FluxMES"))
        .setApplicationUri("urn:fluxmes:mes")
        .setSessionName(() -> "fluxmes-metric-collector")
        .setConnectTimeout(Unsigned.uint(timeoutMs))
        .setRequestTimeout(Unsigned.uint(timeoutMs))
        .setIdentityProvider(new AnonymousProvider())
        .build();
  }

  private void markDown(Exception e) {
    String reason = e.getClass().getSimpleName() + ": " + e.getMessage();
    status.failure("OPC-UA 采集失败：" + reason);
    log.warn("OPC-UA 采集失败，将重建连接：{}", reason);
    synchronized (connectLock) {
      closeQuietly();
    }
  }

  private void closeQuietly() {
    OpcUaClient c = client;
    client = null;
    connected = false;
    if (c == null) return;
    try {
      c.disconnect().get(5, TimeUnit.SECONDS);
    } catch (Exception e) {
      log.debug("关闭 OPC-UA 客户端时异常（可忽略）：{}", e.toString());
    }
  }

  @PreDestroy
  void shutdown() {
    synchronized (connectLock) {
      closeQuietly();
    }
  }

  /* ---------------- OPC-UA 值 → 领域值 ---------------- */

  private static String qualityOf(DataValue dv) {
    if (dv == null) return MetricSample.QUALITY_BAD;
    StatusCode sc = dv.getStatusCode();
    if (sc == null) return MetricSample.QUALITY_BAD;
    if (sc.isGood()) return MetricSample.QUALITY_GOOD;
    if (sc.isUncertain()) return MetricSample.QUALITY_UNCERTAIN;
    return MetricSample.QUALITY_BAD;
  }

  /**
   * 取数据源时间戳（SourceTimestamp）。
   *
   * <p>用 SourceTimestamp 而非 ServerTimestamp：现场采集网关的 ServerTimestamp 是
   * 「网关转发时刻」，SourceTimestamp 才是「变送器采样时刻」，工艺追溯要后者。
   *
   * <p>做了一次合理性夹逼：设备时钟未同步时会给出 1601 年或 2100 年这类离谱时间，
   * 直接入库会污染趋势图，故落在 [2000-01-01, now+1天] 之外时退回本地时间并留痕。
   */
  private static OffsetDateTime sampleTime(DataValue dv) {
    DateTime source = dv == null ? null : dv.getSourceTime();
    if (source == null || source.isNull()) {
      return OffsetDateTime.now();
    }
    Instant instant = source.getJavaInstant();
    if (instant == null) return OffsetDateTime.now();
    Instant low = Instant.parse("2000-01-01T00:00:00Z");
    Instant high = Instant.now().plusSeconds(86_400);
    if (instant.isBefore(low) || instant.isAfter(high)) {
      return OffsetDateTime.now();
    }
    return OffsetDateTime.ofInstant(instant, ZoneId.systemDefault());
  }

  private static Double asDouble(DataValue dv) {
    Object raw = rawValue(dv);
    if (raw instanceof Number n) return n.doubleValue();
    if (raw instanceof String s) {
      try {
        return s.isBlank() ? null : Double.valueOf(s.trim());
      } catch (NumberFormatException e) {
        return null;
      }
    }
    if (raw instanceof Boolean b) return b ? 1.0 : 0.0;
    return null;
  }

  private static String asString(DataValue dv) {
    Object raw = rawValue(dv);
    return raw == null ? null : String.valueOf(raw);
  }

  private static Object rawValue(DataValue dv) {
    if (dv == null) return null;
    Variant v = dv.getValue();
    if (v == null || v.isNull()) return null;
    return v.getValue();
  }

  private static String orDefault(String v, String fallback) {
    return v == null || v.isBlank() ? fallback : v;
  }

  private static double r3(double v) {
    return Math.round(v * 1000.0) / 1000.0;
  }
}
