package com.fluxmes.api.foodsafety;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fluxmes.api.alarm.AlarmService;
import com.fluxmes.api.audit.AuditService;
import com.fluxmes.api.common.ApiSupport;
import com.fluxmes.api.common.CurrentUser;
import com.fluxmes.api.common.Roles;
import com.fluxmes.api.entity.Batch;
import com.fluxmes.api.entity.CcpPoint;
import com.fluxmes.api.entity.CcpRecord;
import com.fluxmes.api.entity.CleaningRecord;
import com.fluxmes.api.entity.Deviation;
import com.fluxmes.api.entity.EnvMonitoring;
import com.fluxmes.api.mapper.BatchMapper;
import com.fluxmes.api.mapper.CcpPointMapper;
import com.fluxmes.api.mapper.CcpRecordMapper;
import com.fluxmes.api.mapper.CleaningRecordMapper;
import com.fluxmes.api.mapper.DeviationMapper;
import com.fluxmes.api.mapper.EnvMonitoringMapper;
import com.fluxmes.api.personnel.PersonnelService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * F1 · 食品安全服务：HACCP 关键控制点（CCP）监控、清场与过敏原换型、环境与卫生监测。
 *
 * <p>核心合规语义：
 * <ul>
 *   <li>CCP 实测值越出关键限值（CL）→ 自动创建偏差单 + 报警（critical 级），并阻断后续放行；</li>
 *   <li>CCP 记录须由 QA 二次复核（双人复核，复核人不得为记录人）；</li>
 *   <li>新建批次前目标产线须有 PASS 且 QA 已确认且在有效期内的清场记录，否则拒绝开工；</li>
 *   <li>环境指标超标自动建偏差单。</li>
 * </ul>
 */
@Service
public class FoodSafetyService {

  private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyMMdd");

  private final CcpPointMapper ccpPoints;
  private final CcpRecordMapper ccpRecords;
  private final CleaningRecordMapper cleanings;
  private final EnvMonitoringMapper envRecords;
  private final DeviationMapper deviations;
  private final BatchMapper batches;
  private final AlarmService alarmService;
  private final AuditService audit;
  /** G2 · 人员资质门禁（CCP 监控 / 批记录复核 / 清场作业须持证上岗）。 */
  private final PersonnelService personnel;

  public FoodSafetyService(CcpPointMapper ccpPoints, CcpRecordMapper ccpRecords,
      CleaningRecordMapper cleanings, EnvMonitoringMapper envRecords, DeviationMapper deviations,
      BatchMapper batches, AlarmService alarmService, AuditService audit,
      PersonnelService personnel) {
    this.ccpPoints = ccpPoints;
    this.ccpRecords = ccpRecords;
    this.cleanings = cleanings;
    this.envRecords = envRecords;
    this.deviations = deviations;
    this.batches = batches;
    this.alarmService = alarmService;
    this.audit = audit;
    this.personnel = personnel;
  }

  /* =================== CCP 关键控制点 =================== */

  public List<Map<String, Object>> ccpPoints(String line) {
    return ccpPoints.selectList(Wrappers.<CcpPoint>lambdaQuery()
            .eq(line != null && !line.isBlank(), CcpPoint::getLine, line)
            .eq(CcpPoint::getEnabled, true)
            .orderByAsc(CcpPoint::getCode))
        .stream().map(p -> ApiSupport.map(
            "code", p.getCode(),
            "name", p.getName(),
            "line", p.getLine(),
            "stepName", p.getStepName(),
            "hazard", p.getHazard(),
            "hazardType", p.getHazardType(),
            "controlMeasure", p.getControlMeasure(),
            "clMin", p.getClMin(),
            "clMax", p.getClMax(),
            "unit", p.getUnit(),
            "monitorFreq", p.getMonitorFreq(),
            "correctiveAction", p.getCorrectiveAction(),
            "responsibleRole", p.getResponsibleRole())).toList();
  }

