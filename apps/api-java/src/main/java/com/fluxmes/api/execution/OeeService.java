package com.fluxmes.api.execution;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fluxmes.api.common.ApiSupport;
import com.fluxmes.api.entity.DowntimeEvent;
import com.fluxmes.api.entity.DowntimeReason;
import com.fluxmes.api.entity.EquipmentEvent;
import com.fluxmes.api.entity.OeeRollup;
import com.fluxmes.api.entity.ProductionLine;
import com.fluxmes.api.entity.StepReport;
import com.fluxmes.api.entity.WorkOrder;
import com.fluxmes.api.mapper.DowntimeEventMapper;
import com.fluxmes.api.mapper.DowntimeReasonMapper;
import com.fluxmes.api.mapper.EquipmentEventMapper;
import com.fluxmes.api.mapper.OeeRollupMapper;
import com.fluxmes.api.mapper.ProductionLineMapper;
import com.fluxmes.api.mapper.StepReportMapper;
import com.fluxmes.api.mapper.WorkOrderMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Phase I · OEE 重算服务（FR-20 ~ FR-23）。
 *
 * <p><b>口径</b>（替代原先「非 abnormal 批次占比 / 当日产量÷设计产能」的替代口径，plan D5）：
 * <ul>
 *   <li>可用率 =（计划生产工时 − 计划外停机工时）÷ 计划生产工时；</li>
 *   <li>性能率 = 标准工时 ÷ 实际生产工时（报工事实，非产量比产能）；</li>
 *   <li>良品率 = 合格量 ÷（合格量 + 废次品量）；</li>
 *   <li>OEE = 三因子乘积 ÷ 10000。</li>
 * </ul>
 *
 * <p><b>不伪造</b>（FR-21 / NFR）：任一因子缺原始数据即返回 {@code null}，
 * 并置 {@code dataSufficient=false} 与 {@code missing} 说明；<b>不使用任何兜底常量</b>。
 */
@Service
public class OeeService {

  private static final ZoneOffset ZONE = ZoneOffset.ofHours(8);
  private static final int DEFAULT_WINDOW_DAYS = 7;

  /** equipment_event 中计为停机的状态（与 EquipmentService 口径一致）。 */
  private static final List<String> STOP_STATUSES = List.of("ALARM", "MAINTENANCE", "STOPPED");

  private final WorkOrderMapper orders;
  private final StepReportMapper reports;
  private final DowntimeEventMapper downtimes;
  private final DowntimeReasonMapper reasons;
  private final OeeRollupMapper rollups;
  private final ProductionLineMapper lines;
  private final EquipmentEventMapper equipmentEvents;

  public OeeService(WorkOrderMapper orders, StepReportMapper reports,
      DowntimeEventMapper downtimes, DowntimeReasonMapper reasons,
      OeeRollupMapper rollups, ProductionLineMapper lines,
      EquipmentEventMapper equipmentEvents) {
    this.orders = orders;
    this.reports = reports;
    this.downtimes = downtimes;
    this.reasons = reasons;
    this.rollups = rollups;
    this.lines = lines;
    this.equipmentEvents = equipmentEvents;
  }

  /* ==================== 主计算 ==================== */

