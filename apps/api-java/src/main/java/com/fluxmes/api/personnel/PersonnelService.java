package com.fluxmes.api.personnel;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fluxmes.api.common.ApiSupport;
import com.fluxmes.api.common.CurrentUser;
import com.fluxmes.api.common.Roles;
import com.fluxmes.api.entity.AppUser;
import com.fluxmes.api.entity.CapabilityDict;
import com.fluxmes.api.entity.PersonCertificate;
import com.fluxmes.api.mapper.AppUserMapper;
import com.fluxmes.api.mapper.CapabilityDictMapper;
import com.fluxmes.api.mapper.PersonCertificateMapper;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * G2 · 人员资质与健康证服务（食品行业法定门槛）。
 *
 * <p>《食品安全法》要求食品从业人员每年健康检查取证后方可上岗；HACCP 体系另要求
 * CCP 监控、配料称量、成品放行等关键岗位人员具备相应资质并定期复审。
 * 本服务提供：
 * <ul>
 *   <li>证书台账（健康证 / 岗位资质）与到期预警；</li>
 *   <li>关键动作门禁 {@link #requireCapability(String)}：能力项 + 有效健康证双重校验；</li>
 *   <li>过期巡检 {@link #sweepExpired()}：把过期证书的 status 标记为 EXPIRED。</li>
 * </ul>
 *
 * <p><b>兼容策略</b>：只有当某能力项在系统中已有人持有时才启用强制校验（
 * {@code capabilityEnforced}），未纳入资质管理的能力项不阻断业务，避免历史数据
 * 与未配置环境下系统不可用。
 */
@Service
public class PersonnelService {

  private static final Logger log = LoggerFactory.getLogger(PersonnelService.class);

  /** 能力项代码（与 capability_dict 表一致）。 */
  public static final String CAP_WEIGHING = "WEIGHING";
  public static final String CAP_CCP_MONITOR = "CCP_MONITOR";
  public static final String CAP_RELEASE = "RELEASE";
  public static final String CAP_BATCH_REVIEW = "BATCH_REVIEW";
  public static final String CAP_SANITATION = "SANITATION";
  public static final String CAP_LAB_TEST = "LAB_TEST";

  private static final String TYPE_HEALTH = "HEALTH";
  private static final String TYPE_QUALIFICATION = "QUALIFICATION";

  private final PersonCertificateMapper certs;
  private final CapabilityDictMapper capabilityDict;
  private final AppUserMapper users;

  public PersonnelService(PersonCertificateMapper certs, CapabilityDictMapper capabilityDict,
      AppUserMapper users) {
    this.certs = certs;
    this.capabilityDict = capabilityDict;
    this.users = users;
  }

  /* =================== 台账 =================== */

  /** GET /api/personnel/certificates —— 证书台账，可按人与类型过滤。 */
  public List<Map<String, Object>> certificates(String username, String certType) {
    return certs.selectList(Wrappers.<PersonCertificate>lambdaQuery()
            .eq(username != null && !username.isBlank(), PersonCertificate::getUsername, username)
            .eq(certType != null && !certType.isBlank(), PersonCertificate::getCertType, certType)
            .orderByAsc(PersonCertificate::getValidUntil))
        .stream().map(this::view).toList();
  }

  /** POST /api/personnel/certificates —— 登记/发证（值班长及以上）。 */
  public Map<String, Object> create(Map<String, Object> body) {
    if (!CurrentUser.hasRole(Roles.SUPERVISOR)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅值班长及以上可登记人员资质");
    }
    String username = str(body.get("username"));
    String type = str(body.get("certType"));
    String name = str(body.get("certName"));
    if (isBlank(username)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "username 必填");
    if (!List.of(TYPE_HEALTH, TYPE_QUALIFICATION).contains(type)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "certType 仅可为 HEALTH / QUALIFICATION");
    }
    if (isBlank(name)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "certName 必填");
    AppUser u = users.selectOne(Wrappers.<AppUser>lambdaQuery().eq(AppUser::getUsername, username));
    if (u == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在: " + username);

    PersonCertificate c = new PersonCertificate();
    c.setUsername(username);
    c.setDisplayName(u.getDisplayName());
    c.setCertType(type);
    c.setCertName(name);
    c.setCapability(str(body.get("capability")));
    c.setCertNo(str(body.get("certNo")));
    c.setIssuedBy(str(body.get("issuedBy")));
    c.setIssuedAt(parseDate(body.get("issuedAt")));
    c.setValidUntil(parseDate(body.get("validUntil")));
    c.setStatus(valid(c) ? "VALID" : "EXPIRED");
    c.setRemark(str(body.get("remark")));
    c.setCreatedAt(OffsetDateTime.now());
    certs.insert(c);
    log.info("certificate #{} issued to {} ({})", c.getId(), username, name);
    return view(c);
  }

  /** POST /api/personnel/certificates/{id}/revoke —— 吊销证书（值班长及以上）。 */
  public Map<String, Object> revoke(Long id, String reason) {
    if (!CurrentUser.hasRole(Roles.SUPERVISOR)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅值班长及以上可吊销人员资质");
    }
    PersonCertificate c = certs.selectById(id);
    if (c == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "证书不存在: " + id);
    String before = c.getStatus();
    c.setStatus("REVOKED");
    if (reason != null && !reason.isBlank()) {
      c.setRemark((c.getRemark() == null ? "" : c.getRemark() + "；") + "吊销原因：" + reason);
    }
    certs.updateById(c);
    log.warn("certificate #{} revoked by {} (was {})", id, CurrentUser.username(), before);
    return view(c);
  }

  /** GET /api/personnel/capabilities —— 能力项字典。 */
  public List<Map<String, Object>> capabilities() {
    return capabilityDict.selectList(Wrappers.<CapabilityDict>lambdaQuery()
            .orderByAsc(CapabilityDict::getCode))
        .stream().map(d -> ApiSupport.map(
            "code", d.getCode(),
            "name", d.getName(),
            "description", d.getDescription(),
            "requiredRole", d.getRequiredRole(),
            "holders", holdersOf(d.getCode()).size(),
            "enforced", capabilityEnforced(d.getCode()))).toList();
  }

  /** GET /api/personnel/{username} —— 某人资质概览（含能力项与到期情况）。 */
  public Map<String, Object> profile(String username) {
    List<PersonCertificate> own = certs.selectList(
        Wrappers.<PersonCertificate>lambdaQuery().eq(PersonCertificate::getUsername, username));
    List<String> caps = own.stream()
        .filter(c -> TYPE_QUALIFICATION.equals(c.getCertType()))
        .filter(this::valid)
        .map(PersonCertificate::getCapability)
        .filter(c -> c != null && !c.isBlank())
        .distinct().toList();
    PersonCertificate health = own.stream()
        .filter(c -> TYPE_HEALTH.equals(c.getCertType()))
        .findFirst().orElse(null);
    return ApiSupport.map(
        "username", username,
        "healthCert", health == null ? null : view(health),
        "healthValid", health != null && valid(health),
        "capabilities", caps,
        "certificates", own.stream().map(this::view).toList());
  }

  /* =================== 到期预警 =================== */

  /**
   * GET /api/personnel/alerts —— 到期预警：已过期 + 即将到期（默认 30 天）。
   * 食品企业年度体检是高频失分项，逾期即视为不合规人员，门禁会自动拦截。
   */
  public Map<String, Object> alerts(int days) {
    sweepExpired();
    int window = days <= 0 ? 30 : days;
    LocalDate today = LocalDate.now();
    LocalDate until = today.plusDays(window);
    List<Map<String, Object>> expired = new ArrayList<>();
    List<Map<String, Object>> expiring = new ArrayList<>();
    for (PersonCertificate c : certs.selectList(Wrappers.<PersonCertificate>lambdaQuery()
        .isNotNull(PersonCertificate::getValidUntil)
        .orderByAsc(PersonCertificate::getValidUntil))) {
      long left = ChronoUnit.DAYS.between(today, c.getValidUntil());
      Map<String, Object> v = view(c);
      if (left < 0) {
        expired.add(new LinkedHashMap<>(v));
        ((Map<String, Object>) expired.get(expired.size() - 1)).put("overdueDays", Math.abs(left));
      } else if (left <= window) {
        expiring.add(new LinkedHashMap<>(v));
        ((Map<String, Object>) expiring.get(expiring.size() - 1)).put("daysLeft", left);
      }
    }
    return ApiSupport.map(
        "generatedAt", ApiSupport.nowIso(),
        "warnDays", window,
        "expiredCount", expired.size(),
        "expiringCount", expiring.size(),
        "expired", expired,
        "expiring", expiring);
  }

  /** GET /api/personnel/summary —— 资质合规看板。 */
  public Map<String, Object> summary() {
    sweepExpired();
    List<PersonCertificate> all = certs.selectList(null);
    long health = all.stream().filter(c -> TYPE_HEALTH.equals(c.getCertType())).count();
    long qual = all.stream().filter(c -> TYPE_QUALIFICATION.equals(c.getCertType())).count();
    long invalid = all.stream().filter(c -> !valid(c)).count();
    List<Map<String, Object>> byCapability = new ArrayList<>();
    for (CapabilityDict d : capabilityDict.selectList(
        Wrappers.<CapabilityDict>lambdaQuery().orderByAsc(CapabilityDict::getCode))) {
      List<PersonCertificate> holders = holdersOf(d.getCode());
      byCapability.add(ApiSupport.map(
          "code", d.getCode(),
          "name", d.getName(),
          "holders", holders.size(),
          "enforced", !holders.isEmpty()));
    }
    return ApiSupport.map(
        "generatedAt", ApiSupport.nowIso(),
        "totalCertificates", all.size(),
        "healthCertificates", health,
        "qualifications", qual,
        "invalidCertificates", invalid,
        "healthCertEnforced", healthCertEnforced(),
        "capabilities", byCapability);
  }

  /** 启动与查询时执行：把已过期证书的 status 标记为 EXPIRED（便于台账展示）。 */
  public void sweepExpired() {
    try {
      List<PersonCertificate> rows = certs.selectList(Wrappers.<PersonCertificate>lambdaQuery()
          .isNotNull(PersonCertificate::getValidUntil)
          .ne(PersonCertificate::getStatus, "REVOKED"));
      LocalDate today = LocalDate.now();
      int n = 0;
      for (PersonCertificate c : rows) {
        boolean shouldExpire = c.getValidUntil().isBefore(today);
        if (shouldExpire && !"EXPIRED".equals(c.getStatus())) {
          c.setStatus("EXPIRED");
          certs.updateById(c);
          n++;
        } else if (!shouldExpire && "EXPIRED".equals(c.getStatus())) {
          c.setStatus("VALID");
          certs.updateById(c);
        }
      }
      if (n > 0) log.info("swept {} expired certificates", n);
    } catch (Exception e) {
      log.warn("certificate sweep failed: {}", e.toString());
    }
  }

  /* =================== 关键动作门禁 =================== */

  /** 当前用户是否持有该能力项的有效资质（不含健康证）。 */
  public boolean hasCapability(String username, String capability) {
    if (username == null || capability == null) return false;
    return certs.selectList(Wrappers.<PersonCertificate>lambdaQuery()
            .eq(PersonCertificate::getUsername, username)
            .eq(PersonCertificate::getCertType, TYPE_QUALIFICATION)
            .eq(PersonCertificate::getCapability, capability))
        .stream().anyMatch(this::valid);
  }

  /** 当前用户是否持有有效健康证。 */
  public boolean hasValidHealthCert(String username) {
    if (username == null) return false;
    return certs.selectList(Wrappers.<PersonCertificate>lambdaQuery()
            .eq(PersonCertificate::getUsername, username)
            .eq(PersonCertificate::getCertType, TYPE_HEALTH))
        .stream().anyMatch(this::valid);
  }

  /**
   * 关键动作资质门禁：能力项有效资质 + 有效健康证。
   *
   * @param capability 能力项代码，见常量；传 null 只校验健康证
   * @throws ResponseStatusException 403 资质缺失
   */
  public void requireCapability(String capability) {
    String who = CurrentUser.username();
    if ("anonymous".equals(who) || CurrentUser.role() == null) return; // 非 HTTP 上下文（种子/定时任务）
    if (capability != null && capabilityEnforced(capability) && !hasCapability(who, capability)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,
          "当前用户缺少有效的「" + capabilityName(capability) + "」岗位资质，无法执行该操作");
    }
    if (healthCertEnforced() && !hasValidHealthCert(who)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,
          "当前用户无有效健康证（食品从业人员须持年度体检健康证上岗）");
    }
  }

  /** 该能力项是否已纳入强制管控（存在任一持证人即启用）。 */
  public boolean capabilityEnforced(String capability) {
    return !holdersOf(capability).isEmpty();
  }

  /** 健康证是否已纳入强制管控。 */
  public boolean healthCertEnforced() {
    return certs.selectCount(Wrappers.<PersonCertificate>lambdaQuery()
        .eq(PersonCertificate::getCertType, TYPE_HEALTH)) > 0;
  }

  private List<PersonCertificate> holdersOf(String capability) {
    if (capability == null) return List.of();
    return certs.selectList(Wrappers.<PersonCertificate>lambdaQuery()
        .eq(PersonCertificate::getCertType, TYPE_QUALIFICATION)
        .eq(PersonCertificate::getCapability, capability)
        .ne(PersonCertificate::getStatus, "REVOKED"));
  }

  /* =================== helpers =================== */

  private boolean valid(PersonCertificate c) {
    if (c == null) return false;
    if ("REVOKED".equals(c.getStatus()) || "EXPIRED".equals(c.getStatus())) return false;
    return c.getValidUntil() == null || !c.getValidUntil().isBefore(LocalDate.now());
  }

  private String capabilityName(String code) {
    CapabilityDict d = capabilityDict.selectById(code);
    return d == null ? code : d.getName();
  }

  private Map<String, Object> view(PersonCertificate c) {
    long daysLeft = c.getValidUntil() == null ? 9999
        : ChronoUnit.DAYS.between(LocalDate.now(), c.getValidUntil());
    return ApiSupport.map(
        "id", c.getId() == null ? null : String.valueOf(c.getId()),
        "username", c.getUsername(),
        "displayName", c.getDisplayName(),
        "certType", c.getCertType(),
        "certName", c.getCertName(),
        "capability", c.getCapability(),
        "certNo", c.getCertNo(),
        "issuedBy", c.getIssuedBy(),
        "issuedAt", c.getIssuedAt() == null ? null : c.getIssuedAt().toString(),
        "validUntil", c.getValidUntil() == null ? null : c.getValidUntil().toString(),
        "daysLeft", daysLeft,
        "status", c.getStatus(),
        "valid", valid(c),
        "remark", c.getRemark());
  }

  private static LocalDate parseDate(Object v) {
    if (v == null || String.valueOf(v).isBlank()) return null;
    try {
      return LocalDate.parse(String.valueOf(v));
    } catch (Exception e) {
      return null;
    }
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }

  private static String str(Object v) {
    return v == null ? null : String.valueOf(v);
  }
}
