package com.fluxmes.api.integration.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fluxmes.api.integration.DataSourceTag;
import com.fluxmes.api.integration.IntegrationProperties;
import com.fluxmes.api.integration.dto.WmsInboundAck;
import com.fluxmes.api.integration.dto.WmsInboundRequest;
import com.fluxmes.api.integration.port.WmsInboundPort;
import com.fluxmes.api.integration.support.AdapterStatus;
import java.time.OffsetDateTime;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 真实 WMS 适配器（{@code fluxmes.integration.wms.mode=real}）。
 *
 * <p>契约（与 {@code tools/ext-simulator} 的 ExtMockServiceMain 同构）：
 * <pre>
 * POST {endpoint}/inbound ← {batchId, product, qty, unit, siteCode, requestedBy}
 *                         → {accepted, ackNo, bin, message, ackedAt, dataSource}
 * </pre>
 */
@Component
@ConditionalOnProperty(name = "fluxmes.integration.wms.mode", havingValue = "real")
public class RestWmsInboundAdapter implements WmsInboundPort {

  private final IntegrationProperties props;
  private final JsonHttpClient http;
  private final AdapterStatus status = new AdapterStatus();

  public RestWmsInboundAdapter(IntegrationProperties props, JsonHttpClient http) {
    this.props = props;
    this.http = http;
  }

  @Override
  public String dataSource() {
    return DataSourceTag.WMS;
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
  public WmsInboundAck requestInbound(WmsInboundRequest request) {
    IntegrationProperties.SystemConfig cfg = props.getWms();
    try {
      JsonNode node = http.post(base(cfg) + "/inbound", request, cfg.getTimeoutMs(),
          cfg.getRetries());
      WmsInboundAck ack = http.mapper().treeToValue(node, WmsInboundAck.class);
      if (ack == null) throw new IllegalStateException("WMS 返回空应答");
      status.success("WMS 受理 " + request.batchId() + " → 库位 " + ack.bin());
      return ack;
    } catch (Exception e) {
      // 外部系统不可用时返回显式的拒绝应答，而不是抛异常——
      // 调用方（批次放行流程）需要能区分「WMS 拒绝」与「WMS 不可达」，并降级为人工指定库位。
      status.failure("WMS 入库申请失败：" + e.getMessage());
      return new WmsInboundAck(false, null, null, "WMS 不可达或应答异常：" + e.getMessage(),
          OffsetDateTime.now(), DataSourceTag.WMS);
    }
  }

  private static String base(IntegrationProperties.SystemConfig cfg) {
    String ep = cfg.getEndpoint();
    return ep == null ? "" : ep.replaceAll("/+$", "");
  }
}
