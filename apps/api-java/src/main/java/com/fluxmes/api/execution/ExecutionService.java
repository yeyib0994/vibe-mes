package com.fluxmes.api.execution;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxmes.api.alarm.AlarmService;
import com.fluxmes.api.audit.AuditService;
import com.fluxmes.api.common.ApiSupport;
import com.fluxmes.api.common.CurrentUser;
import com.fluxmes.api.entity.AppUser;
import com.fluxmes.api.entity.Batch;
import com.fluxmes.api.entity.BatchStep;
import com.fluxmes.api.entity.DowntimeEvent;
import com.fluxmes.api.entity.DowntimeReason;
import com.fluxmes.api.entity.Equipment;
import com.fluxmes.api.entity.EquipmentEvent;
import com.fluxmes.api.entity.StepReport;
import com.fluxmes.api.entity.WorkOrder;
import com.fluxmes.api.entity.WorkOrderAssignment;
import com.fluxmes.api.mapper.AppUserMapper;
import com.fluxmes.api.mapper.BatchMapper;
import com.fluxmes.api.mapper.BatchStepMapper;
import com.fluxmes.api.mapper.DowntimeEventMapper;
import com.fluxmes.api.mapper.DowntimeReasonMapper;
import com.fluxmes.api.mapper.EquipmentEventMapper;
import com.fluxmes.api.mapper.EquipmentMapper;
import com.fluxmes.api.mapper.StepReportMapper;
import com.fluxmes.api.mapper.WorkOrderAssignmentMapper;
import com.fluxmes.api.mapper.WorkOrderMapper;
import com.fluxmes.api.personnel.PersonnelService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
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
 * Phase I · 生产执行服务（工单 / 派工 / 报工 / 停机）。
 *
 * <p>设计要点（见 specs/production-execution/）：
 * <ul>
 *   <li>D1 工单与批次 N:1，继承批次产线/厂区/产品/配方版本<b>快照</b>，批次后续换版不影响已建工单；</li>
 *   <li>D2 批次 progress 由报工事实推导（与工序推进取较大值），不再允许无因果的手工改写；</li>
 *   <li>D3 停机计划/非计划分类挂在原因码上（CHANGEOVER/CLEANING/NO_ORDER 属计划性损失）；</li>
 *   <li>D6 停机事件与 equipment_event 时间窗合并，避免可用率被重复扣减；</li>
 *   <li>R5 数量统一 kg，换算只发生在展示层。</li>
 * </ul>
 */
@Service
public class ExecutionService {

  private static final Logger log = LoggerFactory.getLogger(ExecutionService.class);

  private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyMMdd");
  private static final ZoneOffset ZONE = ZoneOffset.ofHours(8);
  private static final String DEFAULT_UNIT = "kg";
  private static final String DEFAULT_SITE = "SITE-01";

  /** 工单状态机（FR-4）：单向流转，不可回退。 */
  private static final Map<String, String> NEXT_STATUS = Map.of(
      "CREATED", "RELEASED",
      "RELEASED", "RUNNING",
      "RUNNING", "FINISHED",
      "FINISHED", "CLOSED");

  private static final List<String> OPEN_STATUSES = List.of("CREATED", "RELEASED", "RUNNING");

  /** 计为停机的设备状态（与 EquipmentService OEE 口径一致）。 */
  private static final List<String> STOP_STATUSES = List.of("ALARM", "MAINTENANCE", "STOPPED");

  private final WorkOrderMapper orders;
  private final WorkOrderAssignmentMapper assignments;
  private final StepReportMapper reports;
  private final DowntimeEventMapper downtimes;
  private final DowntimeReasonMapper reasons;
  private final BatchMapper batches;
  private final BatchStepMapper batchSteps;
  private final EquipmentMapper equipment;
  private final EquipmentEventMapper equipmentEvents;
  private final AppUserMapper users;
  private final PersonnelService personnel;
  private final AlarmService alarms;
  private final AuditService audit;
  private final ObjectMapper json;

  public ExecutionService(WorkOrderMapper orders, WorkOrderAssignmentMapper assignments,
      StepReportMapper reports, DowntimeEventMapper downtimes, DowntimeReasonMapper reasons,
      BatchMapper batches, BatchStepMapper batchSteps, EquipmentMapper equipment,
      EquipmentEventMapper equipmentEvents, AppUserMapper users, PersonnelService personnel,
      AlarmService alarms, AuditService audit, ObjectMapper json) {
    this.orders = orders;
    this.assignments = assignments;
    this.reports = reports;
    this.downtimes = downtimes;
    this.reasons = reasons;
    this.batches = batches;
    this.batchSteps = batchSteps;
    this.equipment = equipment;
    this.equipmentEvents = equipmentEvents;
    this.users = users;
    this.personnel = personnel;
    this.alarms = alarms;
    this.audit = audit;
    this.json = json;
  }

  /* ==================== 工单（FR-1 ~ FR-5） ==================== */

