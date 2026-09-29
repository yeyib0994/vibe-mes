package com.fluxmes.api.integration.rest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fluxmes.api.integration.DataSourceTag;
import com.fluxmes.api.integration.IntegrationProperties;
import com.fluxmes.api.integration.dto.ErpWorkOrder;
import com.fluxmes.api.integration.port.ErpMasterDataPort;
import com.fluxmes.api.integration.support.AdapterStatus;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 真实 ERP 适配器（{@code fluxmes.integration.erp.mode=real}）。
 *
 * <p>契约（与 {@code tools/ext-simulator} 的 ExtMockServiceMain 同构）：
 * <pre>
 * GET  {endpoint}/work-orders → [ {orderNo,product,planQty,unit,line,siteCode,recipeCode,planStart,planEnd,source} ]
 * POST {endpoint}/completion  ← {orderNo, actualQty, unit}
 * </pre>
 */
@Component
@ConditionalOnProperty(name = "fluxmes.integration.erp.mode", havingValue = "real")
public class RestErpMasterDataAdapter implements ErpMasterDataPort {

  private final IntegrationProperties props;
  private final JsonHttpClient http;
  private final AdapterStatus status = new AdapterStatus();

  public RestErpMasterDataAdapter(IntegrationProperties props, JsonHttpClient http) {
    this.props = props;
    this.http = http;
  }

  @Override
  public String dataSource() {
    return DataSourceTag.ERP;
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
  public List<ErpWorkOrder> pullWorkOrders() {
    IntegrationProperties.SystemConfig cfg = props.getErp();
    try {
      JsonNode node = http.get(base(cfg) + "/work-orders", cfg.getTimeoutMs(), cfg.getRetries());
      List<ErpWorkOrder> out =
          http.mapper().convertValue(node, new TypeReference<List<ErpWorkOrder>>() {});
      status.success("ERP 下发 " + out.size() + " 张工单");
      return out;
    } catch (JsonHttpClient.IntegrationCallException e) {
      status.failure("ERP 取数失败：" + e.getMessage());
      return List.of();
    } catch (Exception e) {
      status.failure("ERP 响应解析失败：" + e);
      return List.of();
    }
  }

  @Override
  public void reportCompletion(String orderNo, Double actualQty, String unit) {
    IntegrationProperties.SystemConfig cfg = props.getErp();
    try {
      Map<String, Object> payload = new HashMap<>();
      payload.put("orderNo", orderNo);
      payload.put("actualQty", actualQty);
      payload.put("unit", unit);
      http.post(base(cfg) + "/completion", payload, cfg.getTimeoutMs(), cfg.getRetries());
      status.success("ERP 已接收完工回报 " + orderNo);
    } catch (Exception e) {
      status.failure("ERP 完工回报失败：" + e.getMessage());
    }
  }

  private static String base(IntegrationProperties.SystemConfig cfg) {
    String ep = cfg.getEndpoint();
    return ep == null ? "" : ep.replaceAll("/+$", "");
  }
}
