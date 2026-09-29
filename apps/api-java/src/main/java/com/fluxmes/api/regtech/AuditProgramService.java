package com.fluxmes.api.regtech;

import static com.fluxmes.api.common.ApiSupport.map;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxmes.api.audit.AuditService;
import com.fluxmes.api.common.CurrentUser;
import com.fluxmes.api.entity.AuditFinding;
import com.fluxmes.api.entity.InternalAudit;
import com.fluxmes.api.mapper.AuditFindingMapper;
import com.fluxmes.api.mapper.InternalAuditMapper;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 内审管理服务（specs/quality-regtech FR-9 ~ FR-13）。
 *
 * <p>状态机：{@code planned → in_progress → reported → closed}。
 * 关键门禁（FR-12）：关闭内审时全部 {@code critical / major} 发现项须已生成 CAPA，否则 409 + 未处理清单。
 */
@Service
public class AuditProgramService {

  private static final Logger log = LoggerFactory.getLogger(AuditProgramService.class);

  public static final List<String> AUDIT_TYPES = List.of("SYSTEM", "PROCESS", "PRODUCT", "GMP_SELF");
  public static final List<String> SEVERITIES = List.of("CRITICAL", "MAJOR", "MINOR", "OBSERVATION");
  public static final List<String> FLOW = List.of("planned", "in_progress", "reported", "closed");

  public static final Map<String, String> TYPE_LABEL = Map.of(
      "SYSTEM", "体系审核", "PROCESS", "过程审核", "PRODUCT", "产品审核", "GMP_SELF", "GMP 自查");

  public static final Map<String, String> SEVERITY_LABEL = Map.of(
      "CRITICAL", "严重", "MAJOR", "主要", "MINOR", "次要", "OBSERVATION", "观察项");

  private final InternalAuditMapper audits;
  private final AuditFindingMapper findings;
  private final CapaService capaService;
  private final ObjectMapper json;
  private final AuditService audit;

  public AuditProgramService(InternalAuditMapper audits, AuditFindingMapper findings,
      CapaService capaService, ObjectMapper json, AuditService audit) {
    this.audits = audits;
    this.findings = findings;
    this.capaService = capaService;
    this.json = json;
    this.audit = audit;
  }

  /* ==================== FR-9 / FR-13 查询 ==================== */

  public Map<String, Object> list(Integer year, String site) {
    List<InternalAudit> rows = audits.selectList(Wrappers.<InternalAudit>lambdaQuery()
        .likeRight(year != null, InternalAudit::getId, "AUDIT-" + year + "-")
        .eq(site != null && !site.isBlank(), InternalAudit::getSite, site)
        .orderByDesc(InternalAudit::getId));
    List<Map<String, Object>> items = rows.stream().map(this::toView).toList();
    return map(
        "items", items,
        "total", items.size(),
        "year", year,
        "summary", summarize(rows),
        "generatedAt", OffsetDateTime.now().toString());
  }

  public Map<String, Object> detail(String id) {
    InternalAudit a = require(id);
    Map<String, Object> v = toView(a);
    v.put("findings", findingViews(id));
    return v;
  }

  /** FR-13 · 按年度统计发现项分布与 CAPA 转化率。 */
  public Map<String, Object> summary(Integer year) {
    List<InternalAudit> rows = audits.selectList(Wrappers.<InternalAudit>lambdaQuery()
        .likeRight(year != null, InternalAudit::getId, "AUDIT-" + year + "-"));
    return summarize(rows);
  }

  private Map<String, Object> summarize(List<InternalAudit> rows) {
    if (rows.isEmpty()) {
      return map(
          "auditCount", 0, "findingCount", 0, "bySeverity", Map.of(),
          "capaCreated", 0, "capaConversionRate", 0.0, "openMajorFindings", 0);
    }
    List<String> ids = rows.stream().map(InternalAudit::getId).toList();
    List<AuditFinding> all = findings.selectList(Wrappers.<AuditFinding>lambdaQuery()
        .in(AuditFinding::getAuditId, ids));
    Map<String, Integer> bySeverity = new LinkedHashMap<>();
    for (String s : SEVERITIES) bySeverity.put(s, 0);
    long withCapa = 0;
    long openMajor = 0;
    for (AuditFinding f : all) {
      bySeverity.merge(f.getSeverity(), 1, Integer::sum);
      if (f.getCapaId() != null && !f.getCapaId().isBlank()) withCapa++;
      else if (List.of("CRITICAL", "MAJOR").contains(f.getSeverity())) openMajor++;
    }
    double rate = all.isEmpty() ? 0.0 : Math.round(withCapa * 1000.0 / all.size()) / 10.0;
    return map(
        "auditCount", rows.size(),
        "findingCount", all.size(),
        "bySeverity", bySeverity,
        "capaCreated", withCapa,
        "capaConversionRate", rate,
        "openMajorFindings", openMajor);
  }

