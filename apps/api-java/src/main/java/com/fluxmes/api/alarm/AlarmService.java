package com.fluxmes.api.alarm;

import static com.fluxmes.api.common.ApiSupport.map;
import static com.fluxmes.api.common.ApiSupport.r1;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fluxmes.api.audit.AuditService;
import com.fluxmes.api.common.ApiSupport;
import com.fluxmes.api.common.CurrentUser;
import com.fluxmes.api.common.Roles;
import com.fluxmes.api.entity.Alarm;
import com.fluxmes.api.entity.AlarmSuppression;
import com.fluxmes.api.mapper.AlarmMapper;
import com.fluxmes.api.mapper.AlarmSuppressionMapper;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 报警中心核心服务：列表/计数/确认/恢复/统计/趋势/高频源 + SSE 实时推送。
 * Phase 1：读写 PostgreSQL（alarm 表，留存不再裁剪，C6 ≥3 年）；
 * 确认/恢复写审计（C3，who/when/前后状态）；SLA 响应分钟数按 createdAt 起算。
 * Phase D1：响应 SLA 按级别配置（critical 5 / major 15 / minor 30 分钟），超时未确认自动升级
 *          并落审计 + SSE 推送；支持抑制规则（同 key 窗口期内重复触发只计数不入库）。
 * 响应形状与 fluxmes/src/api/alarms.ts 及 pages/Alarms.jsx 的消费字段严格对齐。
 */
@Service
public class AlarmService {

  private static final Logger log = LoggerFactory.getLogger(AlarmService.class);
  private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");
  private static final DateTimeFormatter ID_DAY = DateTimeFormatter.ofPattern("yyMMdd");
  private static final ZoneId ZONE = ZoneId.systemDefault();

  /** D1 · 响应时限（分钟）默认值：越严重要求响应越快。G1 起可被配置表覆盖。 */
  public static final int SLA_CRITICAL = 5;
  public static final int SLA_MAJOR = 15;
  public static final int SLA_MINOR = 30;

  private final AlarmMapper alarms;
  private final AlarmSuppressionMapper suppressions;
  private final AuditService audit;
  /** G1 · SLA 政策（运行时可配置，替代原先的代码常量）。 */
  private final AlarmSlaPolicyService slaPolicy;
  /** Phase J · 外部系统接入配置（报警模拟器开关）。 */
  private final com.fluxmes.api.integration.IntegrationProperties integration;
  private final List<SseEmitter> clients = new CopyOnWriteArrayList<>();
  private final AtomicLong alarmSeq = new AtomicLong(100); // 模拟器序号（日期式 id 已含日期，无需去重）
  private ScheduledExecutorService scheduler;

  public AlarmService(AlarmMapper alarms, AlarmSuppressionMapper suppressions, AuditService audit,
      AlarmSlaPolicyService slaPolicy, com.fluxmes.api.integration.IntegrationProperties integration) {
    this.alarms = alarms;
    this.suppressions = suppressions;
    this.audit = audit;
    this.slaPolicy = slaPolicy;
    this.integration = integration;
  }

  /* ---------------- 查询 ---------------- */

  /** GET /api/alarms —— { generatedAt, unackedCount, overdueCount, alarms } */
  public Map<String, Object> list() {
    List<Map<String, Object>> items = all().stream().map(AlarmService::toView).toList();
    long unacked = items.stream().filter(a -> "unacked".equals(a.get("status"))).count();
    long overdue = items.stream()
        .filter(a -> "unacked".equals(a.get("status")) && Boolean.TRUE.equals(a.get("overdue")))
        .count();
    return map("generatedAt", nowIso(), "unackedCount", unacked, "overdueCount", overdue, "alarms", items);
  }

  /** GET /api/alarms/unacked-count —— { unacked, overdue } */
  public Map<String, Object> unackedCount() {
    return map("unacked", count("unacked"), "overdue", countOverdue());
  }

