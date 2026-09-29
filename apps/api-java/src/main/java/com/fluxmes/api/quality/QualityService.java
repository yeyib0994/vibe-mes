package com.fluxmes.api.quality;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fluxmes.api.audit.AuditService;
import com.fluxmes.api.batch.BatchService;
import com.fluxmes.api.common.ApiSupport;
import com.fluxmes.api.common.CurrentUser;
import com.fluxmes.api.core.FixtureStore;
import com.fluxmes.api.entity.Batch;
import com.fluxmes.api.entity.Deviation;
import com.fluxmes.api.entity.SpcLimit;
import com.fluxmes.api.mapper.BatchMapper;
import com.fluxmes.api.mapper.DeviationMapper;
import com.fluxmes.api.mapper.SpcLimitMapper;
import com.fluxmes.api.personnel.PersonnelService;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 质量业务服务：SPC 控制限配置化（T2）、Western Electric 判异引擎（T3）、
 * 偏差工作流（T8）、成品放行 + COA（T7）。
 */
@Service
public class QualityService {

  private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyMMdd");

  private final FixtureStore fixtures;   // 只读展示数据：spc 序列 / pareto / qcTasks
  private final SpcLimitMapper spcLimits;
  private final DeviationMapper deviations;
  private final BatchMapper batches;
  private final AuditService audit;
  /** G2 · 人员资质门禁（成品放行须持放行资质）。 */
  private final PersonnelService personnel;
  /** H1/T8 · 设备校准门禁（放行须排除校准超期设备产生的检验数据）。 */
  private final com.fluxmes.api.equipment.EquipmentService equipmentService;
  /** Phase I · CAPA 闭环门禁（FR-7：未关闭 CAPA 阻断偏差关闭与批次放行）。 */
  private final com.fluxmes.api.regtech.CapaService capaService;
  /** Phase I · 电子签名门禁（FR-21）与记录修订（FR-18）。 */
  private final com.fluxmes.api.regtech.SignatureService signatures;

  public QualityService(FixtureStore fixtures, SpcLimitMapper spcLimits,
      DeviationMapper deviations, BatchMapper batches, AuditService audit,
      PersonnelService personnel,
      com.fluxmes.api.equipment.EquipmentService equipmentService,
      com.fluxmes.api.regtech.CapaService capaService,
      com.fluxmes.api.regtech.SignatureService signatures) {
    this.fixtures = fixtures;
    this.spcLimits = spcLimits;
    this.deviations = deviations;
    this.batches = batches;
    this.audit = audit;
    this.personnel = personnel;
    this.equipmentService = equipmentService;
    this.capaService = capaService;
    this.signatures = signatures;
  }

  /* ---------------- 控制限（T2） ---------------- */

  public Map<String, Object> spcLimit(String product) {
    SpcLimit l = spcLimits.selectOne(Wrappers.<SpcLimit>lambdaQuery()
        .eq(product == null || product.isBlank(), SpcLimit::getProduct, "食品级一水柠檬酸")
        .eq(product != null && !product.isBlank(), SpcLimit::getProduct, product)
        .last("LIMIT 1"));
    if (l == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "未配置控制限: " + product);
    return ApiSupport.map(
        "product", l.getProduct(),
        "feature", l.getFeature(),
        "ucl", l.getUcl(),
        "cl", l.getCl(),
        "lcl", l.getLcl(),
        "unit", l.getUnit(),
        "standardVersion", l.getStandardVersion());
  }

  /* ---------------- 判异引擎（T3，纯函数） ---------------- */

  /**
   * Western Electric 主要判异规则：
   * R1 单点超出 UCL/LCL；R2 连续 9 点位于 CL 同侧；R3 连续 6 点单调上升/下降。
   * 返回 [{ index, rule, desc }]（index 为命中序列下标，0 起）。
   */
  public static List<Map<String, Object>> detectRules(List<Double> v, double ucl, double cl, double lcl) {
    List<Map<String, Object>> hits = new ArrayList<>();
    int n = v.size();
    for (int i = 0; i < n; i++) {
      double x = v.get(i);
      if (x > ucl || x < lcl) {
        hits.add(ApiSupport.map("index", i, "rule", "R1", "desc", "1 点超出控制限（" + x + " ∉ [" + lcl + ", " + ucl + "]）"));
      }
    }
    for (int i = 8; i < n; i++) {
      boolean allAbove = true, allBelow = true;
      for (int j = i - 8; j <= i; j++) {
        if (v.get(j) <= cl) allAbove = false;
        if (v.get(j) >= cl) allBelow = false;
      }
      if (allAbove || allBelow) {
        hits.add(ApiSupport.map("index", i, "rule", "R2", "desc", "连续 9 点位于中心线" + (allAbove ? "上方" : "下方")));
      }
    }
    for (int i = 5; i < n; i++) {
      boolean up = true, down = true;
      for (int j = i - 5; j < i; j++) {
        if (!(v.get(j) < v.get(j + 1))) up = false;
        if (!(v.get(j) > v.get(j + 1))) down = false;
      }
      if (up || down) {
        hits.add(ApiSupport.map("index", i, "rule", "R3", "desc", "连续 6 点持续" + (up ? "上升" : "下降")));
      }
    }
    return hits;
  }