  /** GET /api/execution/oee —— 按厂区/产线/设备/班次/日期区间计算真实 OEE（FR-20~FR-22）。 */
  public Map<String, Object> compute(String site, String line, String equipment, String shift,
      String from, String to) {
    OffsetDateTime f = parseTime(from);
    OffsetDateTime t = parseTime(to);
    if (f == null && t == null) {
      f = LocalDate.now(ZONE).minusDays(DEFAULT_WINDOW_DAYS - 1L).atStartOfDay().atOffset(ZONE);
      t = LocalDate.now(ZONE).plusDays(1).atStartOfDay().atOffset(ZONE);
    }

    // 范围工单（设备维度不限定工单，按报工/停机的 equipment 过滤）
    List<WorkOrder> scope = new ArrayList<>();
    for (WorkOrder o : orders.selectList(Wrappers.<WorkOrder>lambdaQuery()
        .eq(notBlank(line), WorkOrder::getLine, line)
        .eq(notBlank(site), WorkOrder::getSite, site)
        .eq(notBlank(shift), WorkOrder::getShift, shift))) {
      if (inRange(o, f, t)) scope.add(o);
    }
    Set<String> orderIds = new LinkedHashSet<>();
    scope.forEach(o -> orderIds.add(o.getId()));

    // 报工（性能率 / 良品率的原始事实）
    List<StepReport> reportRows = new ArrayList<>();
    for (StepReport r : reports.selectList(Wrappers.<StepReport>lambdaQuery()
        .eq(notBlank(equipment), StepReport::getEquipment, equipment)
        .orderByAsc(StepReport::getStepNo))) {
      if (!notBlank(equipment) && (r.getOrderId() == null || !orderIds.contains(r.getOrderId()))) {
        continue;
      }
      reportRows.add(r);
    }

    // 停机（可用率的原始事实）
    List<DowntimeEvent> stopRows = new ArrayList<>();
    for (DowntimeEvent d : downtimes.selectList(Wrappers.<DowntimeEvent>lambdaQuery()
        .eq(notBlank(equipment), DowntimeEvent::getEquipment, equipment)
        .eq(notBlank(site), DowntimeEvent::getSite, site)
        .eq(notBlank(shift), DowntimeEvent::getShift, shift))) {
      if (notBlank(equipment)) {
        if (inRange(d, f, t)) stopRows.add(d);
        continue;
      }
      if (d.getOrderId() != null && orderIds.contains(d.getOrderId())) {
        stopRows.add(d);
      } else if (inRange(d, f, t) && notBlank(line) && line.equals(d.getLine())) {
        stopRows.add(d);
      }
    }

    // 设备维度：downtime_event 未覆盖的停机段回落 equipment_event（同一停机不重复计，FR-18）
    BigDecimal eventStopMinutes = BigDecimal.ZERO;
    if (notBlank(equipment)) {
      for (EquipmentEvent ev : equipmentEvents.selectList(Wrappers.<EquipmentEvent>lambdaQuery()
          .eq(EquipmentEvent::getEquipmentCode, equipment))) {
        if (!STOP_STATUSES.contains(ev.getToStatus()) || ev.getStartedAt() == null) continue;
        if (!inRange(ev.getStartedAt(), f, t)) continue;
        OffsetDateTime evEnd = ev.getEndedAt() == null ? OffsetDateTime.now() : ev.getEndedAt();
        boolean covered = false;
        for (DowntimeEvent d : stopRows) {
          if (d.getStartedAt() == null) continue;
          OffsetDateTime de = d.getEndedAt() == null ? OffsetDateTime.now() : d.getEndedAt();
          if (ev.getStartedAt().isBefore(de) && d.getStartedAt().isBefore(evEnd)) {
            covered = true;
            break;
          }
        }
        if (!covered) {
          eventStopMinutes = eventStopMinutes.add(minutes(ev.getStartedAt(), ev.getEndedAt()));
        }
      }
    }

    return computeInternal(site, line, equipment, shift, f, t, scope, reportRows, stopRows,
        eventStopMinutes);
  }

