package com.fluxmes.api.recipe;

import static com.fluxmes.api.common.ApiSupport.map;
import static com.fluxmes.api.common.ApiSupport.nowIso;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxmes.api.audit.AuditService;
import com.fluxmes.api.common.CurrentUser;
import com.fluxmes.api.common.Roles;
import com.fluxmes.api.core.FixtureStore;
import com.fluxmes.api.entity.Batch;
import com.fluxmes.api.entity.RecipeChange;
import com.fluxmes.api.entity.RecipeStep;
import com.fluxmes.api.entity.RecipeVersion;
import com.fluxmes.api.mapper.BatchMapper;
import com.fluxmes.api.mapper.RecipeChangeMapper;
import com.fluxmes.api.mapper.RecipeStepMapper;
import com.fluxmes.api.mapper.RecipeVersionMapper;
import com.fluxmes.api.mapper.BatchMapper;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * H2 · 配方版本受控（recipe-management T1–T11）。
 *
 * 状态机（plan D2）：draft → pending → effective ⇄ obsolete；审批不通过 → rejected。
 * NFR-3 生效唯一性双保险：数据库部分唯一索引 + 事务内先置旧版本 obsolete 再抬新版本。
 *
 * 合规要点：
 * - FR-8 批次引用版本快照，配方升级不污染历史批次档案（C2）；
 * - FR-10 生命周期动作全部写 audit_log，含参数差异摘要（C3）；
 * - C4 RBAC：草稿/提交=工艺员+，审批/生效=管理员。
 */
@Service
public class RecipeService {

  private static final Logger log = LoggerFactory.getLogger(RecipeService.class);
  private static final Pattern VERSION_PATTERN = Pattern.compile("^v(\\d+)\\.(\\d+)(.*)$");

  /** 前端配方状态枚举（recipeStatusMap：active / draft / obsolete）。 */
  private static final Map<String, String> STATUS_TO_FRONT = Map.of(
      "effective", "active",
      "pending", "draft",
      "draft", "draft",
      "obsolete", "obsolete",
      "rejected", "obsolete");

  private final RecipeVersionMapper versions;
  private final RecipeStepMapper steps;
  private final RecipeChangeMapper changes;
  private final BatchMapper batches;
  private final FixtureStore fixtures;
  private final AuditService audit;
  private final ObjectMapper json;
  /** Phase I · 电子签名门禁（FR-21 配方生效纳入受控动作）。 */
  private final com.fluxmes.api.regtech.SignatureService signatures;

  public RecipeService(RecipeVersionMapper versions, RecipeStepMapper steps,
      RecipeChangeMapper changes, BatchMapper batches, FixtureStore fixtures,
      AuditService audit, ObjectMapper json,
      com.fluxmes.api.regtech.SignatureService signatures) {
    this.versions = versions;
    this.steps = steps;
    this.changes = changes;
    this.batches = batches;
    this.fixtures = fixtures;
    this.audit = audit;
    this.json = json;
    this.signatures = signatures;
  }

  /* ---------------- T3 · 种子导入（幂等） ---------------- */

  /** fixture 配方 → recipe_version + recipe_step；已存在版本跳过。 */
  public void seed() {
    int created = 0;
    for (Map<String, Object> r : fixtures.recipes()) {
      String code = str(r.get("code"));
      String version = str(r.get("version"));
      if (code.isBlank() || version.isBlank()) continue;
      if (versions.selectCount(Wrappers.<RecipeVersion>lambdaQuery()
          .eq(RecipeVersion::getRecipeCode, code)
          .eq(RecipeVersion::getVersion, version)) > 0) continue;
      RecipeVersion rv = new RecipeVersion();
      rv.setRecipeCode(code);
      rv.setVersion(version);
      rv.setName(str(r.get("name")));
      rv.setProduct(str(r.get("product")));
      rv.setYieldRate(str(r.get("yield")));
      rv.setStages(writeJson(r.get("stages")));
      rv.setStatus(statusFromFixture(str(r.get("status"))));
      rv.setChangeNote(latestNote(r));
      rv.setCreatedBy(str(r.get("updatedBy")));
      rv.setCreatedAt(OffsetDateTime.now());
      if ("effective".equals(rv.getStatus())) rv.setEffectiveAt(OffsetDateTime.now());
      if ("obsolete".equals(rv.getStatus())) rv.setObsoleteAt(OffsetDateTime.now());
      versions.insert(rv);
      seedSteps(rv, r);
      created++;
    }
    if (created > 0) log.info("recipe seed: {} versions created", created);
  }