  /* ==================== FR-9 内审计划 ==================== */

  @Transactional
  public Map<String, Object> create(Map<String, Object> rawBody) {
    Map<String, Object> body = rawBody == null ? Map.of() : rawBody;
    String title = trim(body.get("title"));
    if (title == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "内审标题（title）必填");
    String type = str(body.get("auditType"), "SYSTEM").toUpperCase();
    if (!AUDIT_TYPES.contains(type)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "内审类型非法（FR-9）：" + type + "，可选 " + AUDIT_TYPES);
    }
    String lead = trim(body.get("lead"));
    if (lead == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "审核组长（lead）必填");
    LocalDate start = date(body.get("planStart"));
    LocalDate end = date(body.get("planEnd"));
    if (start == null || end == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "计划起止日期（planStart / planEnd）必填");
    }
    if (end.isBefore(start)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "计划结束日期不得早于开始日期");
    }
    InternalAudit a = new InternalAudit();
    a.setId(nextId(LocalDate.now().getYear()));
    a.setTitle(title);
    a.setAuditType(type);
    a.setScope(trim(body.get("scope")));
    a.setLead(lead);
    a.setTeam(writeJson(body.get("team")));
    a.setPlanStart(start);
    a.setPlanEnd(end);
    a.setStatus("planned");
    a.setSite(trim(body.get("site")) == null ? CurrentUser.site() : trim(body.get("site")));
    a.setCreatedBy(CurrentUser.username());
    a.setCreatedAt(OffsetDateTime.now());
    a.setUpdatedAt(OffsetDateTime.now());
    audits.insert(a);
    audit.record("audit.create", "internal_audit", a.getId(), null, toView(a));
    log.info("internal audit {} created by {}", a.getId(), a.getCreatedBy());
    return detail(a.getId());
  }

  @Transactional
  public Map<String, Object> transition(String id, String target, String comment) {
    InternalAudit a = require(id);
    Map<String, Object> before = toView(a);
    if (!List.of("in_progress", "reported").contains(target == null ? "" : target)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "非法流转：" + target + "，可用目标 in_progress / reported（关闭请走 /close）");
    }
    int from = FLOW.indexOf(a.getStatus());
    int to = FLOW.indexOf(target);
    if (from < 0 || to != from + 1) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "内审状态只能逐级推进 planned→in_progress→reported→closed，当前: " + a.getStatus());
    }
    a.setStatus(target);
    if (comment != null && !comment.isBlank()) a.setReportNote(comment);
    a.setUpdatedAt(OffsetDateTime.now());
    audits.updateById(a);
    audit.record("audit.transition", "internal_audit", id, before, map("status", target, "comment", comment));
    return detail(id);
  }

  /** FR-12 · 关闭内审：全部 critical / major 发现项须已生成 CAPA。 */
  @Transactional
  public Map<String, Object> close(String id, String comment) {
    InternalAudit a = require(id);
    if (!"reported".equals(a.getStatus())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "仅 reported 状态可关闭内审，当前: " + a.getStatus());
    }
    List<AuditFinding> unhandled = findings.selectList(Wrappers.<AuditFinding>lambdaQuery()
        .eq(AuditFinding::getAuditId, id)
        .in(AuditFinding::getSeverity, List.of("CRITICAL", "MAJOR"))
        .and(w -> w.isNull(AuditFinding::getCapaId).or().eq(AuditFinding::getCapaId, "")));
    if (!unhandled.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "存在未生成 CAPA 的 critical / major 发现项，阻断关闭（FR-12）："
              + unhandled.stream().map(f -> "#" + f.getSeq() + "[" + f.getSeverity() + "] "
                  + f.getDescription()).toList());
    }
    Map<String, Object> before = toView(a);
    a.setStatus("closed");
    a.setClosedBy(CurrentUser.username());
    a.setClosedAt(OffsetDateTime.now());
    if (comment != null && !comment.isBlank()) a.setReportNote(comment);
    a.setUpdatedAt(OffsetDateTime.now());
    audits.updateById(a);
    audit.record("audit.close", "internal_audit", id, before, map("status", "closed", "comment", comment));
    return detail(id);
  }

  /* ==================== FR-10 / FR-11 发现项 ==================== */

  @Transactional
  public Map<String, Object> addFinding(String auditId, Map<String, Object> rawBody) {
    Map<String, Object> body = rawBody == null ? Map.of() : rawBody;
    InternalAudit a = require(auditId);
    if ("closed".equals(a.getStatus())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "内审已关闭，不可登记发现项");
    }
    String severity = str(body.get("severity"), "").toUpperCase();
    if (!SEVERITIES.contains(severity)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "发现项分级非法（FR-10）：" + severity + "，可选 " + SEVERITIES);
    }
    String description = trim(body.get("description"));
    if (description == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "发现项描述（description）必填");
    String clause = trim(body.get("clause"));
    // C5 · 依据标注：critical/major 必须标明条款
    if (List.of("CRITICAL", "MAJOR").contains(severity) && clause == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "critical / major 发现项须标注条款号（clause，C5 依据标注），如 FSSC 22000 8.9.5");
    }
    int seq = findings.selectCount(Wrappers.<AuditFinding>lambdaQuery()
        .eq(AuditFinding::getAuditId, auditId)).intValue() + 1;
    AuditFinding f = new AuditFinding();
    f.setAuditId(auditId);
    f.setSeq(seq);
    f.setClause(clause);
    f.setSeverity(severity);
    f.setDescription(description);
    f.setArea(trim(body.get("area")));
    f.setOwner(trim(body.get("owner")) == null ? a.getLead() : trim(body.get("owner")));
    f.setCreatedBy(CurrentUser.username());
    f.setCreatedAt(OffsetDateTime.now());
    findings.insert(f);
    markUpdated(auditId);
    audit.record("audit.finding.add", "audit_finding", String.valueOf(f.getId()), null, findingView(f));
    log.info("audit {} finding #{} [{}] registered", auditId, seq, severity);
    return detail(auditId);
  }

  /**
   * FR-11 · 发现项一键转 CAPA：默认责任人为被审核区域负责人（发现项 owner）。
   * 生成后回写 {@code audit_finding.capa_id}。
   */
  @Transactional
  public Map<String, Object> findingToCapa(Long findingId, Map<String, Object> rawBody) {
    Map<String, Object> body = rawBody == null ? Map.of() : rawBody;
    AuditFinding f = findings.selectById(findingId);
    if (f == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "发现项不存在: " + findingId);
    if (f.getCapaId() != null && !f.getCapaId().isBlank()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "该发现项已生成 CAPA：" + f.getCapaId() + "，请勿重复创建");
    }
    InternalAudit a = audits.selectById(f.getAuditId());
    String owner = trim(body.get("owner")) != null ? trim(body.get("owner"))
        : (f.getOwner() != null ? f.getOwner()
            : (a != null && a.getLead() != null ? a.getLead() : CurrentUser.username()));

    Map<String, Object> capaBody = new LinkedHashMap<>();
    capaBody.put("sourceType", CapaService.SOURCE_AUDIT_FINDING);
    capaBody.put("sourceId", String.valueOf(f.getId()));
    capaBody.put("type", str(body.get("type"), "CORRECTIVE").toUpperCase());
    capaBody.put("title", trim(body.get("title")) != null ? trim(body.get("title"))
        : "[" + f.getSeverity() + "] " + f.getDescription());
    capaBody.put("description", trim(body.get("description")) != null ? trim(body.get("description"))
        : "内审 " + f.getAuditId() + " 发现项 #" + f.getSeq()
            + (f.getClause() == null ? "" : "（条款 " + f.getClause() + "）"));
    capaBody.put("rootCause", trim(body.get("rootCause")) != null ? trim(body.get("rootCause"))
        : "内审发现项待补充根因分析（" + f.getSeverity() + "）：" + f.getDescription());
    capaBody.put("owner", owner);
    capaBody.put("dueDate", trim(body.get("dueDate")) != null ? trim(body.get("dueDate"))
        : LocalDate.now().plusDays("CRITICAL".equals(f.getSeverity()) ? 7 : 30).toString());
    capaBody.put("severity", "CRITICAL".equals(f.getSeverity()) ? "critical"
        : "MAJOR".equals(f.getSeverity()) ? "major" : "minor");
    capaBody.put("site", a == null ? CurrentUser.site() : a.getSite());
    if (body.get("tasks") instanceof List<?> l && !l.isEmpty()) {
      capaBody.put("tasks", l);
    } else {
      capaBody.put("tasks", List.of(map(
          "action", "按内审发现项完成整改并提交证据：" + f.getDescription(),
          "owner", owner,
          "dueDate", capaBody.get("dueDate"))));
    }

    Map<String, Object> created = capaService.create(capaBody);
    String capaId = String.valueOf(created.get("id"));
    f.setCapaId(capaId);
    findings.updateById(f);
    markUpdated(f.getAuditId());
    audit.record("audit.finding.toCapa", "audit_finding", String.valueOf(findingId), null,
        map("capaId", capaId, "owner", owner));
    Map<String, Object> out = detail(f.getAuditId());
    out.put("capa", created);
    out.put("findingId", findingId);
    return out;
  }

  /* ==================== 视图 ==================== */

  public InternalAudit require(String id) {
    InternalAudit a = audits.selectById(id);
    if (a == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "内审不存在: " + id);
    return a;
  }

  public Map<String, Object> toView(InternalAudit a) {
    List<AuditFinding> rows = findings.selectList(Wrappers.<AuditFinding>lambdaQuery()
        .eq(AuditFinding::getAuditId, a.getId()));
    Map<String, Integer> bySeverity = new LinkedHashMap<>();
    for (String s : SEVERITIES) bySeverity.put(s, 0);
    long withCapa = 0;
    long blocking = 0;
    for (AuditFinding f : rows) {
      bySeverity.merge(f.getSeverity(), 1, Integer::sum);
      boolean hasCapa = f.getCapaId() != null && !f.getCapaId().isBlank();
      if (hasCapa) withCapa++;
      else if (List.of("CRITICAL", "MAJOR").contains(f.getSeverity())) blocking++;
    }
    return map(
        "id", a.getId(),
        "title", a.getTitle(),
        "auditType", a.getAuditType(),
        "auditTypeLabel", TYPE_LABEL.getOrDefault(a.getAuditType(), a.getAuditType()),
        "scope", a.getScope(),
        "lead", a.getLead(),
        "team", readJsonList(a.getTeam()),
        "planStart", a.getPlanStart() == null ? null : a.getPlanStart().toString(),
        "planEnd", a.getPlanEnd() == null ? null : a.getPlanEnd().toString(),
        "status", a.getStatus(),
        "reportNote", a.getReportNote(),
        "closedBy", a.getClosedBy(),
        "closedAt", a.getClosedAt() == null ? null : a.getClosedAt().toString(),
        "site", a.getSite(),
        "createdBy", a.getCreatedBy(),
        "createdAt", a.getCreatedAt() == null ? null : a.getCreatedAt().toString(),
        "findingCount", rows.size(),
        "bySeverity", bySeverity,
        "capaCreated", withCapa,
        "capaConversionRate", rows.isEmpty() ? 0.0 : Math.round(withCapa * 1000.0 / rows.size()) / 10.0,
        "blockingFindings", blocking);
  }

  public static Map<String, Object> findingView(AuditFinding f) {
    return map(
        "id", f.getId(),
        "auditId", f.getAuditId(),
        "seq", f.getSeq(),
        "clause", f.getClause(),
        "severity", f.getSeverity(),
        "severityLabel", SEVERITY_LABEL.getOrDefault(f.getSeverity(), f.getSeverity()),
        "description", f.getDescription(),
        "area", f.getArea(),
        "owner", f.getOwner(),
        "capaId", f.getCapaId(),
        "createdBy", f.getCreatedBy(),
        "createdAt", f.getCreatedAt() == null ? null : f.getCreatedAt().toString());
  }

  private List<Map<String, Object>> findingViews(String auditId) {
    return findings.selectList(Wrappers.<AuditFinding>lambdaQuery()
            .eq(AuditFinding::getAuditId, auditId).orderByAsc(AuditFinding::getSeq)).stream()
        .map(AuditProgramService::findingView)
        .toList();
  }

  /* ==================== helpers ==================== */

  private String nextId(int year) {
    String prefix = "AUDIT-" + year + "-";
    long n = audits.selectCount(Wrappers.<InternalAudit>lambdaQuery()
        .likeRight(InternalAudit::getId, prefix));
    return prefix + String.format("%02d", n + 1);
  }

  private void markUpdated(String auditId) {
    InternalAudit a = audits.selectById(auditId);
    if (a == null) return;
    a.setUpdatedAt(OffsetDateTime.now());
    audits.updateById(a);
  }

  private String writeJson(Object v) {
    if (v == null) return null;
    try {
      return json.writeValueAsString(v);
    } catch (Exception e) {
      return null;
    }
  }

  private List<String> readJsonList(String s) {
    if (s == null || s.isBlank()) return List.of();
    try {
      return json.readValue(s, json.getTypeFactory()
          .constructCollectionType(ArrayList.class, String.class));
    } catch (Exception e) {
      return List.of();
    }
  }

  private static String str(Object v, String fallback) {
    String s = trim(v);
    return s == null ? fallback : s;
  }

  private static String trim(Object v) {
    if (v == null) return null;
    String s = String.valueOf(v).trim();
    return s.isEmpty() ? null : s;
  }

  private static LocalDate date(Object v) {
    String s = trim(v);
    if (s == null) return null;
    try {
      return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
    } catch (Exception e) {
      return null;
    }
  }
}
