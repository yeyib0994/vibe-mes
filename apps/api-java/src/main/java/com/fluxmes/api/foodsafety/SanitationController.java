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

/** F1 · 清场卫生与环境监测端点：清场记录、QA 确认、开工前置校验、环境指标上报与汇总。 */
@RestController
@RequestMapping("/api/sanitation")
public class SanitationController {

  private final FoodSafetyService service;

  public SanitationController(FoodSafetyService service) {
    this.service = service;
  }

  @GetMapping("/records")
  public Map<String, Object> records(@RequestParam(required = false) String line,
      @RequestParam(required = false) String type,
      @RequestParam(required = false, defaultValue = "50") int limit) {
    return Map.of("generatedAt", java.time.Instant.now().toString(),
        "records", service.cleaningRecords(line, type, limit));
  }

  @PostMapping("/records")
  public Map<String, Object> create(@RequestBody Map<String, Object> body) {
    require(Roles.OPERATOR, "登记清场需要工艺员及以上角色");
    return service.createCleaning(body);
  }

  @PostMapping("/records/{id}/verify")
  public Map<String, Object> verify(@PathVariable String id,
      @RequestBody(required = false) Map<String, String> body) {
    String result = body == null ? "PASS" : body.get("result");
    String swab = body == null ? null : body.get("swabResult");
    return service.verifyCleaning(id, result, swab);
  }

  /** 开工前置校验：产线是否具备有效清场记录。 */
  @GetMapping("/status")
  public Map<String, Object> status(@RequestParam String line) {
    return service.sanitationStatus(line);
  }

  @GetMapping("/environment")
  public Map<String, Object> env(@RequestParam(required = false) String area,
      @RequestParam(required = false) String metric,
      @RequestParam(required = false, defaultValue = "100") int limit) {
    return Map.of("generatedAt", java.time.Instant.now().toString(),
        "records", service.envRecords(area, metric, limit));
  }

  /** 环境指标上报：超标自动建偏差单 + 报警。 */
  @PostMapping("/environment")
  public Map<String, Object> recordEnv(@RequestBody Map<String, Object> body) {
    require(Roles.OPERATOR, "上报环境监测数据需要工艺员及以上角色");
    return service.recordEnv(body);
  }

  @GetMapping("/environment/summary")
  public Map<String, Object> envSummary() {
    return service.envSummary();
  }

  private static void require(String role, String message) {
    if (!CurrentUser.hasRole(role)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, message);
    }
  }
}
