package com.fluxmes.api.personnel;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * G2 · 人员资质与健康证端点（食品行业法定：持证上岗 + 年度健康检查）。
 * 台账与预警对所有登录用户开放；发证/吊销需值班长及以上。
 */
@RestController
@RequestMapping("/api/personnel")
public class PersonnelController {

  private final PersonnelService service;

  public PersonnelController(PersonnelService service) {
    this.service = service;
  }

  /** GET /api/personnel/certificates —— 证书台账（?username=&certType=HEALTH|QUALIFICATION）。 */
  @GetMapping("/certificates")
  public Object certificates(@RequestParam(required = false) String username,
      @RequestParam(required = false) String certType) {
    return service.certificates(username, certType);
  }

  /** POST /api/personnel/certificates —— 登记发证（值班长及以上）。 */
  @PostMapping("/certificates")
  public Map<String, Object> create(@RequestBody(required = false) Map<String, Object> body) {
    return service.create(body == null ? Map.of() : body);
  }

  /** POST /api/personnel/certificates/{id}/revoke —— 吊销证书（值班长及以上）。 */
  @PostMapping("/certificates/{id}/revoke")
  public Map<String, Object> revoke(@PathVariable Long id,
      @RequestBody(required = false) Map<String, Object> body) {
    String reason = body == null ? null : String.valueOf(body.getOrDefault("reason", ""));
    return service.revoke(id, reason);
  }

  /** GET /api/personnel/capabilities —— 能力项字典与持证人数。 */
  @GetMapping("/capabilities")
  public Object capabilities() {
    return service.capabilities();
  }

  /** GET /api/personnel/alerts —— 已过期 / 即将到期（?days=30）预警。 */
  @GetMapping("/alerts")
  public Map<String, Object> alerts(@RequestParam(defaultValue = "30") int days) {
    return service.alerts(days);
  }

  /** GET /api/personnel/summary —— 资质合规看板。 */
  @GetMapping("/summary")
  public Map<String, Object> summary() {
    return service.summary();
  }

  /** GET /api/personnel/{username} —— 某人资质概览。 */
  @GetMapping("/{username}")
  public Map<String, Object> profile(@PathVariable String username) {
    return service.profile(username);
  }
}
