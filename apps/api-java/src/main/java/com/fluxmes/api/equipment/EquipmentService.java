package com.fluxmes.api.equipment;

import static com.fluxmes.api.common.ApiSupport.map;
import static com.fluxmes.api.common.ApiSupport.nowIso;
import static com.fluxmes.api.common.ApiSupport.num;
import static com.fluxmes.api.common.ApiSupport.r1;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fluxmes.api.audit.AuditService;
import com.fluxmes.api.common.CurrentUser;
import com.fluxmes.api.common.Roles;
import com.fluxmes.api.core.FixtureStore;
import com.fluxmes.api.entity.Batch;
import com.fluxmes.api.entity.Equipment;
import com.fluxmes.api.entity.EquipmentEvent;
import com.fluxmes.api.entity.EquipmentMetric;
import com.fluxmes.api.entity.MaintenanceOrder;
import com.fluxmes.api.integration.port.EquipmentMetricPort;
import com.fluxmes.api.mapper.BatchMapper;
import com.fluxmes.api.mapper.EquipmentEventMapper;
import com.fluxmes.api.mapper.EquipmentMapper;
import com.fluxmes.api.mapper.EquipmentMetricMapper;
import com.fluxmes.api.mapper.MaintenanceOrderMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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
 * H1 · 设备管理（equipment-management T1–T11）。
 *
 * 数据分层约定：
 * - 设备主数据、状态事件、维护工单写入 PostgreSQL（可变业务数据）；
 * - 实时工艺参数由 {@link EquipmentMetricPort} 采集、落 {@code equipment_metric}（Phase J / T12），
 *   本服务只做「PG 主数据 + 已采集时序」缝合，前端契约保持不变。
 *
 * 合规要点：
 * - T5 状态变更写 equipment_event + audit_log（C3）；
 * - T8 校准超期设备不可被新批次引用，其检验数据不得作为放行依据（C5）。
 */
@Service
public class EquipmentService {

  private static final Logger log = LoggerFactory.getLogger(EquipmentService.class);
  private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");
  /** 趋势窗口与目标点数：12 小时窗口降采样到 ≤48 点（章程质量要求「数据量 ≤1k 点」）。 */
  private static final int TREND_HOURS = 12;
  private static final int TREND_POINTS = 48;

  /** 设备状态枚举（内部业务状态）。 */
  public static final List<String> STATUSES =
      List.of("RUNNING", "IDLE", "CLEANING", "ALARM", "MAINTENANCE", "STOPPED");

  /**
   * 演示用校准到期覆盖：F-102 溶氧电极已超期（用于 T8 门禁演示），
   * 每次启动强制对齐，保证演示环境稳定可复现。
   */
  private static final Map<String, LocalDate> CALIBRATION_OVERRIDE = Map.of(
      "F-102", LocalDate.of(2026, 9, 1));

  private final EquipmentMapper equipment;
  private final EquipmentEventMapper events;
  private final MaintenanceOrderMapper orders;
  private final BatchMapper batches;
  private final com.fluxmes.api.mapper.AlarmMapper alarms;
  /** Phase J · 已采集的设备参数时序（Trend 的真实来源）。 */
  private final EquipmentMetricMapper metricMapper;
  /** Phase J · 设备参数采集端口（SCADA/OPC-UA 或 mock）；本服务只依赖接口。 */
  private final EquipmentMetricPort metricPort;
  private final FixtureStore fixtures;
  private final AuditService audit;
  /** Phase I · 真实 OEE（执行事实口径；取代 fixture 采集值兜底）。 */
  private final com.fluxmes.api.execution.OeeService oeeService;

  public EquipmentService(EquipmentMapper equipment, EquipmentEventMapper events,
      MaintenanceOrderMapper orders, BatchMapper batches,
      com.fluxmes.api.mapper.AlarmMapper alarms, EquipmentMetricMapper metricMapper,
      EquipmentMetricPort metricPort, FixtureStore fixtures, AuditService audit,
      com.fluxmes.api.execution.OeeService oeeService) {
    this.equipment = equipment;
    this.events = events;
    this.orders = orders;
    this.batches = batches;
    this.alarms = alarms;
    this.metricMapper = metricMapper;
    this.metricPort = metricPort;
    this.fixtures = fixtures;
    this.audit = audit;
    this.oeeService = oeeService;
  }

  /* ---------------- T3 · 种子导入（幂等） ---------------- */

