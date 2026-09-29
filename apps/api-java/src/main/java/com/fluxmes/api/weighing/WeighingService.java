package com.fluxmes.api.weighing;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fluxmes.api.alarm.AlarmService;
import com.fluxmes.api.audit.AuditService;
import com.fluxmes.api.common.ApiSupport;
import com.fluxmes.api.common.CurrentUser;
import com.fluxmes.api.common.Roles;
import com.fluxmes.api.entity.Batch;
import com.fluxmes.api.entity.Deviation;
import com.fluxmes.api.entity.WeighingItem;
import com.fluxmes.api.entity.WeighingTask;
import com.fluxmes.api.mapper.BatchMapper;
import com.fluxmes.api.mapper.DeviationMapper;
import com.fluxmes.api.mapper.WeighingItemMapper;
import com.fluxmes.api.mapper.WeighingTaskMapper;
import com.fluxmes.api.personnel.PersonnelService;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
 * G3 · 配料称量与容差校验服务。
 *
 * <p>食品配料的称量偏差直接影响配方一致性与成品合规（过敏原、添加剂限量均按配方比例管控）。
 * 本服务把「目标量 ± 容差百分比」落到服务端强制校验：
 * <ul>
 *   <li>称量人须持有 {@code WEIGHING} 岗位资质与有效健康证（G2 门禁）；</li>
 *   <li>超差（|偏差| &gt; 容差）自动创建偏差单 + major 报警，任务置 BLOCKED；</li>
 *   <li>超差记录须由另一人复核（QC 及以上，复核人不得为称量人本人）后方可继续；</li>
 *   <li>已放行批次禁止补录称量数据。</li>
 * </ul>
 */
@Service
public class WeighingService {

  private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyMMdd");

  private final WeighingTaskMapper tasks;
  private final WeighingItemMapper items;
  private final BatchMapper batches;
  private final DeviationMapper deviations;
  private final AlarmService alarms;
  private final AuditService audit;
  private final PersonnelService personnel;

  public WeighingService(WeighingTaskMapper tasks, WeighingItemMapper items, BatchMapper batches,
      DeviationMapper deviations, AlarmService alarms, AuditService audit,
      PersonnelService personnel) {
    this.tasks = tasks;
    this.items = items;
    this.batches = batches;
    this.deviations = deviations;
    this.alarms = alarms;
    this.audit = audit;
    this.personnel = personnel;
  }

  /* =================== 称量任务 =================== */

  /** GET /api/weighing/tasks —— 称量任务列表（?batchId=&status=）。 */
  public List<Map<String, Object>> tasks(String batchId, String status) {
    return tasks.selectList(Wrappers.<WeighingTask>lambdaQuery()
            .eq(batchId != null && !batchId.isBlank(), WeighingTask::getBatchId, batchId)
            .eq(status != null && !status.isBlank(), WeighingTask::getStatus, status)
            .orderByDesc(WeighingTask::getCreatedAt))
        .stream().map(this::taskView).toList();
  }