  /** GET /api/execution/orders —— 工单列表。 */
  public Map<String, Object> listOrders(String batchId, String line, String site, String status,
      String shift, String from, String to, Long page, Long size) {
    OffsetDateTime f = parseTime(from);
    OffsetDateTime t = parseTime(to);
    List<WorkOrder> all = orders.selectList(Wrappers.<WorkOrder>lambdaQuery()
        .eq(notBlank(batchId), WorkOrder::getBatchId, batchId)
        .eq(notBlank(line), WorkOrder::getLine, line)
        .eq(notBlank(site), WorkOrder::getSite, site)
        .eq(notBlank(status), WorkOrder::getStatus, status)
        .eq(notBlank(shift), WorkOrder::getShift, shift)
        .ge(f != null, WorkOrder::getPlanStart, f)
        .le(t != null, WorkOrder::getPlanStart, t)
        .orderByDesc(WorkOrder::getCreatedAt));
    List<Map<String, Object>> items = all.stream().map(this::orderView).toList();
    Map<String, Object> out = ApiSupport.map(
        "generatedAt", ApiSupport.nowIso(),
        "total", items.size(),
        "orders", items);
    if (page != null && page > 0) {
      long s = Math.min(size == null || size <= 0 ? 20 : size, 50);
      int fromIdx = (int) Math.min((page - 1) * s, items.size());
      int toIdx = (int) Math.min(fromIdx + s, items.size());
      out.put("page", page);
      out.put("size", s);
      out.put("orders", items.subList(fromIdx, toIdx));
    }
    return out;
  }

