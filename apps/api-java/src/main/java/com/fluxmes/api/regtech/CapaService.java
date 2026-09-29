package com.fluxmes.api.regtech;

import static com.fluxmes.api.common.ApiSupport.map;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fluxmes.api.alarm.AlarmService;
import com.fluxmes.api.audit.AuditService;
import com.fluxmes.api.common.CurrentUser;
import com.fluxmes.api.common.Roles;
import com.fluxmes.api.entity.Alarm;
import com.fluxmes.api.entity.Capa;
import com.fluxmes.api.entity.CapaEvent;
import com.fluxmes.api.entity.CapaTask;
import com.fluxmes.api.entity.Deviation;
import com.fluxmes.api.mapper.AlarmMapper;
import com.fluxmes.api.mapper.CapaEventMapper;
import com.fluxmes.api.mapper.CapaMapper;
import com.fluxmes.api.mapper.CapaTaskMapper;
import com.fluxmes.api.mapper.DeviationMapper;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
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
 * CAPA 闭环服务（specs/quality-regtech FR-1 ~ FR-8）。
 *
 * <p>状态机（plan §1）：
 * <pre>
 * open ─> in_progress ─> pending_verify ─> verified ─> closed
 *              ▲                 │
 *              └──── rejected ───┘      （验证结论「无效」退回，行动项重开）
 * </pre>
 *
 * <p>关键门禁：
 * <ul>
 *   <li>FR-2 创建须填根因 + ≥1 条行动项</li>
 *   <li>FR-4 存在未完成行动项时拒绝进入 pending_verify（400 + 未完成清单）</li>
 *   <li>FR-7 偏差存在未关闭 CAPA 时，偏差关闭与关联批次放行被阻断（409 + CAPA 编号）</li>
 *   <li>FR-8 关闭须电子签名（CAPA_CLOSE / APPROVED / ADMIN）</li>
 * </ul>
 */
@Service
public class CapaService {

  private static final Logger log = LoggerFactory.getLogger(CapaService.class);
  private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyMMdd");

  public static final String SOURCE_DEVIATION = "DEVIATION";
  public static final String SOURCE_AUDIT_FINDING = "AUDIT_FINDING";
  public static final String SOURCE_ALARM = "ALARM";
  public static final String SOURCE_MANUAL = "MANUAL";

  public static final List<String> SOURCE_TYPES =
      List.of(SOURCE_DEVIATION, SOURCE_AUDIT_FINDING, SOURCE_ALARM, SOURCE_MANUAL);
  public static final List<String> TYPES = List.of("CORRECTION", "CORRECTIVE", "PREVENTIVE");
  public static final List<String> SEVERITIES = List.of("critical", "major", "minor");
  public static final List<String> VERIFY_METHODS = List.of("DOC_REVIEW", "SITE_CHECK", "DATA_REVIEW");

  /** FR-5 · 距到期日 ≤ 3 天视为「临期」。 */
  public static final int DUE_SOON_DAYS = 3;

  public static final Map<String, String> TYPE_LABEL = Map.of(
      "CORRECTION", "纠正", "CORRECTIVE", "纠正措施", "PREVENTIVE", "预防措施");
  public static final Map<String, String> SOURCE_LABEL = Map.of(
      SOURCE_DEVIATION, "偏差单", SOURCE_AUDIT_FINDING, "内审发现项",
      SOURCE_ALARM, "报警", SOURCE_MANUAL, "手工创建");
  public static final Map<String, String> METHOD_LABEL = Map.of(
      "DOC_REVIEW", "文件审查", "SITE_CHECK", "现场确认", "DATA_REVIEW", "数据复核");

  private final CapaMapper capas;
  private final CapaTaskMapper tasks;
  private final CapaEventMapper events;
  private final DeviationMapper deviations;
  private final AlarmMapper alarms;
  private final AlarmService alarmService;
  private final SignatureService signatures;
  private final AuditService audit;