  /** fixture 设备台账 → equipment 表；已存在则跳过，二次启动不重复插入。 */
  public void seed() {
    int created = 0;
    for (Map<String, Object> spec : fixtures.equipmentSpec()) {
      String code = str(spec.get("code"));
      if (code.isBlank() || equipment.selectById(code) != null) continue;
      Equipment e = new Equipment();
      e.setCode(code);
      e.setName(str(spec.get("name")));
      e.setModel(str(spec.get("type")));
      e.setCategory(categoryOf(code));
      e.setLocation(locationOf(code));
      e.setSiteCode("SITE-01");
      e.setVol(str(spec.get("vol")));
      e.setStatus(initialStatus(spec));
      e.setCriticality(criticalityOf(code));
      e.setRuntimeHours(bd(num(spec.get("runHours"), 0)));
      e.setMtbfHours(bd(num(spec.get("mtbf"), 0)));
      @SuppressWarnings("unchecked")
      Map<String, Object> maint = (Map<String, Object>) spec.get("maint");
      if (maint != null) {
        e.setLastMaintenanceAt(dateOf(maint.get("last")));
        e.setMaintenanceCycleDays((int) num(maint.get("cycleDays"), 90));
        e.setNextMaintenanceAt(dateOf(maint.get("next")));
      }
      e.setCalibrationItem(calibrationItemOf(code));
      e.setCalibrationDueAt(CALIBRATION_OVERRIDE.getOrDefault(code, LocalDate.now().plusDays(180)));
      e.setEnabled(true);
      e.setUpdatedAt(OffsetDateTime.now());
      equipment.insert(e);
      created++;
    }
    // 演示用校准到期：每次启动对齐，保证 T8 门禁演示可复现（幂等）
    for (Map.Entry<String, LocalDate> kv : CALIBRATION_OVERRIDE.entrySet()) {
      Equipment e = equipment.selectById(kv.getKey());
      if (e == null) continue;
      if (!kv.getValue().equals(e.getCalibrationDueAt())) {
        e.setCalibrationDueAt(kv.getValue());
        equipment.updateById(e);
      }
    }
    if (created > 0) log.info("equipment seed: {} created", created);
  }

  /* ---------------- T4 · 台账列表 ---------------- */

  /** 台账 + 状态筛选 + 关键字检索；展示数据（params / metrics）由 fixture 缝合。 */
  public Map<String, Object> fleet(String status, String keyword) {
    List<Equipment> rows = equipment.selectList(Wrappers.<Equipment>lambdaQuery()
        .eq(notBlank(status), Equipment::getStatus, status)
        .and(notBlank(keyword), q -> q
            .like(Equipment::getCode, keyword)
            .or().like(Equipment::getName, keyword)
            .or().like(Equipment::getModel, keyword)
            .or().like(Equipment::getLocation, keyword))
        .orderByAsc(Equipment::getCode));
    List<Map<String, Object>> items = new ArrayList<>();
    int running = 0;
    double oeeSum = 0;
    int idle = 0;
    int alarm = 0;
    int maintToday = 0;
    LocalDate today = LocalDate.now();
    for (Equipment e : rows) {
      Map<String, Object> view = mergeFixture(e);
      items.add(view);
      double oee = num(((Map<?, ?>) view.get("metrics")).get("oee"), 0);
      if ("RUNNING".equals(e.getStatus()) && oee > 0) {
        running++;
        oeeSum += oee;
      }
      if ("IDLE".equals(e.getStatus()) || "STOPPED".equals(e.getStatus())) idle++;
      if ("ALARM".equals(e.getStatus())) alarm++;
      if (e.getNextMaintenanceAt() != null && !e.getNextMaintenanceAt().isAfter(today)) maintToday++;
    }
    return map(
        "generatedAt", nowIso(),
        "equipment", items,
        "summary", map(
            "total", items.size(),
            "running", running,
            "idle", idle,
            "alarm", alarm,
            "oeeAvg", running > 0 ? r1(oeeSum / running) : 0,
            "maintToday", maintToday,
            "calibrationExpired", countCalibrationExpired()));
  }

  /* ---------------- T9 · 详情聚合 ---------------- */

