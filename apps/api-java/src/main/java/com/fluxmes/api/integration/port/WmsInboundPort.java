package com.fluxmes.api.integration.port;

import com.fluxmes.api.integration.dto.WmsInboundAck;
import com.fluxmes.api.integration.dto.WmsInboundRequest;

/**
 * 成品入库端口（WMS）。
 *
 * <p>单向为主：MES → WMS 申请入库位，WMS 应答分配库位；MES 把库位回写
 * {@code batch.warehouse_bin}（批次档案 C2 要求的字段之一）。
 *
 * <p>触发时机建议在批次放行成功后（{@code POST /api/quality/release}），
 * 因为未放行的成品不允许入库。
 */
public interface WmsInboundPort {

  default String system() {
    return "WMS";
  }

  /** MOCK 或 WMS。 */
  String dataSource();

  boolean available();

  String detail();

  String lastSuccessAt();

  /** 申请入库，返回 WMS 受理结果（含分配的库位）。 */
  WmsInboundAck requestInbound(WmsInboundRequest request);
}
