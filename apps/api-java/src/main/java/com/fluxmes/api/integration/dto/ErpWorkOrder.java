package com.fluxmes.api.integration.dto;

import java.time.LocalDate;

/**
 * ERP 下发的生产工单。
 *
 * <p>对应 MES 侧 {@code work_order} 表。MES 自建的工单（Phase I-1，{@code source=BACKFILL}）
 * 与 ERP 下发的工单需要可区分，故带 {@code source} 字段。
 *
 * @param orderNo     工单号（ERP 侧主键，用于幂等）
 * @param product     产出品
 * @param planQty     计划数量
 * @param unit        单位
 * @param line        产线（ERP 侧可能为空，由 MES 补齐）
 * @param siteCode    厂区
 * @param recipeCode  配方编号（ERP 可能只给产品，由 MES 解析生效版本）
 * @param planStart   计划开工
 * @param planEnd     计划完工
 * @param source      来源标识：ERP / BACKFILL（MES 自建）
 */
public record ErpWorkOrder(
    String orderNo,
    String product,
    Double planQty,
    String unit,
    String line,
    String siteCode,
    String recipeCode,
    LocalDate planStart,
    LocalDate planEnd,
    String source) {}
