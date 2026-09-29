package com.fluxmes.api.core;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fluxmes.api.entity.Batch;
import com.fluxmes.api.entity.BatchInput;
import com.fluxmes.api.entity.BatchStep;
import com.fluxmes.api.entity.CcpPoint;
import com.fluxmes.api.entity.CcpRecord;
import com.fluxmes.api.entity.CleaningRecord;
import com.fluxmes.api.entity.EnvMonitoring;
import com.fluxmes.api.entity.Material;
import com.fluxmes.api.entity.MaterialLot;
import com.fluxmes.api.entity.RetentionSample;
import com.fluxmes.api.mapper.BatchInputMapper;
import com.fluxmes.api.mapper.BatchMapper;
import com.fluxmes.api.mapper.BatchStepMapper;
import com.fluxmes.api.mapper.CcpPointMapper;
import com.fluxmes.api.mapper.CcpRecordMapper;
import com.fluxmes.api.mapper.CleaningRecordMapper;
import com.fluxmes.api.mapper.EnvMonitoringMapper;
import com.fluxmes.api.mapper.MaterialLotMapper;
import com.fluxmes.api.mapper.MaterialMapper;
import com.fluxmes.api.mapper.RetentionSampleMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Phase E 食品行业种子（幂等，表非空则跳过）：
 * HACCP 关键控制点与监控记录、清场记录、环境监测、物料主数据与原料批、
 * 投料谱系、电子批记录工序、留样记录，并回填历史批次的效期。
 */