  /**
   * GET /api/alarms/stats —— { todayTotal, active, unackedCritical, overdueUnacked,
   * avgResponseMinutes, ackRatePercent, suppressedCount }（字段与 Alarms.jsx 卡片一一对应）。
   */
  public Map<String, Object> stats() {
    List<Alarm> items = all();
    long active = items.stream().filter(a -> !"recovered".equals(a.getStatus())).count();
    long unackedCritical = items.stream()
        .filter(a -> "unacked".equals(a.getStatus()) && "critical".equals(a.getLevel())).count();
    long ackedOrRecovered = items.stream()
        .filter(a -> "acked".equals(a.getStatus()) || "recovered".equals(a.getStatus())).count();
    var responded = items.stream().filter(a -> a.getRespondedMinutes() != null).toList();
    double avgResp = responded.isEmpty() ? 4.2
        : responded.stream().mapToInt(Alarm::getRespondedMinutes).average().orElse(4.2);
    double ackRate = items.isEmpty() ? 0 : ApiSupport.pct1(ackedOrRecovered * 100.0 / items.size());
    long todayTotal = 9 + items.size(); // 演示口径：历史基线 9 + 台账条数
    // D1：逾期 = 未确认且已超该级别 SLA 时限（此前口径为「全部 unacked」，失真）
    long overdue = items.stream().filter(AlarmService::isOverdue).count();
    long suppressed = suppressions.selectCount(Wrappers.<AlarmSuppression>lambdaQuery()
        .eq(AlarmSuppression::getEnabled, true));
    return map(
        "todayTotal", todayTotal,
        "active", active,
        "unackedCritical", unackedCritical,
        "overdueUnacked", overdue,
        "avgResponseMinutes", r1(avgResp),
        "ackRatePercent", ackRate,
        "slaPolicy", slaPolicy.policyMap(),               // G1 · 运行时可配置的响应时限
        "activeSuppressionRules", suppressed);
  }

  /** GET /api/alarms/trend —— [{ t, critical, major, minor }]，按小时聚合最近 N 小时。 */
  public List<Map<String, Object>> trend(int hours) {
    int h = clamp(hours, 1, 48);
    String[] labels = new String[h];
    long[][] counts = new long[h][3];
    LocalDateTime curHour = LocalDateTime.now().withMinute(0).withSecond(0).withNano(0);
    for (int i = 0; i < h; i++) {
      labels[i] = curHour.minusHours(h - 1L - i).format(HHMM);
    }
    for (Alarm a : all()) {
      int idx;
      if (a.getCreatedAt() != null) {
        String label = a.getCreatedAt().atZoneSameInstant(ZONE).toLocalTime().withMinute(0).format(HHMM);
        idx = indexOfLabel(labels, label);
      } else if (a.getTime() != null) {
        idx = indexOfLabel(labels, truncateToHour(a.getTime()));
      } else {
        idx = -1;
      }
      if (idx < 0) idx = labels.length - 1;
      counts[idx][levelIndex(a.getLevel())]++;
    }
    List<Map<String, Object>> out = new ArrayList<>(h);
    for (int i = 0; i < h; i++) {
      out.add(map("t", labels[i], "critical", counts[i][0], "major", counts[i][1], "minor", counts[i][2]));
    }
    return out;
  }

  /** GET /api/alarms/top-sources —— [{ source, count }] */
  public List<Map<String, Object>> topSources(int days, int limit) {
    Map<String, Long> counts = new LinkedHashMap<>();
    for (Alarm a : all()) {
      counts.merge(String.valueOf(a.getSource()), 1L, Long::sum);
    }
    // fixture 基线（近 7 日累计）：叠加当前会话新增
    counts.merge("E-501 MVR 浓缩器", 4L, Long::sum);
    counts.merge("F-102 发酵罐 #2", 3L, Long::sum);
    counts.merge("M-201 配料罐 #1", 2L, Long::sum);
    counts.merge("D-701 流化床干燥机", 1L, Long::sum);
    return counts.entrySet().stream()
        .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
        .limit(Math.max(1, limit))
        .map(e -> map("source", e.getKey(), "count", e.getValue()))
        .toList();
  }