  public CapaService(CapaMapper capas, CapaTaskMapper tasks, CapaEventMapper events,
      DeviationMapper deviations, AlarmMapper alarms, AlarmService alarmService,
      SignatureService signatures, AuditService audit) {
    this.capas = capas;
    this.tasks = tasks;
    this.events = events;
    this.deviations = deviations;
    this.alarms = alarms;
    this.alarmService = alarmService;
    this.signatures = signatures;
    this.audit = audit;
  }

  /* ==================== 查询 ==================== */

  /** CAPA 列表（FR-5 附带 overdue / dueSoon 标记）。 */
  public Map<String, Object> list(String status, String owner, String site, Boolean overdueOnly) {
    List<Capa> rows = capas.selectList(Wrappers.<Capa>lambdaQuery()
        .eq(status != null && !status.isBlank(), Capa::getStatus, status)
        .eq(owner != null && !owner.isBlank(), Capa::getOwner, owner)
        .eq(site != null && !site.isBlank(), Capa::getSite, site)
        .orderByAsc(Capa::getDueDate)
        .orderByDesc(Capa::getCreatedAt));
    LocalDate today = LocalDate.now();
    List<Map<String, Object>> items = new ArrayList<>();
    for (Capa c : rows) {
      Map<String, Object> v = toView(c, today);
      if (Boolean.TRUE.equals(overdueOnly) && !Boolean.TRUE.equals(v.get("overdue"))) continue;
      v.put("taskSummary", taskSummary(c.getId()));
      items.add(v);
    }
    long open = rows.stream().filter(c -> !"closed".equals(c.getStatus())).count();
    long overdue = rows.stream()
        .filter(c -> !"closed".equals(c.getStatus()) && isOverdue(c, today)).count();
    long dueSoon = rows.stream()
        .filter(c -> !"closed".equals(c.getStatus()) && !isOverdue(c, today) && isDueSoon(c, today)).count();
    return map(
        "items", items,
        "total", rows.size(),
        "open", open,
        "overdue", overdue,
        "dueSoon", dueSoon,
        "dueSoonDays", DUE_SOON_DAYS,
        "generatedAt", OffsetDateTime.now().toString());
  }

  public Map<String, Object> detail(String id) {
    Capa c = require(id);
    Map<String, Object> v = toView(c, LocalDate.now());
    v.put("tasks", taskViews(id));
    v.put("events", eventViews(id));
    v.put("signatures", signatures.list("CAPA", id, null, null));
    v.put("recordRevision", c.getRecordRevision());
    return v;
  }

  /** FR-5 · 临期与逾期清单 + 报警联动（major 及以上逾期自动产生报警，同源同内容去重）。 */
  public Map<String, Object> overdue() {
    LocalDate today = LocalDate.now();
    List<Capa> rows = capas.selectList(Wrappers.<Capa>lambdaQuery()
        .ne(Capa::getStatus, "closed")
        .orderByAsc(Capa::getDueDate));
    List<Map<String, Object>> dueSoon = new ArrayList<>();
    List<Map<String, Object>> overdueList = new ArrayList<>();
    int raised = 0;
    for (Capa c : rows) {
      Map<String, Object> v = toView(c, today);
      if (isOverdue(c, today)) {
        overdueList.add(v);
        if (List.of("critical", "major").contains(c.getSeverity()) && raiseOverdueAlarm(c, today)) raised++;
      } else if (isDueSoon(c, today)) {
        dueSoon.add(v);
      }
    }
    return map(
        "overdue", overdueList,
        "dueSoon", dueSoon,
        "overdueCount", overdueList.size(),
        "dueSoonCount", dueSoon.size(),
        "dueSoonDays", DUE_SOON_DAYS,
        "alarmRaised", raised,
        "byOwner", byOwner(rows, today));
  }