@Component
@Order(110)
public class FoodSafetySeeder implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(FoodSafetySeeder.class);

  private final BatchMapper batches;
  private final CcpPointMapper ccpPoints;
  private final CcpRecordMapper ccpRecords;
  private final CleaningRecordMapper cleanings;
  private final EnvMonitoringMapper envRecords;
  private final MaterialMapper materials;
  private final MaterialLotMapper lots;
  private final BatchInputMapper inputs;
  private final BatchStepMapper steps;
  private final RetentionSampleMapper samples;

  public FoodSafetySeeder(BatchMapper batches, CcpPointMapper ccpPoints,
      CcpRecordMapper ccpRecords, CleaningRecordMapper cleanings, EnvMonitoringMapper envRecords,
      MaterialMapper materials, MaterialLotMapper lots, BatchInputMapper inputs,
      BatchStepMapper steps, RetentionSampleMapper samples) {
    this.batches = batches;
    this.ccpPoints = ccpPoints;
    this.ccpRecords = ccpRecords;
    this.cleanings = cleanings;
    this.envRecords = envRecords;
    this.materials = materials;
    this.lots = lots;
    this.inputs = inputs;
    this.steps = steps;
    this.samples = samples;
  }

  @Override
  public void run(ApplicationArguments args) {
    backfillBatchExpiry();
    seedCcp();
    seedCleaning();
    seedEnvironment();
    seedMaterials();
    seedGenealogy();
    seedEbr();
    seedRetention();
  }

  /** F3 · 历史批次回填生产日期/保质期/到期日（柠檬酸默认 540 天）。 */
  private void backfillBatchExpiry() {
    int n = 0;
    for (Batch b : batches.selectList(Wrappers.<Batch>lambdaQuery().isNull(Batch::getExpiryDate))) {
      LocalDate prod = b.getStartedAt() == null || b.getStartedAt().length() < 10
          ? LocalDate.now().minusDays(7)
          : parseDateSafe(b.getStartedAt().substring(0, Math.min(10, b.getStartedAt().length())));
      b.setProductionDate(prod);
      b.setShelfLifeDays(540);
      b.setExpiryDate(prod.plusDays(540));
      // fixture 批次无计划产量，回填一个量级合理的默认值，使召回影响分析能统计涉及吨位
      if (b.getPlanYield() == null) b.setPlanYield(new BigDecimal("12000"));
      batches.updateById(b);
      n++;
    }
    if (n > 0) log.info("backfilled expiry for {} batches", n);
    seedExpiryDemoCases();
  }

  /**
   * 效期预警演示：取 2 个已完成批次，分别置为「近效期（+12 天）」与「已过期（-5 天）」，
   * 使 /api/ebr/expiry-alerts 与前端预警清单有可观察数据。
   */
  private void seedExpiryDemoCases() {
    List<Batch> done = batches.selectList(
        Wrappers.<Batch>lambdaQuery().eq(Batch::getStatus, "done").orderByAsc(Batch::getId));
    List<Batch> src = done.size() >= 2
        ? done
        : batches.selectList(Wrappers.<Batch>lambdaQuery().orderByAsc(Batch::getId));
    if (src.size() >= 1) {
      Batch near = src.get(0);
      near.setExpiryDate(LocalDate.now().plusDays(12));
      batches.updateById(near);
    }
    if (src.size() >= 2) {
      Batch expired = src.get(1);
      expired.setExpiryDate(LocalDate.now().minusDays(5));
      batches.updateById(expired);
    }
    log.info("seeded expiry demo cases (1 近效期 / 1 已过期)");
  }

  private static LocalDate parseDateSafe(String s) {
    try {
      return LocalDate.parse(s);
    } catch (Exception e) {
      return LocalDate.now().minusDays(7);
    }
  }

  /* ---------------- F1 · HACCP ---------------- */

  private void seedCcp() {
    if (ccpPoints.selectCount(null) > 0) return;
    point("CCP-01", "连消灭菌温度", "LINE-1", "灭菌",
        "灭菌不彻底导致耐热菌残留并产毒素", "BIOLOGICAL",
        "维持灭菌温度并连续记录温度曲线", "121", "125", "摄氏度", "每批次",
        "立即隔离该批料液，重新灭菌或降级为非食品级；校验温度计与蒸汽压力");
    point("CCP-02", "金属检测（包装前）", "LINE-3", "包装",
        "设备磨损碎片混入造成物理危害", "PHYSICAL",
        "成品经金属检测仪全检，Fe ≤ 1.5mm", "0", "1.5", "mm", "连续",
        "停机排查筛网与设备磨损件，隔离上一合格点以来的全部产品并复检");
    point("CCP-03", "干燥出风温度", "LINE-2", "干燥",
        "出风温度过低导致水分超标、霉菌滋生", "BIOLOGICAL",
        "控制进/出风温度，每 30 分钟记录", "60", "80", "摄氏度", "每 30 分钟",
        "回机复烘，检测水分与微生物；校验温控与风速");
    point("CCP-04", "成品筛网完整性", "LINE-3", "筛分",
        "筛网破损导致异物混入", "PHYSICAL",
        "每班开工/收工检查筛网并留痕", "0", "2", "mm", "每班",
        "更换筛网，隔离上一检查点以来产品并全检");
    point("CCP-05", "过敏原换型 ATP 验证", "LINE-3", "清场",
        "含亚硫酸盐产品换型后交叉污染", "ALLERGEN",
        "换型清洗后 ATP 涂抹验证", "0", "200", "RLU", "每次换型",
        "重新清洗并复测，不合格前禁止投产");
    log.info("seeded {} CCP points", 5);

    if (ccpRecords.selectCount(null) == 0) {
      List<Batch> bs = batches.selectList(null);
      String batchId = bs.isEmpty() ? null : bs.get(0).getId();
      rec("CCP-01", batchId, "LINE-1", "123.4", true, "张工艺", "李质检");
      rec("CCP-01", batchId, "LINE-1", "124.1", true, "张工艺", "李质检");
      rec("CCP-03", batchId, "LINE-2", "72.5", true, "张工艺", null);
      rec("CCP-03", batchId, "LINE-2", "58.2", false, "张工艺", null);
      rec("CCP-02", null, "LINE-3", "0.8", true, "张工艺", "李质检");
      rec("CCP-04", null, "LINE-3", "1.0", true, "张工艺", null);
      rec("CCP-05", null, "LINE-3", "156", true, "张工艺", "李质检");
      log.info("seeded CCP monitoring records (含 1 条偏离)");
    }
  }

  private void point(String code, String name, String line, String step, String hazard,
      String type, String measure, String min, String max, String unit, String freq,
      String corrective) {
    CcpPoint p = new CcpPoint();
    p.setCode(code);
    p.setName(name);
    p.setLine(line);
    p.setStepName(step);
    p.setHazard(hazard);
    p.setHazardType(type);
    p.setControlMeasure(measure);
    p.setClMin(new BigDecimal(min));
    p.setClMax(new BigDecimal(max));
    p.setUnit(unit);
    p.setMonitorFreq(freq);
    p.setCorrectiveAction(corrective);
    p.setResponsibleRole("OPERATOR");
    p.setEnabled(true);
    ccpPoints.insert(p);
  }

  private void rec(String ccp, String batchId, String line, String value, boolean inLimit,
      String operator, String verifier) {
    CcpRecord r = new CcpRecord();
    r.setCcpCode(ccp);
    r.setBatchId(batchId);
    r.setLine(line);
    r.setValue(new BigDecimal(value));
    r.setInLimit(inLimit);
    r.setOperator(operator);
    r.setVerifier(verifier);
    r.setVerifiedAt(verifier == null ? null : OffsetDateTime.now());
    r.setRecordedAt(OffsetDateTime.now().minusMinutes(inLimit ? 120 : 30));
    if (!inLimit) {
      r.setDeviationId(null);
      r.setCorrective("回机复烘并复检水分");
    }
    ccpRecords.insert(r);
  }

  /* ---------------- F1 · 清场 ---------------- */

  private void seedCleaning() {
    if (cleanings.selectCount(null) > 0) return;
    OffsetDateTime now = OffsetDateTime.now();
    // 三条产线各一条「有效」清场：PASS + QA 已确认 + 有效期 72h 内 → 默认可开工
    cleaning("CLN-001", "LINE-1", "F-101", "ROUTINE", "CIP 碱洗 2% → 清水冲洗 → 酸洗 1%",
        now.minusHours(6), now.minusHours(5), "张工艺", "李质检", "PASS", "ATP 120 RLU", now.plusHours(66));
    cleaning("CLN-002", "LINE-2", "D-701", "CHANGEOVER", "拆洗 + 蒸汽灭菌 121℃/15min",
        now.minusHours(20), now.minusHours(19), "张工艺", "李质检", "PASS", "ATP 88 RLU", now.plusHours(52));
    cleaning("CLN-003", "LINE-3", "M-801", "ALLERGEN", "过敏原换型清洗：碱洗 → 冲洗 → ATP 验证",
        now.minusHours(3), now.minusHours(2), "张工艺", "李质检", "PASS", "ATP 156 RLU", now.plusHours(69));
    // 历史不合格与已过期记录（用于演示门禁拦截与历史追溯）
    cleaning("CLN-000", "LINE-1", "F-101", "DEEP", "深度拆洗 + 碱煮",
        now.minusDays(9), now.minusDays(8), "张工艺", "李质检", "FAIL", "ATP 860 RLU 超标", null);
    cleaning("CLN-00X", "LINE-2", "C-601", "ROUTINE", "CIP 碱洗",
        now.minusDays(6), now.minusDays(6), "张工艺", "李质检", "PASS", "ATP 95 RLU", now.minusDays(3));
    log.info("seeded cleaning records (3 条有效 / 1 条不合格 / 1 条已过期)");
  }

  private void cleaning(String id, String line, String eq, String type, String method,
      OffsetDateTime start, OffsetDateTime end, String by, String qa, String result,
      String swab, OffsetDateTime validUntil) {
    CleaningRecord c = new CleaningRecord();
    c.setId(id);
    c.setLine(line);
    c.setEquipmentCode(eq);
    c.setType(type);
    c.setMethod(method);
    c.setStartedAt(start);
    c.setFinishedAt(end);
    c.setExecutedBy(by);
    c.setVerifiedBy(qa);
    c.setVerifiedAt(end);
    c.setResult(result);
    c.setSwabResult(swab);
    c.setValidUntil(validUntil);
    c.setCreatedAt(start);
    if ("ALLERGEN".equals(type)) {
      c.setAllergenFrom("含亚硫酸盐产品");
      c.setAllergenTo("无过敏原产品");
    }
    cleanings.insert(c);
  }

  /* ---------------- F1 · 环境 ---------------- */

  private void seedEnvironment() {
    if (envRecords.selectCount(null) > 0) return;
    OffsetDateTime now = OffsetDateTime.now();
    env("发酵间", "LINE-1", "TEMP", "34.5", "摄氏度", "30", "38", "PASS", now.minusMinutes(40));
    env("发酵间", "LINE-1", "HUMIDITY", "62.0", "%", "40", "70", "PASS", now.minusMinutes(38));
    env("洁净灌装区", "LINE-3", "PRESSURE_DIFF", "12.0", "Pa", "5", "25", "PASS", now.minusMinutes(30));
    env("洁净灌装区", "LINE-3", "MICRO", "18", "CFU/皿", "0", "30", "PASS", now.minusHours(3));
    env("原料暂存", null, "TEMP", "27.8", "摄氏度", "0", "30", "PASS", now.minusMinutes(20));
    env("原料暂存", null, "HUMIDITY", "58.0", "%", "30", "65", "PASS", now.minusMinutes(18));
    env("洁净灌装区", "LINE-3", "MICRO", "42", "CFU/皿", "0", "30", "FAIL", now.minusHours(6));
    env("包装间", "LINE-3", "ATP", "145", "RLU", "0", "200", "PASS", now.minusMinutes(50));
    log.info("seeded env monitoring records (含 1 条超标)");
  }

  private void env(String area, String line, String metric, String value, String unit,
      String min, String max, String result, OffsetDateTime at) {
    EnvMonitoring e = new EnvMonitoring();
    e.setArea(area);
    e.setLine(line);
    e.setMetric(metric);
    e.setValue(new BigDecimal(value));
    e.setUnit(unit);
    e.setLimitMin(new BigDecimal(min));
    e.setLimitMax(new BigDecimal(max));
    e.setResult(result);
    e.setSampledBy("张工艺");
    e.setSampledAt(at);
    envRecords.insert(e);
  }

  /* ---------------- F2 · 物料与谱系 ---------------- */

  private void seedMaterials() {
    if (materials.selectCount(null) > 0) return;
    mat("RM-001", "玉米淀粉", "RAW", "kg", false, null, 365, "GB/T 8885 一级");
    mat("RM-002", "黑曲霉菌种液", "RAW", "L", false, null, 30, "活菌数 ≥ 1.0E8 CFU/mL");
    mat("RM-003", "碳酸钙", "EXCIPIENT", "kg", false, null, 720, "GB 1886.214 食品级");
    mat("RM-004", "硫酸（食品级）", "EXCIPIENT", "kg", false, null, 720, "GB/T 534 食品级");
    mat("RM-005", "亚硫酸氢钠", "EXCIPIENT", "kg", true, "亚硫酸盐", 365, "GB 1886.46，含过敏原");
    mat("PK-001", "25kg 复合纸塑包装袋", "PACKAGING", "只", false, null, 1080, "食品接触用");

    LocalDate today = LocalDate.now();
    lot("LOT-001", "RM-001", "中粮生化（安徽）", "CL-260812", "42000", "kg", "RELEASED",
        today.minusDays(22), today.plusDays(343), "A-01", "COA-260812-01");
    lot("LOT-002", "RM-002", "齐鲁工业大学中试基地", "AS-260901", "1200", "L", "RELEASED",
        today.minusDays(12), today.plusDays(18), "B-02", "COA-260901-02");
    lot("LOT-003", "RM-003", "广西华纳新材料", "CC-260730", "8600", "kg", "RELEASED",
        today.minusDays(45), today.plusDays(675), "A-03", "COA-260730-03");
    lot("LOT-004", "RM-004", "南京化学工业", "SA-260815", "5300", "kg", "RELEASED",
        today.minusDays(29), today.plusDays(691), "危化库-01", "COA-260815-04");
    lot("LOT-005", "RM-005", "连云港通源化工", "SB-260905", "600", "kg", "PENDING",
        today.minusDays(9), today.plusDays(356), "过敏原专区-01", null);
    lot("LOT-006", "RM-001", "中粮生化（安徽）", "CL-260828", "38000", "kg", "FAIL",
        today.minusDays(16), today.plusDays(349), "待处理区", null);
    lot("LOT-007", "PK-001", "常州华健包装", "PK-260820", "12000", "只", "RELEASED",
        today.minusDays(24), today.plusDays(1056), "C-05", "COA-260820-07");
    log.info("seeded materials and lots (含 1 批待检验 / 1 批不合格)");
  }

  private void mat(String code, String name, String category, String unit, boolean allergen,
      String allergenName, int shelf, String spec) {
    Material m = new Material();
    m.setCode(code);
    m.setName(name);
    m.setCategory(category);
    m.setUnit(unit);
    m.setAllergen(allergen);
    m.setAllergenName(allergenName);
    m.setShelfLifeDays(shelf);
    m.setSpec(spec);
    m.setEnabled(true);
    materials.insert(m);
  }

  private void lot(String id, String material, String supplier, String supplierLot, String qty,
      String unit, String qcStatus, LocalDate prod, LocalDate expiry, String bin, String coa) {
    MaterialLot l = new MaterialLot();
    l.setId(id);
    l.setMaterialCode(material);
    l.setSupplier(supplier);
    l.setSupplierLot(supplierLot);
    l.setReceivedAt(OffsetDateTime.now().minusDays(20));
    l.setQty(new BigDecimal(qty));
    l.setUnit(unit);
    l.setQcStatus(qcStatus);
    l.setQcBy("RELEASED".equals(qcStatus) || "FAIL".equals(qcStatus) ? "李质检" : null);
    l.setQcAt(l.getQcBy() == null ? null : OffsetDateTime.now().minusDays(19));
    l.setProductionDate(prod);
    l.setExpiryDate(expiry);
    l.setWarehouseBin(bin);
    l.setCoaNo(coa);
    lots.insert(l);
  }

  /** 真实谱系：为已有批次登记投料，使追溯与召回基于真实数据而非 fixture 硬编码。 */
  private void seedGenealogy() {
    if (inputs.selectCount(null) > 0) return;
    List<Batch> bs = batches.selectList(Wrappers.<Batch>lambdaQuery().orderByDesc(Batch::getId));
    if (bs.isEmpty()) return;
    String[][] map = {
        {"RM-001", "LOT-001", "18500"},
        {"RM-002", "LOT-002", "320"},
        {"RM-003", "LOT-003", "4200"},
        {"RM-004", "LOT-004", "2100"},
    };
    int i = 0;
    for (Batch b : bs) {
      for (String[] m : map) {
        BatchInput in = new BatchInput();
        in.setBatchId(b.getId());
        in.setMaterialLotId(m[1]);
        in.setMaterialCode(m[0]);
        in.setQty(new BigDecimal(m[2]));
        in.setUnit("kg");
        in.setFedAt(OffsetDateTime.now().minusHours(6 + i));
        in.setOperator("张工艺");
        inputs.insert(in);
      }
      i++;
      if (i >= 6) break;   // 仅前 6 个批次建谱系，避免数据量过大
    }
    log.info("seeded batch_input genealogy for {} batches", i);
  }

  /* ---------------- F3 · eBR 与留样 ---------------- */

  private void seedEbr() {
    if (steps.selectCount(null) > 0) return;
    List<Batch> bs = batches.selectList(Wrappers.<Batch>lambdaQuery().orderByDesc(Batch::getId));
    if (bs.isEmpty()) return;
    String batchId = bs.get(0).getId();
    step(batchId, 1, "配料", "DONE", "{\"淀粉浓度\":\"18%\",\"pH\":\"5.6\"}",
        "{\"淀粉浓度\":\"18.2%\",\"pH\":\"5.5\"}", "张工艺", "李质检");
    step(batchId, 2, "灭菌", "DONE", "{\"温度\":\"123℃\",\"时间\":\"15min\"}",
        "{\"温度\":\"123.4℃\",\"时间\":\"15min\"}", "张工艺", "李质检");
    step(batchId, 3, "发酵", "DONE", "{\"周期\":\"56h\",\"溶氧\":\"30%\"}",
        "{\"周期\":\"55.5h\",\"溶氧\":\"31%\"}", "张工艺", null);
    step(batchId, 4, "提取", "RUNNING", "{\"收率\":\"≥92%\"}", "{\"收率\":\"92.4%\"}", "张工艺", null);
    step(batchId, 5, "精制", "PENDING", "{\"电导率\":\"≤50us/cm\"}", null, null, null);
    step(batchId, 6, "结晶", "PENDING", "{\"降温速率\":\"3℃/h\"}", null, null, null);
    step(batchId, 7, "干燥", "PENDING", "{\"出风温度\":\"70℃\"}", null, null, null);
    step(batchId, 8, "包装", "PENDING", "{\"净重\":\"25kg±0.1\"}", null, null, null);
    log.info("seeded eBR steps for {}", batchId);
  }

  private void step(String batchId, int no, String name, String status, String target,
      String actual, String operator, String reviewer) {
    BatchStep s = new BatchStep();
    s.setBatchId(batchId);
    s.setStepNo(no);
    s.setStepName(name);
    s.setStatus(status);
    s.setTargetParams(target);
    s.setActualParams(actual);
    s.setOperator(operator);
    s.setReviewer(reviewer);
    s.setStartedAt("PENDING".equals(status) ? null : OffsetDateTime.now().minusHours(24 - no));
    s.setFinishedAt("DONE".equals(status) ? OffsetDateTime.now().minusHours(23 - no) : null);
    s.setReviewedAt(reviewer == null ? null : OffsetDateTime.now().minusHours(22 - no));
    steps.insert(s);
  }

  private void seedRetention() {
    if (samples.selectCount(null) > 0) return;
    List<Batch> done = batches.selectList(
        Wrappers.<Batch>lambdaQuery().eq(Batch::getStatus, "done").orderByDesc(Batch::getId));
    List<Batch> src = done.isEmpty()
        ? batches.selectList(Wrappers.<Batch>lambdaQuery().orderByDesc(Batch::getId))
        : done;
    int i = 0;
    for (Batch b : src) {
      LocalDate base = b.getExpiryDate() == null ? LocalDate.now().plusYears(2) : b.getExpiryDate();
      RetentionSample s = new RetentionSample();
      s.setId("RS-" + String.format("%03d", i + 1));
      s.setBatchId(b.getId());
      s.setQty(new BigDecimal("500"));
      s.setUnit("g");
      s.setLocation("留样柜 " + (char) ('A' + (i % 3)) + "-" + String.format("%02d", i + 1));
      s.setRetainedBy("李质检");
      s.setRetainedAt(OffsetDateTime.now().minusDays(10 - i));
      s.setExpiryDate(base.plusDays(180));
      s.setStatus(i == 3 ? "TESTED" : "RETAINED");
      samples.insert(s);
      if (++i >= 5) break;
    }
    log.info("seeded {} retention samples", i);
  }
}