  /* ---------------- 状态变更（FR-3/4 + 审计 C3） ---------------- */

  public record AckResult(boolean changed, Map<String, Object> alarm) {}

  /** 确认：仅 unacked → acked；操作人取 JWT 当前用户；写审计 + SSE；同时清除升级标记。 */
  public AckResult ack(String id, String operator) {
    Alarm a = alarms.selectById(id);
    if (a == null) return new AckResult(false, null);
    Map<String, Object> before = toView(a);
    String who = (operator == null || operator.isBlank()) ? CurrentUser.username() : operator;
    if ("unacked".equals(a.getStatus())) {
      a.setStatus("acked");
      a.setAckBy(who);
      a.setAckedAt(OffsetDateTime.now());
      if (a.getRespondedMinutes() == null) a.setRespondedMinutes((int) responseMinutes(a));
      alarms.updateById(a);
    }
    Map<String, Object> after = toView(a);
    audit.record("alarm.ack", "alarm", id, before, after);
    publish("acked", after);
    return new AckResult(true, after);
  }

  /** 恢复：acked/unacked → recovered；auto=true 表示系统自动恢复；写审计 + SSE。 */
  public AckResult recover(String id, String operator, boolean auto) {
    Alarm a = alarms.selectById(id);
    if (a == null) return new AckResult(false, null);
    Map<String, Object> before = toView(a);
    if (!"recovered".equals(a.getStatus())) {
      a.setStatus("recovered");
      a.setRecoveredAt(OffsetDateTime.now());
      if (auto) a.setRecoveredBy("system");
      else a.setRecoveredBy((operator == null || operator.isBlank()) ? CurrentUser.username() : operator);
      alarms.updateById(a);
    }
    Map<String, Object> after = toView(a);
    audit.record("alarm.recover", "alarm", id, before, after);
    publish("recovered", after);
    return new AckResult(true, after);
  }

  /* ---------------- D1 · SLA 升级 ---------------- */

  /** 内置默认响应时限（分钟）：配置表缺失或非法时回落至此（G1）。 */
  public static int defaultSla(String level) {
    return switch (String.valueOf(level)) {
      case "critical" -> SLA_CRITICAL;
      case "major" -> SLA_MAJOR;
      default -> SLA_MINOR;
    };
  }

  /** G1 · 按级别取当前生效的响应时限（读配置表；0 表示该级别豁免考核）。 */
  public int slaFor(String level) {
    return slaPolicy == null ? defaultSla(level) : slaPolicy.effectiveMinutes(level);
  }

  /** 报警自身的响应时限：优先取创建时写入的值（审计口径），缺省回落默认常量。 */
  public static int storedSla(Alarm a) {
    return a == null || a.getSlaMinutes() == null ? defaultSla(a == null ? null : a.getLevel())
        : a.getSlaMinutes();
  }

  /** 未确认且已超过该级别响应时限 → 逾期（SLA 违约）；时限为 0 表示豁免考核。 */
  public static boolean isOverdue(Alarm a) {
    if (a == null || !"unacked".equals(a.getStatus()) || a.getCreatedAt() == null) return false;
    int sla = storedSla(a);
    if (sla <= 0) return false;
    return Duration.between(a.getCreatedAt(), OffsetDateTime.now()).toMinutes() > sla;
  }