  /** 单设备详情：参数趋势 + 关联报警 + 在制批次 + 最近维护记录 + 状态事件。 */
  public Map<String, Object> detail(String code) {
    Equipment e = require(code);
    Map<String, Object> spec = fixtureOf(code);
    long now = System.currentTimeMillis();
    List<Map<String, Object>> trend = buildTrend(code, spec);
    String prefix = code + " ";
    List<Map<String, Object>> alarms = this.alarms.selectList(
            Wrappers.<com.fluxmes.api.entity.Alarm>lambdaQuery()
                .likeRight(com.fluxmes.api.entity.Alarm::getSource, prefix))
        .stream().map(com.fluxmes.api.alarm.AlarmService::toView).toList();
    List<Map<String, Object>> activeBatches = batches.selectList(Wrappers.<Batch>lambdaQuery()
            .eq(Batch::getEquipment, code).orderByDesc(Batch::getCreatedAt))
        .stream().limit(5).map(b -> map(
            "id", b.getId(),
            "product", b.getProduct(),
            "status", b.getStatus(),
            "stage", b.getStage(),
            "progress", b.getProgress(),
            "released", b.getReleased())).toList();
    List<Map<String, Object>> recentOrders = orders.selectList(
            Wrappers.<MaintenanceOrder>lambdaQuery()
                .eq(MaintenanceOrder::getEquipmentCode, code)
                .orderByDesc(MaintenanceOrder::getCreatedAt))
        .stream().limit(5).map(this::orderView).toList();
    List<Map<String, Object>> recentEvents = events.selectList(
            Wrappers.<EquipmentEvent>lambdaQuery()
                .eq(EquipmentEvent::getEquipmentCode, code)
                .orderByDesc(EquipmentEvent::getStartedAt))
        .stream().limit(10).map(this::eventView).toList();
    return map(
        "generatedAt", nowIso(),
        "code", code,
        "equipment", mergeFixture(e),
        "trend", trend,
        // Phase J · 数据来源标识：MOCK 表示参数非真实采集，前端据此展示来源标签
        "dataSource", metricPort.dataSource(),
        "alarms", alarms,
        "activeBatches", activeBatches,
        "maintenanceOrders", recentOrders,
        "events", recentEvents,
        "calibration", calibrationView(e));
  }

  /* ---------------- Phase J · 趋势与实时参数（equipment_metric） ---------------- */

  /**
   * 趋势序列：读 {@code equipment_metric} 近 {@value #TREND_HOURS} 小时的采样，
   * 按等宽时间桶降采样到 ≤{@value #TREND_POINTS} 点。
   *
   * <p>此前这里直接调用 {@code genSeries()} 现场编造 24 个点——**刷新一次换一批随机数**，
   * 而且没有任何历史可查。改为读库后，趋势是真时序，且能被审计与追查。
   *
   * <p>返回项在原有契约（name/unit/lo/hi/series）之上追加
   * key/value/quality/dataSource/sampledAt，属向后兼容的增量字段。
   */
  private List<Map<String, Object>> buildTrend(String code, Map<String, Object> spec) {
    OffsetDateTime to = OffsetDateTime.now();
    OffsetDateTime from = to.minusHours(TREND_HOURS);
    List<EquipmentMetric> rows = metricMapper.selectList(
        Wrappers.<EquipmentMetric>lambdaQuery()
            .eq(EquipmentMetric::getEquipmentCode, code)
            .ge(EquipmentMetric::getSampledAt, from)
            .orderByAsc(EquipmentMetric::getSampledAt));
    if (rows.isEmpty()) return List.of();

    Map<String, List<EquipmentMetric>> byKey = new LinkedHashMap<>();
    for (EquipmentMetric r : rows) {
      byKey.computeIfAbsent(r.getMetricKey(), k -> new ArrayList<>()).add(r);
    }
    long bucketMs = Math.max(1, Duration.between(from, to).toMillis() / TREND_POINTS);

    List<Map<String, Object>> out = new ArrayList<>();
    // 按 fixture 参数顺序输出：前端以数组下标选参数（paramIdx），顺序必须稳定
    for (Map<String, Object> p : asList(spec.get("params"))) {
      String key = str(p.get("key"));
      List<EquipmentMetric> rs = byKey.get(key);
      if (rs == null || rs.isEmpty()) continue;
      EquipmentMetric last = rs.get(rs.size() - 1);
      out.add(map(
          "key", key,
          "name", last.getMetricName() == null ? p.get("name") : last.getMetricName(),
          "unit", last.getUnit() == null ? p.get("unit") : last.getUnit(),
          "lo", last.getLowerLimit() == null ? p.get("lo") : last.getLowerLimit(),
          "hi", last.getUpperLimit() == null ? p.get("hi") : last.getUpperLimit(),
          "value", last.getValue(),
          "quality", last.getQuality(),
          "dataSource", last.getSource(),
          "sampledAt", last.getSampledAt() == null ? null : last.getSampledAt().toString(),
          "series", bucketize(rs, from, bucketMs)));
    }
    return out;
  }

  /** 等宽时间桶内取**最后一条**采样（比均值更贴近实际读数），标签为采样时刻。 */
  private static List<Map<String, Object>> bucketize(List<EquipmentMetric> rows,
      OffsetDateTime from, long bucketMs) {
    Map<Long, EquipmentMetric> lastOfBucket = new LinkedHashMap<>();
    for (EquipmentMetric r : rows) {
      if (r.getSampledAt() == null || r.getValue() == null) continue;
      long idx = Math.max(0, Duration.between(from, r.getSampledAt()).toMillis() / bucketMs);
      lastOfBucket.put(idx, r);   // 同键覆盖 → 桶内最后一条
    }
    List<Map<String, Object>> out = new ArrayList<>();
    for (EquipmentMetric r : lastOfBucket.values()) {
      out.add(map(
          "t", r.getSampledAt().atZoneSameInstant(ZoneId.systemDefault()).format(HHMM),
          "v", r.getValue().doubleValue()));
    }
    return out;
  }

