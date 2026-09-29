package com.fluxmes.api.foodsafety;

import com.fluxmes.api.common.CurrentUser;
import com.fluxmes.api.common.Roles;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** F2 · 物料谱系端点：物料主数据、原料批到货与检验放行、投料登记、真实谱系与召回影响分析。 */
@RestController
@RequestMapping("/api/materials")
public class MaterialController {

  private final MaterialService service;

  public MaterialController(MaterialService service) {
    this.service = service;
  }

  @GetMapping
  public Map<String, Object> materials() {
    return Map.of("generatedAt", java.time.Instant.now().toString(),
        "materials", service.materials());
  }

  @GetMapping("/lots")
  public Map<String, Object> lots(@RequestParam(required = false) String materialCode,
      @RequestParam(required = false) String qcStatus,
      @RequestParam(required = false) Boolean expiringSoon) {
    return Map.of("generatedAt", java.time.Instant.now().toString(),
        "lots", service.lots(materialCode, qcStatus, expiringSoon));
  }

  @PostMapping("/lots")
  public Map<String, Object> createLot(@RequestBody Map<String, Object> body) {
    require(Roles.OPERATOR, "登记原料到货需要工艺员及以上角色");
    return service.createLot(body);
  }

  @PostMapping("/lots/{id}/inspect")
  public Map<String, Object> inspect(@PathVariable String id,
      @RequestBody(required = false) Map<String, String> body) {
    String result = body == null ? "PASS" : body.get("result");
    String coa = body == null ? null : body.get("coaNo");
    return service.inspectLot(id, result, coa);
  }

  @GetMapping("/{batchId}/inputs")
  public Map<String, Object> inputs(@PathVariable String batchId) {
    return Map.of("generatedAt", java.time.Instant.now().toString(),
        "batchId", batchId, "inputs", service.inputsOf(batchId));
  }

  /** 投料登记：原料批须已检验放行且未过期。 */
  @PostMapping("/{batchId}/inputs")
  public Map<String, Object> feed(@PathVariable String batchId,
      @RequestBody Map<String, Object> body) {
    require(Roles.OPERATOR, "登记投料需要工艺员及以上角色");
    return service.feed(batchId, body);
  }

  /** 真实谱系：成品批次的上游原料批与下游用途。 */
  @GetMapping("/genealogy/{batchId}")
  public Map<String, Object> genealogy(@PathVariable String batchId) {
    return service.genealogy(batchId);
  }

  /** 召回影响分析：以问题原料批反查受影响成品批次。 */
  @GetMapping("/recall/{materialLotId}")
  public Map<String, Object> recall(@PathVariable String materialLotId) {
    return service.recall(materialLotId);
  }

  private static void require(String role, String message) {
    if (!CurrentUser.hasRole(role)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, message);
    }
  }
}