  /** 对 SPC 序列（fixture 展示数据）执行判异，返回命中点与所用控制限。 */
  public Map<String, Object> detect() {
    Map<String, Object> limit = spcLimit(null);
    List<Double> values = fixtures.spcData().stream()
        .map(p -> ApiSupport.num(p.get("v"), 0))
        .toList();
    List<Map<String, Object>> hits = detectRules(values,
        ApiSupport.num(limit.get("ucl"), 0), ApiSupport.num(limit.get("cl"), 0),
        ApiSupport.num(limit.get("lcl"), 0));
    return ApiSupport.map("limit", limit, "violations", hits);
  }

  /* ---------------- 偏差工作流（T8） ---------------- */

  public List<Map<String, Object>> listDeviations() {
    return deviations.selectList(Wrappers.<Deviation>lambdaQuery()
            .orderByDesc(Deviation::getCreatedAt)).stream()
        .map(QualityService::toView)
        .toList();
  }

  /**
   * 偏差状态推进：open→investigating→capa→closed；closed 需值班长及以上。
   *
   * <p>Phase I 追加：
   * <ul>
   *   <li>FR-7 · 存在未关闭 CAPA 时阻断关闭（409 + CAPA 编号）；</li>
   *   <li>FR-21 · 关闭为受控动作，须提交电子签名（DEVIATION_CLOSE / APPROVED / 值班长+）；</li>
   *   <li>FR-18 · 本次流转改写了根因 / CAPA 文本时，记录修订号 +1 并作废既有签名。</li>
   * </ul>
   */
  @org.springframework.transaction.annotation.Transactional
  public Map<String, Object> transitionDeviation(String id, String target,
      String rootCause, String capa,
      Map<String, Object> signatureBody, String ip, String ua) {
    Deviation d = deviations.selectById(id);
    if (d == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "偏差单不存在: " + id);
    Map<String, Object> before = toView(d);
    List<String> flow = List.of("open", "investigating", "capa", "closed");
    int from = flow.indexOf(d.getStatus());
    int to = flow.indexOf(target);
    if (from < 0 || to < 0 || to != from + 1) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "偏差状态只能逐级推进 open→investigating→capa→closed，当前: " + d.getStatus());
    }
    if ("closed".equals(target) && !CurrentUser.hasRole(com.fluxmes.api.common.Roles.SUPERVISOR)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "关闭偏差单需要值班长及以上角色");
    }
    if ("closed".equals(target) && (d.getRootCause() == null || d.getRootCause().isBlank())
        && (rootCause == null || rootCause.isBlank())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "关闭偏差单须填写根因（rootCause）");
    }

    // FR-18 · 内容确实被改写时先升版并作废旧签名，随后新签名绑定新版本
    boolean contentChanged = false;
    if (rootCause != null && !rootCause.isBlank() && !rootCause.equals(d.getRootCause())) {
      d.setRootCause(rootCause);
      contentChanged = true;
    }
    if (capa != null && !capa.isBlank() && !capa.equals(d.getCapa())) {
      d.setCapa(capa);
      contentChanged = true;
    }
    if (contentChanged) {
      deviations.updateById(d);
      signatures.bumpRevision("DEVIATION", id, "偏差单内容修订（" + CurrentUser.username() + "）");
    }

    if ("closed".equals(target)) {
      // FR-7 · CAPA 闭环门禁：存在未关闭 CAPA 则阻断
      capaService.assertDeviationCloseAllowed(id);
      // FR-21 · 电子签名门禁
      signatures.requireSignature("DEVIATION_CLOSE", id, signatureBody, ip, ua);
      d.setClosedBy(CurrentUser.username());
      d.setClosedAt(OffsetDateTime.now());
    }
    d.setStatus(target);
    deviations.updateById(d);
    audit.record("deviation.transition", "deviation", id, before, toView(d));
    return toView(d);
  }

  /* ---------------- 成品放行 + COA（T7） ---------------- */

  /**
   * 放行：质检员及以上；批次须已生产完成（waiting/done）且关联偏差单全部关闭、
   * 关联 CAPA 全部关闭（FR-7）；生成 COA 编号回写批次并锁定（released=true）。
   *
   * <p>FR-21 · 放行属受控动作：{@code signature_policy.BATCH_RELEASE} 启用时须提交电子签名
   * （含义 APPROVED / 角色 QC+），签名绑定批次档案内容哈希（C2）。
   */
  @org.springframework.transaction.annotation.Transactional
  public Map<String, Object> release(String batchId, Map<String, Object> signatureBody,
      String ip, String ua) {
    // G2 · 资质门禁：成品放行资质 + 有效健康证
    personnel.requireCapability(PersonnelService.CAP_RELEASE);
    Batch b = batches.selectById(batchId);
    if (b == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "批次不存在: " + batchId);
    Map<String, Object> before = BatchService.toView(b);
    if (Boolean.TRUE.equals(b.getReleased())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "批次已放行（COA " + b.getCoaNo() + "），不可重复放行");
    }
    if (!"waiting".equals(b.getStatus()) && !"done".equals(b.getStatus())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "批次未完成生产，当前状态: " + b.getStatus());
    }
    if (b.getDeviationNo() != null && !b.getDeviationNo().isBlank()) {
      Deviation d = deviations.selectById(b.getDeviationNo());
      if (d != null && !"closed".equals(d.getStatus())) {
        throw new ResponseStatusException(HttpStatus.CONFLICT,
            "偏差单 " + b.getDeviationNo() + " 未关闭，阻断放行（当前: " + d.getStatus() + "）");
      }
    }
    // FR-7 · CAPA 闭环门禁：关联偏差存在未关闭 CAPA 时阻断放行
    capaService.assertBatchReleaseAllowed(batchId);
    // H1/T8 · 校准门禁：主设备校准超期时，其检验数据不得作为放行依据（FR-8 / C5）
    equipmentService.assertReleaseAllowed(b.getEquipment());
    // FR-21 · 电子签名门禁（业务校验全部通过后才要求签名，避免无效签名被消耗）
    signatures.requireSignature("BATCH_RELEASE", batchId, signatureBody, ip, ua);

    String coa = "COA-" + LocalDate.now().format(YYMMDD) + "-"
        + String.format("%03d", countCoa() + 1);
    b.setCoaNo(coa);
    b.setReleased(true);
    b.setStatus("done");
    b.setUpdatedAt(OffsetDateTime.now());
    batches.updateById(b);
    audit.record("batch.release", "batch", batchId, before, BatchService.toView(b));
    return ApiSupport.map("batchId", batchId, "coaNo", coa, "released", true,
        "releasedBy", CurrentUser.username(), "releasedAt", ApiSupport.nowIso());
  }

  /* ---------------- overview（兼容原契约 + 扩展） ---------------- */

  public List<Map<String, Object>> spcData() { return fixtures.spcData(); }
  public List<Map<String, Object>> pareto() { return fixtures.pareto(); }
  public List<Map<String, Object>> qcTasks() { return fixtures.qcTasks(); }

  public Map<String, Object> overview() {
    return ApiSupport.map(
        "generatedAt", ApiSupport.nowIso(),
        "spcData", fixtures.spcData(),
        "pareto", fixtures.pareto(),
        "qcTasks", fixtures.qcTasks(),
        "spcLimit", spcLimit(null),
        "violations", detect().get("violations"),
        "deviations", listDeviations());
  }

  /* ---------------- helpers ---------------- */

  private long countCoa() {
    return batches.selectCount(Wrappers.<Batch>lambdaQuery().isNotNull(Batch::getCoaNo));
  }

  public static Map<String, Object> toView(Deviation d) {
    return ApiSupport.map(
        "id", d.getId(),
        "batchId", d.getBatchId(),
        "source", d.getSource(),
        "description", d.getDescription(),
        "status", d.getStatus(),
        "rootCause", d.getRootCause(),
        "capa", d.getCapa(),
        "createdBy", d.getCreatedBy(),
        "closedBy", d.getClosedBy(),
        "createdAt", d.getCreatedAt() == null ? null : d.getCreatedAt().toString(),
        "closedAt", d.getClosedAt() == null ? null : d.getClosedAt().toString());
  }
}