  /** 各指标的**最新**一次读数（按 metricKey 索引，后写覆盖先写）。 */
  private Map<String, EquipmentMetric> latestMetrics(String code) {
    List<EquipmentMetric> rows = metricMapper.selectList(
        Wrappers.<EquipmentMetric>lambdaQuery()
            .eq(EquipmentMetric::getEquipmentCode, code)
            .orderByAsc(EquipmentMetric::getSampledAt));
    Map<String, EquipmentMetric> byKey = new LinkedHashMap<>();
    for (EquipmentMetric r : rows) byKey.put(r.getMetricKey(), r);
    return byKey;
  }

  /* ---------------- T5 · 状态变更 ---------------- */

  /** 状态变更：写事件流水（结束上一段）+ 更新主数据 + 审计（C3）；工艺员及以上（C4）。 */
  public Map<String, Object> changeStatus(String code, Map<String, Object> body) {
    if (!CurrentUser.hasRole(Roles.OPERATOR)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "设备状态变更需要工艺员及以上角色（C4）");
    }
    Equipment e = require(code);
    String to = str(body.get("toStatus")).toUpperCase();
    if (!STATUSES.contains(to)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "非法目标状态：" + to + "（可选 " + String.join("/", STATUSES) + "）");
    }
    String from = e.getStatus();
    if (from != null && from.equals(to)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "设备已处于该状态：" + to);
    }
    String reason = str(body.get("reason"));
    OffsetDateTime now = OffsetDateTime.now();
    Map<String, Object> before = map("status", from);

    closeOpenEvent(code, now);
    EquipmentEvent ev = new EquipmentEvent();
    ev.setEquipmentCode(code);
    ev.setFromStatus(from);
    ev.setToStatus(to);
    ev.setReason(reason);
    ev.setOperator(com.fluxmes.api.common.CurrentUser.username());
    ev.setStartedAt(now);
    events.insert(ev);

    e.setStatus(to);
    e.setUpdatedAt(now);
    equipment.updateById(e);

    audit.record("equipment.status", "equipment", code, before,
        map("status", to, "reason", reason));
    log.info("equipment {} status {} -> {} by {}", code, from, to, ev.getOperator());
    return map("code", code, "fromStatus", from, "toStatus", to, "reason", reason, "changedAt", nowIso());
  }

  /* ---------------- T6 · 保养与校准预警 ---------------- */

  /** 保养到期/逾期 + 校准超期清单（驾驶舱与台账共用）。 */
  public Map<String, Object> alerts(int withinDays) {
    LocalDate today = LocalDate.now();
    LocalDate horizon = today.plusDays(withinDays);
    List<Equipment> all = equipment.selectList(Wrappers.<Equipment>lambdaQuery()
        .orderByAsc(Equipment::getStatus));
    List<Map<String, Object>> maintenance = new ArrayList<>();
    List<Map<String, Object>> calibration = new ArrayList<>();
    for (Equipment e : all) {
      if (e.getNextMaintenanceAt() != null && !e.getNextMaintenanceAt().isAfter(horizon)) {
        boolean overdue = e.getNextMaintenanceAt().isBefore(today);
        maintenance.add(map(
            "code", e.getCode(), "name", e.getName(),
            "nextMaintenanceAt", String.valueOf(e.getNextMaintenanceAt()),
            "cycleDays", e.getMaintenanceCycleDays(),
            "overdue", overdue,
            "daysRemaining", java.time.temporal.ChronoUnit.DAYS.between(today, e.getNextMaintenanceAt())));
      }
      if (isCalibrationExpired(e)) {
        calibration.add(map(
            "code", e.getCode(), "name", e.getName(),
            "calibrationItem", e.getCalibrationItem(),
            "calibrationDueAt", String.valueOf(e.getCalibrationDueAt()),
            "daysOverdue", e.getCalibrationDueAt() == null ? 0
                : java.time.temporal.ChronoUnit.DAYS.between(e.getCalibrationDueAt(), today)));
      }
    }
    return map(
        "generatedAt", nowIso(),
        "withinDays", withinDays,
        "maintenanceOverdue", maintenance.stream().filter(m -> Boolean.TRUE.equals(m.get("overdue"))).count(),
        "maintenanceDue", maintenance.stream().filter(m -> !Boolean.TRUE.equals(m.get("overdue"))).count(),
        "calibrationExpired", calibration.size(),
        "maintenance", maintenance,
        "calibration", calibration);
  }

  /** 校准是否有效（null 表示未纳入计量管理，视为有效以兼容历史数据）。 */
  public boolean isCalibrationExpired(Equipment e) {
    return e != null && e.getCalibrationDueAt() != null && e.getCalibrationDueAt().isBefore(LocalDate.now());
  }

  private long countCalibrationExpired() {
    return equipment.selectList(null).stream().filter(this::isCalibrationExpired).count();
  }

  /* ---------------- T7 · 维护工单 ---------------- */

  public List<Map<String, Object>> maintenanceOrders(String code, String status) {
    return orders.selectList(Wrappers.<MaintenanceOrder>lambdaQuery()
            .eq(notBlank(code), MaintenanceOrder::getEquipmentCode, code)
            .eq(notBlank(status), MaintenanceOrder::getStatus, status)
            .orderByDesc(MaintenanceOrder::getCreatedAt))
        .stream().map(this::orderView).toList();
  }

  /** 创建工单：设备须存在且启用；计划日期默认明天；值班长及以上（C4）。 */
  public Map<String, Object> createOrder(Map<String, Object> body) {
    if (!CurrentUser.hasRole(Roles.SUPERVISOR)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "创建维护工单需要值班长及以上角色（C4）");
    }
    String code = str(body.get("equipmentCode"));
    Equipment e = require(code);
    String type = str(body.get("type")).toUpperCase();
    if (type.isBlank()) type = "PREVENTIVE";
    if (!List.of("PREVENTIVE", "CORRECTIVE", "CALIBRATION").contains(type)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "非法工单类型：" + type);
    }
    MaintenanceOrder o = new MaintenanceOrder();
    o.setId(nextOrderId());
    o.setEquipmentCode(code);
    o.setType(type);
    o.setStatus("OPEN");
    o.setPlanDate(dateOf(body.get("planDate")) == null ? LocalDate.now().plusDays(1)
        : dateOf(body.get("planDate")));
    o.setRemark(str(body.get("remark")));
    o.setCreatedBy(com.fluxmes.api.common.CurrentUser.username());
    o.setCreatedAt(OffsetDateTime.now());
    orders.insert(o);
    audit.record("equipment.maintenance.create", "maintenance_order", o.getId(),
        null, map("equipmentCode", code, "type", type, "planDate", String.valueOf(o.getPlanDate())));
    return orderView(o);
  }

  /**
   * 完成工单：回填实际运行小时、按周期顺延下次保养日；
   * CALIBRATION 类工单同时推进校准有效期（FR-7 + T8 闭环）。
   */
  public Map<String, Object> completeOrder(String id, Map<String, Object> body) {
    MaintenanceOrder o = orders.selectById(id);
    if (o == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "维护工单不存在: " + id);
    }
    if ("DONE".equals(o.getStatus())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "工单已完成，不可重复执行");
    }
    if ("CANCELLED".equals(o.getStatus())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "工单已取消，不可完成");
    }
    Equipment e = require(o.getEquipmentCode());
    BigDecimal before = e.getRuntimeHours();
    BigDecimal after = body.get("runtimeHours") == null ? before
        : new BigDecimal(str(body.get("runtimeHours")));
    if (after.signum() < 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "运行小时不能为负数");
    }
    OffsetDateTime now = OffsetDateTime.now();
    Map<String, Object> beforeSnap = map("runtimeHours", before,
        "nextMaintenanceAt", str(e.getNextMaintenanceAt()), "status", o.getStatus());

    o.setStatus("DONE");
    o.setRuntimeBefore(before);
    o.setRuntimeAfter(after);
    o.setDoneAt(now);
    o.setDoneBy(com.fluxmes.api.common.CurrentUser.username());
    if (body.get("remark") != null) o.setRemark(str(body.get("remark")));
    orders.updateById(o);

    e.setRuntimeHours(after);
    e.setLastMaintenanceAt(now.toLocalDate());
    int cycle = e.getMaintenanceCycleDays() == null || e.getMaintenanceCycleDays() <= 0
        ? 90 : e.getMaintenanceCycleDays();
    e.setMaintenanceCycleDays(cycle);
    e.setNextMaintenanceAt(now.toLocalDate().plusDays(cycle));
    if ("CALIBRATION".equals(o.getType())) {
      // 校准完工 → 有效期顺延一年，解除 T8 门禁
      e.setCalibrationDueAt(now.toLocalDate().plusDays(365));
    }
    if ("ALARM".equals(e.getStatus()) && !"CALIBRATION".equals(o.getType())) {
      e.setStatus("RUNNING");
      closeOpenEvent(e.getCode(), now);
    }
    e.setUpdatedAt(now);
    equipment.updateById(e);

    audit.record("equipment.maintenance.done", "maintenance_order", id, beforeSnap,
        map("runtimeHours", after, "nextMaintenanceAt", str(e.getNextMaintenanceAt()),
            "calibrationDueAt", str(e.getCalibrationDueAt()), "status", "DONE"));
    return map("order", orderView(o), "equipment", mergeFixture(e));
  }

  /* ---------------- FR-10 · 可选设备（批次建单） ---------------- */

  /** 仅列出启用、非故障且校准在有效期内的设备（批次档案引用约束）。 */
  public List<Map<String, Object>> available(String line) {
    return equipment.selectList(Wrappers.<Equipment>lambdaQuery()
            .eq(Equipment::getEnabled, true)
            .notIn(Equipment::getStatus, List.of("ALARM", "STOPPED", "MAINTENANCE"))
            .eq(notBlank(line), Equipment::getLine, line)
            .orderByAsc(Equipment::getCode))
        .stream().filter(e -> !isCalibrationExpired(e)).map(e -> map(
            "code", e.getCode(),
            "name", e.getName(),
            "category", e.getCategory(),
            "status", e.getStatus(),
            "criticality", e.getCriticality(),
            "calibrationDueAt", str(e.getCalibrationDueAt()))).toList();
  }

  /* ---------------- T8 · 校准阻断门禁 ---------------- */

  /**
   * 校准有效性门禁：新批次引用超期计量设备时拒绝开工（409）。
   * 未纳入计量管理的设备（calibration_due_at 为空）放行。
   */
  public void assertCalibrationValid(String code) {
    if (code == null || code.isBlank()) return;
    Equipment e = resolveEquipment(code);
    // 未纳入计量管理的设备（台账无记录）不阻断，避免历史批次引用设备名称时被误拒
    if (e == null) return;
    if (Boolean.FALSE.equals(e.getEnabled())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "设备已停用，不可用于生产: " + code);
    }
    if (isCalibrationExpired(e)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "设备 " + code + " 校准已于 " + e.getCalibrationDueAt() + " 到期，须完成校准后方可开工（C5）");
    }
  }

  /** 放行门禁：批次主设备校准超期时，其检验数据不得作为放行依据（FR-8）。 */
  public void assertReleaseAllowed(String equipmentCode) {
    if (equipmentCode == null || equipmentCode.isBlank()) return;
    Equipment e = resolveEquipment(equipmentCode);
    if (e != null && isCalibrationExpired(e)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "批次主设备 " + equipmentCode + " 校准超期（" + e.getCalibrationDueAt()
              + "），检验数据不得作为放行依据（C5 / FR-8）");
    }
  }

  /* ---------------- FR-9 · 设备 OEE 三因子 ---------------- */

  /**
   * Phase I · 设备 OEE（FR-9 / FR-20）——改由<b>执行事实</b>计算：
   * 可用率 = 实际作业工时 ÷（实际作业工时 + 计划外停机工时），
   * 性能率 = 标准工时 ÷ 实际作业工时，良品率 = 合格 ÷（合格 + 废次品）。
   *
   * <p>计划外停机优先取 {@code downtime_event}，未被其覆盖的停机段回落 {@code equipment_event}
   * （FR-18：同一次停机不重复扣减）。<b>已移除</b>原「168h 窗口推算可用率 + fixture 采集值兜底」，
   * 数据不足时因子为 {@code null} 且 {@code dataSufficient=false}。
   */
  public Map<String, Object> oee(String code) {
    require(code);
    Map<String, Object> real = oeeService.compute(null, null, code, null, null, null);
    List<EquipmentEvent> recent = events.selectList(Wrappers.<EquipmentEvent>lambdaQuery()
        .eq(EquipmentEvent::getEquipmentCode, code)
        .orderByDesc(EquipmentEvent::getStartedAt));
    double downtimeHours = recent.stream()
        .filter(ev -> List.of("ALARM", "MAINTENANCE", "STOPPED").contains(ev.getToStatus()))
        .filter(ev -> ev.getStartedAt() != null)
        .mapToDouble(ev -> {
          OffsetDateTime end = ev.getEndedAt() == null ? OffsetDateTime.now() : ev.getEndedAt();
          return java.time.Duration.between(ev.getStartedAt(), end).toMinutes() / 60.0;
        }).sum();
    String note = real.get("note") == null ? String.valueOf(real.get("missing") == null
        ? "按执行事实计算（设备维度：作业/停机/报工）"
        : real.get("missing")) : String.valueOf(real.get("note"));
    return map(
        "code", code,
        "availability", real.get("availability"),
        "performance", real.get("performance"),
        "quality", real.get("quality"),
        "oee", real.get("oee"),
        "downtimeHours", r1(downtimeHours),
        "eventCount", recent.size(),
        "dataSufficient", real.get("dataSufficient"),
        "missing", real.get("missing"),
        "runMinutes", real.get("runMinutes"),
        "stdMinutes", real.get("stdMinutes"),
        "unplannedStopMinutes", real.get("unplannedStopMinutes"),
        "plannedMinutesDerived", real.get("plannedMinutesDerived"),
        "stopSource", real.get("stopSource"),
        "formula", real.get("formula"),
        "note", note);
  }

  /* ---------------- views ---------------- */

  /** PG 主数据 + fixture 展示数据缝合：PG 的 status 覆盖 fixture 的 health。 */
  private Map<String, Object> mergeFixture(Equipment e) {
    Map<String, Object> spec = fixtureOf(e.getCode());
    Map<String, Object> view = new LinkedHashMap<>(spec);
    view.put("health", healthOf(e.getStatus()));
    view.put("status", e.getStatus());
    view.put("criticality", e.getCriticality());
    view.put("category", e.getCategory());
    view.put("site", e.getSiteCode());
    view.put("runHours", e.getRuntimeHours());
    view.put("mtbf", e.getMtbfHours());
    view.put("model", e.getModel());
    view.put("location", e.getLocation());
    view.put("line", e.getLine());
    view.put("enabled", e.getEnabled());
    if (view.get("maint") == null) view.put("maint", Map.of());
    @SuppressWarnings("unchecked")
    Map<String, Object> maint = new LinkedHashMap<>(
        (Map<String, Object>) view.getOrDefault("maint", Map.of()));
    maint.put("last", str(e.getLastMaintenanceAt()));
    maint.put("next", str(e.getNextMaintenanceAt()));
    maint.put("cycleDays", e.getMaintenanceCycleDays());
    view.put("maint", maint);
    view.put("calibration", calibrationView(e));
    view.put("maintenanceDue", e.getNextMaintenanceAt() != null
        && !e.getNextMaintenanceAt().isAfter(LocalDate.now()));

    // Phase J：实时参数以**已采集**读数为准。此前这里是 fixture 静态值直传——
    // 参数卡上显示的数字从来没有变过，却被当成"实时监控"。若尚无采集数据（如
    // 刚启动、或 SCADA 断线中），保留 fixture 值但标注 dataSource 缺失，不伪造。
    Map<String, EquipmentMetric> live = latestMetrics(e.getCode());
    Object rawParams = view.get("params");
    if (rawParams instanceof List<?> ps && !ps.isEmpty()) {
      List<Map<String, Object>> merged = new ArrayList<>();
      for (Object o : ps) {
        if (!(o instanceof Map<?, ?> pm)) continue;
        @SuppressWarnings("unchecked")
        Map<String, Object> p = new LinkedHashMap<>((Map<String, Object>) pm);
        EquipmentMetric m = live.get(str(p.get("key")));
        if (m != null && m.getValue() != null) {
          p.put("value", m.getValue());
          p.put("quality", m.getQuality());
          p.put("dataSource", m.getSource());
          p.put("sampledAt", m.getSampledAt() == null ? null : m.getSampledAt().toString());
        } else {
          p.put("quality", null);
          p.put("dataSource", null);
        }
        merged.add(p);
      }
      view.put("params", merged);
    }
    view.put("paramsDataSource", metricPort.dataSource());
    return view;
  }

  private Map<String, Object> calibrationView(Equipment e) {
    boolean expired = isCalibrationExpired(e);
    return map(
        "item", e.getCalibrationItem(),
        "dueAt", str(e.getCalibrationDueAt()),
        "expired", expired,
        "managed", e.getCalibrationDueAt() != null,
        "daysRemaining", e.getCalibrationDueAt() == null ? null
            : java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), e.getCalibrationDueAt()));
  }

  private Map<String, Object> orderView(MaintenanceOrder o) {
    return map(
        "id", o.getId(),
        "equipmentCode", o.getEquipmentCode(),
        "type", o.getType(),
        "status", o.getStatus(),
        "planDate", str(o.getPlanDate()),
        "runtimeBefore", o.getRuntimeBefore(),
        "runtimeAfter", o.getRuntimeAfter(),
        "doneAt", str(o.getDoneAt()),
        "doneBy", o.getDoneBy(),
        "createdBy", o.getCreatedBy(),
        "createdAt", str(o.getCreatedAt()),
        "remark", o.getRemark());
  }

  private Map<String, Object> eventView(EquipmentEvent ev) {
    long minutes = ev.getStartedAt() == null ? 0
        : java.time.Duration.between(ev.getStartedAt(),
            ev.getEndedAt() == null ? OffsetDateTime.now() : ev.getEndedAt()).toMinutes();
    return map(
        "id", String.valueOf(ev.getId()),
        "fromStatus", ev.getFromStatus(),
        "toStatus", ev.getToStatus(),
        "reason", ev.getReason(),
        "operator", ev.getOperator(),
        "startedAt", str(ev.getStartedAt()),
        "endedAt", str(ev.getEndedAt()),
        "durationMin", minutes);
  }

  /**
   * 按引用值解析设备：批次档案里的 equipment 常写成「编号 + 名称」（如 F-101 发酵罐 #1），
   * 而设备台账主键是纯编号。先整体匹配，再退化为编号前缀匹配；都落空则返回 null。
   */
  public Equipment resolveEquipment(String ref) {
    if (ref == null || ref.isBlank()) return null;
    Equipment e = equipment.selectById(ref);
    if (e != null) return e;
    String code = ref.split(" ")[0];
    return code.isBlank() ? null : equipment.selectById(code);
  }

  private Equipment require(String code) {
    Equipment e = equipment.selectById(code);
    if (e == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "设备不存在: " + code);
    }
    return e;
  }

  /** 关闭该设备上一条未结束的状态事件，用于计算停机时长。 */
  private void closeOpenEvent(String code, OffsetDateTime at) {
    List<EquipmentEvent> open = events.selectList(Wrappers.<EquipmentEvent>lambdaQuery()
        .eq(EquipmentEvent::getEquipmentCode, code)
        .isNull(EquipmentEvent::getEndedAt)
        .orderByDesc(EquipmentEvent::getStartedAt));
    for (EquipmentEvent ev : open) {
      ev.setEndedAt(at);
      events.updateById(ev);
    }
  }

  private Map<String, Object> fixtureOf(String code) {
    return fixtures.equipmentSpec().stream()
        .filter(s -> code.equals(s.get("code")))
        .findFirst()
        .orElse(Map.of("code", code));
  }

  private String nextOrderId() {
    String stamp = LocalDate.now().format(DateTimeFormatter.ofPattern("yyMMdd"));
    String prefix = "MO-" + stamp + "-";
    long seq = orders.selectCount(Wrappers.<MaintenanceOrder>lambdaQuery()
        .likeRight(MaintenanceOrder::getId, prefix)) + 1;
    return prefix + String.format("%03d", seq);
  }

  /* ---------------- helpers ---------------- */

  /** 业务状态 → 前端 health 枚举（good / watch / fault / idle）。 */
  public static String healthOf(String status) {
    return switch (status == null ? "" : status) {
      case "RUNNING" -> "good";
      case "ALARM" -> "fault";
      case "IDLE", "STOPPED" -> "idle";
      case "CLEANING", "MAINTENANCE" -> "watch";
      default -> "good";
    };
  }

  private static String initialStatus(Map<String, Object> spec) {
    String code = str(spec.get("code"));
    if ("fault".equals(spec.get("health"))) return "ALARM";
    if ("idle".equals(spec.get("health"))) return "IDLE";
    // F-103 在 fixture 中执行 CIP 喷嘴检查，初始判为清洗中
    if ("F-103".equals(code)) return "CLEANING";
    return "RUNNING";
  }

  private static String categoryOf(String code) {
    return switch (code.charAt(0)) {
      case 'F' -> "FERMENTER";
      case 'M' -> "MIXING";
      case 'S' -> "STERILIZER";
      case 'E' -> "EVAPORATOR";
      case 'C' -> "CRYSTALLIZER";
      case 'D' -> "DRYER";
      case 'P' -> "PACKING";
      default -> "UTILITY";
    };
  }

  private static String locationOf(String code) {
    return switch (code.charAt(0)) {
      case 'F' -> "一车间发酵区";
      case 'M' -> "一车间配料区";
      case 'S' -> "一车间灭菌区";
      case 'E' -> "二车间浓缩区";
      case 'C' -> "二车间结晶区";
      case 'D' -> "二车间干燥区";
      case 'P' -> "三车间包装区";
      default -> "公用工程";
    };
  }

  private static String criticalityOf(String code) {
    // 发酵罐与连消机属关键设备（CCP 载体），故障直接阻断生产
    return ("F".equals(String.valueOf(code.charAt(0))) || "S".equals(String.valueOf(code.charAt(0))))
        ? "A" : "B";
  }

  private static String calibrationItemOf(String code) {
    return switch (code.charAt(0)) {
      case 'F' -> "罐体铂电阻温度计 / pH 电极";
      case 'M' -> "配料衡器 / 液位计";
      case 'S' -> "灭菌温度计 / 压力变送器";
      case 'E' -> "蒸汽压力表 / 真空表";
      case 'C' -> "结晶温度计";
      case 'D' -> "进风温度传感器";
      case 'P' -> "自动衡器 / 金属检测仪";
      default -> "通用压力表";
    };
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> asList(Object v) {
    return v instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
  }

  private static String str(Object v) {
    return v == null || "null".equals(String.valueOf(v)) ? "" : String.valueOf(v);
  }

  private static LocalDate dateOf(Object v) {
    String s = str(v);
    if (s.isBlank()) return null;
    try {
      return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
    } catch (Exception ignore) {
      return null;
    }
  }

  private static boolean notBlank(String s) {
    return s != null && !s.isBlank();
  }

  private static BigDecimal bd(double v) {
    return BigDecimal.valueOf(v);
  }
}
