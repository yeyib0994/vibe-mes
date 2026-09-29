package com.fluxmes.api.integration.port;

import com.fluxmes.api.integration.dto.MetricSample;
import java.util.List;

/**
 * 设备参数采集端口（SCADA / OPC-UA）。
 *
 * <p>业务层（{@code EquipmentService} / {@code EquipmentMetricCollector}）只依赖本接口，
 * 不认识 OPC-UA、也不认识 mock。实现见 {@code integration.opcua} 与 {@code integration.mock}。
 *
 * <p>对应章程 §3 非目标 1：MES **不做**控制逻辑本身，只采集、存储与展示。
 * 因此本端口是**只读**的——下发指令是另一个端口的事，不在本期范围。
 */
public interface EquipmentMetricPort {

  /** 系统标识：SCADA。 */
  default String system() {
    return "SCADA";
  }

  /** 数据来源标识（{@code DataSourceTag}）：MOCK 或 OPCUA。 */
  String dataSource();

  /** 最近一次探测的连通状态。 */
  boolean available();

  /** 连通性详情（失败原因 / 采集统计），供健康端点展示。 */
  String detail();

  /** 最近一次成功采集时间（ISO），从未成功返回 null。 */
  String lastSuccessAt();

  /**
   * 读取全部设备当前参数快照。
   *
   * <p>语义是「当前值」而非「历史序列」——历史由调用方落 {@code equipment_metric}，
   * 这样端口不必关心存储，存储也不必关心协议。
   */
  List<MetricSample> readAll();

  /** 读取单台设备的当前参数快照。 */
  default List<MetricSample> read(String equipmentCode) {
    if (equipmentCode == null) return List.of();
    return readAll().stream()
        .filter(s -> equipmentCode.equals(s.equipmentCode()))
        .toList();
  }

  /**
   * 冷启动回填：为空表构造一段历史序列，使首次上线时趋势图不是空白的。
   *
   * <p>真实采集源**没有历史可回填**（设备昨天没连上 MES，那段数据就是不存在），
   * 故默认返回空；只有 mock 实现才构造历史，且构造出的采样带 {@code dataSource=MOCK}
   * 标识、可被识别与清理。
   *
   * @param points 点数
   * @param stepMs 相邻点间隔（毫秒）
   */
  default List<MetricSample> backfill(int points, long stepMs) {
    return List.of();
  }
}