  public Map<String, Object> createCcpPoint(Map<String, Object> body) {
    if (!CurrentUser.hasRole(Roles.SUPERVISOR)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅值班长及以上可维护 CCP 点");
    }
    String code = str(body.get("code"));
    if (isBlank(code)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "code 必填");
    if (ccpPoints.selectById(code) != null) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "CCP 点已存在: " + code);
    }
    CcpPoint p = new CcpPoint();
    p.setCode(code);
    p.setName(str(body.get("name")));
    p.setLine(str(body.get("line")));
    p.setStepName(str(body.get("stepName")));
    p.setHazard(str(body.get("hazard")));
    p.setHazardType(str(body.get("hazardType")));
    p.setControlMeasure(str(body.get("controlMeasure")));
    p.setClMin(dec(body.get("clMin")));
    p.setClMax(dec(body.get("clMax")));
    p.setUnit(str(body.get("unit")));
    p.setMonitorFreq(str(body.get("monitorFreq")));
    p.setCorrectiveAction(str(body.get("correctiveAction")));
    p.setResponsibleRole(str(body.getOrDefault("responsibleRole", Roles.OPERATOR)));
    p.setEnabled(true);
    ccpPoints.insert(p);
    audit.record("ccp.point.create", "ccp_point", code, null, ApiSupport.map("name", p.getName()));
    return ApiSupport.map("code", code, "created", true);
  }

  public List<Map<String, Object>> ccpRecords(String batchId, String ccpCode, Boolean onlyDeviation,
      int limit) {
    var q = Wrappers.<CcpRecord>lambdaQuery()
        .eq(batchId != null && !batchId.isBlank(), CcpRecord::getBatchId, batchId)
        .eq(ccpCode != null && !ccpCode.isBlank(), CcpRecord::getCcpCode, ccpCode)
        .eq(Boolean.TRUE.equals(onlyDeviation), CcpRecord::getInLimit, false)
        .orderByDesc(CcpRecord::getRecordedAt);
    List<CcpRecord> all = ccpRecords.selectList(q);
    int n = limit <= 0 ? 100 : Math.min(limit, 500);
    return all.stream().limit(n).map(this::ccpRecordView).toList();
  }

  /**
   * CCP 监控记录上报：判定是否在关键限值内；偏离则自动建偏差单 + critical 报警并写审计。
   */
  public Map<String, Object> recordCcp(Map<String, Object> body) {
    // G2 · 资质门禁：CCP 监控岗位资质 + 有效健康证
    personnel.requireCapability(PersonnelService.CAP_CCP_MONITOR);
    String ccpCode = str(body.get("ccpCode"));
    CcpPoint point = ccpPoints.selectById(ccpCode);
    if (point == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "CCP 点不存在: " + ccpCode);

    BigDecimal value = dec(body.get("value"));
    if (value == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "value 必填");

    boolean inLimit = withinLimit(value, point.getClMin(), point.getClMax());
    String batchId = str(body.get("batchId"));

    CcpRecord r = new CcpRecord();
    r.setCcpCode(ccpCode);
    r.setBatchId(batchId);
    r.setLine(point.getLine());
    r.setValue(value);
    r.setInLimit(inLimit);
    r.setCorrective(str(body.get("corrective")));
    r.setOperator(CurrentUser.username());
    r.setRemark(str(body.get("remark")));
    r.setRecordedAt(OffsetDateTime.now());

    if (!inLimit) {
      String devId = newDeviation(batchId, "ccp",
          "CCP 偏离：" + point.getName() + " 实测 " + value + (point.getUnit() == null ? "" : point.getUnit())
              + "，超出关键限值 [" + point.getClMin() + ", " + point.getClMax() + "]");
      r.setDeviationId(devId);
      Map<String, Object> alarm = alarmService.raise(
          "CCP " + ccpCode + " " + point.getName(),
          "关键限值偏离：实测 " + value + (point.getUnit() == null ? "" : point.getUnit()),
          "critical",
          String.valueOf(value),
          "[" + point.getClMin() + ", " + point.getClMax() + "]");
      if (alarm != null) r.setAlarmId(str(alarm.get("id")));
      // 关联批次置为异常，阻断推进
      if (batchId != null && !batchId.isBlank()) {
        Batch b = batches.selectById(batchId);
        if (b != null && !"abnormal".equals(b.getStatus()) && !Boolean.TRUE.equals(b.getReleased())) {
          b.setStatus("abnormal");
          b.setDeviationNo(devId);
          b.setUpdatedAt(OffsetDateTime.now());
          batches.updateById(b);
        }
      }
    }
    ccpRecords.insert(r);
    audit.record(inLimit ? "ccp.record" : "ccp.deviation", "ccp_record",
        String.valueOf(r.getId()), null,
        ApiSupport.map("ccpCode", ccpCode, "value", value, "inLimit", inLimit,
            "deviationId", r.getDeviationId()));
    return ccpRecordView(r);
  }

  /** CCP 记录复核（QC+，复核人不得为记录人本人——食品 GMP 双人复核要求）。 */
  public Map<String, Object> verifyCcp(Long id) {
    if (!CurrentUser.hasRole(Roles.QC)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅质检员及以上可复核 CCP 记录");
    }
    // G2 · 资质门禁：批记录复核资质
    personnel.requireCapability(PersonnelService.CAP_BATCH_REVIEW);
    CcpRecord r = ccpRecords.selectById(id);
    if (r == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "记录不存在: " + id);
    String me = CurrentUser.username();
    if (me.equals(r.getOperator())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "复核人不得为记录人本人（双人复核要求）");
    }
    r.setVerifier(me);
    r.setVerifiedAt(OffsetDateTime.now());
    ccpRecords.updateById(r);
    audit.record("ccp.verify", "ccp_record", String.valueOf(id), null,
        ApiSupport.map("verifier", me, "ccpCode", r.getCcpCode()));
    return ccpRecordView(r);
  }

  /** CCP 合规看板：监控点数、记录数、偏离数、未复核数、合规率。 */
  public Map<String, Object> ccpSummary() {
    long pointCount = ccpPoints.selectCount(Wrappers.<CcpPoint>lambdaQuery().eq(CcpPoint::getEnabled, true));
    List<CcpRecord> all = ccpRecords.selectList(null);
    long total = all.size();
    long dev = all.stream().filter(r -> !Boolean.TRUE.equals(r.getInLimit())).count();
    long unverified = all.stream().filter(r -> r.getVerifier() == null).count();
    long openDev = all.stream().filter(r -> !Boolean.TRUE.equals(r.getInLimit()))
        .filter(r -> {
          if (r.getDeviationId() == null) return false;
          Deviation d = deviations.selectById(r.getDeviationId());
          return d != null && !"closed".equals(d.getStatus());
        }).count();
    double compliance = total == 0 ? 100.0 : ApiSupport.pct1((total - dev) * 100.0 / total);
    return ApiSupport.map(
        "generatedAt", ApiSupport.nowIso(),
        "pointCount", pointCount,
        "recordCount", total,
        "deviationCount", dev,
        "openDeviationCount", openDev,
        "unverifiedCount", unverified,
        "complianceRate", compliance);
  }

  /* =================== 清场与卫生 =================== */

  public List<Map<String, Object>> cleaningRecords(String line, String type, int limit) {
    var q = Wrappers.<CleaningRecord>lambdaQuery()
        .eq(line != null && !line.isBlank(), CleaningRecord::getLine, line)
        .eq(type != null && !type.isBlank(), CleaningRecord::getType, type)
        .orderByDesc(CleaningRecord::getCreatedAt);
    int n = limit <= 0 ? 50 : Math.min(limit, 200);
    return cleanings.selectList(q).stream().limit(n).map(this::cleaningView).toList();
  }

  public Map<String, Object> createCleaning(Map<String, Object> body) {
    // G2 · 资质门禁：清场作业资质
    personnel.requireCapability(PersonnelService.CAP_SANITATION);
    String line = str(body.get("line"));
    if (isBlank(line)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "line 必填");
    String id = "CLN-" + LocalDate.now().format(YYMMDD) + "-"
        + String.format("%03d", cleanings.selectCount(null) + 1);
    CleaningRecord c = new CleaningRecord();
    c.setId(id);
    c.setLine(line);
    c.setEquipmentCode(str(body.get("equipmentCode")));
    String type = str(body.getOrDefault("type", "ROUTINE"));
    c.setType(type);
    c.setMethod(str(body.get("method")));
    c.setAllergenFrom(str(body.get("allergenFrom")));
    c.setAllergenTo(str(body.get("allergenTo")));
    c.setStartedAt(OffsetDateTime.now());
    c.setExecutedBy(CurrentUser.username());
    c.setResult("PENDING");
    c.setNextBatchId(str(body.get("nextBatchId")));
    c.setRemark(str(body.get("remark")));
    c.setCreatedAt(OffsetDateTime.now());
    if ("ALLERGEN".equals(type) && isBlank(c.getMethod())) {
      c.setMethod("过敏原换型清洗：碱洗 → 冲洗 → ATP 验证");
    }
    cleanings.insert(c);
    audit.record("cleaning.create", "cleaning_record", id, null,
        ApiSupport.map("line", line, "type", type));
    return cleaningView(c);
  }

  /** QA / 值班长确认清场结果；PASS 后写入有效期（默认 72 小时）。 */
  public Map<String, Object> verifyCleaning(String id, String result, String swabResult) {
    if (!CurrentUser.hasRole(Roles.QC)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅质检员及以上可确认清场结果");
    }
    // G2 · 资质门禁：清场确认同样要求清场作业资质
    personnel.requireCapability(PersonnelService.CAP_SANITATION);
    CleaningRecord c = cleanings.selectById(id);
    if (c == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "清场记录不存在: " + id);
    String res = result == null || result.isBlank() ? "PASS" : result.toUpperCase();
    if (!List.of("PASS", "FAIL").contains(res)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "result 仅可为 PASS / FAIL");
    }
    String before = c.getResult();
    c.setResult(res);
    c.setSwabResult(swabResult);
    c.setFinishedAt(OffsetDateTime.now());
    c.setVerifiedBy(CurrentUser.username());
    c.setVerifiedAt(OffsetDateTime.now());
    c.setValidUntil("PASS".equals(res) ? OffsetDateTime.now().plusHours(72) : null);
    cleanings.updateById(c);

    if ("FAIL".equals(res)) {
      newDeviation(null, "sanitation", "清场不合格：" + id + "（" + c.getLine() + " / " + c.getType() + "）");
    }
    audit.record("cleaning.verify", "cleaning_record", id, ApiSupport.map("result", before),
        ApiSupport.map("result", res, "swabResult", swabResult));
    return cleaningView(c);
  }

  /**
   * 开工前置校验：目标产线是否有 PASS + 已 QA 确认 + 未过有效期的清场记录。
   * 无有效清场 → 拒绝新建批次（SUPERVISOR 可强制开工，写审计）。
   */
  public Map<String, Object> sanitationStatus(String line) {
    OffsetDateTime now = OffsetDateTime.now();
    CleaningRecord latest = cleanings.selectList(Wrappers.<CleaningRecord>lambdaQuery()
            .eq(CleaningRecord::getLine, line)
            .orderByDesc(CleaningRecord::getCreatedAt))
        .stream().findFirst().orElse(null);
    boolean pass = latest != null && "PASS".equals(latest.getResult())
        && latest.getVerifiedAt() != null
        && (latest.getValidUntil() == null || latest.getValidUntil().isAfter(now));
    String reason = pass ? null
        : latest == null ? "该产线无清场记录"
        : !"PASS".equals(latest.getResult()) ? "最新清场结果为 " + latest.getResult()
        : latest.getVerifiedAt() == null ? "清场未经 QA 确认"
        : "清场有效期已过（" + latest.getValidUntil() + "）";
    return ApiSupport.map(
        "line", line,
        "cleared", pass,
        "reason", reason,
        "latest", latest == null ? null : cleaningView(latest),
        "checkedAt", ApiSupport.nowIso());
  }

  /** 供 BatchService 复用的清场门禁（不抛异常，返回状态）。 */
  public boolean lineCleared(String line) {
    Map<String, Object> st = sanitationStatus(line);
    return Boolean.TRUE.equals(st.get("cleared"));
  }

  /* =================== 环境与卫生监测 =================== */

  public List<Map<String, Object>> envRecords(String area, String metric, int limit) {
    var q = Wrappers.<EnvMonitoring>lambdaQuery()
        .eq(area != null && !area.isBlank(), EnvMonitoring::getArea, area)
        .eq(metric != null && !metric.isBlank(), EnvMonitoring::getMetric, metric)
        .orderByDesc(EnvMonitoring::getSampledAt);
    int n = limit <= 0 ? 100 : Math.min(limit, 500);
    return envRecords.selectList(q).stream().limit(n).map(this::envView).toList();
  }

  public Map<String, Object> recordEnv(Map<String, Object> body) {
    String area = str(body.get("area"));
    String metric = str(body.get("metric"));
    if (isBlank(area) || isBlank(metric)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "area / metric 必填");
    }
    BigDecimal value = dec(body.get("value"));
    if (value == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "value 必填");
    BigDecimal min = dec(body.get("limitMin"));
    BigDecimal max = dec(body.get("limitMax"));
    boolean pass = withinLimit(value, min, max);

    EnvMonitoring e = new EnvMonitoring();
    e.setArea(area);
    e.setLine(str(body.get("line")));
    e.setMetric(metric);
    e.setValue(value);
    e.setUnit(str(body.get("unit")));
    e.setLimitMin(min);
    e.setLimitMax(max);
    e.setResult(pass ? "PASS" : "FAIL");
    e.setSampledBy(CurrentUser.username());
    e.setSampledAt(OffsetDateTime.now());
    e.setRemark(str(body.get("remark")));
    if (!pass) {
      e.setDeviationId(newDeviation(null, "environment",
          "环境超标：" + area + " " + metric + " = " + value
              + (e.getUnit() == null ? "" : e.getUnit())));
      alarmService.raise("环境 " + area, metric + " 超标：" + value, "major", String.valueOf(value),
          "[" + min + ", " + max + "]");
    }
    envRecords.insert(e);
    audit.record(pass ? "env.record" : "env.exceed", "env_monitoring", String.valueOf(e.getId()),
        null, ApiSupport.map("area", area, "metric", metric, "value", value, "result", e.getResult()));
    return envView(e);
  }

  public Map<String, Object> envSummary() {
    List<EnvMonitoring> all = envRecords.selectList(null);
    long total = all.size();
    long fail = all.stream().filter(e -> "FAIL".equals(e.getResult())).count();
    Map<String, List<EnvMonitoring>> byArea = new LinkedHashMap<>();
    for (EnvMonitoring e : all) {
      byArea.computeIfAbsent(e.getArea(), k -> new ArrayList<>()).add(e);
    }
    List<Map<String, Object>> areas = new ArrayList<>();
    for (Map.Entry<String, List<EnvMonitoring>> en : byArea.entrySet()) {
      List<EnvMonitoring> rs = en.getValue();
      long f = rs.stream().filter(e -> "FAIL".equals(e.getResult())).count();
      EnvMonitoring last = rs.stream().findFirst().orElse(null);
      areas.add(ApiSupport.map(
          "area", en.getKey(),
          "recordCount", rs.size(),
          "failCount", f,
          "passRate", ApiSupport.pct1((rs.size() - f) * 100.0 / rs.size()),
          "latestResult", last == null ? null : last.getResult()));
    }
    return ApiSupport.map(
        "generatedAt", ApiSupport.nowIso(),
        "recordCount", total,
        "failCount", fail,
        "passRate", total == 0 ? 100.0 : ApiSupport.pct1((total - fail) * 100.0 / total),
        "areas", areas);
  }

  /* =================== helpers =================== */

  private String newDeviation(String batchId, String source, String description) {
    String id = "DEV-" + LocalDate.now().format(YYMMDD) + "-"
        + String.format("%03d", deviations.selectCount(null) + 1);
    Deviation d = new Deviation();
    d.setId(id);
    d.setBatchId(batchId);
    d.setSource(source);
    d.setDescription(description);
    d.setStatus("open");
    d.setCreatedBy(CurrentUser.username());
    d.setCreatedAt(OffsetDateTime.now());
    deviations.insert(d);
    audit.record("deviation.create", "deviation", id, null,
        ApiSupport.map("source", source, "batchId", batchId));
    return id;
  }

  private static boolean withinLimit(BigDecimal v, BigDecimal min, BigDecimal max) {
    if (v == null) return true;
    if (min != null && v.compareTo(min) < 0) return false;
    return max == null || v.compareTo(max) <= 0;
  }

  private Map<String, Object> ccpRecordView(CcpRecord r) {
    CcpPoint p = ccpPoints.selectById(r.getCcpCode());
    // id 以字符串输出：BIGSERIAL 主键可能超过 JS Number.MAX_SAFE_INTEGER，
    // 直接返回 Long 会在浏览器端丢精度，导致后续 /verify 命中不到记录（404）。
    return ApiSupport.map(
        "id", r.getId() == null ? null : String.valueOf(r.getId()),
        "ccpCode", r.getCcpCode(),
        "ccpName", p == null ? null : p.getName(),
        "unit", p == null ? null : p.getUnit(),
        "clMin", p == null ? null : p.getClMin(),
        "clMax", p == null ? null : p.getClMax(),
        "batchId", r.getBatchId(),
        "line", r.getLine(),
        "value", r.getValue(),
        "inLimit", r.getInLimit(),
        "deviationId", r.getDeviationId(),
        "alarmId", r.getAlarmId(),
        "corrective", r.getCorrective(),
        "operator", r.getOperator(),
        "verifier", r.getVerifier(),
        "verifiedAt", r.getVerifiedAt() == null ? null : r.getVerifiedAt().toString(),
        "remark", r.getRemark(),
        "recordedAt", r.getRecordedAt() == null ? null : r.getRecordedAt().toString());
  }

  private Map<String, Object> cleaningView(CleaningRecord c) {
    return ApiSupport.map(
        "id", c.getId(),
        "line", c.getLine(),
        "equipmentCode", c.getEquipmentCode(),
        "type", c.getType(),
        "method", c.getMethod(),
        "allergenFrom", c.getAllergenFrom(),
        "allergenTo", c.getAllergenTo(),
        "startedAt", c.getStartedAt() == null ? null : c.getStartedAt().toString(),
        "finishedAt", c.getFinishedAt() == null ? null : c.getFinishedAt().toString(),
        "executedBy", c.getExecutedBy(),
        "verifiedBy", c.getVerifiedBy(),
        "result", c.getResult(),
        "swabResult", c.getSwabResult(),
        "validUntil", c.getValidUntil() == null ? null : c.getValidUntil().toString(),
        "nextBatchId", c.getNextBatchId(),
        "remark", c.getRemark());
  }

  private Map<String, Object> envView(EnvMonitoring e) {
    return ApiSupport.map(
        "id", e.getId() == null ? null : String.valueOf(e.getId()),
        "area", e.getArea(),
        "line", e.getLine(),
        "metric", e.getMetric(),
        "value", e.getValue(),
        "unit", e.getUnit(),
        "limitMin", e.getLimitMin(),
        "limitMax", e.getLimitMax(),
        "result", e.getResult(),
        "deviationId", e.getDeviationId(),
        "sampledBy", e.getSampledBy(),
        "sampledAt", e.getSampledAt() == null ? null : e.getSampledAt().toString(),
        "remark", e.getRemark());
  }

  private static BigDecimal dec(Object v) {
    if (v == null || String.valueOf(v).isBlank()) return null;
    try {
      return new BigDecimal(String.valueOf(v));
    } catch (NumberFormatException e) {
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