  private Map<String, Object> computeInternal(String site, String line, String equipment,
      String shift, OffsetDateTime f, OffsetDateTime t, List<WorkOrder> scope,
      List<StepReport> reportRows, List<DowntimeEvent> stopRows, BigDecimal eventStopMinutes) {

    BigDecimal runMin = BigDecimal.ZERO;
    BigDecimal stdMin = BigDecimal.ZERO;
    BigDecimal good = BigDecimal.ZERO;
    BigDecimal scrap = BigDecimal.ZERO;
    boolean stdMissing = false;
    for (StepReport r : reportRows) {
      BigDecimal d = nz(r.getDurationMin());
      boolean hasStd = r.getStdMinutes() != null && r.getStdMinutes().compareTo(BigDecimal.ZERO) > 0;
      boolean hasDur = d.compareTo(BigDecimal.ZERO) > 0;
      // 无标准工时或耗时的报工无法参与性能率核算（不假定、不外推）
      if (!hasStd || !hasDur) {
        stdMissing = true;
      } else {
        stdMin = stdMin.add(r.getStdMinutes());
        runMin = runMin.add(d);
      }
      good = good.add(nz(r.getGoodQty()));
      scrap = scrap.add(nz(r.getScrapQty()));
    }

    BigDecimal unplanned = BigDecimal.ZERO;
    for (DowntimeEvent d : stopRows) {
      if (Boolean.TRUE.equals(d.getPlanned())) continue;   // FR-17：计划停机不计入可用率损失
      unplanned = unplanned.add(d.getDurationMin() == null
          ? minutes(d.getStartedAt(), d.getEndedAt()) : d.getDurationMin());
    }
    BigDecimal unplannedFromEvents = nz(eventStopMinutes);
    unplanned = unplanned.add(unplannedFromEvents);

    BigDecimal plannedOrderMin = BigDecimal.ZERO;
    for (WorkOrder o : scope) {
      if (o.getPlanMinutes() != null && o.getPlanMinutes() > 0) {
        plannedOrderMin = plannedOrderMin.add(BigDecimal.valueOf(o.getPlanMinutes()));
      }
    }

    List<String> missing = new ArrayList<>();
    boolean plannedDerived = false;
    BigDecimal planned;
    if (notBlank(equipment) || plannedOrderMin.compareTo(BigDecimal.ZERO) <= 0) {
      // 设备维度或工单未填计划工时：计划工时 = 实际作业 + 计划外停机（SEMI E10 型可解释口径）
      planned = runMin.add(unplanned);
      plannedDerived = true;
      if (planned.compareTo(BigDecimal.ZERO) <= 0) missing.add("计划生产工时（无工单计划工时，也无报工/停机记录）");
    } else {
      planned = plannedOrderMin;
    }

    Double availability = null;
    if (planned.compareTo(BigDecimal.ZERO) > 0) {
      BigDecimal up = planned.subtract(unplanned);
      if (up.compareTo(BigDecimal.ZERO) < 0) up = BigDecimal.ZERO;
      availability = pct(up, planned);
    }

    Double performance = null;
    if (runMin.compareTo(BigDecimal.ZERO) > 0 && stdMin.compareTo(BigDecimal.ZERO) > 0) {
      performance = pct(stdMin, runMin);
    } else {
      missing.add("性能率原始数据（标准工时 stdMinutes 与实际工时 durationMin）");
      if (stdMissing && !reportRows.isEmpty()) {
        missing.add("部分报工缺少标准工时或耗时，已从性能率核算中剔除");
      }
    }

    Double quality = null;
    BigDecimal produced = good.add(scrap);
    if (produced.compareTo(BigDecimal.ZERO) > 0) {
      quality = pct(good, produced);
    } else {
      missing.add("良品率原始数据（报工合格量与废次品量）");
    }
    if (scope.isEmpty() && reportRows.isEmpty()) {
      missing.add(0, "该范围内无工单与报工记录");
    }

    Double oee = (availability != null && performance != null && quality != null)
        ? ApiSupport.pct1(availability * performance * quality / 10000.0) : null;
    boolean sufficient = oee != null;
    boolean overrun = performance != null && performance > 100.0;

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("generatedAt", ApiSupport.nowIso());
    out.put("scope", ApiSupport.map(
        "site", notBlank(site) ? site : "ALL",
        "line", notBlank(line) ? line : "ALL",
        "equipment", notBlank(equipment) ? equipment : "ALL",
        "shift", notBlank(shift) ? shift : "ALL",
        "from", f == null ? null : f.toString(),
        "to", t == null ? null : t.toString()));
    out.put("availability", availability);
    out.put("performance", performance);
    out.put("quality", quality);
    out.put("oee", oee);
    out.put("dataSufficient", sufficient);
    out.put("missing", missing.isEmpty() ? null : String.join("；", missing));
    out.put("plannedMinutes", ApiSupport.r1(planned.doubleValue()));
    out.put("plannedMinutesDerived", plannedDerived);
    out.put("unplannedStopMinutes", ApiSupport.r1(unplanned.doubleValue()));
    if (unplannedFromEvents.compareTo(BigDecimal.ZERO) > 0) {
      out.put("unplannedFromEquipmentEvents", ApiSupport.r1(unplannedFromEvents.doubleValue()));
      out.put("stopSource", "downtime_event + equipment_event（未与停机事件重叠的部分）");
    }
    out.put("runMinutes", ApiSupport.r1(runMin.doubleValue()));
    out.put("stdMinutes", ApiSupport.r1(stdMin.doubleValue()));
    out.put("goodQty", ApiSupport.r3(good.doubleValue()));
    out.put("scrapQty", ApiSupport.r3(scrap.doubleValue()));
    out.put("orderCount", scope.size());
    out.put("reportCount", reportRows.size());
    out.put("downtimeCount", stopRows.size());
    out.put("performanceOverrun", overrun);
    out.put("formula", "OEE = 可用率 × 性能率 × 良品率；可用率=(计划工时−计划外停机)/计划工时；"
        + "性能率=标准工时/实际工时；良品率=合格量/(合格量+废次品量)");
    if (!sufficient) {
      out.put("note", "数据不足，未输出 OEE（不采用兜底数值，FR-21）");
    } else if (plannedDerived) {
      out.put("note", "计划生产工时由「实际作业 + 计划外停机」推导（工单未填计划工时或为设备维度）");
    } else if (overrun) {
      out.put("note", "性能率 > 100%，说明标准工时偏低或产出高于理论，请复核标准工时维护");
    }
    return out;
  }

