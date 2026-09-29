package com.fluxmes.api.foodsafety;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fluxmes.api.audit.AuditService;
import com.fluxmes.api.common.ApiSupport;
import com.fluxmes.api.common.CurrentUser;
import com.fluxmes.api.common.Roles;
import com.fluxmes.api.entity.Batch;
import com.fluxmes.api.entity.BatchStep;
import com.fluxmes.api.entity.RetentionSample;
import com.fluxmes.api.mapper.BatchMapper;
import com.fluxmes.api.mapper.BatchStepMapper;
import com.fluxmes.api.mapper.RetentionSampleMapper;
import com.fluxmes.api.personnel.PersonnelService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * F3 · 批记录与效期服务：电子批记录 eBR（工序级执行 + 双人复核）、留样管理、批次效期预警。
 *
 * <p>合规要点：
 * <ul>
 *   <li>eBR 工序须记录工艺设定值与实际值，关键工序由另一人复核（复核人不得为操作人）；</li>
 *   <li>留样量按法定要求（一般 ≥ 检验用量的 3 倍），留至保质期后 6 个月；</li>
 *   <li>近效期（默认 30 天）成品自动进入预警清单，便于先进先出与临期处理。</li>
 * </ul>
 */
@Service
public class BatchRecordService {

  private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyMMdd");
  private static final int RETENTION_EXTENSION_DAYS = 180; // 保质期后 6 个月
  private static final int EXPIRY_WARN_DAYS = 30;

  private final BatchMapper batches;
  private final BatchStepMapper steps;
  private final RetentionSampleMapper samples;
  private final AuditService audit;
  private final ObjectMapper json;
  /** G2 · 人员资质门禁（工序复核须持批记录复核资质）。 */
  private final PersonnelService personnel;

  public BatchRecordService(BatchMapper batches, BatchStepMapper steps,
      RetentionSampleMapper samples, AuditService audit, ObjectMapper json,
      PersonnelService personnel) {
    this.batches = batches;
    this.steps = steps;
    this.samples = samples;
    this.audit = audit;
    this.json = json;
    this.personnel = personnel;
  }

  /* =================== 电子批记录 eBR =================== */

  public Map<String, Object> ebr(String batchId) {
    Batch b = batches.selectById(batchId);
    if (b == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "批次不存在: " + batchId);
    List<Map<String, Object>> list = steps.selectList(Wrappers.<BatchStep>lambdaQuery()
            .eq(BatchStep::getBatchId, batchId)
            .orderByAsc(BatchStep::getStepNo))
        .stream().map(this::stepView).toList();
    long done = steps.selectCount(Wrappers.<BatchStep>lambdaQuery()
        .eq(BatchStep::getBatchId, batchId).eq(BatchStep::getStatus, "DONE"));
    long unreviewed = steps.selectCount(Wrappers.<BatchStep>lambdaQuery()
        .eq(BatchStep::getBatchId, batchId).eq(BatchStep::getStatus, "DONE")
        .isNull(BatchStep::getReviewer));
    return ApiSupport.map(
        "generatedAt", ApiSupport.nowIso(),
        "batchId", batchId,
        "product", b.getProduct(),
        "status", b.getStatus(),
        "released", b.getReleased(),
        "steps", list,
        "stepCount", list.size(),
        "doneCount", done,
        "unreviewedCount", unreviewed,
        "complete", unreviewed == 0 && done > 0);
  }

