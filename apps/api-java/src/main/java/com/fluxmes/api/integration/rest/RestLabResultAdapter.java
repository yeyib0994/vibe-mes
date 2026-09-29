package com.fluxmes.api.integration.rest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fluxmes.api.integration.DataSourceTag;
import com.fluxmes.api.integration.IntegrationProperties;
import com.fluxmes.api.integration.dto.LabResult;
import com.fluxmes.api.integration.port.LabResultPort;
import com.fluxmes.api.integration.support.AdapterStatus;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 真实 LIMS 适配器（{@code fluxmes.integration.lims.mode=real}）。
 *
 * <p>契约（与 {@code tools/ext-simulator} 的 ExtMockServiceMain 同构）：
 * <pre>
 * GET  {endpoint}/results   → [ {taskNo,batchId,item,value,unit,standard,conclusion,coaNo,inspector,reportedAt} ]
 * POST {endpoint}/coa       ← {batchId, coaNo}
 * </pre>
 *
 * <p>接入真实 LIMS 时只需要改 {@code endpoint}——业务代码零改动。
 */
@Component
@ConditionalOnProperty(name = "fluxmes.integration.lims.mode", havingValue = "real")
public class RestLabResultAdapter implements LabResultPort {

  private final IntegrationProperties props;
  private final JsonHttpClient http;
  private final AdapterStatus status = new AdapterStatus();

  public RestLabResultAdapter(IntegrationProperties props, JsonHttpClient http) {
    this.props = props;
    this.http = http;
  }

  @Override
  public String dataSource() {
    return DataSourceTag.LIMS;
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
  public List<LabResult> poll() {
    IntegrationProperties.SystemConfig cfg = props.getLims();
    try {
      JsonNode node = http.get(base(cfg) + "/results", cfg.getTimeoutMs(), cfg.getRetries());
      List<LabResult> out = http.mapper().convertValue(node, new TypeReference<List<LabResult>>() {});
      status.success("LIMS 回流 " + out.size() + " 条检验结果");
      return out;
    } catch (JsonHttpClient.IntegrationCallException e) {
      status.failure("LIMS 取数失败：" + e.getMessage());
      return List.of();
    } catch (Exception e) {
      status.failure("LIMS 响应解析失败：" + e);
      return List.of();
    }
  }

  @Override
  public void publishCoa(String batchId, String coaNo) {
    IntegrationProperties.SystemConfig cfg = props.getLims();
    try {
      Map<String, Object> payload = new HashMap<>();
      payload.put("batchId", batchId);
      payload.put("coaNo", coaNo);
      http.post(base(cfg) + "/coa", payload, cfg.getTimeoutMs(), cfg.getRetries());
      status.success("LIMS 已接收 COA 回传 " + coaNo);
    } catch (Exception e) {
      status.failure("LIMS COA 回传失败：" + e.getMessage());
    }
  }

  private static String base(IntegrationProperties.SystemConfig cfg) {
    String ep = cfg.getEndpoint();
    return ep == null ? "" : ep.replaceAll("/+$", "");
  }
}