  /* ==================== 预汇总重算（FR-22） ==================== */

  /** POST /api/execution/oee/recompute —— 按产线 × 日 × 班次重算并 upsert（plan D4）。 */
  public Map<String, Object> recompute(String date, String site, String line) {
    LocalDate d;
    try {
      d = notBlank(date) ? LocalDate.parse(date) : LocalDate.now(ZONE);
    } catch (Exception e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "date 格式须为 yyyy-MM-dd");
    }
    List<ProductionLine> target = lines.selectList(Wrappers.<ProductionLine>lambdaQuery()
        .eq(ProductionLine::getEnabled, true)
        .eq(notBlank(site), ProductionLine::getSiteCode, site)
        .eq(notBlank(line), ProductionLine::getCode, line)
        .orderByAsc(ProductionLine::getCode));
    if (target.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "无匹配产线（site=" + site + " line=" + line + "）");
    }
    OffsetDateTime f = d.atStartOfDay().atOffset(ZONE);
    OffsetDateTime t = d.plusDays(1).atStartOfDay().atOffset(ZONE);

    List<Map<String, Object>> rows = new ArrayList<>();
    for (ProductionLine l : target) {
      Map<String, Object> computed = compute(l.getSiteCode(), l.getCode(), null, null,
          f.toString(), t.toString());
      OeeRollup r = upsert("LINE", l.getCode(), d, "ALL", l.getSiteCode(), computed);
      rows.add(ApiSupport.map(
          "scopeType", r.getScopeType(), "scopeKey", r.getScopeKey(),
          "statDate", r.getStatDate() == null ? null : r.getStatDate().toString(),
          "shift", r.getShift(), "site", r.getSite(),
          "availability", r.getAvailability(), "performance", r.getPerformance(),
          "quality", r.getQuality(), "oee", r.getOee(),
          "dataSufficient", r.getDataSufficient(), "missing", r.getMissing()));
    }
    return ApiSupport.map("generatedAt", ApiSupport.nowIso(),
        "date", d.toString(), "computedAt", ApiSupport.nowIso(), "rows", rows);
  }

  private OeeRollup upsert(String scopeType, String scopeKey, LocalDate date, String shift,
      String site, Map<String, Object> computed) {
    OeeRollup r = rollups.selectOne(Wrappers.<OeeRollup>lambdaQuery()
        .eq(OeeRollup::getScopeType, scopeType)
        .eq(OeeRollup::getScopeKey, scopeKey)
        .eq(OeeRollup::getStatDate, date)
        .eq(OeeRollup::getShift, shift)
        .last("limit 1"));
    boolean insert = r == null;
    if (insert) r = new OeeRollup();
    r.setScopeType(scopeType);
    r.setScopeKey(scopeKey);
    r.setStatDate(date);
    r.setShift(shift);
    r.setSite(site);
    r.setPlannedMinutes(bd(computed.get("plannedMinutes")));
    r.setUnplannedStopMinutes(bd(computed.get("unplannedStopMinutes")));
    r.setRunMinutes(bd(computed.get("runMinutes")));
    r.setStdMinutes(bd(computed.get("stdMinutes")));
    r.setGoodQty(bd(computed.get("goodQty")));
    r.setScrapQty(bd(computed.get("scrapQty")));
    r.setAvailability(bd(computed.get("availability")));
    r.setPerformance(bd(computed.get("performance")));
    r.setQuality(bd(computed.get("quality")));
    r.setOee(bd(computed.get("oee")));
    r.setDataSufficient(Boolean.TRUE.equals(computed.get("dataSufficient")));
    r.setMissing(computed.get("missing") == null ? null : String.valueOf(computed.get("missing")));
    r.setComputedAt(OffsetDateTime.now());
    if (insert) {
      rollups.insert(r);
    } else {
      rollups.updateById(r);
    }
    return r;
  }

  /* ==================== 停机帕累托（FR-23） ==================== */

  /** GET /api/execution/oee/pareto —— 停机原因帕累托（降序 + 累计百分比）。 */
  public Map<String, Object> pareto(String from, String to, String site, String line) {
    OffsetDateTime f = parseTime(from);
    OffsetDateTime t = parseTime(to);
    if (f == null) f = LocalDate.now(ZONE).minusDays(29).atStartOfDay().atOffset(ZONE);
    if (t == null) t = LocalDate.now(ZONE).plusDays(1).atStartOfDay().atOffset(ZONE);

    Map<String, BigDecimal> byCode = new LinkedHashMap<>();
    Map<String, Integer> counts = new LinkedHashMap<>();
    BigDecimal total = BigDecimal.ZERO;
    for (DowntimeEvent d : downtimes.selectList(Wrappers.<DowntimeEvent>lambdaQuery()
        .eq(notBlank(site), DowntimeEvent::getSite, site)
        .eq(notBlank(line), DowntimeEvent::getLine, line))) {
      if (!inRange(d, f, t)) continue;
      String code = d.getReasonCode() == null ? "UNKNOWN" : d.getReasonCode();
      BigDecimal dur = d.getDurationMin() == null
          ? minutes(d.getStartedAt(), d.getEndedAt()) : d.getDurationMin();
      byCode.merge(code, dur, BigDecimal::add);
      counts.merge(code, 1, Integer::sum);
      total = total.add(dur);
    }

    List<Map<String, Object>> items = new ArrayList<>();
    BigDecimal cum = BigDecimal.ZERO;
    List<Map.Entry<String, BigDecimal>> sorted = new ArrayList<>(byCode.entrySet());
    sorted.sort(Comparator.comparing((Map.Entry<String, BigDecimal> e) -> e.getValue()).reversed());
    for (Map.Entry<String, BigDecimal> e : sorted) {
      DowntimeReason reason = reasons.selectById(e.getKey());
      cum = cum.add(e.getValue());
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("reasonCode", e.getKey());
      row.put("reasonName", reason == null ? e.getKey() : reason.getName());
      row.put("category", reason == null ? null : reason.getCategory());
      row.put("planned", reason != null && "PLANNED".equals(reason.getCategory()));
      row.put("count", counts.getOrDefault(e.getKey(), 0));
      row.put("minutes", ApiSupport.r1(e.getValue().doubleValue()));
      row.put("sharePct", total.compareTo(BigDecimal.ZERO) > 0
          ? pct(e.getValue(), total) : 0.0);
      row.put("cumPct", total.compareTo(BigDecimal.ZERO) > 0 ? pct(cum, total) : 0.0);
      items.add(row);
    }

    BigDecimal plannedMin = BigDecimal.ZERO;
    BigDecimal unplannedMin = BigDecimal.ZERO;
    for (Map<String, Object> row : items) {
      BigDecimal v = bd(row.get("minutes"));
      if (v == null) continue;
      if (Boolean.TRUE.equals(row.get("planned"))) {
        plannedMin = plannedMin.add(v);
      } else {
        unplannedMin = unplannedMin.add(v);
      }
    }
    return ApiSupport.map(
        "generatedAt", ApiSupport.nowIso(),
        "window", ApiSupport.map("from", f.toString(), "to", t.toString()),
        "site", notBlank(site) ? site : "ALL",
        "line", notBlank(line) ? line : "ALL",
        "totalMinutes", ApiSupport.r1(total.doubleValue()),
        "plannedMinutes", ApiSupport.r1(plannedMin.doubleValue()),
        "unplannedMinutes", ApiSupport.r1(unplannedMin.doubleValue()),
        "items", items);
  }

  /* ==================== helpers ==================== */

  private static Double pct(BigDecimal numerator, BigDecimal denominator) {
    if (denominator == null || denominator.compareTo(BigDecimal.ZERO) <= 0) return null;
    return ApiSupport.pct1(numerator.multiply(new BigDecimal("100"))
        .divide(denominator, 4, RoundingMode.HALF_UP).doubleValue());
  }

  private static BigDecimal minutes(OffsetDateTime from, OffsetDateTime to) {
    if (from == null) return BigDecimal.ZERO;
    OffsetDateTime end = to == null ? OffsetDateTime.now() : to;
    return BigDecimal.valueOf(Math.max(0, Duration.between(from, end).toMinutes()));
  }

  private static boolean inRange(WorkOrder o, OffsetDateTime from, OffsetDateTime to) {
    OffsetDateTime ref = o.getPlanStart() != null ? o.getPlanStart() : o.getCreatedAt();
    if (ref == null) return false;
    return (from == null || !ref.isBefore(from)) && (to == null || !ref.isAfter(to));
  }

  private static boolean inRange(DowntimeEvent d, OffsetDateTime from, OffsetDateTime to) {
    return inRange(d.getStartedAt(), from, to);
  }

  private static boolean inRange(OffsetDateTime ref, OffsetDateTime from, OffsetDateTime to) {
    if (ref == null) return false;
    return (from == null || !ref.isBefore(from)) && (to == null || !ref.isAfter(to));
  }

  private static OffsetDateTime parseTime(String v) {
    if (v == null || v.isBlank()) return null;
    String s = v.trim();
    try {
      return OffsetDateTime.parse(s);
    } catch (Exception ignored) {
      // fallthrough
    }
    try {
      return LocalDate.parse(s).atStartOfDay().atOffset(ZONE);
    } catch (Exception ignored) {
      return null;
    }
  }

  private static BigDecimal bd(Object v) {
    if (v == null) return null;
    if (v instanceof BigDecimal b) return b;
    if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
    try {
      return new BigDecimal(String.valueOf(v));
    } catch (Exception e) {
      return null;
    }
  }

  private static BigDecimal nz(BigDecimal v) {
    return v == null ? BigDecimal.ZERO : v;
  }

  private static boolean notBlank(String s) {
    return s != null && !s.isBlank();
  }
}
