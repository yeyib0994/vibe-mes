package com.fluxmes.api.core;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fluxmes.api.entity.Batch;
import com.fluxmes.api.entity.DowntimeEvent;
import com.fluxmes.api.entity.DowntimeReason;
import com.fluxmes.api.entity.StepReport;
import com.fluxmes.api.entity.WorkOrder;
import com.fluxmes.api.mapper.BatchMapper;
import com.fluxmes.api.mapper.DowntimeEventMapper;
import com.fluxmes.api.mapper.DowntimeReasonMapper;
import com.fluxmes.api.mapper.StepReportMapper;
import com.fluxmes.api.mapper.WorkOrderMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Phase I 种子（幂等）：把既有批次回填为「工单 + 报工 + 停机」执行记录。
 *
 * <p>动因（plan R1）：移除 OEE 的硬编码兜底后，若历史数据没有报工与停机事实，
 * 驾驶舱将显示「数据不足」。本种子按批次反推执行记录（{@code source=BACKFILL}），
 * 使 OEE 有真实且非极端的数值可展示；真实生产中由执行层写入而非种子。
 *
 * <p>数量口径与 batch.plan_yield 一致（kg）；标准/实际工时用于性能率核算。
 */
@Component
@Order(140)
public class P5Seeder implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(P5Seeder.class);

  private static final ZoneOffset ZONE = ZoneOffset.ofHours(8);
  private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyMMdd");

  /** 工序模板（与批次 stages 顺序一致的常见流程段）。 */
  private static final String[][] STEPS = {
      {"1", "配料称量"}, {"2", "发酵"}, {"3", "提取"}, {"4", "精制"},
      {"5", "结晶"}, {"6", "干燥"}, {"7", "包装"}};

  private final WorkOrderMapper orders;
  private final StepReportMapper reports;
  private final DowntimeEventMapper downtimes;
  private final DowntimeReasonMapper reasons;
  private final BatchMapper batches;

  public P5Seeder(WorkOrderMapper orders, StepReportMapper reports, DowntimeEventMapper downtimes,
      DowntimeReasonMapper reasons, BatchMapper batches) {
    this.orders = orders;
    this.reports = reports;
    this.downtimes = downtimes;
    this.reasons = reasons;
    this.batches = batches;
  }

  @Override
  public void run(ApplicationArguments args) {
    try {
      seed();
    } catch (Exception e) {
      log.warn("P5 seed failed: {}", e.toString());
    }
  }

  /** 幂等：已有工单则跳过（用户真实数据优先）。 */
  public void seed() {
    long existing = orders.selectCount(null);
    if (existing > 0) {
      log.info("P5 seed skipped: {} work orders already present", existing);
      return;
    }
    if (reasons.selectCount(null) == 0) {
      log.warn("P5 seed skipped: downtime_reason dictionary is empty");
      return;
    }
    List<Batch> all = batches.selectList(Wrappers.<Batch>lambdaQuery()
        .orderByDesc(Batch::getId));
    if (all.isEmpty()) {
      log.info("P5 seed skipped: no batches to backfill");
      return;
    }
    int created = 0;
    LocalDate today = LocalDate.now(ZONE);
    // 近 12 个批次回填工单，日期按批次序号错开，保证近 7 天窗口内有数据
    int idx = 0;
    for (Batch b : all) {
      if (idx >= 12) break;
      LocalDate day = today.minusDays(idx);
      if (seedOrderFor(b, day, idx)) created++;
      idx++;
    }
    log.info("P5 seed done: {} work orders backfilled (with reports & downtime)", created);
  }

  private boolean seedOrderFor(Batch b, LocalDate day, int idx) {
    BigDecimal planQty = b.getPlanYield() == null ? new BigDecimal("40000")
        : b.getPlanYield();
    if (planQty.compareTo(BigDecimal.ZERO) <= 0) planQty = new BigDecimal("40000");

    String orderId = "WO-" + day.format(YYMMDD) + "-" + String.format("%03d", idx + 1);
    if (orders.selectById(orderId) != null) return false;

    OffsetDateTime planStart = day.atTime(8, 0).atOffset(ZONE);
    OffsetDateTime planEnd = day.atTime(20, 0).atOffset(ZONE);
    boolean recent = idx < 4;   // 近 4 天为在产，其余为已完工
    String status = recent ? (idx == 0 ? "RUNNING" : "RELEASED") : "FINISHED";

    WorkOrder o = new WorkOrder();
    o.setId(orderId);
    o.setBatchId(b.getId());
    o.setLine(b.getLine());
    o.setSite(b.getSite() == null ? "SITE-01" : b.getSite());
    o.setProduct(b.getProduct());
    o.setRecipeCode(b.getRecipe());
    o.setRecipeVersion(b.getRecipeVersion());
    o.setPlanQty(planQty);
    o.setUnit("kg");
    o.setPlanMinutes(720);            // 08:00–20:00 计划生产工时（可用率分母）
    o.setPlanStart(planStart);
    o.setPlanEnd(planEnd);
    o.setShift("DAY");
    o.setStatus(status);
    o.setInputQty(BigDecimal.ZERO);
    o.setGoodQty(BigDecimal.ZERO);
    o.setScrapQty(BigDecimal.ZERO);
    o.setSource("BACKFILL");
    o.setCreatedBy("seed");
    o.setCreatedAt(planStart);
    o.setUpdatedAt(planStart);
    if (!"CREATED".equals(status)) o.setReleasedAt(planStart);
    if ("RUNNING".equals(status)) o.setActualMinutes(0);
    if ("FINISHED".equals(status)) o.setFinishedAt(planEnd);
    o.setRemark("Phase I 回填工单（历史批次反推，source=BACKFILL）");
    orders.insert(o);

    // 报工：给已开工/已完工工单补 3 道工序；标准工时略低于实际工时 → 性能率 ~86–92%
    if (!"RELEASED".equals(status)) {
      int steps = 3;
      BigDecimal perStepGood = planQty.divide(BigDecimal.valueOf(steps), 3, RoundingMode.HALF_UP);
      BigDecimal scrapPerStep = perStepGood.multiply(new BigDecimal("0.025"))
          .setScale(3, RoundingMode.HALF_UP);
      BigDecimal inputPerStep = perStepGood.add(scrapPerStep);
      BigDecimal durationPerStep = new BigDecimal(140 + (idx % 3) * 12);
      BigDecimal stdPerStep = durationPerStep.multiply(new BigDecimal("0.88"))
          .setScale(1, RoundingMode.HALF_UP);

      int stepCount = "FINISHED".equals(status) ? steps : steps - 1;
      for (int s = 0; s < stepCount; s++) {
        StepReport r = new StepReport();
        r.setOrderId(orderId);
        r.setBatchId(b.getId());
        r.setStepNo(Integer.valueOf(STEPS[s][0]));
        r.setStepName(STEPS[s][1]);
        r.setEquipment(b.getEquipment());
        r.setUsername("operator");
        r.setStartedAt(planStart.plusHours(s * 3L));
        r.setFinishedAt(planStart.plusHours(s * 3L).plusMinutes(durationPerStep.longValue()));
        r.setDurationMin(durationPerStep);
        r.setInputQty(inputPerStep);
        r.setGoodQty(perStepGood);
        r.setScrapQty(scrapPerStep);
        r.setStdMinutes(stdPerStep);
        r.setCritical(s == 1);          // 发酵为关键工序（可演示双人复核）
        r.setReviewer(s == 1 ? "qc" : null);
        r.setReviewedAt(s == 1 ? r.getFinishedAt() : null);
        r.setSource("BACKFILL");
        r.setRemark("回填报工");
        r.setCreatedAt(r.getFinishedAt());
        reports.insert(r);
      }
      // 汇总工单
      BigDecimal good = perStepGood.multiply(BigDecimal.valueOf(stepCount));
      BigDecimal scrap = scrapPerStep.multiply(BigDecimal.valueOf(stepCount));
      BigDecimal input = inputPerStep.multiply(BigDecimal.valueOf(stepCount));
      o.setGoodQty(good);
      o.setScrapQty(scrap);
      o.setInputQty(input);
      o.setActualMinutes(durationPerStep.multiply(BigDecimal.valueOf(stepCount)).intValue());
      orders.updateById(o);
    }

    // 停机：约 1/3 工单补停机记录（含计划停机，用于验证 FR-17 分类）
    if (idx % 3 == 0) {
      insertDowntime("MECH", b, orderId, planStart.plusHours(6), 95, "搅拌电机过热跳停");
    } else if (idx % 3 == 1) {
      insertDowntime("CHANGEOVER", b, orderId, planStart.plusHours(11), 55, "换规格清场（计划）");
    } else if (idx % 3 == 2) {
      insertDowntime("MATERIAL", b, orderId, planStart.plusHours(4), 140, "上道工序未及时供料");
    }
    return true;
  }

  private void insertDowntime(String code, Batch b, String orderId, OffsetDateTime start,
      int minutes, String desc) {
    DowntimeReason reason = reasons.selectById(code);
    if (reason == null) return;
    DowntimeEvent d = new DowntimeEvent();
    d.setOrderId(orderId);
    d.setEquipment(b.getEquipment());
    d.setLine(b.getLine());
    d.setSite(b.getSite() == null ? "SITE-01" : b.getSite());
    d.setShift("DAY");
    d.setReasonCode(code);
    d.setPlanned("PLANNED".equals(reason.getCategory()));
    d.setStartedAt(start);
    d.setEndedAt(start.plusMinutes(minutes));
    d.setDurationMin(BigDecimal.valueOf(minutes));
    d.setDescription(desc);
    d.setSource("BACKFILL");
    d.setCreatedBy("seed");
    d.setCreatedAt(start.plusMinutes(minutes));
    downtimes.insert(d);
  }
}