  private void seedSteps(RecipeVersion rv, Map<String, Object> r) {
    int seq = 1;
    for (Map<String, Object> p : asList(r.get("params"))) {
      RecipeStep s = new RecipeStep();
      s.setRecipeCode(rv.getRecipeCode());
      s.setVersion(rv.getVersion());
      s.setSeq(seq++);
      s.setStage(str(p.get("stage")));
      s.setParamName(str(p.get("name")));
      s.setParamKey(pinyinKey(str(p.get("name"))));
      s.setLowerLimit(dec(p.get("lo")));
      s.setUpperLimit(dec(p.get("hi")));
      s.setTargetValue(mid(s.getLowerLimit(), s.getUpperLimit()));
      s.setUnit(str(p.get("unit")));
      s.setCcp(Boolean.TRUE.equals(p.get("ccp")));
      s.setEquipmentCategory(null);
      steps.insert(s);
    }
  }

  /* ---------------- 列表与版本历史（T4） ---------------- */

  /** 配方列表（契约兼容前端 api/recipes.ts）：版本与 params 取自 PG，其余展示字段回落 fixture。 */
  public Map<String, Object> list() {
    Map<String, Integer> activeBatchCount = new LinkedHashMap<>();
    for (Batch b : batches.selectList(null)) {
      String code = String.valueOf(b.getRecipe() == null ? "" : b.getRecipe()).split(" ")[0];
      if (code.isBlank() || "null".equals(code)) continue;
      activeBatchCount.merge(code, 1, Integer::sum);
    }
    List<Map<String, Object>> items = new ArrayList<>();
    long draftCount = 0;
    long activeCount = 0;
    long obsoleteCount = 0;
    long ccpTotal = 0;
    for (Map<String, Object> r : fixtures.recipes()) {
      String code = str(r.get("code"));
      String version = str(r.get("version"));
      RecipeVersion rv = findVersion(code, version);
      Map<String, Object> view = new LinkedHashMap<>(r);
      List<Map<String, Object>> stepList = stepView(code, version);
      if (!stepList.isEmpty()) {
        view.put("params", paramsFromSteps(stepList));
        ccpTotal += stepList.stream().filter(s -> Boolean.TRUE.equals(s.get("ccp"))).count();
      }
      if (rv != null) {
        view.put("status", STATUS_TO_FRONT.getOrDefault(rv.getStatus(), "draft"));
        // versionRole 暴露受控状态机内部态（draft/pending/effective/obsolete/rejected），
        // 供前端展示生命周期操作入口；history 保留 fixture 契约 {v,date,by,note} 不变。
        view.put("versionRole", rv.getStatus());
        view.put("versions", history(code));
        view.put("id", String.valueOf(rv.getId()));
      }
      String st = str(view.get("status"));
      if ("draft".equals(st)) draftCount++;
      else if ("obsolete".equals(st)) obsoleteCount++;
      else activeCount++;
      if (stepList.isEmpty()) {
        ccpTotal += asList(r.get("params")).stream().filter(p -> Boolean.TRUE.equals(p.get("ccp"))).count();
      }
      items.add(view);
    }
    return map(
        "generatedAt", nowIso(),
        "recipes", items,
        "activeBatchCount", activeBatchCount,
        "summary", map(
            "total", items.size(),
            "active", activeCount,
            "draft", draftCount,
            "obsolete", obsoleteCount,
            "ccpTotal", ccpTotal,
            "pendingApproval", versions.selectCount(
                Wrappers.<RecipeVersion>lambdaQuery().eq(RecipeVersion::getStatus, "pending"))));
  }

  /** 版本历史倒序（NFR-2）。 */
  public List<Map<String, Object>> history(String code) {
    return versions.selectList(Wrappers.<RecipeVersion>lambdaQuery()
            .eq(RecipeVersion::getRecipeCode, code))
        .stream()
        .sorted(Comparator.comparing((RecipeVersion v) -> versionKey(v.getVersion())).reversed())
        .map(this::versionView).toList();
  }

  public List<Map<String, Object>> versions(String code) {
    return history(code);
  }

  public Map<String, Object> detail(String code, String version) {
    RecipeVersion rv = version == null || version.isBlank()
        ? effective(code) : require(code, version);
    return map("generatedAt", nowIso(), "version", versionView(rv),
        "steps", stepView(code, rv.getVersion()));
  }

