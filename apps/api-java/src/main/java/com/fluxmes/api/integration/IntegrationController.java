package com.fluxmes.api.integration;

import com.fluxmes.api.integration.dto.IntegrationHealth;
import com.fluxmes.api.integration.dto.WmsInboundRequest;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase J · 外部系统集成端点。
 *
 * <p>只读端点对所有登录用户开放；手工触发类端点限管理员/值班长——
 * 它们会真的去调外部系统，不是查询。
 */
@RestController
@RequestMapping("/api/integration")
public class IntegrationController {

  private final IntegrationService service;

  public IntegrationController(IntegrationService service) {
    this.service = service;
  }

  /** GET /api/integration/health —— 四个外部系统的模式与连通状态（只读最近已知状态）。 */
  @GetMapping("/health")
  public List<IntegrationHealth> health() {
    return service.health();
  }

  /** GET /api/integration/summary —— 集成总览（健康 + 采集器统计 + 是否存在 mock）。 */
  @GetMapping("/summary")
  public Map<String, Object> summary() {
    return service.summary();
  }

  /** POST /api/integration/{system}/probe —— 主动探测（值班长+，有副作用）。 */
  @PostMapping("/{system}/probe")
  public Map<String, Object> probe(@PathVariable String system) {
    return service.probe(system);
  }

  /** POST /api/integration/scada/collect —— 立即采集一次设备参数（值班长+）。 */
  @PostMapping("/scada/collect")
  public Map<String, Object> collect() {
    return service.collectNow();
  }

  /** POST /api/integration/lims/results —— 拉取待回流检验结果（管理员，消费式）。 */
  @PostMapping("/lims/results")
  public Map<String, Object> pollLims() {
    return service.pollLims();
  }

  /** POST /api/integration/lims/reset —— 重置 mock LIMS 存量（管理员，仅 mock 模式）。 */
  @PostMapping("/lims/reset")
  public Map<String, Object> resetLims() {
    return service.resetLims();
  }

  /** POST /api/integration/erp/work-orders —— 拉取 ERP 待开工工单（管理员）。 */
  @PostMapping("/erp/work-orders")
  public Map<String, Object> pullErp() {
    return service.pullErp();
  }

  /** POST /api/integration/wms/test —— 试一次入库申请（管理员，不落库）。 */
  @PostMapping("/wms/test")
  public Map<String, Object> testWms(@RequestBody(required = false) WmsInboundRequest request) {
    return service.testWms(request);
  }
}