  /** POST /api/weighing/tasks —— 创建称量任务（配方目标量 + 允许容差）。 */
  public Map<String, Object> createTask(Map<String, Object> body) {
    String batchId = str(body.get("batchId"));
    if (isBlank(batchId)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "batchId 必填");
    Batch b = batches.selectById(batchId);
    if (b == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "批次不存在: " + batchId);
    if (Boolean.TRUE.equals(b.getReleased())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "批次已放行，禁止新增称量任务");
    }
    BigDecimal target = dec(body.get("targetQty"));
    if (target == null || target.compareTo(BigDecimal.ZERO) <= 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "targetQty 必填且须大于 0");
    }
    BigDecimal tol = dec(body.get("tolerancePct"));
    if (tol == null || tol.compareTo(BigDecimal.ZERO) < 0) tol = new BigDecimal("1.0");
    if (tol.compareTo(new BigDecimal("50")) > 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tolerancePct 不得超过 50%");
    }

    String id = "WT-" + LocalDate.now().format(YYMMDD) + "-"
        + String.format("%03d", tasks.selectCount(null) + 1);
    WeighingTask t = new WeighingTask();
    t.setId(id);
    t.setBatchId(batchId);
    t.setMaterialCode(str(body.get("materialCode")));
    t.setMaterialName(str(body.get("materialName")));
    t.setTargetQty(target);
    t.setUnit(str(body.getOrDefault("unit", "kg")));
    t.setTolerancePct(tol);
    t.setTotalWeighed(BigDecimal.ZERO);
    t.setStatus("OPEN");
    t.setCreatedBy(CurrentUser.username());
    t.setCreatedAt(OffsetDateTime.now());
    tasks.insert(t);
    audit.record("weighing.task.create", "weighing_task", id, null,
        ApiSupport.map("batchId", batchId, "targetQty", target, "tolerancePct", tol));
    return taskView(t);
  }

  /* =================== 称量登记 =================== */

  /**
   * POST /api/weighing/tasks/{id}/weigh —— 登记一次实际称量并做容差判定。
   * 超差自动建偏差单 + major 报警，任务置 BLOCKED，须 QC 复核后恢复。
   */
  public Map<String, Object> weigh(String taskId, Map<String, Object> body) {
    // G2 · 资质门禁：配料称量资质 + 有效健康证
    personnel.requireCapability(PersonnelService.CAP_WEIGHING);

    WeighingTask t = tasks.selectById(taskId);
    if (t == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "称量任务不存在: " + taskId);
    if ("DONE".equals(t.getStatus())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "称量任务已完成，不可继续称量");
    }
    Batch b = batches.selectById(t.getBatchId());
    if (b != null && Boolean.TRUE.equals(b.getReleased())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "批次已放行，禁止补录称量数据");
    }
    BigDecimal actual = dec(body.get("actualQty"));
    if (actual == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "actualQty 必填");

    BigDecimal target = t.getTargetQty();
    BigDecimal tol = t.getTolerancePct() == null ? new BigDecimal("1.0") : t.getTolerancePct();
    // 偏差百分比 =（实际 - 目标）/ 目标 × 100
    BigDecimal devPct = actual.subtract(target)
        .divide(target, 6, RoundingMode.HALF_UP)
        .multiply(new BigDecimal("100"))
        .setScale(3, RoundingMode.HALF_UP);
    boolean pass = devPct.abs().compareTo(tol) <= 0;
    String result = pass ? "PASS" : (devPct.compareTo(BigDecimal.ZERO) > 0 ? "OVER" : "UNDER");

    int seq = items.selectCount(Wrappers.<WeighingItem>lambdaQuery()
        .eq(WeighingItem::getTaskId, taskId)).intValue() + 1;
    WeighingItem it = new WeighingItem();
    it.setTaskId(taskId);
    it.setSeq(seq);
    it.setActualQty(actual);
    it.setDeviationPct(devPct);
    it.setResult(result);
    it.setOperator(CurrentUser.username());
    it.setEquipment(str(body.get("equipment")));
    it.setWeighedAt(OffsetDateTime.now());
    it.setRemark(str(body.get("remark")));

    if (!pass) {
      String devId = newDeviation(t.getBatchId(), "weighing",
          "称量超差：" + (t.getMaterialName() == null ? t.getMaterialCode() : t.getMaterialName())
              + " 目标 " + target + t.getUnit() + "，实测 " + actual + t.getUnit()
              + "（偏差 " + devPct + "%，允许 ±" + tol + "%）");
      it.setDeviationId(devId);
      Map<String, Object> alarm = alarms.raise(
          "称量 " + taskId + " " + (t.getMaterialName() == null ? "" : t.getMaterialName()),
          "配料称量超差：实测 " + actual + t.getUnit() + "，偏差 " + devPct + "%",
          "major",
          String.valueOf(actual),
          "±" + tol + "%");
      if (alarm != null) it.setAlarmId(str(alarm.get("id")));
      // 任务阻断：须复核后方可继续
      t.setStatus("BLOCKED");
    } else {
      t.setTotalWeighed((t.getTotalWeighed() == null ? BigDecimal.ZERO : t.getTotalWeighed()).add(actual));
      if (t.getTotalWeighed().compareTo(target) >= 0) t.setStatus("DONE");
    }
    tasks.updateById(t);
    items.insert(it);
    audit.record(pass ? "weighing.weigh" : "weighing.out_of_tolerance", "weighing_item",
        String.valueOf(it.getId()), null,
        ApiSupport.map("taskId", taskId, "actualQty", actual, "deviationPct", devPct,
            "result", result, "deviationId", it.getDeviationId()));
    return itemView(it);
  }

  /** POST /api/weighing/items/{id}/review —— 超差复核（QC+，复核人不得为称量人本人）。 */
  public Map<String, Object> review(Long id) {
    if (!CurrentUser.hasRole(Roles.QC)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅质检员及以上可复核超差记录");
    }
    personnel.requireCapability(PersonnelService.CAP_BATCH_REVIEW);
    WeighingItem it = items.selectById(id);
    if (it == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "称量记录不存在: " + id);
    String me = CurrentUser.username();
    if (me.equals(it.getOperator())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "复核人不得为称量人本人（双人复核要求）");
    }
    it.setReviewer(me);
    it.setReviewedAt(OffsetDateTime.now());
    items.updateById(it);

    // 复核后解除任务阻断（仍有未复核的超差记录则维持 BLOCKED）
    WeighingTask t = tasks.selectById(it.getTaskId());
    if (t != null && "BLOCKED".equals(t.getStatus())) {
      long unreviewed = items.selectList(Wrappers.<WeighingItem>lambdaQuery()
              .eq(WeighingItem::getTaskId, t.getId())
              .ne(WeighingItem::getResult, "PASS"))
          .stream().filter(x -> x.getReviewer() == null || x.getReviewer().isBlank()).count();
      if (unreviewed == 0) {
        t.setStatus(t.getTotalWeighed() != null
            && t.getTotalWeighed().compareTo(t.getTargetQty()) >= 0 ? "DONE" : "OPEN");
        tasks.updateById(t);
      }
    }
    audit.record("weighing.review", "weighing_item", String.valueOf(id), null,
        ApiSupport.map("reviewer", me, "taskId", it.getTaskId()));
    return itemView(it);
  }

  /** GET /api/weighing/tasks/{id}/items —— 某任务的称量明细。 */
  public List<Map<String, Object>> itemsOf(String taskId) {
    return items.selectList(Wrappers.<WeighingItem>lambdaQuery()
            .eq(WeighingItem::getTaskId, taskId)
            .orderByAsc(WeighingItem::getSeq))
        .stream().map(this::itemView).toList();
  }

  /** GET /api/weighing/summary —— 称量合规看板：合格率 / 超差数 / 待复核数。 */
  public Map<String, Object> summary() {
    List<WeighingItem> all = items.selectList(null);
    long total = all.size();
    long pass = all.stream().filter(i -> "PASS".equals(i.getResult())).count();
    long over = all.stream().filter(i -> "OVER".equals(i.getResult())).count();
    long under = all.stream().filter(i -> "UNDER".equals(i.getResult())).count();
    long pending = all.stream()
        .filter(i -> !"PASS".equals(i.getResult()))
        .filter(i -> i.getReviewer() == null || i.getReviewer().isBlank())
        .count();
    List<WeighingTask> ts = tasks.selectList(null);
    Map<String, Object> byStatus = new LinkedHashMap<>();
    for (String s : List.of("OPEN", "BLOCKED", "DONE")) {
      byStatus.put(s, ts.stream().filter(t -> s.equals(t.getStatus())).count());
    }
    return ApiSupport.map(
        "generatedAt", ApiSupport.nowIso(),
        "taskCount", ts.size(),
        "weighCount", total,
        "passCount", pass,
        "overCount", over,
        "underCount", under,
        "outOfToleranceCount", over + under,
        "passRatePercent", total == 0 ? 100.0 : ApiSupport.pct1(pass * 100.0 / total),
        "pendingReviewCount", pending,
        "tasksByStatus", byStatus);
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

  private Map<String, Object> taskView(WeighingTask t) {
    BigDecimal target = t.getTargetQty() == null ? BigDecimal.ZERO : t.getTargetQty();
    BigDecimal done = t.getTotalWeighed() == null ? BigDecimal.ZERO : t.getTotalWeighed();
    double progress = target.compareTo(BigDecimal.ZERO) == 0 ? 0
        : ApiSupport.r1(done.multiply(new BigDecimal("100"))
            .divide(target, 4, RoundingMode.HALF_UP).doubleValue());
    List<WeighingItem> own = items.selectList(
        Wrappers.<WeighingItem>lambdaQuery().eq(WeighingItem::getTaskId, t.getId()));
    return ApiSupport.map(
        "id", t.getId(),
        "batchId", t.getBatchId(),
        "materialCode", t.getMaterialCode(),
        "materialName", t.getMaterialName(),
        "targetQty", target,
        "unit", t.getUnit(),
        "tolerancePct", t.getTolerancePct(),
        "totalWeighed", done,
        "progressPercent", progress,
        "status", t.getStatus(),
        "weighCount", own.size(),
        "createdBy", t.getCreatedBy(),
        "createdAt", t.getCreatedAt() == null ? null : t.getCreatedAt().toString(),
        "items", new ArrayList<>(own.stream().map(this::itemView).toList()));
  }

  private Map<String, Object> itemView(WeighingItem i) {
    return ApiSupport.map(
        "id", i.getId() == null ? null : String.valueOf(i.getId()),
        "taskId", i.getTaskId(),
        "seq", i.getSeq(),
        "actualQty", i.getActualQty(),
        "deviationPct", i.getDeviationPct(),
        "result", i.getResult(),
        "deviationId", i.getDeviationId(),
        "alarmId", i.getAlarmId(),
        "operator", i.getOperator(),
        "reviewer", i.getReviewer(),
        "reviewedAt", i.getReviewedAt() == null ? null : i.getReviewedAt().toString(),
        "equipment", i.getEquipment(),
        "weighedAt", i.getWeighedAt() == null ? null : i.getWeighedAt().toString(),
        "remark", i.getRemark());
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