  /**
   * SLA 巡检（每 60s）：把逾期未确认且尚未升级的报警标记为 escalated，
   * 写审计 alarm.escalate 并 SSE 推送，供前端「升级」徽标与声音提示。
   */
  void sweepSla() {
    try {
      List<Alarm> pending = alarms.selectList(Wrappers.<Alarm>lambdaQuery()
          .eq(Alarm::getStatus, "unacked")
          .eq(Alarm::getEscalated, false));
      for (Alarm a : pending) {
        if (!isOverdue(a)) continue;
        Map<String, Object> before = toView(a);
        int over = (int) Duration.between(a.getCreatedAt(), OffsetDateTime.now()).toMinutes()
            - storedSla(a);
        a.setEscalated(true);
        a.setEscalatedAt(OffsetDateTime.now());
        a.setEscalationNote("超时 " + Math.max(0, over) + " 分钟未确认，已升级至值班长");
        alarms.updateById(a);
        Map<String, Object> after = toView(a);
        audit.record("alarm.escalate", "alarm", a.getId(), before, after);
        publish("escalated", after);
        log.warn("alarm escalated: {} ({})", a.getId(), a.getEscalationNote());
      }
    } catch (Exception e) {
      log.warn("sla sweep failed: {}", e.toString());
    }
  }

  /* ---------------- D1 · 抑制规则 ---------------- */

  /** GET /api/alarms/suppressions —— 抑制规则列表。 */
  public List<Map<String, Object>> listSuppressions() {
    return suppressions.selectList(Wrappers.<AlarmSuppression>lambdaQuery()
            .orderByDesc(AlarmSuppression::getId))
        .stream().map(AlarmService::suppressionView).toList();
  }

  /** POST /api/alarms/suppressions —— 新建抑制规则（SUPERVISOR+，写审计）。 */
  public Map<String, Object> createSuppression(Map<String, Object> body) {
    if (!CurrentUser.hasRole(Roles.SUPERVISOR)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要值班长及以上权限维护抑制规则");
    }
    String name = String.valueOf(body == null ? null : body.get("name"));
    if (name == null || name.isBlank() || "null".equals(name)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少必填项: name");
    }
    AlarmSuppression s = new AlarmSuppression();
    s.setName(name);
    s.setSource(strOrNull(body.get("source")));
    s.setContent(strOrNull(body.get("content")));
    s.setLevel(strOrNull(body.get("level")));
    s.setWindowMinutes((int) Math.max(1, ApiSupport.num(body.get("windowMinutes"), 10)));
    s.setEnabled(body.get("enabled") == null || Boolean.parseBoolean(String.valueOf(body.get("enabled"))));
    s.setReason(strOrNull(body.get("reason")));
    s.setCreatedBy(CurrentUser.username());
    s.setCreatedAt(OffsetDateTime.now());
    suppressions.insert(s);
    Map<String, Object> view = suppressionView(s);
    audit.record("alarm.suppression.create", "alarm_suppression", String.valueOf(s.getId()), null, view);
    return view;
  }

  /** DELETE /api/alarms/suppressions/{id} —— 停用并删除抑制规则（SUPERVISOR+，写审计）。 */
  public Map<String, Object> deleteSuppression(Long id) {
    if (!CurrentUser.hasRole(Roles.SUPERVISOR)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要值班长及以上权限维护抑制规则");
    }
    AlarmSuppression s = suppressions.selectById(id);
    if (s == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "抑制规则不存在: " + id);
    Map<String, Object> before = suppressionView(s);
    suppressions.deleteById(id);
    audit.record("alarm.suppression.delete", "alarm_suppression", String.valueOf(id), before, null);
    return map("deleted", true, "id", id);
  }

