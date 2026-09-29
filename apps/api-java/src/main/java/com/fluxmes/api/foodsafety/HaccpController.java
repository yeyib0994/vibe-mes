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

/** F1 · HACCP 关键控制点端点：CCP 点维护（值班长+）、监控记录上报（工艺员+）、QA 复核（质检员+）。 */
@RestController
@RequestMapping("/api/haccp")
public class HaccpController {

  private final FoodSafetyService service;

  public HaccpController(FoodSafetyService service) {
    this.service = service;
  }

  @GetMapping("/points")
  public Map<String, Object> points(@RequestParam(required = false) String line) {
    return Map.of("generatedAt", java.time.Instant.now().toString(),
        "points", service.ccpPoints(line));
  }

  @PostMapping("/points")
  public Map<String, Object> createPoint(@RequestBody Map<String, Object> body) {
    require(Roles.SUPERVISOR, "维护 CCP 点需要值班长及以上角色");
    return service.createCcpPoint(body);
  }

  @GetMapping("/records")
  public Map<String, Object> records(@RequestParam(required = false) String batchId,
      @RequestParam(required = false) String ccpCode,
      @RequestParam(required = false) Boolean onlyDeviation,
      @RequestParam(required = false, defaultValue = "100") int limit) {
    return Map.of("generatedAt", java.time.Instant.now().toString(),
        "records", service.ccpRecords(batchId, ccpCode, onlyDeviation, limit));
  }

  /** 上报一条 CCP 监控记录；越出关键限值自动建偏差单 + 报警并阻断批次。 */
  @PostMapping("/records")
  public Map<String, Object> record(@RequestBody Map<String, Object> body) {
    require(Roles.OPERATOR, "上报 CCP 监控记录需要工艺员及以上角色");
    return service.recordCcp(body);
  }

  @PostMapping("/records/{id}/verify")
  public Map<String, Object> verify(@PathVariable Long id) {
    return service.verifyCcp(id);
  }

  @GetMapping("/summary")
  public Map<String, Object> summary() {
    return service.ccpSummary();
  }

  private static void require(String role, String message) {
    if (!CurrentUser.hasRole(role)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, message);
    }
  }
}