  /** US2 · 按责任人汇总（逾期 / 临期 / 在办）。 */
  public List<Map<String, Object>> byOwner(List<Capa> rows, LocalDate today) {
    Map<String, int[]> agg = new LinkedHashMap<>();
    for (Capa c : rows) {
      String owner = c.getOwner() == null ? "—" : c.getOwner();
      int[] box = agg.computeIfAbsent(owner, k -> new int[3]);
      if (isOverdue(c, today)) box[0]++;
      else if (isDueSoon(c, today)) box[1]++;
      else box[2]++;
    }
    List<Map<String, Object>> out = new ArrayList<>();
    agg.forEach((owner, box) -> out.add(map(
        "owner", owner, "overdue", box[0], "dueSoon", box[1], "onTrack", box[2],
        "total", box[0] + box[1] + box[2])));
    out.sort((a, b) -> Long.compare(numOf(b.get("overdue")), numOf(a.get("overdue"))));
    return out;
  }

  /* ==================== FR-1 / FR-2 创建 ==================== */

  @Transactional
  public Map<String, Object> create(Map<String, Object> body) {
    String sourceType = str(body.get("sourceType"), SOURCE_MANUAL).toUpperCase();
    if (!SOURCE_TYPES.contains(sourceType)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "来源类型非法（FR-1）：" + sourceType + "，可选 " + SOURCE_TYPES);
    }
    String type = str(body.get("type"), "CORRECTIVE").toUpperCase();
    if (!TYPES.contains(type)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "CAPA 类型非法（FR-1）：" + type + "，可选 " + TYPES);
    }
    String title = trim(body.get("title"));
    if (title == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "标题（title）必填");
    String rootCause = trim(body.get("rootCause"));
    if (rootCause == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "根因分析（rootCause）不得为空（FR-2）");
    }
    String owner = trim(body.get("owner"));
    if (owner == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "责任人（owner）必填");
    LocalDate due = date(body.get("dueDate"));
    if (due == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "到期日（dueDate）必填，格式 yyyy-MM-dd");
    String severity = str(body.get("severity"), "major").toLowerCase();
    if (!SEVERITIES.contains(severity)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "严重度非法：" + severity + "，可选 " + SEVERITIES);
    }
    List<?> taskList = body.get("tasks") instanceof List<?> l ? l : List.of();
    if (taskList.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "至少需要一条行动项（tasks，FR-2）");
    }

    Capa c = new Capa();
    c.setId(nextId());
    c.setSourceType(sourceType);
    c.setSourceId(trim(body.get("sourceId")));
    c.setType(type);
    c.setTitle(title);
    c.setDescription(trim(body.get("description")));
    c.setRootCause(rootCause);
    c.setOwner(owner);
    c.setDueDate(due);
    c.setSeverity(severity);
    c.setStatus("open");
    c.setSite(trim(body.get("site")) == null ? CurrentUser.site() : trim(body.get("site")));
    c.setRecordRevision(1);
    c.setCreatedBy(CurrentUser.username());
    c.setCreatedAt(OffsetDateTime.now());
    c.setUpdatedAt(OffsetDateTime.now());
    capas.insert(c);

    int seq = 1;
    for (Object raw : taskList) {
      if (!(raw instanceof Map<?, ?> m)) continue;
      Map<String, Object> tm = castMap(m);
      String action = trim(tm.get("action"));
      if (action == null) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "行动项 " + seq + " 缺少动作描述（action）");
      }
      CapaTask t = new CapaTask();
      t.setCapaId(c.getId());
      t.setSeq(seq++);
      t.setAction(action);
      t.setOwner(trim(tm.get("owner")) == null ? owner : trim(tm.get("owner")));
      t.setDueDate(date(tm.get("dueDate")) == null ? due : date(tm.get("dueDate")));
      t.setDone(false);
      t.setEvidence(trim(tm.get("evidence")));
      t.setRemark(trim(tm.get("remark")));
      t.setCreatedAt(OffsetDateTime.now());
      tasks.insert(t);
    }

    writeEvent(c.getId(), null, "open", "创建 CAPA（" + SOURCE_LABEL.getOrDefault(sourceType, sourceType)
        + " · " + TYPE_LABEL.getOrDefault(type, type) + "）");
    audit.record("capa.create", "capa", c.getId(), null, toView(c, LocalDate.now()));
    log.info("CAPA {} created from {}:{} by {}", c.getId(), sourceType, c.getSourceId(), c.getCreatedBy());
    return detail(c.getId());
  }

  /* ==================== FR-3 / FR-4 状态流转 ==================== */

  @Transactional
  public Map<String, Object> transition(String id, String target, String comment) {
    Capa c = require(id);
    Map<String, Object> before = toView(c, LocalDate.now());
    switch (target == null ? "" : target) {
      case "in_progress" -> {
        if (!List.of("open", "rejected").contains(c.getStatus())) {
          throw new ResponseStatusException(HttpStatus.CONFLICT,
              "仅 open / rejected 可推进到 in_progress，当前: " + c.getStatus());
        }
      }
      case "pending_verify" -> {
        if (!"in_progress".equals(c.getStatus())) {
          throw new ResponseStatusException(HttpStatus.CONFLICT,
              "仅 in_progress 可提交验证，当前: " + c.getStatus());
        }
        // FR-4 · 行动项门禁
        List<CapaTask> pending = tasks.selectList(Wrappers.<CapaTask>lambdaQuery()
            .eq(CapaTask::getCapaId, id).eq(CapaTask::getDone, false)
            .orderByAsc(CapaTask::getSeq));
        if (!pending.isEmpty()) {
          throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
              "存在未完成行动项，不可提交验证（FR-4）："
                  + pending.stream().map(t -> "#" + t.getSeq() + " " + t.getAction()).toList());
        }
      }
      default -> throw new ResponseStatusException(HttpStatus.CONFLICT,
          "非法流转：" + target + "，可用目标 in_progress / pending_verify"
              + "（验证与关闭请走 /verify 与 /close）");
    }
    String from = c.getStatus();
    c.setStatus(target);
    c.setUpdatedAt(OffsetDateTime.now());
    capas.updateById(c);
    writeEvent(id, from, target, comment);
    audit.record("capa.transition", "capa", id, before, map("status", target, "comment", comment));
    return detail(id);
  }

  /* ==================== FR-2 行动项 ==================== */

  @Transactional
  public Map<String, Object> addTask(String capaId, Map<String, Object> body) {
    Capa c = require(capaId);
    if ("closed".equals(c.getStatus())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "CAPA 已关闭，不可追加行动项");
    }
    String action = trim(body.get("action"));
    if (action == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "行动项动作描述（action）必填");
    int seq = tasks.selectCount(Wrappers.<CapaTask>lambdaQuery()
        .eq(CapaTask::getCapaId, capaId)).intValue() + 1;
    CapaTask t = new CapaTask();
    t.setCapaId(capaId);
    t.setSeq(seq);
    t.setAction(action);
    t.setOwner(trim(body.get("owner")) == null ? c.getOwner() : trim(body.get("owner")));
    t.setDueDate(date(body.get("dueDate")) == null ? c.getDueDate() : date(body.get("dueDate")));
    t.setDone(false);
    t.setEvidence(trim(body.get("evidence")));
    t.setRemark(trim(body.get("remark")));
    t.setCreatedAt(OffsetDateTime.now());
    tasks.insert(t);
    c.setUpdatedAt(OffsetDateTime.now());
    capas.updateById(c);
    audit.record("capa.task.add", "capa_task", String.valueOf(t.getId()), null,
        map("capaId", capaId, "seq", seq, "action", action));
    return detail(capaId);
  }

  /** 完成行动项（支持证据上传说明）。 */
  @Transactional
  public Map<String, Object> doneTask(Long taskId, Map<String, Object> body) {
    CapaTask t = tasks.selectById(taskId);
    if (t == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "行动项不存在: " + taskId);
    Capa c = require(t.getCapaId());
    if ("closed".equals(c.getStatus())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "CAPA 已关闭，不可变更行动项");
    }
    boolean delegated = CurrentUser.username().equals(t.getOwner());
    if (!delegated && !CurrentUser.hasRole(Roles.QC)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,
          "仅行动项责任人（" + t.getOwner() + "）或质检员及以上可标记完成");
    }
    Map<String, Object> before = taskView(t);
    t.setDone(true);
    t.setDoneAt(OffsetDateTime.now());
    t.setDoneBy(CurrentUser.username());
    if (trim(body.get("evidence")) != null) t.setEvidence(trim(body.get("evidence")));
    if (trim(body.get("remark")) != null) t.setRemark(trim(body.get("remark")));
    tasks.updateById(t);
    audit.record("capa.task.done", "capa_task", String.valueOf(taskId), before, taskView(t));
    return detail(t.getCapaId());
  }

  /* ==================== FR-6 有效性验证 ==================== */

  /** 验证 CAPA 有效性：需电子签名（CAPA_VERIFY / VERIFIED / ADMIN）；无效退回并重开行动项。 */
  @Transactional
  public Map<String, Object> verify(String id, Map<String, Object> rawBody, String ip, String ua) {
    Map<String, Object> body = rawBody == null ? Map.of() : rawBody;
    Capa c = require(id);
    if (!"pending_verify".equals(c.getStatus())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "仅 pending_verify 状态可执行验证，当前: " + c.getStatus());
    }
    String method = str(body.get("verificationMethod"), "").toUpperCase();
    if (!VERIFY_METHODS.contains(method)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "验证方式非法（FR-6）：" + method + "，可选 " + VERIFY_METHODS);
    }
    String effectiveness = str(body.get("effectiveness"), "").toUpperCase();
    if (!List.of("EFFECTIVE", "INEFFECTIVE").contains(effectiveness)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "有效性结论非法（FR-6）：" + effectiveness + "，可选 EFFECTIVE / INEFFECTIVE");
    }
    String comment = trim(body.get("comment"));
    if ("INEFFECTIVE".equals(effectiveness) && comment == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "结论为「无效」时须填写说明（comment）");
    }
    // FR-8/FR-21 · 电子签名门禁
    signatures.requireSignature("CAPA_VERIFY", id, signatureBody(body), ip, ua);

    Map<String, Object> before = toView(c, LocalDate.now());
    String from = c.getStatus();
    c.setVerificationMethod(method);
    c.setEffectiveness(effectiveness);
    c.setVerifyComment(comment);
    c.setVerifiedBy(CurrentUser.username());
    c.setVerifiedAt(OffsetDateTime.now());
    c.setUpdatedAt(OffsetDateTime.now());
    int reopened = 0;
    if ("EFFECTIVE".equals(effectiveness)) {
      c.setStatus("verified");
      c.setRejectReason(null);
    } else {
      // FR-6 · 无效退回：状态置 rejected，原因留痕，行动项重开
      c.setStatus("rejected");
      c.setRejectReason(comment);
      for (CapaTask t : tasks.selectList(Wrappers.<CapaTask>lambdaQuery()
          .eq(CapaTask::getCapaId, id).eq(CapaTask::getDone, true))) {
        t.setDone(false);
        t.setDoneAt(null);
        t.setDoneBy(null);
        tasks.updateById(t);
        reopened++;
      }
    }
    capas.updateById(c);
    writeEvent(id, from, c.getStatus(), "验证(" + METHOD_LABEL.getOrDefault(method, method)
        + ") 结论=" + effectiveness + (comment == null ? "" : " · " + comment));
    audit.record("capa.verify", "capa", id, before, map(
        "status", c.getStatus(), "method", method, "effectiveness", effectiveness,
        "comment", comment, "reopenedTasks", reopened));
    Map<String, Object> out = detail(id);
    out.put("reopenedTasks", reopened);
    return out;
  }

  /* ==================== FR-8 关闭 ==================== */

  /** 关闭 CAPA：需电子签名（CAPA_CLOSE / APPROVED / ADMIN）。 */
  @Transactional
  public Map<String, Object> close(String id, Map<String, Object> rawBody, String ip, String ua) {
    Map<String, Object> body = rawBody == null ? Map.of() : rawBody;
    Capa c = require(id);
    if (!"verified".equals(c.getStatus())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "仅验证有效（verified）的 CAPA 可关闭，当前: " + c.getStatus()
              + "；若验证结论为无效，请先重开并完成行动项");
    }
    signatures.requireSignature("CAPA_CLOSE", id, signatureBody(body), ip, ua);
    Map<String, Object> before = toView(c, LocalDate.now());
    c.setStatus("closed");
    c.setClosedBy(CurrentUser.username());
    c.setClosedAt(OffsetDateTime.now());
    c.setUpdatedAt(OffsetDateTime.now());
    capas.updateById(c);
    writeEvent(id, "verified", "closed", trim(body.get("comment")));
    audit.record("capa.close", "capa", id, before, map("status", "closed"));
    return detail(id);
  }

  /* ==================== FR-18 内容修订（触发签名作废） ==================== */

  /**
   * 修订 CAPA 内容（标题 / 描述 / 根因 / 责任人 / 到期日 / 严重度）。
   * 实质变更会让记录修订号 +1，并作废该 CAPA 的全部有效签名（FR-18），需重新签名。
   */
  @Transactional
  public Map<String, Object> update(String id, Map<String, Object> rawBody) {
    Map<String, Object> body = rawBody == null ? Map.of() : rawBody;
    Capa c = require(id);
    if ("closed".equals(c.getStatus())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "CAPA 已关闭，不可修订");
    }
    Map<String, Object> before = toView(c, LocalDate.now());
    boolean changed = false;
    changed |= setIfPresent(c, "title", body.get("title"));
    changed |= setIfPresent(c, "description", body.get("description"));
    changed |= setIfPresent(c, "rootCause", body.get("rootCause"));
    changed |= setIfPresent(c, "owner", body.get("owner"));
    if (body.get("dueDate") != null) {
      LocalDate d = date(body.get("dueDate"));
      if (d == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "dueDate 格式应为 yyyy-MM-dd");
      if (!d.equals(c.getDueDate())) { c.setDueDate(d); changed = true; }
    }
    if (body.get("severity") != null) {
      String sev = str(body.get("severity"), "").toLowerCase();
      if (!SEVERITIES.contains(sev)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "严重度非法：" + sev);
      }
      if (!sev.equals(c.getSeverity())) { c.setSeverity(sev); changed = true; }
    }
    if (!changed) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未检测到内容变更");
    }
    c.setUpdatedAt(OffsetDateTime.now());
    capas.updateById(c);
    int voided = signatures.bumpRevision("CAPA", id, "CAPA 内容修订（" + CurrentUser.username() + "）");
    audit.record("capa.update", "capa", id, before, map(
        "after", toView(require(id), LocalDate.now()), "voidedSignatures", voided));
    Map<String, Object> out = detail(id);
    out.put("voidedSignatures", voided);
    return out;
  }

  /* ==================== FR-7 阻断门禁（供偏差 / 放行调用） ==================== */

  /** 偏差关联的未关闭 CAPA 编号清单。 */
  public List<String> openCapaIdsOfDeviation(String deviationId) {
    if (deviationId == null || deviationId.isBlank()) return List.of();
    return capas.selectList(Wrappers.<Capa>lambdaQuery()
            .eq(Capa::getSourceType, SOURCE_DEVIATION)
            .eq(Capa::getSourceId, deviationId)
            .ne(Capa::getStatus, "closed")).stream()
        .map(Capa::getId)
        .toList();
  }

  /** 偏差关联的未关闭 CAPA 编号清单（含报警来源 CAPA）。 */
  public List<String> openCapaIdsOfBatch(String batchId) {
    if (batchId == null || batchId.isBlank()) return List.of();
    List<String> devIds = deviations.selectList(Wrappers.<Deviation>lambdaQuery()
            .eq(Deviation::getBatchId, batchId)).stream()
        .map(Deviation::getId).toList();
    if (devIds.isEmpty()) return List.of();
    return capas.selectList(Wrappers.<Capa>lambdaQuery()
            .eq(Capa::getSourceType, SOURCE_DEVIATION)
            .in(Capa::getSourceId, devIds)
            .ne(Capa::getStatus, "closed")).stream()
        .map(Capa::getId)
        .toList();
  }

  /** FR-7 · 偏差关闭门禁。 */
  public void assertDeviationCloseAllowed(String deviationId) {
    List<String> open = openCapaIdsOfDeviation(deviationId);
    if (!open.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "偏差 " + deviationId + " 存在未关闭 CAPA，阻断关闭（FR-7）：" + open);
    }
  }

  /** FR-7 · 批量放行门禁。 */
  public void assertBatchReleaseAllowed(String batchId) {
    List<String> open = openCapaIdsOfBatch(batchId);
    if (!open.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "批次 " + batchId + " 存在未关闭 CAPA，阻断放行（FR-7）：" + open);
    }
  }

  /* ==================== 视图与工具 ==================== */

  public Capa require(String id) {
    Capa c = capas.selectById(id);
    if (c == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "CAPA 不存在: " + id);
    return c;
  }

  public static Map<String, Object> toView(Capa c, LocalDate today) {
    boolean overdue = isOverdue(c, today);
    boolean dueSoon = !overdue && isDueSoon(c, today);
    long daysLeft = c.getDueDate() == null ? 0 : ChronoUnit.DAYS.between(today, c.getDueDate());
    return map(
        "id", c.getId(),
        "sourceType", c.getSourceType(),
        "sourceLabel", SOURCE_LABEL.getOrDefault(c.getSourceType(), c.getSourceType()),
        "sourceId", c.getSourceId(),
        "type", c.getType(),
        "typeLabel", TYPE_LABEL.getOrDefault(c.getType(), c.getType()),
        "title", c.getTitle(),
        "description", c.getDescription(),
        "rootCause", c.getRootCause(),
        "owner", c.getOwner(),
        "dueDate", c.getDueDate() == null ? null : c.getDueDate().toString(),
        "daysLeft", daysLeft,
        "severity", c.getSeverity(),
        "status", c.getStatus(),
        "overdue", overdue,
        "dueSoon", dueSoon,
        "verificationMethod", c.getVerificationMethod(),
        "verificationMethodLabel", c.getVerificationMethod() == null ? null
            : METHOD_LABEL.getOrDefault(c.getVerificationMethod(), c.getVerificationMethod()),
        "effectiveness", c.getEffectiveness(),
        "verifyComment", c.getVerifyComment(),
        "rejectReason", c.getRejectReason(),
        "verifiedBy", c.getVerifiedBy(),
        "verifiedAt", c.getVerifiedAt() == null ? null : c.getVerifiedAt().toString(),
        "closedBy", c.getClosedBy(),
        "closedAt", c.getClosedAt() == null ? null : c.getClosedAt().toString(),
        "site", c.getSite(),
        "recordRevision", c.getRecordRevision(),
        "createdBy", c.getCreatedBy(),
        "createdAt", c.getCreatedAt() == null ? null : c.getCreatedAt().toString(),
        "updatedAt", c.getUpdatedAt() == null ? null : c.getUpdatedAt().toString());
  }

  public static Map<String, Object> taskView(CapaTask t) {
    return map(
        "id", t.getId(),
        "capaId", t.getCapaId(),
        "seq", t.getSeq(),
        "action", t.getAction(),
        "owner", t.getOwner(),
        "dueDate", t.getDueDate() == null ? null : t.getDueDate().toString(),
        "done", Boolean.TRUE.equals(t.getDone()),
        "doneAt", t.getDoneAt() == null ? null : t.getDoneAt().toString(),
        "doneBy", t.getDoneBy(),
        "evidence", t.getEvidence(),
        "remark", t.getRemark());
  }

  private List<Map<String, Object>> taskViews(String capaId) {
    return tasks.selectList(Wrappers.<CapaTask>lambdaQuery()
            .eq(CapaTask::getCapaId, capaId).orderByAsc(CapaTask::getSeq)).stream()
        .map(CapaService::taskView)
        .toList();
  }

  private Map<String, Object> taskSummary(String capaId) {
    List<CapaTask> rows = tasks.selectList(Wrappers.<CapaTask>lambdaQuery().eq(CapaTask::getCapaId, capaId));
    long done = rows.stream().filter(t -> Boolean.TRUE.equals(t.getDone())).count();
    return map("total", rows.size(), "done", done, "pending", rows.size() - done);
  }

  private List<Map<String, Object>> eventViews(String capaId) {
    return events.selectList(Wrappers.<CapaEvent>lambdaQuery()
            .eq(CapaEvent::getCapaId, capaId).orderByAsc(CapaEvent::getCreatedAt)).stream()
        .map(e -> map(
            "fromStatus", e.getFromStatus(), "toStatus", e.getToStatus(),
            "comment", e.getComment(), "operator", e.getOperator(),
            "createdAt", e.getCreatedAt() == null ? null : e.getCreatedAt().toString()))
        .toList();
  }

  private void writeEvent(String capaId, String from, String to, String comment) {
    CapaEvent e = new CapaEvent();
    e.setCapaId(capaId);
    e.setFromStatus(from);
    e.setToStatus(to);
    e.setComment(comment);
    e.setOperator(CurrentUser.username());
    e.setCreatedAt(OffsetDateTime.now());
    events.insert(e);
  }

  private boolean raiseOverdueAlarm(Capa c, LocalDate today) {
    String source = "CAPA 逾期";
    String content = c.getId() + " " + c.getTitle();
    long existing = alarms.selectCount(Wrappers.<Alarm>lambdaQuery()
        .eq(Alarm::getSource, source).eq(Alarm::getContent, content).eq(Alarm::getStatus, "unacked"));
    if (existing > 0) return false;
    long days = ChronoUnit.DAYS.between(c.getDueDate(), today);
    alarmService.raise(source, content,
        "critical".equals(c.getSeverity()) ? "critical" : "major",
        days + " 天", c.getDueDate().toString());
    return true;
  }

  static boolean isOverdue(Capa c, LocalDate today) {
    return c.getDueDate() != null && c.getDueDate().isBefore(today) && !"closed".equals(c.getStatus());
  }

  static boolean isDueSoon(Capa c, LocalDate today) {
    if (c.getDueDate() == null || "closed".equals(c.getStatus())) return false;
    long d = ChronoUnit.DAYS.between(today, c.getDueDate());
    return d >= 0 && d <= DUE_SOON_DAYS;
  }

  private String nextId() {
    String prefix = "CAPA-" + LocalDate.now().format(YYMMDD) + "-";
    long n = capas.selectCount(Wrappers.<Capa>lambdaQuery().likeRight(Capa::getId, prefix));
    return prefix + String.format("%03d", n + 1);
  }

  private boolean setIfPresent(Capa c, String field, Object value) {
    String v = trim(value);
    if (v == null) return false;
    switch (field) {
      case "title" -> { if (!v.equals(c.getTitle())) { c.setTitle(v); return true; } }
      case "description" -> { if (!v.equals(c.getDescription())) { c.setDescription(v); return true; } }
      case "rootCause" -> { if (!v.equals(c.getRootCause())) { c.setRootCause(v); return true; } }
      case "owner" -> { if (!v.equals(c.getOwner())) { c.setOwner(v); return true; } }
      default -> { return false; }
    }
    return false;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> signatureBody(Map<String, Object> body) {
    Object sig = body == null ? null : body.get("signature");
    return sig instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
  }

  private static Map<String, Object> castMap(Map<?, ?> m) {
    Map<String, Object> out = new LinkedHashMap<>();
    m.forEach((k, v) -> out.put(String.valueOf(k), v));
    return out;
  }

  private static long numOf(Object v) {
    return v instanceof Number n ? n.longValue() : 0;
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
