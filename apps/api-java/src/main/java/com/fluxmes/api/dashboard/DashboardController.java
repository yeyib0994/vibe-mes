package com.fluxmes.api.dashboard;

import static com.fluxmes.api.common.ApiSupport.map;
import static com.fluxmes.api.common.ApiSupport.nowIso;
import static com.fluxmes.api.common.ApiSupport.pct1;
import static com.fluxmes.api.common.ApiSupport.r1;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fluxmes.api.alarm.AlarmService;
import com.fluxmes.api.common.ApiSupport;
import com.fluxmes.api.core.FixtureStore;
import com.fluxmes.api.entity.Alarm;
import com.fluxmes.api.entity.Batch;
import com.fluxmes.api.entity.Deviation;
import com.fluxmes.api.entity.ProductionLine;
import com.fluxmes.api.entity.QcTask;
import com.fluxmes.api.entity.Site;
import com.fluxmes.api.execution.OeeService;
import com.fluxmes.api.mapper.AlarmMapper;
import com.fluxmes.api.mapper.BatchMapper;
import com.fluxmes.api.mapper.DeviationMapper;
import com.fluxmes.api.mapper.ProductionLineMapper;
import com.fluxmes.api.mapper.QcTaskMapper;
import com.fluxmes.api.mapper.SiteMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 生产驾驶舱聚合端点（目标契约 = fluxmes/src/api/dashboard.ts buildCockpit）。
 *
 * <p>口径说明：
 * <ul>
 *   <li>Phase 1：activeAlarms / recentAlarms 由报警台账（PostgreSQL）实时计算；</li>
 *   <li>Phase D2：日产量/合格率/OEE 改为按产线从批次与质检数据聚合（不再硬编码常量），
 *       支持 {@code ?line=LINE-1} 维度切换；</li>
 *   <li>Phase D3：{@code /shift-report} 生成当班班报（JSON + Markdown 双形态，可导出）。</li>
 * </ul>
 * Phase I（2026-09-15）：OEE 三因子改由 {@link OeeService} 按真实执行事实计算
 * （可用率 = 计划工时−计划外停机；性能率 = 标准工时/实际工时；良品率 = 合格/(合格+废次品)），
 * <b>移除 92.1 / 96.5 / 33.4 / Math.min(120,…) 等硬编码兜底</b>：
 * 数据不足时因子为 {@code null} 且 {@code dataSufficient=false}，由前端展示「数据不足」。
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

  private static final ZoneId ZONE = ZoneId.systemDefault();
  private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");
  private static final double FALLBACK_PASS_RATE = 99.2;

  private final FixtureStore store;
  private final AlarmMapper alarms;
  private final BatchMapper batches;
  private final QcTaskMapper qcTasks;
  private final DeviationMapper deviations;
  private final ProductionLineMapper lines;
  /** G4 · 厂区主数据（多厂区切换与聚合）。 */
  private final SiteMapper sites;
  /** Phase I · 真实 OEE 计算（执行事实口径，替代原替代口径）。 */
  private final OeeService oeeService;

  public DashboardController(FixtureStore store, AlarmMapper alarms, BatchMapper batches,
      QcTaskMapper qcTasks, DeviationMapper deviations, ProductionLineMapper lines,
      SiteMapper sites, OeeService oeeService) {
    this.store = store;
    this.alarms = alarms;
    this.batches = batches;
    this.qcTasks = qcTasks;
    this.deviations = deviations;
    this.lines = lines;
    this.sites = sites;
    this.oeeService = oeeService;
  }

  /** G4 · 厂区主数据：{ sites: [{ code, name, address, lineCount, batchCount }] } */
  @GetMapping("/sites")
  public Map<String, Object> sites() {
    List<Map<String, Object>> items = new ArrayList<>();
    for (Site s : sites.selectList(Wrappers.<Site>lambdaQuery()
        .eq(Site::getEnabled, true).orderByAsc(Site::getCode))) {
      List<String> own = lines.selectList(Wrappers.<ProductionLine>lambdaQuery()
              .eq(ProductionLine::getSiteCode, s.getCode()))
          .stream().map(ProductionLine::getCode).toList();
      long batchCount = own.isEmpty() ? 0 : batches.selectCount(
          Wrappers.<Batch>lambdaQuery().in(Batch::getLine, own));
      items.add(map(
          "code", s.getCode(),
          "name", s.getName(),
          "address", s.getAddress(),
          "lineCount", own.size(),
          "batchCount", batchCount));
    }
    return map("generatedAt", nowIso(), "sites", items);
  }

  /** D2 · 产线主数据：{ lines: [...] }；G4 支持 ?site= 过滤。 */
  @GetMapping("/lines")
  public Map<String, Object> lines(@RequestParam(required = false) String site) {
    List<Map<String, Object>> items = new ArrayList<>();
    for (ProductionLine l : lines.selectList(Wrappers.<ProductionLine>lambdaQuery()
        .eq(ProductionLine::getEnabled, true)
        .eq(site != null && !site.isBlank(), ProductionLine::getSiteCode, site)
        .orderByAsc(ProductionLine::getCode))) {
      List<Batch> own = batches.selectList(Wrappers.<Batch>lambdaQuery().eq(Batch::getLine, l.getCode()));
      items.add(map(
          "code", l.getCode(),
          "name", l.getName(),
          "workshop", l.getWorkshop(),
          "capacityT", l.getCapacityT(),
          "site", l.getSiteCode(),
          "batchCount", own.size(),
          "activeBatches", own.stream().filter(b -> "running".equals(b.getStatus())).count()));
    }
    return map("generatedAt", nowIso(), "lines", items);
  }

  /** G4 · 驾驶舱：{@code GET /api/dashboard/cockpit?site=SITE-01&line=LINE-1} */
  @GetMapping("/cockpit")
  public Map<String, Object> cockpit(@RequestParam(required = false) String line,
      @RequestParam(required = false) String site) {
    return cockpitBySite(line, site);
  }

  /** 兼容旧契约 {@code /cockpit/{siteId}}（siteId 现为厂区代码）。 */
  @GetMapping("/cockpit/{siteId}")
  public Map<String, Object> cockpitBySite(@RequestParam(required = false) String line,
      @PathVariable(required = false) String siteId) {
    String site = (siteId == null || !siteId.startsWith("SITE-")) ? null : siteId;
    List<Alarm> allAlarms = alarms.selectList(Wrappers.<Alarm>lambdaQuery()
        .orderByDesc(Alarm::getCreatedAt).orderByDesc(Alarm::getId));
    long activeTotal = allAlarms.stream().filter(a -> !"recovered".equals(a.getStatus())).count();
    long overdue = allAlarms.stream().filter(AlarmService::isOverdue).count();

    Map<String, Object> kpis = map(
        "dailyOutput", dailyOutput(line, site),
        "batchPassRate", map("value", passRate(), "target", 98.5),
        "oee", oee(line, site),
        "activeAlarms", map(
            "total", activeTotal,
            "critical", countLevel(allAlarms, "critical"),
            "major", countLevel(allAlarms, "major"),
            "minor", countLevel(allAlarms, "minor"),
            "overdue", overdue));
    return map(
        "siteId", site == null ? "ALL" : site,
        "site", site == null ? "ALL" : site,
        "line", line == null ? "ALL" : line,
        "generatedAt", nowIso(),
        "kpis", kpis,
        "planRate", store.planRate(),
        "productionTrend", store.productionTrend(),
        "equipment", store.tankEquipment(),
        "runningBatches", runningBatches(line, site),
        "recentAlarms", allAlarms.stream().limit(4).map(AlarmService::toView).toList());
  }

  /**
   * D3 · 班报：{@code GET /api/dashboard/shift-report?date=2026-09-14&shift=DAY&line=LINE-1}
   * 返回结构化数据 + Markdown 文本（前端可直接 Blob 下载 .md）。
   * 班次窗口：DAY 08:00–20:00；NIGHT 20:00–次日 08:00。
   */
  @GetMapping("/shift-report")
  public Map<String, Object> shiftReport(@RequestParam(required = false) String date,
      @RequestParam(defaultValue = "DAY") String shift,
      @RequestParam(required = false) String line,
      @RequestParam(required = false) String site) {   // G4 · 厂区维度
    LocalDate d = date == null || date.isBlank() ? LocalDate.now() : LocalDate.parse(date);
    boolean day = !"NIGHT".equalsIgnoreCase(shift);
    LocalDateTime start = d.atTime(day ? 8 : 20, 0);
    LocalDateTime end = day ? d.atTime(20, 0) : d.plusDays(1).atTime(8, 0);
    OffsetDateTime from = start.atZone(ZONE).toOffsetDateTime();
    OffsetDateTime to = end.atZone(ZONE).toOffsetDateTime();

    List<Batch> shiftBatches = batches.selectList(Wrappers.<Batch>lambdaQuery()
            .eq(line != null && !line.isBlank(), Batch::getLine, line)
            .eq(site != null && !site.isBlank(), Batch::getSite, site)
            .ge(Batch::getCreatedAt, from).le(Batch::getCreatedAt, to))
        .stream().sorted(Comparator.comparing(Batch::getId)).toList();
    List<Alarm> shiftAlarms = alarms.selectList(Wrappers.<Alarm>lambdaQuery()
        .ge(Alarm::getCreatedAt, from).le(Alarm::getCreatedAt, to));
    List<QcTask> shiftQc = qcTasks.selectList(Wrappers.<QcTask>lambdaQuery()
        .eq(QcTask::getStatus, "done"));
    List<Deviation> openDev = deviations.selectList(Wrappers.<Deviation>lambdaQuery()
        .ne(Deviation::getStatus, "closed"));

    long done = shiftBatches.stream().filter(b -> "done".equals(b.getStatus())).count();
    long abnormal = shiftBatches.stream().filter(b -> "abnormal".equals(b.getStatus())).count();
    double output = toTons(shiftBatches);
    long acked = shiftAlarms.stream().filter(a -> !"unacked".equals(a.getStatus())).count();
    long overdue = shiftAlarms.stream().filter(AlarmService::isOverdue).count();
    long qcPass = shiftQc.stream().filter(t -> Boolean.TRUE.equals(t.getPass())).count();

    Map<String, Object> report = map(
        "date", d.toString(),
        "shift", day ? "DAY" : "NIGHT",
        "window", map("start", start.toString(), "end", end.toString()),
        "line", line == null ? "ALL" : line,
        "site", site == null ? "ALL" : site,
        "generatedAt", nowIso(),
        "summary", map(
            "batchTotal", shiftBatches.size(),
            "batchDone", done,
            "batchAbnormal", abnormal,
            "outputT", r1(output),
            "alarmTotal", shiftAlarms.size(),
            "alarmAcked", acked,
            "alarmOverdue", overdue,
            "qcTotal", shiftQc.size(),
            "qcPass", qcPass,
            "qcPassRate", shiftQc.isEmpty() ? FALLBACK_PASS_RATE : pct1(qcPass * 100.0 / shiftQc.size()),
            "openDeviations", openDev.size()),
        "batches", shiftBatches.stream().map(b -> map(
            "id", b.getId(), "product", b.getProduct(), "stage", b.getStage(),
            "status", b.getStatus(), "progress", b.getProgress(),
            "operator", b.getOperator(), "released", b.getReleased())).toList(),
        "alarms", shiftAlarms.stream().map(a -> map(
            "id", a.getId(), "level", a.getLevel(), "source", a.getSource(),
            "content", a.getContent(), "status", a.getStatus(),
            "overdue", AlarmService.isOverdue(a))).toList(),
        "deviations", openDev.stream().map(v -> map(
            "id", v.getId(), "batchId", v.getBatchId(), "status", v.getStatus(),
            "source", v.getSource(), "description", v.getDescription())).toList());

    String markdown = renderMarkdown(report, shiftBatches, shiftAlarms, openDev);
    report.put("markdown", markdown);
    return report;
  }

  /* ---------------- KPI 聚合（D2） ---------------- */

  /** 日产量：当日批次按进度折算产出 / 产线设计产能（G4 支持厂区维度）。 */
  private Map<String, Object> dailyOutput(String line, String site) {
    OffsetDateTime dayStart = LocalDate.now().atStartOfDay(ZONE).toOffsetDateTime();
    List<Batch> today = batches.selectList(Wrappers.<Batch>lambdaQuery()
            .eq(line != null && !line.isBlank(), Batch::getLine, line)
            .eq(site != null && !site.isBlank(), Batch::getSite, site)
            .ge(Batch::getCreatedAt, dayStart));
    // 单位换算：batch.plan_yield 存 kg，产能与 KPI 口径为吨
    double actual = toTons(today);
    double plan = capacity(line, site);
    // Phase I · 移除 33.4 示例基线（假数据）：无当日批次时如实为 0 并标记数据不足
    double progressPct = plan > 0 ? r1(actual / plan * 100) : 0;
    return map("actual", r1(actual), "plan", r1(plan),
        "progressPct", progressPct,
        "vsSchedulePct", plan > 0 ? r1(progressPct - 69.6) : null,
        "dataSufficient", !today.isEmpty());
  }

  /** 批次产出折算（吨）：plan_yield(kg) × 进度% ÷ 1000。 */
  private static double toTons(List<Batch> list) {
    return list.stream()
        .mapToDouble(b -> (b.getPlanYield() == null ? 0 : b.getPlanYield().doubleValue())
            * (b.getProgress() == null ? 0 : b.getProgress()) / 100.0 / 1000.0)
        .sum();
  }

  /** 质检合格率：已完成质检任务的合格占比（C1 数据完整性：无数据时回落基线）。 */
  private double passRate() {
    List<QcTask> done = qcTasks.selectList(Wrappers.<QcTask>lambdaQuery().eq(QcTask::getStatus, "done"));
    if (done.isEmpty()) return FALLBACK_PASS_RATE;
    long pass = done.stream().filter(t -> Boolean.TRUE.equals(t.getPass())).count();
    return pct1(pass * 100.0 / done.size());
  }

  /** OEE = 可用性 × 性能 × 质量（G4 支持厂区维度）。 */
  /**
   * Phase I · 真实 OEE（spec FR-20 / FR-21）：改由执行事实计算，
   * 移除原「非异常批次占比 / 当日产量÷产能 / 质检合格率」替代口径与
   * 92.1 / 96.5 / Math.min(120,…) 硬编码兜底。
   *
   * <p>兼容既有字段 value / availability / performance / quality，但值可能为 {@code null}；
   * 前端须依据 {@code dataSufficient=false} 展示「数据不足」而非 0。
   */
  private Map<String, Object> oee(String line, String site) {
    Map<String, Object> real = oeeService.compute(site, line, null, null, null, null);
    return map(
        "value", real.get("oee"),
        "availability", real.get("availability"),
        "performance", real.get("performance"),
        "quality", real.get("quality"),
        "dataSufficient", real.get("dataSufficient"),
        "missing", real.get("missing"),
        "note", real.get("note"),
        "plannedMinutes", real.get("plannedMinutes"),
        "unplannedStopMinutes", real.get("unplannedStopMinutes"),
        "performanceOverrun", real.get("performanceOverrun"),
        "formula", real.get("formula"),
        "source", "execution");
  }

  /** 产线日设计产能（吨）；未指定产线则取（该厂区）全部启用产线之和。 */
  private double capacity(String line, String site) {
    if (line != null && !line.isBlank()) {
      ProductionLine l = lines.selectById(line);
      if (l != null && l.getCapacityT() != null) return l.getCapacityT().doubleValue();
      return 48.0;
    }
    return lines.selectList(Wrappers.<ProductionLine>lambdaQuery()
            .eq(ProductionLine::getEnabled, true)
            .eq(site != null && !site.isBlank(), ProductionLine::getSiteCode, site))
        .stream().mapToDouble(l -> l.getCapacityT() == null ? 0 : l.getCapacityT().doubleValue()).sum();
  }

  /** 在产批次卡片：优先取 PG 中 running 批次，无则回落 fixture（保持前端既有展示）。 */
  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> runningBatches(String line, String site) {
    List<Batch> running = batches.selectList(Wrappers.<Batch>lambdaQuery()
        .eq(Batch::getStatus, "running")
        .eq(line != null && !line.isBlank(), Batch::getLine, line)
        .eq(site != null && !site.isBlank(), Batch::getSite, site));
    if (!running.isEmpty()) {
      return running.stream().limit(6).map(b -> map(
          "id", b.getId(),
          "product", b.getProduct(),
          "stage", b.getStage(),
          "progress", b.getProgress(),
          "equipment", b.getEquipment(),
          "operator", b.getOperator(),
          "line", b.getLine())).toList();
    }
    return (List<Map<String, Object>>) store.runningBatches();
  }

  private static long countLevel(List<Alarm> alarms, String level) {
    return alarms.stream()
        .filter(a -> !"recovered".equals(a.getStatus()) && level.equals(a.getLevel()))
        .count();
  }

  /* ---------------- 班报渲染（D3） ---------------- */

  private static String renderMarkdown(Map<String, Object> report, List<Batch> batchList,
      List<Alarm> alarmList, List<Deviation> devList) {
    Map<String, Object> s = (Map<String, Object>) report.get("summary");
    StringBuilder md = new StringBuilder();
    md.append("# 班报 · ").append(report.get("date")).append(" · ")
        .append("DAY".equals(report.get("shift")) ? "白班 08:00–20:00" : "夜班 20:00–次日08:00")
        .append(" · 产线 ").append(report.get("line")).append("\n\n");
    md.append("> 生成时间：").append(report.get("generatedAt")).append("\n\n");

    md.append("## 一、当班概览\n\n");
    md.append("| 指标 | 数值 |\n| --- | --- |\n");
    md.append("| 批次总数 | ").append(s.get("batchTotal")).append(" |\n");
    md.append("| 已完成 | ").append(s.get("batchDone")).append(" |\n");
    md.append("| 异常批次 | ").append(s.get("batchAbnormal")).append(" |\n");
    md.append("| 当班产量（吨） | ").append(s.get("outputT")).append(" |\n");
    md.append("| 报警总数 | ").append(s.get("alarmTotal")).append(" |\n");
    md.append("| 已确认 | ").append(s.get("alarmAcked")).append(" |\n");
    md.append("| 逾期未确认 | ").append(s.get("alarmOverdue")).append(" |\n");
    md.append("| 质检合格率（%） | ").append(s.get("qcPassRate")).append(" |\n");
    md.append("| 未关闭偏差 | ").append(s.get("openDeviations")).append(" |\n\n");

    md.append("## 二、批次明细\n\n");
    if (batchList.isEmpty()) {
      md.append("_当班无批次记录_\n\n");
    } else {
      md.append("| 批次号 | 产品 | 当前工序 | 状态 | 进度 | 操作人 | 已放行 |\n| --- | --- | --- | --- | --- | --- | --- |\n");
      for (Batch b : batchList) {
        md.append("| ").append(b.getId()).append(" | ").append(nvl(b.getProduct())).append(" | ")
            .append(nvl(b.getStage())).append(" | ").append(nvl(b.getStatus())).append(" | ")
            .append(b.getProgress() == null ? 0 : b.getProgress()).append("% | ")
            .append(nvl(b.getOperator())).append(" | ")
            .append(Boolean.TRUE.equals(b.getReleased()) ? "是" : "否").append(" |\n");
      }
      md.append("\n");
    }

    md.append("## 三、报警清单\n\n");
    if (alarmList.isEmpty()) {
      md.append("_当班无报警_\n\n");
    } else {
      md.append("| 报警号 | 级别 | 报警源 | 内容 | 状态 | 逾期 |\n| --- | --- | --- | --- | --- | --- |\n");
      for (Alarm a : alarmList) {
        md.append("| ").append(a.getId()).append(" | ").append(nvl(a.getLevel())).append(" | ")
            .append(nvl(a.getSource())).append(" | ").append(nvl(a.getContent())).append(" | ")
            .append(nvl(a.getStatus())).append(" | ")
            .append(AlarmService.isOverdue(a) ? "**是**" : "否").append(" |\n");
      }
      md.append("\n");
    }

    md.append("## 四、未关闭偏差\n\n");
    if (devList.isEmpty()) {
      md.append("_无未关闭偏差单_\n");
    } else {
      for (Deviation v : devList) {
        md.append("- ").append(v.getId()).append("（批次 ").append(nvl(v.getBatchId()))
            .append("，").append(nvl(v.getStatus())).append("）：").append(nvl(v.getDescription())).append("\n");
      }
    }
    return md.toString();
  }

  private static String nvl(Object v) {
    return v == null ? "-" : String.valueOf(v);
  }
}
