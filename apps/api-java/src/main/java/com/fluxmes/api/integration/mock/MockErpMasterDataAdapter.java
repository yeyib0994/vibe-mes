package com.fluxmes.api.integration.mock;

import com.fluxmes.api.core.FixtureStore;
import com.fluxmes.api.integration.DataSourceTag;
import com.fluxmes.api.integration.dto.ErpWorkOrder;
import com.fluxmes.api.integration.port.ErpMasterDataPort;
import com.fluxmes.api.integration.support.AdapterStatus;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * L1 · 进程内 Fake 的 ERP 主数据源（{@code fluxmes.integration.erp.mode=mock}）。
 *
 * <p>从 fixture 批次派生「ERP 下发但 MES 尚未建单」的工单。关键是
 * {@code source=ERP} 与 MES 自建工单（Phase I-1, {@code source=BACKFILL}）可区分——
 * 否则对账时无法判断工单归属。
 */
@Component
@ConditionalOnProperty(name = "fluxmes.integration.erp.mode", havingValue = "mock",
    matchIfMissing = true)
public class MockErpMasterDataAdapter implements ErpMasterDataPort {

  /** 派生工单条数上限：只取前 N 个在制批次，避免 mock 数据淹没真实工单列表。 */
  private static final int MAX_ORDERS = 3;

  private final FixtureStore fixtures;
  private final AdapterStatus status = new AdapterStatus();

  public MockErpMasterDataAdapter(FixtureStore fixtures) {
    this.fixtures = fixtures;
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
    try {
      List<ErpWorkOrder> out = new ArrayList<>();
      LocalDate today = LocalDate.now();
      for (Map<String, Object> b : fixtures.runningBatches()) {
        if (out.size() >= MAX_ORDERS) break;
        String batchId = str(b.get("id"));
        if (batchId.isBlank()) continue;
        out.add(new ErpWorkOrder(
            "ERP-WO-" + batchId.replace("B-", ""),
            str(b.get("product")),
            null,
            "t",
            null,
            "SITE-01",
            str(b.get("recipe")),
            today,
            today.plusDays(3),
            "ERP"));
      }
      status.success("mock ERP：下发 " + out.size() + " 张工单（待建单，source=ERP）");
      return out;
    } catch (Exception e) {
      status.failure("mock ERP 取数失败：" + e);
      return List.of();
    }
  }

  @Override
  public void reportCompletion(String orderNo, Double actualQty, String unit) {
    status.success("mock ERP：已接收完工回报 " + orderNo + " / "
        + (actualQty == null ? "-" : actualQty) + (unit == null ? "" : unit));
  }

  private static String str(Object v) {
    return v == null ? "" : String.valueOf(v);
  }
}
