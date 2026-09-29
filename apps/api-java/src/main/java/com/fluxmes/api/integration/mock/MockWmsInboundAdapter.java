package com.fluxmes.api.integration.mock;

import com.fluxmes.api.integration.DataSourceTag;
import com.fluxmes.api.integration.dto.WmsInboundAck;
import com.fluxmes.api.integration.dto.WmsInboundRequest;
import com.fluxmes.api.integration.port.WmsInboundPort;
import com.fluxmes.api.integration.support.AdapterStatus;
import java.time.OffsetDateTime;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * L1 · 进程内 Fake 的 WMS 入库受理源（{@code fluxmes.integration.wms.mode=mock}）。
 *
 * <p>受理规则（模拟真实 WMS 的拒绝场景，让降级分支能被测到）：
 * <ul>
 *   <li>数量缺失或 ≤0 → 拒绝（message 说明原因）；</li>
 *   <li>其余 → 受理，按批次号哈希**确定性**分配库位（同一批次每次得到同一库位，便于复现）。</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "fluxmes.integration.wms.mode", havingValue = "mock",
    matchIfMissing = true)
public class MockWmsInboundAdapter implements WmsInboundPort {

  private final AdapterStatus status = new AdapterStatus();

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
    try {
      if (request == null || request.qty() == null || request.qty() <= 0) {
        status.failure("mock WMS：拒绝入库请求（数量缺失或非正）");
        return new WmsInboundAck(false, null, null, "数量缺失或非正，拒绝入库",
            OffsetDateTime.now(), DataSourceTag.MOCK);
      }
      String bin = allocateBin(request.batchId());
      status.success("mock WMS：受理批次 " + request.batchId() + "，分配库位 " + bin);
      return new WmsInboundAck(true, "WMS-ACK-" + request.batchId(), bin,
          "库位已分配（mock）", OffsetDateTime.now(), DataSourceTag.MOCK);
    } catch (Exception e) {
      status.failure("mock WMS 受理失败：" + e);
      return new WmsInboundAck(false, null, null, "mock WMS 异常：" + e,
          OffsetDateTime.now(), DataSourceTag.MOCK);
    }
  }

  /** 成品库 A 区，18 个库位循环：CP-A-01 .. CP-A-18。 */
  private static String allocateBin(String batchId) {
    int h = Math.abs(String.valueOf(batchId).hashCode());
    return String.format("CP-A-%02d", (h % 18) + 1);
  }
}
