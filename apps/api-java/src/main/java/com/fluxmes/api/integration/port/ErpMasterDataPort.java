package com.fluxmes.api.integration.port;

import com.fluxmes.api.integration.dto.ErpWorkOrder;
import java.util.List;

/**
 * ERP 主数据端口（工单 / 物料）。
 *
 * <p>双向：
 * <ul>
 *   <li>拉取——ERP 下发的生产工单与物料主数据，落 {@code work_order} / {@code material}；</li>
 *   <li>回报——MES 完工后把实际产出回传 ERP。</li>
 * </ul>
 *
 * <p>注意 MES 自建工单（Phase I-1, {@code source=BACKFILL}）与 ERP 下发工单
 * （{@code source=ERP}）必须可区分，否则对账时分不清是谁的工单。
 */
public interface ErpMasterDataPort {

  default String system() {
    return "ERP";
  }

  /** MOCK 或 ERP。 */
  String dataSource();

  boolean available();

  String detail();

  String lastSuccessAt();

  /** 拉取待开工的生产工单。 */
  List<ErpWorkOrder> pullWorkOrders();

  /** 完工回报（MES → ERP）。 */
  void reportCompletion(String orderNo, Double actualQty, String unit);
}
