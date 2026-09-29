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

/** F3 · 批记录与效期端点：电子批记录 eBR、留样管理、近效期预警。 */
@RestController
@RequestMapping("/api/ebr")
public class BatchRecordController {

  private final BatchRecordService service;

  public BatchRecordController(BatchRecordService service) {
    this.service = service;
  }

  @GetMapping("/{batchId}")
  public Map<String, Object> ebr(@PathVariable String batchId) {
    return service.ebr(batchId);
  }

  @PostMapping("/{batchId}/steps")
  public Map<String, Object> recordStep(@PathVariable String batchId,
      @RequestBody Map<String, Object> body) {
    require(Roles.OPERATOR, "记录工序需要工艺员及以上角色");
    return service.recordStep(batchId, body);
  }

  @PostMapping("/steps/{id}/review")
  public Map<String, Object> reviewStep(@PathVariable Long id) {
    return service.reviewStep(id);
  }

  @GetMapping("/retention")
  public Map<String, Object> retention(@RequestParam(required = false) String status,
      @RequestParam(required = false) Boolean expiringSoon) {
    return Map.of("generatedAt", java.time.Instant.now().toString(),
        "samples", service.samples(status, expiringSoon));
  }

  @PostMapping("/retention")
  public Map<String, Object> retain(@RequestBody Map<String, Object> body) {
    return service.retainSample(body);
  }

  @PostMapping("/retention/{id}/dispose")
  public Map<String, Object> dispose(@PathVariable String id,
      @RequestBody(required = false) Map<String, String> body) {
    return service.disposeSample(id, body == null ? null : body.get("remark"));
  }

  @GetMapping("/expiry-alerts")
  public Map<String, Object> expiryAlerts(
      @RequestParam(required = false, defaultValue = "30") int warnDays) {
    return service.expiryAlerts(warnDays);
  }

  private static void require(String role, String message) {
    if (!CurrentUser.hasRole(role)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, message);
    }
  }
}