  /**
   * 命中抑制规则则返回该规则：窗口期内（createdAt ≥ now - windowMinutes）已有同规则的报警。
   * 用于新报警入库前判定——被抑制的报警不落库，只记日志，避免报警洪水。
   */
  private AlarmSuppression matchSuppression(String source, String content, String level) {
    List<AlarmSuppression> rules = suppressions.selectList(
        Wrappers.<AlarmSuppression>lambdaQuery().eq(AlarmSuppression::getEnabled, true));
    OffsetDateTime now = OffsetDateTime.now();
    for (AlarmSuppression r : rules) {
      if (notBlank(r.getSource()) && !r.getSource().equals(source)) continue;
      if (notBlank(r.getLevel()) && !r.getLevel().equals(level)) continue;
      if (notBlank(r.getContent()) && (content == null || !content.contains(r.getContent()))) continue;
      long dup = alarms.selectCount(Wrappers.<Alarm>lambdaQuery()
          .eq(Alarm::getSource, source)
          .eq(Alarm::getContent, content)
          .ge(Alarm::getCreatedAt, now.minusMinutes(r.getWindowMinutes() == null ? 10 : r.getWindowMinutes())));
      if (dup > 0) return r;
    }
    return null;
  }

  /* ---------------- SSE 实时推送 ---------------- */

  public SseEmitter stream() {
    SseEmitter emitter = new SseEmitter(0L);
    clients.add(emitter);
    emitter.onCompletion(() -> clients.remove(emitter));
    emitter.onTimeout(() -> clients.remove(emitter));
    emitter.onError(e -> clients.remove(emitter));
    try {
      emitter.send(SseEmitter.event().name("connected").data(map("ok", true)));
    } catch (Exception e) {
      clients.remove(emitter);
    }
    return emitter;
  }

  private void publish(String type, Map<String, Object> alarm) {
    Map<String, Object> payload = map("type", type, "id", alarm.get("id"),
        "status", alarm.get("status"), "level", alarm.get("level"));
    for (SseEmitter emitter : clients) {
      try {
        emitter.send(SseEmitter.event().name("alarm").data(payload));
      } catch (Exception e) {
        clients.remove(emitter);
      }
    }
  }

  /**
   * 演示用报警模拟器：每 45s 产生一条新报警（轮转 5 个报警源）写入 PG 并 SSE 推送，
   * 使「新报警 ≤2s 可见」（NFR-1）可被实际观察到。
   * D1：入库前先过抑制规则；同时写入该级别 SLA 时限。
   * 生产环境应替换为 OPC-UA / PLC 采集接入（Phase 2）。
   */
  /**
   * 重启后把模拟器序号对齐到库内当日最大序号。
   * 序号原本固定从 100 起，跨重启会与历史报警主键冲突（A-yyMMdd-NNN），
   * 表现为模拟器首次入库即 DuplicateKeyException。
   */
  private void initSeq() {
    try {
      String prefix = "A-" + LocalDate.now().format(ID_DAY) + "-";
      long max = alarms.selectList(Wrappers.<Alarm>lambdaQuery().likeRight(Alarm::getId, prefix))
          .stream()
          .map(a -> a.getId().substring(prefix.length()))
          .filter(s -> s.chars().allMatch(Character::isDigit) && !s.isEmpty())
          .mapToLong(Long::parseLong)
          .max().orElse(100);
      alarmSeq.set(Math.max(100, max));
    } catch (Exception e) {
      log.warn("alarm seq init failed: {}", e.toString());
    }
  }

