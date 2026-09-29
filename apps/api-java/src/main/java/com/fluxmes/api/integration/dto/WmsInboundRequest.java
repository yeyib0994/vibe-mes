package com.fluxmes.api.integration.dto;

/**
 * MES → WMS 的成品入库请求。
 *
 * <p>对应 MES 侧 {@code batch.warehouse_bin} 与成品数量。触发时机：批次放行后
 * （{@code POST /api/quality/release} 成功）向 WMS 申请入库位。
 */
public record WmsInboundRequest(
    String batchId,
    String product,
    Double qty,
    String unit,
    String siteCode,
    String requestedBy) {}