  public List<Map<String, Object>> steps(String code, String version) {
    return stepView(code, version);
  }

  /* ---------------- FR-4/5/6 · 生命周期 ---------------- */

  /** FR-4 · 基于已有版本创建草稿：复制工序参数，版本号次版本递增。 */
  public Map<String, Object> createDraft(String code, Map<String, Object> body) {
    String fromVersion = str(body.get("fromVersion"));
    RecipeVersion src = fromVersion.isBlank() ? effectiveOrThrow(code) : require(code, fromVersion);
    // 版本号递增直到不与既有版本冲突（历史草稿/停用版本可能占用号段）
    String newVersion = nextVersion(src.getVersion());
    for (int guard = 0; guard < 20 && findVersion(code, newVersion) != null; guard++) {
      newVersion = nextVersion(newVersion);
    }
    RecipeVersion rv = new RecipeVersion();
    rv.setRecipeCode(code);
    rv.setVersion(newVersion);
    rv.setName(src.getName());
    rv.setProduct(src.getProduct());
    rv.setYieldRate(src.getYieldRate());
    rv.setStages(src.getStages());
    rv.setStatus("draft");
    rv.setSourceVersion(src.getVersion());
    rv.setChangeNote(str(body.get("changeNote")));
    rv.setCreatedBy(CurrentUser.username());
    rv.setCreatedAt(OffsetDateTime.now());
    versions.insert(rv);

    List<RecipeStep> srcSteps = steps.selectList(Wrappers.<RecipeStep>lambdaQuery()
        .eq(RecipeStep::getRecipeCode, code).eq(RecipeStep::getVersion, src.getVersion())
        .orderByAsc(RecipeStep::getSeq));
    List<Map<String, Object>> copied = new ArrayList<>();
    for (RecipeStep s : srcSteps) {
      RecipeStep copy = new RecipeStep();
      copy.setRecipeCode(code);
      copy.setVersion(newVersion);
      copy.setSeq(s.getSeq());
      copy.setStage(s.getStage());
      copy.setParamName(s.getParamName());
      copy.setParamKey(s.getParamKey());
      copy.setTargetValue(s.getTargetValue());
      copy.setLowerLimit(s.getLowerLimit());
      copy.setUpperLimit(s.getUpperLimit());
      copy.setUnit(s.getUnit());
      copy.setCcp(s.getCcp());
      copy.setEquipmentCategory(s.getEquipmentCategory());
      steps.insert(copy);
      copied.add(stepItem(copy));
    }
    // 允许在草稿上直接调整参数（body.steps）
    for (Map<String, Object> patch : asList(body.get("steps"))) {
      applyStepPatch(code, newVersion, patch);
    }
    audit.record("recipe.draft", "recipe_version", code + "@" + newVersion, null,
        map("fromVersion", src.getVersion(), "steps", copied.size(),
            "changeNote", rv.getChangeNote()));
    return map("recipeCode", code, "version", newVersion, "status", "draft",
        "sourceVersion", src.getVersion(), "steps", stepView(code, newVersion));
  }

  /** FR-5 · 提交审批：draft → pending，此后冻结编辑。 */
  public Map<String, Object> submit(String code, String version) {
    RecipeVersion rv = require(code, version);
    assertStatus(rv, "draft", "提交审批");
    Map<String, Object> before = map("status", rv.getStatus());
    rv.setStatus("pending");
    rv.setSubmittedBy(CurrentUser.username());
    rv.setSubmittedAt(OffsetDateTime.now());
    versions.updateById(rv);
    audit.record("recipe.submit", "recipe_version", code + "@" + version, before,
        map("status", "pending", "submittedBy", rv.getSubmittedBy()));
    return versionView(rv);
  }

  /** FR-6 · 审批：pending → effective（通过）或 rejected（驳回），须管理员。 */
  public Map<String, Object> approve(String code, String version, Map<String, Object> body,
      String ip, String ua) {
    if (!CurrentUser.hasRole(Roles.ADMIN)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅管理员可审批配方版本（C4）");
    }
    RecipeVersion rv = require(code, version);
    assertStatus(rv, "pending", "审批");
    boolean pass = !Boolean.FALSE.equals(body.get("approved"));
    String comment = str(body.get("comment"));
    Map<String, Object> before = map("status", rv.getStatus());
    if (pass) {
      // 审批通过即生效 → 受控动作，携带签名（FR-21）
      return activate(code, version, comment, signatureBody(body), ip, ua);
    }
    rv.setStatus("rejected");
    rv.setApprovedBy(CurrentUser.username());
    rv.setApprovedAt(OffsetDateTime.now());
    rv.setChangeNote(comment);
    versions.updateById(rv);
    audit.record("recipe.reject", "recipe_version", code + "@" + version, before,
        map("status", "rejected", "comment", comment));
    return versionView(rv);
  }