  /** POST /api/execution/orders —— 建工单（继承批次快照，FR-1~FR-3）。 */
  @Transactional
  public Map<String, Object> createOrder(Map<String, Object> body) {
    String batchId = str(body.get("batchId"));
    if (isBlank(batchId)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "batchId 必填");
    }
    Batch b = batches.selectById(batchId);
    if (b == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "批次不存在: " + batchId);
    }
    if (Boolean.TRUE.equals(b.getReleased())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "批次已放行，档案只读，禁止新建工单（C2）");
    }
    BigDecimal planQty = dec(body.get("planQty"));
    if (planQty == null || planQty.compareTo(BigDecimal.ZERO) <= 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "planQty 必填且须大于 0");
    }
    OffsetDateTime planStart = parseTime(body.get("planStart"));
    OffsetDateTime planEnd = parseTime(body.get("planEnd"));
    if (planStart != null && planEnd != null && !planEnd.isAfter(planStart)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "planEnd 必须晚于 planStart");
    }

    WorkOrder o = new WorkOrder();
    o.setId(nextOrderId());
    o.setBatchId(batchId);
    // D1 · 快照：产线/厂区/产品/配方版本固定于建单时刻
    o.setLine(notBlank(str(body.get("line"))) ? str(body.get("line")) : b.getLine());
    o.setSite(notBlank(str(body.get("site"))) ? str(body.get("site"))
        : (notBlank(b.getSite()) ? b.getSite() : fallbackSite()));
    o.setProduct(b.getProduct());
    o.setRecipeCode(b.getRecipe());
    o.setRecipeVersion(b.getRecipeVersion());
    o.setPlanQty(planQty);
    o.setUnit(notBlank(str(body.get("unit"))) ? str(body.get("unit")) : DEFAULT_UNIT);
    o.setPlanStart(planStart);
    o.setPlanEnd(planEnd);
    Integer planMinutes = intOrNull(body.get("planMinutes"));
    if (planMinutes == null && planStart != null && planEnd != null) {
      planMinutes = (int) Duration.between(planStart, planEnd).toMinutes();
    }
    o.setPlanMinutes(planMinutes);
    o.setShift(notBlank(str(body.get("shift"))) ? str(body.get("shift")) : "DAY");
    o.setStatus("CREATED");
    o.setInputQty(BigDecimal.ZERO);
    o.setGoodQty(BigDecimal.ZERO);
    o.setScrapQty(BigDecimal.ZERO);
    o.setSource("MANUAL");
    o.setCreatedBy(CurrentUser.username());
    o.setRemark(str(body.get("remark")));
    o.setCreatedAt(OffsetDateTime.now());
    o.setUpdatedAt(OffsetDateTime.now());
    orders.insert(o);
    audit.record("execution.order.create", "work_order", o.getId(), null, orderView(o));
    return orderView(o);
  }

  /** GET /api/execution/orders/{id} —— 详情（派工 + 报工 + 停机）。 */
  public Map<String, Object> orderDetail(String id) {
    WorkOrder o = requireOrder(id);
    return ApiSupport.map(
        "generatedAt", ApiSupport.nowIso(),
        "order", orderView(o),
        "assignments", assignmentsOf(id).stream().map(this::assignmentView).toList(),
        "reports", reportsOf(id).stream().map(this::reportView).toList(),
        "downtime", downtimeOfOrder(id).stream().map(this::downtimeView).toList(),
        "nextStatus", NEXT_STATUS.get(o.getStatus()));
  }

  /**
   * POST /api/execution/orders/{id}/{target} —— 状态流转（FR-4）。
   * release / start / finish / close，非法回退与跳级返回 409。
   */
  @Transactional
  public Map<String, Object> transition(String id, String target) {
    WorkOrder o = requireOrder(id);
    String next = normalizeTarget(target);
    if (!next.equals(NEXT_STATUS.get(o.getStatus()))) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "非法流转：" + o.getStatus() + " → " + next + "（期望 " + NEXT_STATUS.get(o.getStatus()) + "）");
    }
    Map<String, Object> before = orderView(o);
    OffsetDateTime now = OffsetDateTime.now();
    switch (next) {
      case "RELEASED" -> o.setReleasedAt(now);
      case "RUNNING" -> { /* 开工无额外字段 */ }
      case "FINISHED" -> {
        o.setFinishedAt(now);
        summarize(o);
      }
      case "CLOSED" -> o.setClosedAt(now);
      default -> { }
    }
    o.setStatus(next);
    o.setUpdatedAt(now);
    orders.updateById(o);

    Map<String, Object> after = orderView(o);
    audit.record("execution.order." + next.toLowerCase(), "work_order", id, before, after);
    Map<String, Object> out = ApiSupport.map("generatedAt", ApiSupport.nowIso(), "order", after);
    if ("FINISHED".equals(next)) {
      BigDecimal plan = o.getPlanQty() == null ? BigDecimal.ZERO : o.getPlanQty();
      if (plan.compareTo(BigDecimal.ZERO) > 0) {
        double dev = o.getGoodQty().subtract(plan)
            .divide(plan, 6, RoundingMode.HALF_UP).doubleValue() * 100;
        out.put("goodQtyDeviationPct", ApiSupport.r1(dev));
        boolean needsConfirm = Math.abs(dev) > 5.0;
        out.put("confirmNeeded", needsConfirm);
        if (needsConfirm) {
          out.put("confirmNote", "完工合格量与计划量偏差 " + ApiSupport.r1(dev)
              + "%（>5%），请值班长确认（FR-5，不阻断）");
        }
      }
    }
    return out;
  }

  /* ==================== 派工（FR-6 ~ FR-9） ==================== */

  /**
   * POST /api/execution/orders/{id}/dispatch —— 派工。
   * 逐人校验账号启用 + 岗位资质 + 有效健康证（渐进启用：该能力项已有人持证才强制），
   * 并在未确认的情况下拒绝同一人同时段重复派工（FR-8）。
   */
  @Transactional
  public Map<String, Object> dispatch(String id, Map<String, Object> body) {
    WorkOrder o = requireOrder(id);
    if (!OPEN_STATUSES.contains(o.getStatus())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "工单 " + o.getStatus() + " 状态不可派工");
    }
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows = body.get("assignments") instanceof List<?> l
        ? (List<Map<String, Object>>) l : List.of();
    if (rows.isEmpty() && notBlank(str(body.get("username")))) {
      rows = List.of(body);
    }
    if (rows.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "assignments 必填（或直接提供 username）");
    }
    boolean confirmConflict = Boolean.TRUE.equals(body.get("confirmConflict"));

    List<Map<String, Object>> created = new ArrayList<>();
    List<String> missing = new ArrayList<>();
    List<String> conflicts = new ArrayList<>();

    for (Map<String, Object> row : rows) {
      String username = str(row.get("username"));
      if (isBlank(username)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "assignments[].username 必填");
      }
      AppUser u = users.selectOne(Wrappers.<AppUser>lambdaQuery().eq(AppUser::getUsername, username));
      if (u == null) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在: " + username);
      }
      if (!Boolean.TRUE.equals(u.getEnabled())) {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "账号已停用，不可派工: " + username);
      }
      String capability = notBlank(str(row.get("capability"))) ? str(row.get("capability"))
          : PersonnelService.CAP_WEIGHING;
      // 资质门禁（FR-6/FR-7）：缺证不放行；未纳入强制管控的能力项不阻断（与 G2 渐进启用一致）
      if (personnel.capabilityEnforced(capability) && !personnel.hasCapability(username, capability)) {
        missing.add(username + "（缺「" + capability + "」岗位资质）");
      }
      if (personnel.healthCertEnforced() && !personnel.hasValidHealthCert(username)) {
        missing.add(username + "（无有效健康证）");
      }
      // 同时段冲突（FR-8）
      List<WorkOrderAssignment> active = assignments.selectList(
          Wrappers.<WorkOrderAssignment>lambdaQuery()
              .eq(WorkOrderAssignment::getUsername, username)
              .isNull(WorkOrderAssignment::getRevokedAt)
              .ne(WorkOrderAssignment::getOrderId, id));
      for (WorkOrderAssignment a : active) {
        WorkOrder other = orders.selectById(a.getOrderId());
        if (other != null && OPEN_STATUSES.contains(other.getStatus())
            && windowsOverlap(o, other)) {
          conflicts.add(username + " 已被派至工单 " + other.getId());
        }
      }
      if (missing.isEmpty() && (conflicts.isEmpty() || confirmConflict)) {
        WorkOrderAssignment a = new WorkOrderAssignment();
        a.setOrderId(id);
        a.setUsername(username);
        a.setDisplayName(u.getDisplayName());
        a.setRoleInOrder(notBlank(str(row.get("roleInOrder"))) ? str(row.get("roleInOrder")) : "OPERATOR");
        a.setCapability(capability);
        a.setAssignedBy(CurrentUser.username());
        a.setAssignedAt(OffsetDateTime.now());
        assignments.insert(a);
        created.add(assignmentView(a));
        audit.record("execution.dispatch", "work_order", id, null,
            ApiSupport.map("username", username, "capability", capability,
                "roleInOrder", a.getRoleInOrder()));
      }
    }

    if (!missing.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,
          "派工失败，以下人员资质不合规： " + String.join("；", missing));
    }
    if (!conflicts.isEmpty() && !confirmConflict) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "人员时段冲突： " + String.join("；", conflicts) + "（如需强制派工请传 confirmConflict=true）");
    }
    return ApiSupport.map("generatedAt", ApiSupport.nowIso(), "orderId", id,
        "created", created, "assignments", assignmentsOf(id).stream().map(this::assignmentView).toList());
  }

  /** DELETE /api/execution/orders/{id}/dispatch/{aid} —— 撤销派工（软删留痕，FR-9）。 */
  @Transactional
  public Map<String, Object> revokeDispatch(String id, Long aid, String reason) {
    requireOrder(id);
    WorkOrderAssignment a = assignments.selectById(aid);
    if (a == null || !id.equals(a.getOrderId())) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "派工记录不存在: " + aid);
    }
    if (a.getRevokedAt() != null) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "该派工已撤销");
    }
    a.setRevokedBy(CurrentUser.username());
    a.setRevokedAt(OffsetDateTime.now());
    a.setRevokeReason(isBlank(reason) ? "未填写" : reason);
    assignments.updateById(a);
    audit.record("execution.dispatch.revoke", "work_order", id,
        ApiSupport.map("assignmentId", aid, "username", a.getUsername()),
        ApiSupport.map("revokedBy", a.getRevokedBy(), "reason", a.getRevokeReason()));
    return assignmentView(a);
  }

  /* ==================== 报工（FR-10 ~ FR-14） ==================== */

  /** GET /api/execution/orders/{id}/reports —— 报工明细。 */
  public List<Map<String, Object>> reportsOfPublic(String id) {
    requireOrder(id);
    return reportsOf(id).stream().map(this::reportView).toList();
  }

  /**
   * POST /api/execution/orders/{id}/reports —— 工序报工（FR-10 ~ FR-14）。
   * 幂等：同工单 + 工序 + 报工人 + 开工时间重复提交返回既有记录（NFR-2）。
   */
  @Transactional
  public Map<String, Object> report(String id, Map<String, Object> body) {
    WorkOrder o = requireOrder(id);
    if (!"RUNNING".equals(o.getStatus())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "工单 " + o.getStatus() + " 状态不可报工（须先开工）");
    }
    Batch b = batches.selectById(o.getBatchId());
    if (b != null && Boolean.TRUE.equals(b.getReleased())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "批次已放行，档案只读，禁止报工（C2 / FR-14）");
    }

    Integer stepNo = intOrNull(body.get("stepNo"));
    String username = CurrentUser.username();
    OffsetDateTime startedAt = parseTime(body.get("startedAt"));
    OffsetDateTime finishedAt = parseTime(body.get("finishedAt"));

    // 幂等（NFR-2）
    StepReport existing = reports.selectOne(Wrappers.<StepReport>lambdaQuery()
        .eq(StepReport::getOrderId, id)
        .eq(stepNo != null, StepReport::getStepNo, stepNo)
        .eq(StepReport::getUsername, username)
        .eq(startedAt != null, StepReport::getStartedAt, startedAt)
        .last("limit 1"));
    if (existing != null) {
      return ApiSupport.map("generatedAt", ApiSupport.nowIso(),
          "report", reportView(existing), "idempotent", true);
    }

    BigDecimal inputQty = decOrZero(body.get("inputQty"));
    BigDecimal goodQty = decOrZero(body.get("goodQty"));
    BigDecimal scrapQty = decOrZero(body.get("scrapQty"));
    if (goodQty.add(scrapQty).compareTo(inputQty) > 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "goodQty + scrapQty 不得超过 inputQty");
    }
    // FR-12 · 累计超计划 110% 须复核
    BigDecimal plan = o.getPlanQty() == null ? BigDecimal.ZERO : o.getPlanQty();
    if (plan.compareTo(BigDecimal.ZERO) > 0) {
      BigDecimal projected = sumGood(id).add(goodQty);
      BigDecimal cap = plan.multiply(new BigDecimal("1.10"));
      if (projected.compareTo(cap) > 0) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "累计合格量 " + projected.stripTrailingZeros().toPlainString() + " 超过计划量 110%"
                + "（计划 " + plan.stripTrailingZeros().toPlainString() + "），请复核数据");
      }
    }
    // FR-13 · 关键工序双人复核
    boolean critical = Boolean.TRUE.equals(body.get("critical")) || isCriticalStep(o, stepNo);
    String reviewer = str(body.get("reviewer"));
    if (critical) {
      if (isBlank(reviewer)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "关键工序报工须填写复核人 reviewer");
      }
      if (reviewer.equals(username)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "复核人不得为报工人本人（双人复核要求）");
      }
    }

    StepReport r = new StepReport();
    r.setOrderId(id);
    r.setBatchId(o.getBatchId());
    r.setStepNo(stepNo);
    r.setStepName(str(body.get("stepName")));
    r.setEquipment(str(body.get("equipment")));
    r.setUsername(username);
    r.setReviewer(reviewer);
    r.setReviewedAt(isBlank(reviewer) ? null : OffsetDateTime.now());
    r.setStartedAt(startedAt);
    r.setFinishedAt(finishedAt);
    BigDecimal durationMin = dec(body.get("durationMin"));
    if (durationMin == null && startedAt != null && finishedAt != null) {
      durationMin = BigDecimal.valueOf(Duration.between(startedAt, finishedAt).toMinutes());
    }
    r.setDurationMin(durationMin);
    r.setInputQty(inputQty);
    r.setGoodQty(goodQty);
    r.setScrapQty(scrapQty);
    r.setStdMinutes(dec(body.get("stdMinutes")));
    r.setCritical(critical);
    r.setSource("MANUAL");
    r.setRemark(str(body.get("remark")));
    r.setCreatedAt(OffsetDateTime.now());
    reports.insert(r);

    // 回写 batch_step（eBR 工序实际值与状态）
    if (b != null) {
      syncBatchStep(b.getId(), stepNo, r);
      advanceProgress(b, plan);
    }
    // 工单汇总
    summarize(o);
    o.setUpdatedAt(OffsetDateTime.now());
    orders.updateById(o);

    audit.record("execution.report", "work_order", id, null, reportView(r));
    return ApiSupport.map("generatedAt", ApiSupport.nowIso(),
        "report", reportView(r), "order", orderView(o), "idempotent", false);
  }

  /* ==================== 停机（FR-15 ~ FR-19） ==================== */

  /** GET /api/execution/downtime —— 停机事件列表。 */
  public List<Map<String, Object>> downtimeList(String equipmentCode, String orderId, String site,
      String from, String to) {
    OffsetDateTime f = parseTime(from);
    OffsetDateTime t = parseTime(to);
    return downtimes.selectList(Wrappers.<DowntimeEvent>lambdaQuery()
            .eq(notBlank(equipmentCode), DowntimeEvent::getEquipment, equipmentCode)
            .eq(notBlank(orderId), DowntimeEvent::getOrderId, orderId)
            .eq(notBlank(site), DowntimeEvent::getSite, site)
            .ge(f != null, DowntimeEvent::getStartedAt, f)
            .le(t != null, DowntimeEvent::getStartedAt, t)
            .orderByDesc(DowntimeEvent::getStartedAt))
        .stream().map(this::downtimeView).toList();
  }

  /** GET /api/execution/downtime/reasons —— 原因码字典（FR-16）。 */
  public List<Map<String, Object>> reasonDict() {
    return reasons.selectList(Wrappers.<DowntimeReason>lambdaQuery()
            .orderByAsc(DowntimeReason::getSortNo))
        .stream().map(r -> ApiSupport.map(
            "code", r.getCode(),
            "name", r.getName(),
            "category", r.getCategory(),
            "planned", "PLANNED".equals(r.getCategory()),
            "alarmThresholdMin", r.getAlarmThresholdMin(),
            "enabled", r.getEnabled(),
            "note", r.getNote())).toList();
  }

  /**
   * POST /api/execution/downtime —— 录入停机（FR-15 ~ FR-19）。
   * 与同设备已有停机事件时间窗合并（D6/FR-18），超过原因码阈值自动报警（FR-19）。
   */
  @Transactional
  public Map<String, Object> recordDowntime(Map<String, Object> body) {
    String reasonCode = str(body.get("reasonCode"));
    if (isBlank(reasonCode)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "reasonCode 必填");
    }
    DowntimeReason reason = reasons.selectById(reasonCode);
    if (reason == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知原因码: " + reasonCode);
    }
    if (!Boolean.TRUE.equals(reason.getEnabled())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "原因码已停用: " + reasonCode);
    }
    OffsetDateTime startedAt = parseTime(body.get("startedAt"));
    if (startedAt == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "startedAt 必填");
    }
    OffsetDateTime endedAt = parseTime(body.get("endedAt"));
    if (endedAt != null && endedAt.isBefore(startedAt)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "endedAt 不得早于 startedAt");
    }
    String equipmentCode = str(body.get("equipment"));
    String orderId = str(body.get("orderId"));
    WorkOrder order = notBlank(orderId) ? orders.selectById(orderId) : null;
    if (notBlank(orderId) && order == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "工单不存在: " + orderId);
    }

    String mergedFrom = null;
    // D6 · 同设备时间窗合并：重叠视为同一次停机，延长既有事件而不新建
    if (notBlank(equipmentCode)) {
      for (DowntimeEvent prev : downtimes.selectList(Wrappers.<DowntimeEvent>lambdaQuery()
          .eq(DowntimeEvent::getEquipment, equipmentCode))) {
        OffsetDateTime prevEnd = prev.getEndedAt() == null ? OffsetDateTime.now() : prev.getEndedAt();
        if (windowsTouch(startedAt, endedAt, prev.getStartedAt(), prevEnd)) {
          OffsetDateTime newStart = startedAt.isBefore(prev.getStartedAt()) ? startedAt : prev.getStartedAt();
          OffsetDateTime newEnd = endedAt == null ? null
              : (prevEnd.isAfter(endedAt) ? prevEnd : endedAt);
          prev.setStartedAt(newStart);
          prev.setEndedAt(newEnd);
          prev.setDurationMin(minutes(newStart, newEnd));
          prev.setMergedFrom(appendNote(prev.getMergedFrom(),
              "合并同设备重叠停机 #" + prev.getId()));
          downtimes.updateById(prev);
          audit.record("execution.downtime.merge", "downtime_event", String.valueOf(prev.getId()),
              null, downtimeView(prev));
          return ApiSupport.map("generatedAt", ApiSupport.nowIso(),
              "downtime", downtimeView(prev), "merged", true);
        }
      }
    }
    // FR-18 · 与设备状态事件（equipment_event）时间窗重叠说明（OEE 以停机事件为准，不重复扣减）
    if (notBlank(equipmentCode)) {
      for (EquipmentEvent ev : equipmentEvents.selectList(Wrappers.<EquipmentEvent>lambdaQuery()
          .eq(EquipmentEvent::getEquipmentCode, equipmentCode))) {
        if (!STOP_STATUSES.contains(ev.getToStatus()) || ev.getStartedAt() == null) continue;
        OffsetDateTime evEnd = ev.getEndedAt() == null ? OffsetDateTime.now() : ev.getEndedAt();
        if (windowsTouch(startedAt, endedAt, ev.getStartedAt(), evEnd)) {
          mergedFrom = appendNote(mergedFrom, "equipment_event#" + ev.getId()
              + "（" + ev.getToStatus() + "，OEE 以停机事件为准不重复计）");
        }
      }
    }

    DowntimeEvent d = new DowntimeEvent();
    d.setOrderId(orderId);
    d.setEquipment(equipmentCode);
    d.setLine(notBlank(str(body.get("line"))) ? str(body.get("line"))
        : (order != null ? order.getLine() : null));
    d.setSite(notBlank(str(body.get("site"))) ? str(body.get("site"))
        : (order != null ? order.getSite() : fallbackSite()));
    d.setShift(notBlank(str(body.get("shift"))) ? str(body.get("shift"))
        : (order != null ? order.getShift() : null));
    d.setReasonCode(reasonCode);
    // FR-17 · planned 由原因码 category 推导，不由调用方指定
    d.setPlanned("PLANNED".equals(reason.getCategory()));
    d.setStartedAt(startedAt);
    d.setEndedAt(endedAt);
    d.setDurationMin(minutes(startedAt, endedAt));
    d.setDescription(str(body.get("description")));
    d.setSource("MANUAL");
    d.setMergedFrom(mergedFrom);
    d.setCreatedBy(CurrentUser.username());
    d.setCreatedAt(OffsetDateTime.now());
    downtimes.insert(d);
    audit.record("execution.downtime", "downtime_event", String.valueOf(d.getId()), null,
        downtimeView(d));

    Map<String, Object> out = ApiSupport.map("generatedAt", ApiSupport.nowIso(),
        "downtime", downtimeView(d), "merged", false);

    // FR-19 · 长时停机自动报警
    int threshold = reason.getAlarmThresholdMin() == null ? 0 : reason.getAlarmThresholdMin();
    double dm = d.getDurationMin() == null ? 0 : d.getDurationMin().doubleValue();
    if (threshold > 0 && dm >= threshold) {
      Map<String, Object> alarm = alarms.raise(
          (notBlank(equipmentCode) ? equipmentCode : "停机 " + d.getId()),
          reason.getName() + "停机 " + ApiSupport.r1(dm) + " 分钟（阈值 " + threshold + " 分钟）",
          "major",
          ApiSupport.r1(dm) + "min",
          threshold + "min");
      if (alarm != null) {
        out.put("alarmId", alarm.get("id"));
        d.setMergedFrom(appendNote(d.getMergedFrom(), "报警 " + alarm.get("id")));
        downtimes.updateById(d);
      }
    }
    return out;
  }

  /* ==================== 内部：汇总与回写 ==================== */

  /** 工单汇总：按报工聚合投入/合格/废次品与实时长。 */
  private void summarize(WorkOrder o) {
    List<StepReport> rs = reportsOf(o.getId());
    BigDecimal input = BigDecimal.ZERO;
    BigDecimal good = BigDecimal.ZERO;
    BigDecimal scrap = BigDecimal.ZERO;
    BigDecimal mins = BigDecimal.ZERO;
    for (StepReport r : rs) {
      input = input.add(nz(r.getInputQty()));
      good = good.add(nz(r.getGoodQty()));
      scrap = scrap.add(nz(r.getScrapQty()));
      mins = mins.add(nz(r.getDurationMin()));
    }
    o.setInputQty(input);
    o.setGoodQty(good);
    o.setScrapQty(scrap);
    o.setActualMinutes((int) Math.round(mins.doubleValue()));
  }

  private BigDecimal sumGood(String orderId) {
    BigDecimal sum = BigDecimal.ZERO;
    for (StepReport r : reportsOf(orderId)) sum = sum.add(nz(r.getGoodQty()));
    return sum;
  }

  /** D2 · 批次进度：产量进度与工序进度取较大值，上限 100。 */
  private void advanceProgress(Batch b, BigDecimal plan) {
    if (plan == null || plan.compareTo(BigDecimal.ZERO) <= 0) return;
    int qtyPct = sumGood(b.getId()).multiply(new BigDecimal("100"))
        .divide(plan, 0, RoundingMode.DOWN).intValue();
    qtyPct = Math.max(0, Math.min(100, qtyPct));
    int cur = b.getProgress() == null ? 0 : b.getProgress();
    if (qtyPct > cur) {
      b.setProgress(qtyPct);
      b.setUpdatedAt(OffsetDateTime.now());
      batches.updateById(b);
    }
  }

  private void syncBatchStep(String batchId, Integer stepNo, StepReport r) {
    if (stepNo == null) return;
    BatchStep step = batchSteps.selectOne(Wrappers.<BatchStep>lambdaQuery()
        .eq(BatchStep::getBatchId, batchId)
        .eq(BatchStep::getStepNo, stepNo)
        .last("limit 1"));
    if (step == null) return;
    Map<String, Object> actual = new LinkedHashMap<>();
    actual.put("inputQty", r.getInputQty());
    actual.put("goodQty", r.getGoodQty());
    actual.put("scrapQty", r.getScrapQty());
    actual.put("durationMin", r.getDurationMin());
    actual.put("equipment", r.getEquipment());
    try {
      step.setActualParams(json.writeValueAsString(actual));
    } catch (Exception e) {
      step.setActualParams(String.valueOf(actual));
    }
    step.setStatus("DONE");
    step.setOperator(r.getUsername());
    step.setReviewer(r.getReviewer());
    step.setReviewedAt(r.getReviewedAt());
    step.setStartedAt(r.getStartedAt());
    step.setFinishedAt(r.getFinishedAt());
    batchSteps.updateById(step);
  }

  /** 工序是否标记为关键（来自 batch_step 名称含「关键」或配方步骤 critical 约定）。 */
  private boolean isCriticalStep(WorkOrder o, Integer stepNo) {
    if (stepNo == null) return false;
    BatchStep step = batchSteps.selectOne(Wrappers.<BatchStep>lambdaQuery()
        .eq(BatchStep::getBatchId, o.getBatchId())
        .eq(BatchStep::getStepNo, stepNo)
        .last("limit 1"));
    if (step == null) return false;
    String name = step.getStepName() == null ? "" : step.getStepName();
    return name.contains("关键") || name.contains("CCP") || name.contains("杀菌") || name.contains("灭菌");
  }

  private List<WorkOrderAssignment> assignmentsOf(String orderId) {
    return assignments.selectList(Wrappers.<WorkOrderAssignment>lambdaQuery()
        .eq(WorkOrderAssignment::getOrderId, orderId)
        .orderByAsc(WorkOrderAssignment::getId));
  }

  private List<StepReport> reportsOf(String orderId) {
    return reports.selectList(Wrappers.<StepReport>lambdaQuery()
        .eq(StepReport::getOrderId, orderId)
        .orderByAsc(StepReport::getStepNo)
        .orderByAsc(StepReport::getId));
  }

  private List<DowntimeEvent> downtimeOfOrder(String orderId) {
    return downtimes.selectList(Wrappers.<DowntimeEvent>lambdaQuery()
        .eq(DowntimeEvent::getOrderId, orderId)
        .orderByDesc(DowntimeEvent::getStartedAt));
  }

  /* ==================== 内部：视图 ==================== */

  private Map<String, Object> orderView(WorkOrder o) {
    List<WorkOrderAssignment> as = assignmentsOf(o.getId());
    long active = as.stream().filter(a -> a.getRevokedAt() == null).count();
    BigDecimal plan = o.getPlanQty() == null ? BigDecimal.ZERO : o.getPlanQty();
    int qtyPct = plan.compareTo(BigDecimal.ZERO) <= 0 ? 0
        : Math.min(100, nz(o.getGoodQty()).multiply(new BigDecimal("100"))
            .divide(plan, 0, RoundingMode.DOWN).intValue());
    return ApiSupport.map(
        "id", o.getId(),
        "batchId", o.getBatchId(),
        "line", o.getLine(),
        "site", o.getSite(),
        "product", o.getProduct(),
        "recipeCode", o.getRecipeCode(),
        "recipeVersion", o.getRecipeVersion(),
        "planQty", o.getPlanQty(),
        "unit", o.getUnit(),
        "planMinutes", o.getPlanMinutes(),
        "planStart", iso(o.getPlanStart()),
        "planEnd", iso(o.getPlanEnd()),
        "shift", o.getShift(),
        "status", o.getStatus(),
        "inputQty", o.getInputQty(),
        "goodQty", o.getGoodQty(),
        "scrapQty", o.getScrapQty(),
        "actualMinutes", o.getActualMinutes(),
        "progressPct", qtyPct,
        "plannedQtyLeft", ApiSupport.r3(Math.max(0, plan.subtract(nz(o.getGoodQty())).doubleValue())),
        "assigneeCount", active,
        "source", o.getSource(),
        "createdBy", o.getCreatedBy(),
        "createdAt", iso(o.getCreatedAt()),
        "releasedAt", iso(o.getReleasedAt()),
        "finishedAt", iso(o.getFinishedAt()),
        "closedAt", iso(o.getClosedAt()),
        "remark", o.getRemark());
  }

  private Map<String, Object> assignmentView(WorkOrderAssignment a) {
    return ApiSupport.map(
        "id", a.getId(),
        "orderId", a.getOrderId(),
        "username", a.getUsername(),
        "displayName", a.getDisplayName(),
        "roleInOrder", a.getRoleInOrder(),
        "capability", a.getCapability(),
        "assignedBy", a.getAssignedBy(),
        "assignedAt", iso(a.getAssignedAt()),
        "status", a.getRevokedAt() == null ? "ACTIVE" : "REVOKED",
        "revokedBy", a.getRevokedBy(),
        "revokedAt", iso(a.getRevokedAt()),
        "revokeReason", a.getRevokeReason());
  }

  private Map<String, Object> reportView(StepReport r) {
    return ApiSupport.map(
        "id", r.getId(),
        "orderId", r.getOrderId(),
        "batchId", r.getBatchId(),
        "stepNo", r.getStepNo(),
        "stepName", r.getStepName(),
        "equipment", r.getEquipment(),
        "username", r.getUsername(),
        "reviewer", r.getReviewer(),
        "reviewedAt", iso(r.getReviewedAt()),
        "startedAt", iso(r.getStartedAt()),
        "finishedAt", iso(r.getFinishedAt()),
        "durationMin", r.getDurationMin(),
        "inputQty", r.getInputQty(),
        "goodQty", r.getGoodQty(),
        "scrapQty", r.getScrapQty(),
        "stdMinutes", r.getStdMinutes(),
        "critical", r.getCritical(),
        "source", r.getSource(),
        "yieldPct", yieldPct(r),
        "remark", r.getRemark());
  }

  private Map<String, Object> downtimeView(DowntimeEvent d) {
    DowntimeReason r = d.getReasonCode() == null ? null : reasons.selectById(d.getReasonCode());
    return ApiSupport.map(
        "id", d.getId(),
        "orderId", d.getOrderId(),
        "equipment", d.getEquipment(),
        "line", d.getLine(),
        "site", d.getSite(),
        "shift", d.getShift(),
        "reasonCode", d.getReasonCode(),
        "reasonName", r == null ? d.getReasonCode() : r.getName(),
        "category", r == null ? null : r.getCategory(),
        "planned", d.getPlanned(),
        "startedAt", iso(d.getStartedAt()),
        "endedAt", iso(d.getEndedAt()),
        "durationMin", d.getDurationMin(),
        "description", d.getDescription(),
        "source", d.getSource(),
        "mergedFrom", d.getMergedFrom(),
        "createdBy", d.getCreatedBy());
  }

  private static Double yieldPct(StepReport r) {
    BigDecimal g = nz(r.getGoodQty());
    BigDecimal s = nz(r.getScrapQty());
    BigDecimal denom = g.add(s);
    if (denom.compareTo(BigDecimal.ZERO) <= 0) return null;
    return ApiSupport.pct1(g.multiply(new BigDecimal("100"))
        .divide(denom, 4, RoundingMode.HALF_UP).doubleValue());
  }

  /* ==================== 内部：helpers ==================== */

  private WorkOrder requireOrder(String id) {
    WorkOrder o = orders.selectById(id);
    if (o == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "工单不存在: " + id);
    }
    return o;
  }

  /** 工单号：WO-yyMMdd-NNN，当日递增且循环判重（避免并发/删除后撞号）。 */
  private String nextOrderId() {
    String date = LocalDate.now().format(YYMMDD);
    long seq = orders.selectCount(Wrappers.<WorkOrder>lambdaQuery()
        .likeRight(WorkOrder::getId, "WO-" + date)) + 1;
    String id = "WO-" + date + "-" + String.format("%03d", seq);
    while (orders.selectById(id) != null) {
      seq++;
      id = "WO-" + date + "-" + String.format("%03d", seq);
    }
    return id;
  }

  /** release/start/finish/close 或 RELEASED/RUNNING/FINISHED/CLOSED。 */
  private static String normalizeTarget(String target) {
    String t = target == null ? "" : target.trim().toUpperCase();
    return switch (t) {
      case "RELEASE", "RELEASED" -> "RELEASED";
      case "START", "RUNNING" -> "RUNNING";
      case "FINISH", "FINISHED" -> "FINISHED";
      case "CLOSE", "CLOSED" -> "CLOSED";
      default -> t;
    };
  }

  private static boolean windowsOverlap(WorkOrder a, WorkOrder b) {
    if (a.getPlanStart() == null || b.getPlanStart() == null) return true;   // 无计划时间视为可能冲突
    OffsetDateTime aEnd = a.getPlanEnd() == null ? a.getPlanStart().plusHours(8) : a.getPlanEnd();
    OffsetDateTime bEnd = b.getPlanEnd() == null ? b.getPlanStart().plusHours(8) : b.getPlanEnd();
    return a.getPlanStart().isBefore(bEnd) && b.getPlanStart().isBefore(aEnd);
  }

  private static boolean windowsTouch(OffsetDateTime s1, OffsetDateTime e1,
      OffsetDateTime s2, OffsetDateTime e2) {
    if (s1 == null || s2 == null) return false;
    OffsetDateTime end1 = e1 == null ? OffsetDateTime.now() : e1;
    OffsetDateTime end2 = e2 == null ? OffsetDateTime.now() : e2;
    return s1.isBefore(end2) && s2.isBefore(end1);
  }

  private static BigDecimal minutes(OffsetDateTime from, OffsetDateTime to) {
    if (from == null) return null;
    OffsetDateTime end = to == null ? OffsetDateTime.now() : to;
    return BigDecimal.valueOf(Math.max(0, Duration.between(from, end).toMinutes()));
  }

  private static String appendNote(String existing, String note) {
    if (note == null) return existing;
    return isBlank(existing) ? note : existing + "；" + note;
  }

  private String fallbackSite() {
    String s = CurrentUser.site();
    return isBlank(s) ? DEFAULT_SITE : s;
  }

  private static OffsetDateTime parseTime(Object v) {
    if (v == null || String.valueOf(v).isBlank()) return null;
    String s = String.valueOf(v).trim();
    try {
      return OffsetDateTime.parse(s);
    } catch (Exception ignored) {
      // fallthrough
    }
    try {
      return LocalDateTime.parse(s).atOffset(ZONE);
    } catch (Exception ignored) {
      // fallthrough
    }
    try {
      return LocalDate.parse(s).atStartOfDay().atOffset(ZONE);
    } catch (Exception ignored) {
      return null;
    }
  }

  private static String iso(OffsetDateTime t) {
    return t == null ? null : t.toString();
  }

  private static BigDecimal nz(BigDecimal v) {
    return v == null ? BigDecimal.ZERO : v;
  }

  private static BigDecimal decOrZero(Object v) {
    BigDecimal d = dec(v);
    return d == null ? BigDecimal.ZERO : d;
  }

  private static BigDecimal dec(Object v) {
    if (v == null || String.valueOf(v).isBlank()) return null;
    try {
      return new BigDecimal(String.valueOf(v).trim());
    } catch (Exception e) {
      return null;
    }
  }

  private static Integer intOrNull(Object v) {
    if (v == null || String.valueOf(v).isBlank()) return null;
    try {
      return Integer.valueOf(String.valueOf(v).trim());
    } catch (Exception e) {
      return null;
    }
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }

  private static boolean notBlank(String s) {
    return !isBlank(s);
  }

  private static String str(Object v) {
    return v == null ? null : String.valueOf(v);
  }

  /** 停机与设备维度：供 OeeService 复用（按设备前缀解析主数据）。 */
  Equipment equipmentOf(String code) {
    if (code == null) return null;
    Equipment e = equipment.selectById(code);
    if (e != null) return e;
    String prefix = code.contains(" ") ? code.substring(0, code.indexOf(' ')) : code;
    return equipment.selectById(prefix);
  }

  /** 按状态统计工单（供 OEE / 看板复用）。 */
  public List<WorkOrder> ordersByRange(OffsetDateTime from, OffsetDateTime to, String line, String site) {
    return orders.selectList(Wrappers.<WorkOrder>lambdaQuery()
            .eq(notBlank(line), WorkOrder::getLine, line)
            .eq(notBlank(site), WorkOrder::getSite, site)
            .orderByAsc(WorkOrder::getPlanStart))
        .stream()
        .filter(o -> inRange(o, from, to))
        .sorted(Comparator.comparing(WorkOrder::getId))
        .toList();
  }

  private static boolean inRange(WorkOrder o, OffsetDateTime from, OffsetDateTime to) {
    OffsetDateTime ref = o.getPlanStart() != null ? o.getPlanStart() : o.getCreatedAt();
    if (ref == null) return false;
    if (from != null && ref.isBefore(from)) return false;
    if (to != null && ref.isAfter(to)) return false;
    return true;
  }
}
