package com.fluxmes.api.integration;

import static com.fluxmes.api.common.ApiSupport.map;

import com.fluxmes.api.common.CurrentUser;
import com.fluxmes.api.common.Roles;
import com.fluxmes.api.integration.dto.ErpWorkOrder;
import com.fluxmes.api.integration.dto.IntegrationHealth;
import com.fluxmes.api.integration.dto.LabResult;
import com.fluxmes.api.integration.dto.WmsInboundAck;
import com.fluxmes.api.integration.dto.WmsInboundRequest;
import com.fluxmes.api.integration.port.EquipmentMetricPort;
import com.fluxmes.api.integration.port.ErpMasterDataPort;
import com.fluxmes.api.integration.port.LabResultPort;
import com.fluxmes.api.integration.port.WmsInboundPort;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Phase J · 外部系统集成服务（健康看板 + 手工触发）。
 *
 * <p>设计取舍：{@link #health()} 只读**最近一次已知状态**，不做主动探测——
 * 健康检查在页面每 30s 轮询，若每次都真去连 OPC-UA、真去拉 LIMS，
 * 既浪费资源又会把「读健康」变成有副作用的操作（LIMS 的 poll 是**消费式**的，
 * 一次健康检查会把待回流的报告吃掉）。主动探测单独放在 {@link #probe(String)}。
 */
@Service
public class IntegrationService {

  private static final Logger log = LoggerFactory.getLogger(IntegrationService.class);

  private final EquipmentMetricPort scada;
  private final LabResultPort lims;
  private final ErpMasterDataPort erp;
  private final WmsInboundPort wms;
  private final EquipmentMetricCollector collector;
  private final IntegrationProperties props;

  public IntegrationService(EquipmentMetricPort scada, LabResultPort lims, ErpMasterDataPort erp,
      WmsInboundPort wms, EquipmentMetricCollector collector, IntegrationProperties props) {
    this.scada = scada;
    this.lims = lims;
    this.erp = erp;
    this.wms = wms;
    this.collector = collector;
    this.props = props;
  }

  /* ---------------- 健康看板 ---------------- */

  public List<IntegrationHealth> health() {
    List<IntegrationHealth> out = new ArrayList<>(4);
    out.add(scadaHealth());
    out.add(restHealth("LIMS", props.getLims(), lims.dataSource(), lims.available(),
        lims.detail(), lims.lastSuccessAt()));
    out.add(restHealth("ERP", props.getErp(), erp.dataSource(), erp.available(),
        erp.detail(), erp.lastSuccessAt()));
    out.add(restHealth("WMS", props.getWms(), wms.dataSource(), wms.available(),
        wms.detail(), wms.lastSuccessAt()));
    return out;
  }

  private IntegrationHealth scadaHealth() {
    IntegrationProperties.SystemConfig cfg = props.getScada();
    boolean mock = cfg.isMock();
    return new IntegrationHealth(
        "SCADA",
        String.valueOf(cfg.getMode()),
        cfg.getEndpoint(),
        mock || scada.available(),
        mock ? "mock 模式：进程内 Fake，非真实采集" : scada.detail(),
        mock ? collector.lastRunAt() : scada.lastSuccessAt(),
        scada.dataSource());
  }

  private IntegrationHealth restHealth(String system, IntegrationProperties.SystemConfig cfg,
      String dataSource, boolean available, String detail, String lastSuccessAt) {
    boolean mock = cfg.isMock();
    return new IntegrationHealth(
        system,
        String.valueOf(cfg.getMode()),
        cfg.getEndpoint(),
        mock || available,
        mock ? "mock 模式：独立 Mock 服务（tools/ext-simulator）" : detail,
        lastSuccessAt,
        dataSource);
  }

  /** 集成总览：健康看板 + 采集器统计 + 数据源分布。 */
  public Map<String, Object> summary() {
    return map(
        "systems", health(),
        "collector", map(
            "lastRunAt", collector.lastRunAt(),
            "lastRunStatus", collector.lastRunStatus(),
            "lastBatchSize", collector.lastBatchSize(),
            "totalCollected", collector.totalCollected(),
            "storedCount", collector.storedCount(),
            "intervalSeconds", props.getCollectIntervalSeconds(),
            "retentionDays", props.getMetricRetentionDays()),
        "alarmSimulatorEnabled", props.isAlarmSimulatorEnabled(),
        "anyMock", health().stream().anyMatch(h -> "MOCK".equals(h.dataSource())));
  }

  /* ---------------- 主动探测 ---------------- */

  /** 主动探测某个系统是否真的可达（有副作用：SCADA 会真读一次，LIMS 会真拉一次）。 */
  public Map<String, Object> probe(String system) {
    requireSupervisor();
    String s = system == null ? "" : system.trim().toUpperCase();
    return switch (s) {
      case "SCADA" -> map("system", "SCADA", "reachable", true,
          "detail", "手动采集完成，入库 " + collector.collectNow() + " 条",
          "dataSource", scada.dataSource());
      case "LIMS" -> map("system", "LIMS", "reachable", lims.available(),
          "detail", "拉取到 " + lims.poll().size() + " 条检验结果（消费式，已从待回流队列移除）",
          "dataSource", lims.dataSource());
      case "ERP" -> map("system", "ERP", "reachable", erp.available(),
          "detail", "拉取到 " + erp.pullWorkOrders().size() + " 张工单",
          "dataSource", erp.dataSource());
      case "WMS" -> map("system", "WMS", "reachable", wms.available(),
          "detail", "WMS 无只读探测接口；请用入库申请端点验证", "dataSource", wms.dataSource());
      default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "未知系统：" + system + "（可选 SCADA / LIMS / ERP / WMS）");
    };
  }

  /* ---------------- 手工触发（演示与联调） ---------------- */

  /** 立即执行一次设备参数采集。 */
  public Map<String, Object> collectNow() {
    requireSupervisor();
    int n = collector.collectNow();
    return map("collected", n, "dataSource", scada.dataSource(),
        "detail", collector.lastRunStatus(), "at", collector.lastRunAt());
  }

  /** 拉取 LIMS 待回流检验结果（消费式）。 */
  public Map<String, Object> pollLims() {
    requireAdmin();
    List<LabResult> results = lims.poll();
    log.info("manual LIMS poll by {} → {} results", CurrentUser.username(), results.size());
    return map("count", results.size(), "results", results, "dataSource", lims.dataSource(),
        "detail", lims.detail());
  }

  /** 重置 mock LIMS 存量，便于反复演示回流。 */
  public Map<String, Object> resetLims() {
    requireAdmin();
    if (lims instanceof com.fluxmes.api.integration.mock.MockLabResultAdapter mockLims) {
      mockLims.reset();
      return map("reset", true, "detail", mockLims.detail());
    }
    throw new ResponseStatusException(HttpStatus.CONFLICT,
        "当前 LIMS 为 REAL 模式，mock 重置不适用");
  }

  /** 拉取 ERP 待开工工单。 */
  public Map<String, Object> pullErp() {
    requireAdmin();
    List<ErpWorkOrder> orders = erp.pullWorkOrders();
    log.info("manual ERP pull by {} → {} orders", CurrentUser.username(), orders.size());
    return map("count", orders.size(), "orders", orders, "dataSource", erp.dataSource(),
        "detail", erp.detail());
  }

  /** 试一次 WMS 入库申请（不落库，仅验证链路与应答）。 */
  public Map<String, Object> testWms(WmsInboundRequest request) {
    requireAdmin();
    WmsInboundAck ack = wms.requestInbound(request);
    return map("ack", ack, "dataSource", wms.dataSource(), "detail", wms.detail());
  }

  private static void requireSupervisor() {
    if (!CurrentUser.hasRole(Roles.SUPERVISOR)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要值班长及以上权限");
    }
  }

  private static void requireAdmin() {
    if (!CurrentUser.hasRole(Roles.ADMIN)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要管理员权限");
    }
  }
}
