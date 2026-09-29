package com.fluxmes.api.integration.dto;

/**
 * 单个外部系统的连通状态（供 {@code GET /api/integration/health} 与前端展示）。
 *
 * @param system      系统标识：SCADA / LIMS / ERP / WMS
 * @param mode        MOCK / REAL
 * @param endpoint    端点（mock 模式下仍回显配置值，便于对照）
 * @param available   最近一次探测是否连通
 * @param detail      状态说明（失败原因 / 最近一次采集统计）
 * @param lastSuccessAt 最近一次成功时间（ISO），从未成功则为 null
 * @param dataSource  该系统的数据来源标识，mock 模式下为 MOCK
 */
public record IntegrationHealth(
    String system,
    String mode,
    String endpoint,
    boolean available,
    String detail,
    String lastSuccessAt,
    String dataSource) {}