  /** 工序执行记录写入（设定值/实际值）；同工序重复调用则更新实际值。 */
  public Map<String, Object> recordStep(String batchId, Map<String, Object> body) {
    Batch b = batches.selectById(batchId);
    if (b == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "批次不存在: " + batchId);
    if (Boolean.TRUE.equals(b.getReleased())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "批次已放行，eBR 只读");
    }
    Integer stepNo = body.get("stepNo") == null ? null
        : Integer.valueOf(String.valueOf(body.get("stepNo")));
    String stepName = str(body.get("stepName"));
    if (stepNo == null || isBlank(stepName)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "stepNo / stepName 必填");
    }
    BatchStep s = steps.selectOne(Wrappers.<BatchStep>lambdaQuery()
        .eq(BatchStep::getBatchId, batchId).eq(BatchStep::getStepNo, stepNo));
    boolean isNew = s == null;
    if (isNew) {
      s = new BatchStep();
      s.setBatchId(batchId);
      s.setStepNo(stepNo);
      s.setStepName(stepName);
      s.setStatus("PENDING");
      s.setStartedAt(OffsetDateTime.now());
    }
    if (body.containsKey("targetParams")) s.setTargetParams(writeJson(body.get("targetParams")));
    if (body.containsKey("actualParams")) s.setActualParams(writeJson(body.get("actualParams")));
    String status = str(body.get("status"));
    if (!isBlank(status)) {
      s.setStatus(status);
      if ("DONE".equals(status) && s.getFinishedAt() == null) s.setFinishedAt(OffsetDateTime.now());
      if ("RUNNING".equals(status) && s.getStartedAt() == null) s.setStartedAt(OffsetDateTime.now());
    }
    s.setOperator(CurrentUser.username());
    s.setRemark(str(body.get("remark")));
    if (isNew) steps.insert(s);
    else steps.updateById(s);
    audit.record(isNew ? "ebr.step.create" : "ebr.step.update", "batch_step",
        String.valueOf(s.getId()), null,
        ApiSupport.map("batchId", batchId, "stepNo", stepNo, "status", s.getStatus()));
    return stepView(s);
  }

  /** 工序复核（QC+，复核人不得为操作人本人）。 */
  public Map<String, Object> reviewStep(Long id) {
    if (!CurrentUser.hasRole(Roles.QC)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅质检员及以上可复核工序记录");
    }
    // G2 · 资质门禁：批记录复核资质
    personnel.requireCapability(PersonnelService.CAP_BATCH_REVIEW);
    BatchStep s = steps.selectById(id);
    if (s == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "工序记录不存在: " + id);
    String me = CurrentUser.username();
    if (me.equals(s.getOperator())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "复核人不得为操作人本人（双人复核要求）");
    }
    if (!"DONE".equals(s.getStatus())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "仅已完成工序可复核");
    }
    s.setReviewer(me);
    s.setReviewedAt(OffsetDateTime.now());
    steps.updateById(s);
    audit.record("ebr.step.review", "batch_step", String.valueOf(id), null,
        ApiSupport.map("reviewer", me, "batchId", s.getBatchId()));
    return stepView(s);
  }

  /* =================== 留样管理 =================== */

  public List<Map<String, Object>> samples(String status, Boolean expiringSoon) {
    List<RetentionSample> all = samples.selectList(Wrappers.<RetentionSample>lambdaQuery()
        .eq(status != null && !status.isBlank(), RetentionSample::getStatus, status)
        .orderByAsc(RetentionSample::getExpiryDate));
    LocalDate soon = LocalDate.now().plusDays(EXPIRY_WARN_DAYS);
    return all.stream()
        .filter(s -> !Boolean.TRUE.equals(expiringSoon)
            || (s.getExpiryDate() != null && !s.getExpiryDate().isAfter(soon)
                && "DISCARDED".equals(s.getStatus()) == false))
        .map(this::sampleView).toList();
  }

  /** 留样登记（QC+）：到期日 = 成品效期 + 180 天；效期缺失时按保质期 + 180 天推算。 */
  public Map<String, Object> retainSample(Map<String, Object> body) {
    if (!CurrentUser.hasRole(Roles.QC)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅质检员及以上可登记留样");
    }
    String batchId = str(body.get("batchId"));
    if (isBlank(batchId)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "batchId 必填");
    Batch b = batches.selectById(batchId);
    if (b == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "批次不存在: " + batchId);

    LocalDate base = b.getExpiryDate();
    if (base == null && b.getProductionDate() != null && b.getShelfLifeDays() != null) {
      base = b.getProductionDate().plusDays(b.getShelfLifeDays());
    }
    if (base == null) base = LocalDate.now().plusYears(2);

    String id = "RS-" + LocalDate.now().format(YYMMDD) + "-"
        + String.format("%03d", samples.selectCount(null) + 1);
    RetentionSample s = new RetentionSample();
    s.setId(id);
    s.setBatchId(batchId);
    s.setQty(dec(body.getOrDefault("qty", 500)));
    s.setUnit(str(body.getOrDefault("unit", "g")));
    s.setLocation(str(body.get("location")));
    s.setRetainedBy(CurrentUser.username());
    s.setRetainedAt(OffsetDateTime.now());
    s.setExpiryDate(base.plusDays(RETENTION_EXTENSION_DAYS));
    s.setStatus("RETAINED");
    s.setRemark(str(body.get("remark")));
    samples.insert(s);
    audit.record("retention.create", "retention_sample", id, null,
        ApiSupport.map("batchId", batchId, "expiryDate", s.getExpiryDate(), "qty", s.getQty()));
    return sampleView(s);
  }

  /** 留样处置（QC+）：到期或复检后销毁，记录处置人与时间。 */
  public Map<String, Object> disposeSample(String id, String remark) {
    if (!CurrentUser.hasRole(Roles.QC)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅质检员及以上可处置留样");
    }
    RetentionSample s = samples.selectById(id);
    if (s == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "留样不存在: " + id);
    if ("DISCARDED".equals(s.getStatus())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "留样已处置");
    }
    String before = s.getStatus();
    s.setStatus("DISCARDED");
    s.setDisposedAt(OffsetDateTime.now());
    s.setDisposedBy(CurrentUser.username());
    if (remark != null && !remark.isBlank()) s.setRemark(remark);
    samples.updateById(s);
    audit.record("retention.dispose", "retention_sample", id, ApiSupport.map("status", before),
        ApiSupport.map("status", "DISCARDED", "by", s.getDisposedBy()));
    return sampleView(s);
  }

  /* =================== 效期预警 =================== */

  /** 近效期（≤30 天）与已过期成品批次清单，支撑先进先出与临期处理。 */
  public Map<String, Object> expiryAlerts(int warnDays) {
    int days = warnDays <= 0 ? EXPIRY_WARN_DAYS : warnDays;
    LocalDate today = LocalDate.now();
    List<Map<String, Object>> expiring = new ArrayList<>();
    List<Map<String, Object>> expired = new ArrayList<>();
    for (Batch b : batches.selectList(Wrappers.<Batch>lambdaQuery()
        .isNotNull(Batch::getExpiryDate)
        .orderByAsc(Batch::getExpiryDate))) {
      long left = ChronoUnit.DAYS.between(today, b.getExpiryDate());
      Map<String, Object> row = ApiSupport.map(
          "batchId", b.getId(),
          "product", b.getProduct(),
          "expiryDate", b.getExpiryDate(),
          "daysLeft", left,
          "status", b.getStatus(),
          "released", b.getReleased(),
          "warehouseBin", b.getWarehouseBin(),
          "planYield", b.getPlanYield());
      if (left < 0) expired.add(row);
      else if (left <= days) expiring.add(row);
    }
    return ApiSupport.map(
        "generatedAt", ApiSupport.nowIso(),
        "warnDays", days,
        "expiringCount", expiring.size(),
        "expiredCount", expired.size(),
        "expiring", expiring,
        "expired", expired);
  }

  /** 批次放行时回填效期：生产日期 + 保质期 → 到期日。 */
  public void ensureExpiry(Batch b) {
    if (b.getExpiryDate() != null) return;
    if (b.getProductionDate() == null || b.getShelfLifeDays() == null) return;
    b.setExpiryDate(b.getProductionDate().plusDays(b.getShelfLifeDays()));
  }

  /* =================== helpers =================== */

  private Map<String, Object> stepView(BatchStep s) {
    // id 以字符串输出：BIGSERIAL 主键可能超过 JS Number.MAX_SAFE_INTEGER，
    // 直接返回 Long 会在浏览器端丢精度，导致后续 /review 命中不到记录（404）。
    return ApiSupport.map(
        "id", s.getId() == null ? null : String.valueOf(s.getId()),
        "batchId", s.getBatchId(),
        "stepNo", s.getStepNo(),
        "stepName", s.getStepName(),
        "status", s.getStatus(),
        "targetParams", readJson(s.getTargetParams()),
        "actualParams", readJson(s.getActualParams()),
        "operator", s.getOperator(),
        "reviewer", s.getReviewer(),
        "startedAt", s.getStartedAt() == null ? null : s.getStartedAt().toString(),
        "finishedAt", s.getFinishedAt() == null ? null : s.getFinishedAt().toString(),
        "reviewedAt", s.getReviewedAt() == null ? null : s.getReviewedAt().toString(),
        "remark", s.getRemark());
  }

  private Map<String, Object> sampleView(RetentionSample s) {
    Batch b = batches.selectById(s.getBatchId());
    long daysLeft = s.getExpiryDate() == null ? 9999
        : ChronoUnit.DAYS.between(LocalDate.now(), s.getExpiryDate());
    return ApiSupport.map(
        "id", s.getId(),
        "batchId", s.getBatchId(),
        "product", b == null ? null : b.getProduct(),
        "qty", s.getQty(),
        "unit", s.getUnit(),
        "location", s.getLocation(),
        "retainedBy", s.getRetainedBy(),
        "retainedAt", s.getRetainedAt() == null ? null : s.getRetainedAt().toString(),
        "expiryDate", s.getExpiryDate(),
        "daysLeft", daysLeft,
        "status", s.getStatus(),
        "disposedAt", s.getDisposedAt() == null ? null : s.getDisposedAt().toString(),
        "disposedBy", s.getDisposedBy(),
        "remark", s.getRemark());
  }

  private String writeJson(Object v) {
    if (v == null) return null;
    try {
      return json.writeValueAsString(v);
    } catch (Exception e) {
      return String.valueOf(v);
    }
  }

  private Object readJson(String raw) {
    if (raw == null || raw.isBlank()) return null;
    try {
      return json.readValue(raw, Object.class);
    } catch (Exception e) {
      return raw;
    }
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
