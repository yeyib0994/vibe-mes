package com.fluxmes.api.alarm;

import com.fluxmes.api.common.ApiSupport;
import com.fluxmes.api.common.CurrentUser;
import com.fluxmes.api.common.Roles;
import com.fluxmes.api.entity.AlarmSlaPolicy;
import com.fluxmes.api.mapper.AlarmSlaPolicyMapper;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * G1 · 报警响应 SLA 政策服务：把原先写死在 {@link AlarmService} 里的响应时限
 * （critical 5 / major 15 / minor 30 分钟）搬到配置表，由值班长在运行时调整。
 *
 * <p>约定：
 * <ul>
 *   <li>{@code enabled=false} → 该级别豁免 SLA 考核，生效时限记 0（永不逾期、不升级）；</li>
 *   <li>政策行缺失或非法 → 回落 {@link AlarmService#defaultSla(String)} 默认常量；</li>
 *   <li>已在途报警保留创建时写入的时限，政策变更只影响此后新建的报警（符合审计要求）。</li>
 * </ul>
 */
@Service
public class AlarmSlaPolicyService {

  public static final List<String> LEVELS = List.of("critical", "major", "minor");

  private final AlarmSlaPolicyMapper policies;

  public AlarmSlaPolicyService(AlarmSlaPolicyMapper policies) {
    this.policies = policies;
  }

  /** 生效时限（分钟）；0 表示豁免考核。 */
  public int effectiveMinutes(String level) {
    String lv = level == null ? "minor" : level;
    AlarmSlaPolicy p = policies.selectById(lv);
    if (p == null || p.getMinutes() == null) return AlarmService.defaultSla(lv);
    if (!Boolean.TRUE.equals(p.getEnabled())) return 0;
    return Math.max(1, p.getMinutes());
  }

  /** { critical: 5, major: 15, minor: 30 } —— 供报警统计卡片与前端展示。 */
  public Map<String, Object> policyMap() {
    Map<String, Object> m = new LinkedHashMap<>();
    for (String lv : LEVELS) m.put(lv, effectiveMinutes(lv));
    return m;
  }

  /** 政策列表（缺失的级别用默认常量补齐视图，不落库）。 */
  public List<Map<String, Object>> list() {
    ensureDefaults();
    List<Map<String, Object>> out = new ArrayList<>();
    for (String lv : LEVELS) {
      AlarmSlaPolicy p = policies.selectById(lv);
      out.add(ApiSupport.map(
          "level", lv,
          "minutes", p == null ? AlarmService.defaultSla(lv) : p.getMinutes(),
          "enabled", p == null || Boolean.TRUE.equals(p.getEnabled()),
          "note", p == null ? null : p.getNote(),
          "updatedBy", p == null ? null : p.getUpdatedBy(),
          "updatedAt", p == null || p.getUpdatedAt() == null ? null : p.getUpdatedAt().toString(),
          "effectiveMinutes", effectiveMinutes(lv)));
    }
    return out;
  }

  /** 更新某级别的响应时限（值班长及以上）。 */
  public Map<String, Object> update(String level, Integer minutes, Boolean enabled, String note) {
    if (!CurrentUser.hasRole(Roles.SUPERVISOR)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅值班长及以上可调整报警响应 SLA 政策");
    }
    String lv = level == null ? "" : level.toLowerCase();
    if (!LEVELS.contains(lv)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "level 仅可为 critical / major / minor");
    }
    if (minutes != null && (minutes < 1 || minutes > 1440)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "minutes 需在 1–1440 之间");
    }
    AlarmSlaPolicy p = policies.selectById(lv);
    boolean insert = p == null;
    if (insert) p = new AlarmSlaPolicy();
    if (insert) p.setLevel(lv);
    if (minutes != null) p.setMinutes(minutes);
    if (enabled != null) p.setEnabled(enabled);
    if (note != null) p.setNote(note);
    if (p.getMinutes() == null) p.setMinutes(AlarmService.defaultSla(lv));
    if (p.getEnabled() == null) p.setEnabled(true);
    p.setUpdatedBy(CurrentUser.username());
    p.setUpdatedAt(OffsetDateTime.now());
    if (insert) policies.insert(p); else policies.updateById(p);
    return ApiSupport.map(
        "level", lv,
        "minutes", p.getMinutes(),
        "enabled", p.getEnabled(),
        "note", p.getNote(),
        "effectiveMinutes", effectiveMinutes(lv),
        "updatedBy", p.getUpdatedBy());
  }

  /** 首次运行兜底：若表为空（如外部导入时被跳过），按默认常量写入三条政策。 */
  private void ensureDefaults() {
    if (policies.selectCount(null) > 0) return;
    for (String lv : LEVELS) {
      AlarmSlaPolicy p = new AlarmSlaPolicy();
      p.setLevel(lv);
      p.setMinutes(AlarmService.defaultSla(lv));
      p.setEnabled(true);
      p.setNote("系统默认");
      p.setUpdatedBy("system");
      p.setUpdatedAt(OffsetDateTime.now());
      policies.insert(p);
    }
  }
}
