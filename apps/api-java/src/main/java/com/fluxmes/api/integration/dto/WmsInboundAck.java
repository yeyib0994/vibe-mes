package com.fluxmes.api.integration.dto;

import java.time.OffsetDateTime;

/**
 * WMS 对入库请求的应答。
 *
 * @param accepted  是否受理
 * @param ackNo     WMS 侧受理单号（幂等依据）
 * @param bin       分配的库位（受理时才有值），回写 MES 的 {@code batch.warehouse_bin}
 * @param message   拒绝原因或备注
 * @param ackedAt   WMS 应答时间
 * @param dataSource {@link com.fluxmes.api.integration.DataSourceTag} 之一
 */
public record WmsInboundAck(
    boolean accepted,
    String ackNo,
    String bin,
    String message,
    OffsetDateTime ackedAt,
    String dataSource) {}
