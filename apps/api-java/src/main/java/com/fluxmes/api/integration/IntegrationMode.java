package com.fluxmes.api.integration;

/**
 * 外部系统接入模式。
 *
 * <p>与 {@code specs/constitution.md:63}「集成边界以『接口契约 + 适配器』隔离」对应：
 * 业务层只依赖 {@code integration.port} 下的接口，本枚举决定装配哪个实现。
 *
 * <ul>
 *   <li>{@link #MOCK} —— 本地无设备/无外部系统时使用，产出演示数据（L1 进程内 Fake）；</li>
 *   <li>{@link #REAL} —— 连真实系统或协议模拟器（SCADA 走 OPC-UA，LIMS/ERP/WMS 走 REST）。</li>
 * </ul>
 *
 * <p>注意：mock 与 real 的业务代码是同一条路径，切换只影响装配，不改业务。
 */
public enum IntegrationMode {
  MOCK,
  REAL
}