  @jakarta.annotation.PostConstruct
  void startSimulator() {
    initSeq();
    scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "alarm-worker");
      t.setDaemon(true);
      return t;
    });
    // P0-2：模拟器受 fluxmes.integration.alarm-simulator-enabled 控制。
    // 此前它无条件启动，每 45s 往 alarm 表写一条 value="模拟器数据" 的报警；
    // 接入真实采集后这些数据会与真实报警混在同一张表，故必须可关，且打上 data_source=MOCK。
    if (integration.isAlarmSimulatorEnabled()) {
      scheduler.scheduleWithFixedDelay(this::simulateNewAlarm, 45, 45, TimeUnit.SECONDS);
      log.warn("报警模拟器已启用（每 45s 造一条 data_source=MOCK 的报警）"
          + "——仅限演示环境，生产须置 fluxmes.integration.alarm-simulator-enabled=false");
    } else {
      log.info("报警模拟器已关闭（fluxmes.integration.alarm-simulator-enabled=false）");
    }
    // SLA 巡检与模拟器无关：它是响应时限的合规要求，任何模式都必须运行
    scheduler.scheduleWithFixedDelay(this::sweepSla, 30, 60, TimeUnit.SECONDS);
  }

  @jakarta.annotation.PreDestroy
  void stopSimulator() {
    if (scheduler != null) scheduler.shutdownNow();
  }

  private final String[][] simPool = {
    {"F-101 发酵罐 #1", "罐温偏高", "minor"},
    {"C-601 结晶罐", "搅拌扭矩波动", "minor"},
    {"E-501 MVR 浓缩器", "真空度下降", "major"},
    {"S-201 连消机", "蒸汽压力波动", "major"},
    {"D-701 流化床干燥机", "出风温度偏高", "critical"},
  };
  private int simIndex;

  private void simulateNewAlarm() {
    try {
      long seq = alarmSeq.incrementAndGet();
      String[] pick = simPool[simIndex++ % simPool.length];
      // D1：抑制判定——窗口期内同 source+content 已存在则不入库
      AlarmSuppression hit = matchSuppression(pick[0], pick[1], pick[2]);
      if (hit != null) {
        log.info("alarm suppressed by rule #{} ({}): {} / {}", hit.getId(), hit.getName(), pick[0], pick[1]);
        return;
      }
      OffsetDateTime now = OffsetDateTime.now();
      Alarm a = new Alarm();
      a.setId("A-" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyMMdd")) + "-" + String.format("%03d", seq));
      a.setTime(now.atZoneSameInstant(ZONE).toLocalTime().format(HHMM));
      a.setLevel(pick[2]);
      a.setSource(pick[0]);
      a.setContent(pick[1]);
      a.setValue("模拟器数据");
      a.setThreshold("演示阈值");
      a.setStatus("unacked");
      a.setDataSource(com.fluxmes.api.integration.DataSourceTag.MOCK);
      a.setSlaMinutes(slaFor(pick[2]));
      a.setEscalated(false);
      a.setSuppressionKey(pick[0] + "|" + pick[1]);
      a.setCreatedAt(now);
      alarms.insert(a);
      publish("created", toView(a));
      log.info("simulated alarm created: {}", a.getId());
    } catch (Exception e) {
      log.warn("alarm simulator failed: {}", e.toString());
    }
  }

  /* ---------------- 外部触发（F1 CCP / 环境超标联动） ---------------- */

  /**
   * 由业务事件主动触发报警（如 CCP 关键限值偏离、环境指标超标）。
   * 过抑制规则；写入该级别 SLA 时限并 SSE 推送。返回 null 表示被抑制规则拦截。
   */
  public Map<String, Object> raise(String source, String content, String level, String value,
      String threshold) {
    if (matchSuppression(source, content, level) != null) return null;
    OffsetDateTime now = OffsetDateTime.now();
    long seq = alarmSeq.incrementAndGet();
    Alarm a = new Alarm();
    a.setId("A-" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyMMdd")) + "-"
        + String.format("%03d", seq));
    a.setTime(now.atZoneSameInstant(ZONE).toLocalTime().format(HHMM));
    a.setLevel(level == null ? "major" : level);
    a.setSource(source);
    a.setContent(content);
    a.setValue(value);
    a.setThreshold(threshold);
    a.setStatus("unacked");
    a.setSlaMinutes(slaFor(a.getLevel()));
    a.setEscalated(false);
    a.setSuppressionKey(source + "|" + content);
    a.setCreatedAt(now);
    alarms.insert(a);
    Map<String, Object> view = toView(a);
    publish("created", view);
    return view;
  }

  /* ---------------- helpers ---------------- */

  private List<Alarm> all() {
    return alarms.selectList(Wrappers.<Alarm>lambdaQuery()
        .orderByDesc(Alarm::getCreatedAt)
        .orderByDesc(Alarm::getId));
  }

  private long count(String status) {
    return alarms.selectCount(Wrappers.<Alarm>lambdaQuery().eq(Alarm::getStatus, status));
  }

  private long countOverdue() {
    return alarms.selectList(Wrappers.<Alarm>lambdaQuery().eq(Alarm::getStatus, "unacked"))
        .stream().filter(AlarmService::isOverdue).count();
  }

  /** 实体 → 前端契约视图（字段与 alarms.ts 消费严格对齐；D1 追加 SLA 字段）。 */
  public static Map<String, Object> toView(Alarm a) {
    int sla = storedSla(a);
    long elapsed = a.getCreatedAt() == null ? 0
        : Math.max(0, Duration.between(a.getCreatedAt(), OffsetDateTime.now()).toMinutes());
    boolean overdue = isOverdue(a);
    return map(
        "id", a.getId(),
        "time", a.getTime(),
        "level", a.getLevel(),
        "source", a.getSource(),
        "content", a.getContent(),
        "value", a.getValue(),
        "threshold", a.getThreshold(),
        "status", a.getStatus(),
        "ackBy", a.getAckBy(),
        "ackedAt", a.getAckedAt() == null ? null : a.getAckedAt().toString(),
        "recoveredAt", a.getRecoveredAt() == null ? null : a.getRecoveredAt().toString(),
        "respondedMinutes", a.getRespondedMinutes(),
        "createdAt", a.getCreatedAt() == null ? null : a.getCreatedAt().toString(),
        "slaMinutes", sla,
        "elapsedMinutes", elapsed,
        "overdue", overdue,
        "escalated", Boolean.TRUE.equals(a.getEscalated()),
        "escalatedAt", a.getEscalatedAt() == null ? null : a.getEscalatedAt().toString(),
        "escalationNote", a.getEscalationNote(),
        // J2 · 数据来源：MOCK 表示报警模拟器造的演示数据，非真实触发
        "dataSource", a.getDataSource());
  }

  private static Map<String, Object> suppressionView(AlarmSuppression s) {
    return map(
        "id", s.getId(),
        "name", s.getName(),
        "source", s.getSource(),
        "content", s.getContent(),
        "level", s.getLevel(),
        "windowMinutes", s.getWindowMinutes(),
        "enabled", s.getEnabled(),
        "reason", s.getReason(),
        "createdBy", s.getCreatedBy(),
        "createdAt", s.getCreatedAt() == null ? null : s.getCreatedAt().toString());
  }

  private static long responseMinutes(Alarm a) {
    if (a.getCreatedAt() == null) return 5;
    return Math.max(1, Duration.between(a.getCreatedAt(), OffsetDateTime.now()).toMinutes());
  }

  private static int indexOfLabel(String[] labels, String label) {
    for (int i = 0; i < labels.length; i++) {
      if (labels[i].equals(label)) return i;
    }
    return -1;
  }

  private static String truncateToHour(String hhmm) {
    int idx = hhmm.indexOf(':');
    return idx >= 2 ? hhmm.substring(0, idx) + ":00" : hhmm;
  }

  private static int levelIndex(Object level) {
    return switch (String.valueOf(level)) {
      case "critical" -> 0;
      case "major" -> 1;
      default -> 2;
    };
  }

  private static int clamp(int v, int lo, int hi) {
    return Math.max(lo, Math.min(hi, v));
  }

  private static boolean notBlank(String s) {
    return s != null && !s.isBlank();
  }

  private static String strOrNull(Object v) {
    if (v == null) return null;
    String s = String.valueOf(v);
    return (s.isBlank() || "null".equals(s)) ? null : s;
  }

  private static String nowIso() {
    return java.time.Instant.now().toString();
  }
}
