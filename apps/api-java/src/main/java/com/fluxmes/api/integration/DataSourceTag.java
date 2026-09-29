package com.fluxmes.api.integration;

/**
 * 数据来源标识。
 *
 * <p>外部系统数据必须可辨识——演示数据与真实数据混在同一张表、同一个接口里是最容易
 * 出事的地方（本项目演示账号与生产账号共用一套种子密码）。所有来自外部系统的数据在
 * API 响应里带 {@code dataSource} 字段，前端据此展示来源标签。
 *
 * <p>同时满足 {@code specs/constitution.md} 的 P4（实时数据须标注时效与来源）。
 */
public final class DataSourceTag {

  /** 进程内 Fake / HTTP Stub 产出的演示数据，非真实采集。 */
  public static final String MOCK = "MOCK";

  /** 经 OPC-UA 客户端从 SCADA / PLC 侧采集。 */
  public static final String OPCUA = "OPCUA";

  /** 实验室信息系统回流。 */
  public static final String LIMS = "LIMS";

  /** 企业资源计划系统（工单 / 物料主数据）。 */
  public static final String ERP = "ERP";

  /** 仓储管理系统（成品入库）。 */
  public static final String WMS = "WMS";

  /** 人在 MES 界面手工录入。 */
  public static final String MANUAL = "MANUAL";

  private DataSourceTag() {}
}