  /**
   * FR-6/NFR-3 · 生效切换：原子事务内置旧版本 obsolete、新版本 effective。
   * 数据库唯一索引作为并发兜底，违反时抛出 409 而非静默双生效。
   *
   * <p>FR-21 · 生效属受控动作：{@code signature_policy.RECIPE_ACTIVATE} 启用时须提交电子签名
   * （含义 APPROVED / 角色 SUPERVISOR+），签名绑定 {@code code@version} 记录内容哈希。
   */
  @Transactional
  public Map<String, Object> activate(String code, String version, String comment,
      Map<String, Object> signatureBody, String ip, String ua) {
    RecipeVersion rv = require(code, version);
    if (!List.of("pending", "draft", "obsolete").contains(rv.getStatus())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "版本 " + version + " 当前状态为 " + rv.getStatus() + "，不可直接生效");
    }
    signatures.requireSignature("RECIPE_ACTIVATE", code + "@" + version, signatureBody, ip, ua);
    List<RecipeVersion> oldEffective = versions.selectList(Wrappers.<RecipeVersion>lambdaQuery()
        .eq(RecipeVersion::getRecipeCode, code)
        .eq(RecipeVersion::getStatus, "effective"));
    OffsetDateTime now = OffsetDateTime.now();
    List<String> deactivated = new ArrayList<>();
    for (RecipeVersion old : oldEffective) {
      if (old.getId().equals(rv.getId())) continue;
      old.setStatus("obsolete");
      old.setObsoleteAt(now);
      versions.updateById(old);
      deactivated.add(old.getVersion());
    }
    Map<String, Object> before = map("status", rv.getStatus(), "effectiveVersion", deactivated);
    rv.setStatus("effective");
    rv.setApprovedBy(CurrentUser.username());
    rv.setApprovedAt(now);
    rv.setEffectiveAt(now);
    rv.setObsoleteAt(null);
    if (!comment.isBlank()) rv.setChangeNote(comment);
    versions.updateById(rv);
    recordChange(code, deactivated, rv.getVersion());
    audit.record("recipe.activate", "recipe_version", code + "@" + version, before,
        map("status", "effective", "deactivated", deactivated, "comment", comment));
    log.info("recipe {} version {} effective, superseded: {}", code, version, deactivated);
    return map("recipeCode", code, "version", version, "status", "effective",
        "deactivated", deactivated, "change", latestChange(code));
  }

  /* ---------------- FR-7 · 变更影响分析 ---------------- */

  /**
   * 影响面：仍在执行旧版本的在制批次 + 是否需重评检验方法（存在 CCP 参数变更即需重评）。
   */
  public Map<String, Object> impact(String code, String fromVersion) {
    // 未显式指定时，以「最近一次变更的源版本 → 当前生效版本」为比较基准；
    // 无变更记录时退化为最近一个被停用的版本，避免拿生效版本与自己比较得出恒为 false 的结论。
    RecipeVersion old = fromVersion != null && !fromVersion.isBlank()
        ? require(code, fromVersion)
        : (lastChangeSource(code) != null ? require(code, lastChangeSource(code))
            : latestObsoleteOrEffective(code));
    RecipeVersion next = history(code).stream()
        .filter(v -> "effective".equals(str(v.get("status"))) || "active".equals(str(v.get("status"))))
        .findFirst().map(v -> require(code, str(v.get("version")))).orElse(null);
    List<Batch> affected = batches.selectList(Wrappers.<Batch>lambdaQuery()
        .likeRight(Batch::getRecipe, code)
        .ne(Batch::getStatus, "done"));
    List<Map<String, Object>> batchesView = affected.stream()
        .filter(b -> matchesVersion(b.getRecipeVersion(), old.getVersion()))
        .map(b -> map("id", b.getId(), "product", b.getProduct(), "status", b.getStatus(),
            "stage", b.getStage(), "recipeVersion", b.getRecipeVersion(),
            "progress", b.getProgress(), "released", b.getReleased()))
        .toList();
    boolean reviewRequired = ccpDiffExists(old, next);
    return map(
        "generatedAt", nowIso(),
        "recipeCode", code,
        "fromVersion", old.getVersion(),
        "toVersion", next == null ? null : next.getVersion(),
        "affectedBatchCount", batchesView.size(),
        "affectedBatches", batchesView,
        "reviewRequired", reviewRequired,
        "reviewReason", reviewRequired
            ? "存在关键控制点（CCP）参数上下限变更，须重评检验方法与纠偏措施（C5）" : null,
        "changeHistory", changeHistory(code));
  }

  /* ---------------- FR-9 · 容差校验 ---------------- */

  /**
   * 工序采集值容差校验：超上下限返回违例清单，由调用方决定是否生成过程报警。
   */
  public List<Map<String, Object>> checkTolerance(String code, String version,
      String stage, Map<String, Object> values) {
    List<RecipeStep> scoped = steps.selectList(Wrappers.<RecipeStep>lambdaQuery()
        .eq(RecipeStep::getRecipeCode, code)
        .eq(RecipeStep::getVersion, version)
        .eq(stage != null && !stage.isBlank(), RecipeStep::getStage, stage));
    List<Map<String, Object>> violations = new ArrayList<>();
    for (RecipeStep s : scoped) {
      Object raw = values.get(s.getParamName()) == null ? values.get(s.getParamKey()) : values.get(s.getParamName());
      if (raw == null) continue;
      double v = Double.parseDouble(String.valueOf(raw));
      double lo = s.getLowerLimit() == null ? Double.NEGATIVE_INFINITY : s.getLowerLimit().doubleValue();
      double hi = s.getUpperLimit() == null ? Double.POSITIVE_INFINITY : s.getUpperLimit().doubleValue();
      String result = v > hi ? "OVER" : v < lo ? "UNDER" : "PASS";
      if (!"PASS".equals(result)) {
        violations.add(map("stage", s.getStage(), "param", s.getParamName(),
            "value", v, "lower", s.getLowerLimit(), "upper", s.getUpperLimit(),
            "unit", s.getUnit(), "result", result, "ccp", s.getCcp()));
      }
    }
    return violations;
  }

  /* ---------------- FR-8 · 批次建单：版本快照解析 ---------------- */

  /**
   * 解析批次应锁定的配方版本（FR-8 快照，避免配方升级污染历史批次档案 C2）。
   *
   * 降级口径（兼顾历史数据与演示表单可能传简写版本号）：
   * <ol>
   *   <li>配方未纳入版本受控（无 recipe_version 记录）→ 沿用调用方传入值；</li>
   *   <li>传入版本存在且生效 → 锁定该版本；</li>
   *   <li>传入版本存在但未生效 → 明确拒绝（400，防止用失效版本开工）；</li>
   *   <li>传入版本为空或不存在（如表单传 v3 而实际 v3.2）→ 自动锁定当前生效版本。</li>
   * </ol>
   */
  public String resolveVersionForBatch(String recipeRef, String requested) {
    String code = recipeRef == null ? "" : recipeRef.split(" ")[0];
    if (code.isBlank()) return requested;
    long managed = versions.selectCount(Wrappers.<RecipeVersion>lambdaQuery()
        .eq(RecipeVersion::getRecipeCode, code));
    if (managed == 0) return requested;
    if (requested != null && !requested.isBlank()) {
      RecipeVersion rv = findVersion(code, requested);
      if (rv != null) {
        assertVersionUsable(code, rv);
        return requested;
      }
    }
    RecipeVersion eff = effective(code);
    if (eff == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "配方 " + code + " 无生效版本，不可开工（须先完成版本审批与生效）");
    }
    return eff.getVersion();
  }

  /** 版本可用性校验：仅生效版本可用于生产。 */
  public void assertVersionUsable(String code, RecipeVersion rv) {
    if (!"effective".equals(rv.getStatus())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "配方 " + code + " 版本 " + rv.getVersion() + " 状态为 " + rv.getStatus()
              + "，仅生效版本可用于生产（FR-8）");
    }
  }

  /* ---------------- views & helpers ---------------- */

  public RecipeVersion require(String code, String version) {
    RecipeVersion rv = findVersion(code, version);
    if (rv == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND,
          "配方 " + code + " 版本 " + version + " 不存在");
    }
    return rv;
  }

  public RecipeVersion findVersion(String code, String version) {
    return versions.selectOne(Wrappers.<RecipeVersion>lambdaQuery()
        .eq(RecipeVersion::getRecipeCode, code)
        .eq(RecipeVersion::getVersion, version)
        .last("LIMIT 1"));
  }

  public RecipeVersion effective(String code) {
    return versions.selectOne(Wrappers.<RecipeVersion>lambdaQuery()
        .eq(RecipeVersion::getRecipeCode, code)
        .eq(RecipeVersion::getStatus, "effective")
        .last("LIMIT 1"));
  }

  private RecipeVersion effectiveOrThrow(String code) {
    RecipeVersion rv = effective(code);
    if (rv == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND,
          "配方 " + code + " 无生效版本，请显式指定 fromVersion");
    }
    return rv;
  }

  /** 最近一次变更记录中的源版本（含多个源时取第一个）。 */
  private String lastChangeSource(String code) {
    List<RecipeChange> list = changeHistoryRows(code);
    if (list.isEmpty() || list.get(0).getFromVersion() == null) return null;
    String[] parts = list.get(0).getFromVersion().split(",");
    String v = parts[0].trim();
    return v.isBlank() ? null : v;
  }

  /** 最近一个被停用的版本；无则退回当前生效版本（此时影响面为自比较）。 */
  private RecipeVersion latestObsoleteOrEffective(String code) {
    RecipeVersion obsolete = versions.selectList(Wrappers.<RecipeVersion>lambdaQuery()
            .eq(RecipeVersion::getRecipeCode, code)
            .eq(RecipeVersion::getStatus, "obsolete"))
        .stream().max(Comparator.comparing(v -> versionKey(v.getVersion()))).orElse(null);
    if (obsolete != null) return obsolete;
    RecipeVersion rv = effective(code);
    if (rv == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND,
          "配方 " + code + " 无生效版本，无法计算变更影响面");
    }
    return rv;
  }

  private static void assertStatus(RecipeVersion rv, String expected, String action) {
    if (!expected.equals(rv.getStatus())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "版本 " + rv.getVersion() + " 当前状态为 " + rv.getStatus() + "，不可执行" + action
              + "（期望 " + expected + "）");
    }
  }

  private List<Map<String, Object>> stepView(String code, String version) {
    return steps.selectList(Wrappers.<RecipeStep>lambdaQuery()
            .eq(RecipeStep::getRecipeCode, code)
            .eq(RecipeStep::getVersion, version)
            .orderByAsc(RecipeStep::getSeq))
        .stream().map(this::stepItem).toList();
  }

  private Map<String, Object> stepItem(RecipeStep s) {
    return map("seq", s.getSeq(), "stage", s.getStage(), "name", s.getParamName(),
        "key", s.getParamKey(), "target", s.getTargetValue(), "lo", s.getLowerLimit(),
        "hi", s.getUpperLimit(), "unit", s.getUnit(), "ccp", s.getCcp());
  }

  /** 与前端 params 契约对齐（stage/name/lo/hi/unit/ccp），无缝切换数据源。 */
  private static List<Map<String, Object>> paramsFromSteps(List<Map<String, Object>> stepList) {
    return stepList.stream()
        .map(s -> map("stage", s.get("stage"), "name", s.get("name"),
            "lo", s.get("lo"), "hi", s.get("hi"), "unit", s.get("unit"), "ccp", s.get("ccp")))
        .toList();
  }

  private Map<String, Object> versionView(RecipeVersion rv) {
    return map(
        "id", String.valueOf(rv.getId()),
        "recipeCode", rv.getRecipeCode(),
        "version", rv.getVersion(),
        "name", rv.getName(),
        "product", rv.getProduct(),
        "yield", rv.getYieldRate(),
        "status", rv.getStatus(),
        "statusLabel", STATUS_TO_FRONT.getOrDefault(rv.getStatus(), rv.getStatus()),
        "sourceVersion", rv.getSourceVersion(),
        "changeNote", rv.getChangeNote(),
        "createdBy", rv.getCreatedBy(),
        "createdAt", str(rv.getCreatedAt()),
        "submittedBy", rv.getSubmittedBy(),
        "submittedAt", str(rv.getSubmittedAt()),
        "approvedBy", rv.getApprovedBy(),
        "approvedAt", str(rv.getApprovedAt()),
        "effectiveAt", str(rv.getEffectiveAt()),
        "stepCount", steps.selectCount(Wrappers.<RecipeStep>lambdaQuery()
            .eq(RecipeStep::getRecipeCode, rv.getRecipeCode())
            .eq(RecipeStep::getVersion, rv.getVersion())));
  }

  private void recordChange(String code, List<String> fromVersions, String toVersion) {
    RecipeChange c = new RecipeChange();
    c.setRecipeCode(code);
    c.setFromVersion(fromVersions.isEmpty() ? null : String.join(",", fromVersions));
    c.setToVersion(toVersion);
    c.setAffectedBatches(writeJson(batches.selectList(Wrappers.<Batch>lambdaQuery()
            .likeRight(Batch::getRecipe, code).ne(Batch::getStatus, "done"))
        .stream().map(Batch::getId).toList()));
    c.setReviewRequired(ccpDiffExists(
        c.getFromVersion() == null ? null : findVersion(code, c.getFromVersion()),
        findVersion(code, toVersion)));
    c.setSummary("版本切换 " + c.getFromVersion() + " → " + toVersion);
    c.setCreatedBy(CurrentUser.username());
    c.setCreatedAt(OffsetDateTime.now());
    changes.insert(c);
  }

  private Map<String, Object> latestChange(String code) {
    List<RecipeChange> list = changeHistoryRows(code);
    return list.isEmpty() ? Map.of() : map(
        "id", String.valueOf(list.get(0).getId()),
        "fromVersion", list.get(0).getFromVersion(),
        "toVersion", list.get(0).getToVersion(),
        "reviewRequired", list.get(0).getReviewRequired(),
        "summary", list.get(0).getSummary(),
        "createdAt", str(list.get(0).getCreatedAt()));
  }

  private List<Map<String, Object>> changeHistory(String code) {
    return changeHistoryRows(code).stream().map(c -> map(
        "id", String.valueOf(c.getId()),
        "fromVersion", c.getFromVersion(),
        "toVersion", c.getToVersion(),
        "reviewRequired", c.getReviewRequired(),
        "summary", c.getSummary(),
        "createdBy", c.getCreatedBy(),
        "createdAt", str(c.getCreatedAt()))).toList();
  }

  private List<RecipeChange> changeHistoryRows(String code) {
    return changes.selectList(Wrappers.<RecipeChange>lambdaQuery()
        .eq(RecipeChange::getRecipeCode, code)
        .orderByDesc(RecipeChange::getId));
  }

  /** CCP 参数是否发生变更（用于判定是否需重评检验方法）。 */
  private boolean ccpDiffExists(RecipeVersion old, RecipeVersion next) {
    if (old == null || next == null) return false;
    Map<String, RecipeStep> oldCcp = ccpMap(old);
    if (oldCcp.isEmpty()) return false;
    for (Map.Entry<String, RecipeStep> e : ccpMap(next).entrySet()) {
      RecipeStep prev = oldCcp.get(e.getKey());
      if (prev == null) return true;
      RecipeStep cur = e.getValue();
      if (!eq(prev.getLowerLimit(), cur.getLowerLimit()) || !eq(prev.getUpperLimit(), cur.getUpperLimit())) {
        return true;
      }
    }
    return oldCcp.size() != ccpMap(next).size();
  }

  private Map<String, RecipeStep> ccpMap(RecipeVersion v) {
    Map<String, RecipeStep> m = new LinkedHashMap<>();
    for (RecipeStep s : steps.selectList(Wrappers.<RecipeStep>lambdaQuery()
        .eq(RecipeStep::getRecipeCode, v.getRecipeCode())
        .eq(RecipeStep::getVersion, v.getVersion()))) {
      if (Boolean.TRUE.equals(s.getCcp())) m.put(s.getStage() + "/" + s.getParamName(), s);
    }
    return m;
  }

  private void applyStepPatch(String code, String version, Map<String, Object> patch) {
    String name = str(patch.get("name"));
    if (name.isBlank()) return;
    RecipeStep s = steps.selectOne(Wrappers.<RecipeStep>lambdaQuery()
        .eq(RecipeStep::getRecipeCode, code)
        .eq(RecipeStep::getVersion, version)
        .eq(RecipeStep::getParamName, name)
        .last("LIMIT 1"));
    if (s == null) {
      s = new RecipeStep();
      s.setRecipeCode(code);
      s.setVersion(version);
      s.setParamName(name);
      s.setSeq(999);
      s.setCcp(Boolean.TRUE.equals(patch.get("ccp")));
    }
    if (patch.get("stage") != null) s.setStage(str(patch.get("stage")));
    if (patch.get("lo") != null) s.setLowerLimit(dec(patch.get("lo")));
    if (patch.get("hi") != null) s.setUpperLimit(dec(patch.get("hi")));
    if (patch.get("unit") != null) s.setUnit(str(patch.get("unit")));
    s.setTargetValue(mid(s.getLowerLimit(), s.getUpperLimit()));
    s.setParamKey(s.getParamKey() == null ? pinyinKey(name) : s.getParamKey());
    if (s.getId() == null) steps.insert(s); else steps.updateById(s);
  }

  private static boolean eq(BigDecimal a, BigDecimal b) {
    if (a == null && b == null) return true;
    if (a == null || b == null) return false;
    return a.compareTo(b) == 0;
  }

  private static boolean matchesVersion(String batchVersion, String version) {
    if (batchVersion == null || batchVersion.isBlank()) return true; // 历史批次未锁版本，按受影响处理
    return batchVersion.equals(version);
  }

  /** 版本号次版本递增：v3.2 → v3.3；次版本进位后主版本 +1（≥10）。 */
  static String nextVersion(String version) {
    Matcher m = VERSION_PATTERN.matcher(version == null ? "" : version.trim());
    if (!m.matches()) return version + "-rc";
    int major = Integer.parseInt(m.group(1));
    int minor = Integer.parseInt(m.group(2)) + 1;
    if (minor >= 10) {
      major += 1;
      minor = 0;
    }
    return "v" + major + "." + minor;
  }

  /** 版本号排序键：v3.2 → 3002（用于倒序）。 */
  static int versionKey(String version) {
    Matcher m = VERSION_PATTERN.matcher(version == null ? "" : version.trim());
    if (!m.matches()) return 0;
    return Integer.parseInt(m.group(1)) * 1000 + Integer.parseInt(m.group(2));
  }

  private static String statusFromFixture(String status) {
    return switch (status == null ? "" : status) {
      case "active" -> "effective";
      case "draft" -> "draft";
      case "obsolete" -> "obsolete";
      default -> "draft";
    };
  }

  /** fixture 版本中最新的变更说明（history[0].note）。 */
  private static String latestNote(Map<String, Object> r) {
    List<Map<String, Object>> history = asList(r.get("history"));
    return history.isEmpty() ? "" : str(history.get(0).get("note"));
  }

  private static String pinyinKey(String name) {
    // 不做真实拼音转换（避免引入依赖），仅用于稳定参数键：取汉字首字 + 哈希后缀
    String base = switch (name) {
      case "灭菌温度" -> "sterilize_temp";
      case "灭菌时间" -> "sterilize_time";
      case "接种量" -> "inoculum_ratio";
      case "发酵温度" -> "ferment_temp";
      case "通气量" -> "aeration";
      case "pH" -> "ph";
      case "溶氧 DO" -> "do";
      case "中和终点 pH" -> "neutral_ph";
      case "反应温度" -> "reaction_temp";
      case "活性炭投加" -> "carbon_dose";
      case "结晶温度" -> "crystal_temp";
      case "成品水分" -> "product_moisture";
      case "终点水分" -> "final_moisture";
      case "进风温度" -> "inlet_temp";
      case "包装间湿度" -> "packing_humidity";
      case "溶解温度" -> "dissolve_temp";
      case "装量偏差" -> "fill_deviation";
      default -> "param";
    };
    return base.isEmpty() ? "param" : base;
  }

  private String writeJson(Object v) {
    try {
      return json.writeValueAsString(v == null ? List.of() : v);
    } catch (Exception ignored) {
      return "[]";
    }
  }

  private static BigDecimal dec(Object v) {
    if (v == null) return null;
    try {
      return new BigDecimal(String.valueOf(v));
    } catch (Exception e) {
      return null;
    }
  }

  private static BigDecimal mid(BigDecimal lo, BigDecimal hi) {
    if (lo == null && hi == null) return null;
    if (lo == null) return hi;
    if (hi == null) return lo;
    return lo.add(hi).divide(BigDecimal.valueOf(2), 4, java.math.RoundingMode.HALF_UP);
  }

  private static String str(Object v) {
    return v == null || "null".equals(String.valueOf(v)) ? "" : String.valueOf(v);
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> asList(Object v) {
    return v instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
  }

  /** 从请求体中取出嵌套的 signature 对象（{meaning, password}），无则返回 null。 */
  @SuppressWarnings("unchecked")
  private static Map<String, Object> signatureBody(Map<String, Object> body) {
    if (body == null) return null;
    Object sig = body.get("signature");
    return sig instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
  }
}
