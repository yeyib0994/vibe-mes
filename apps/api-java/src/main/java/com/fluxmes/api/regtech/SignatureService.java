package com.fluxmes.api.regtech;

import static com.fluxmes.api.common.ApiSupport.map;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fluxmes.api.audit.AuditService;
import com.fluxmes.api.common.CurrentUser;
import com.fluxmes.api.common.Roles;
import com.fluxmes.api.entity.AppUser;
import com.fluxmes.api.entity.Batch;
import com.fluxmes.api.entity.Capa;
import com.fluxmes.api.entity.Deviation;
import com.fluxmes.api.entity.ESignature;
import com.fluxmes.api.entity.RecipeVersion;
import com.fluxmes.api.entity.SignatureAttempt;
import com.fluxmes.api.entity.SignaturePolicy;
import com.fluxmes.api.mapper.AppUserMapper;
import com.fluxmes.api.mapper.BatchMapper;
import com.fluxmes.api.mapper.CapaMapper;
import com.fluxmes.api.mapper.DeviationMapper;
import com.fluxmes.api.mapper.ESignatureMapper;
import com.fluxmes.api.mapper.RecipeVersionMapper;
import com.fluxmes.api.mapper.SignatureAttemptMapper;
import com.fluxmes.api.mapper.SignaturePolicyMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 电子签名服务（21 CFR Part 11 / EU GMP 附录 11）。
 *
 * <p>职责：
 * <ul>
 *   <li><b>门禁</b>（FR-21）：受控动作（放行 / 偏差关闭 / CAPA 关闭与验证 / 配方生效）按
 *       {@code signature_policy} 配置强制签名，未启用策略则旁路（plan D7 降级开关）。</li>
 *   <li><b>要素落库</b>（FR-15）：签名人 / 姓名 / 当时角色 / 服务端时间 / 含义 / 记录类型 / 记录 ID /
 *       记录修订号 / 记录内容 SHA-256 / IP / User-Agent。</li>
 *   <li><b>防共享账号</b>（FR-16）：系统与集成账号禁止签名。</li>
 *   <li><b>失败锁定</b>（FR-17）：连续 5 次密码错误锁定 15 分钟，计数持久化（plan D5，多实例安全）。</li>
 *   <li><b>篡改检测</b>（FR-19）：重算 canonical JSON 哈希与签名时哈希比对。</li>
 *   <li><b>只追加</b>（NFR-2）：签名记录不可改删；记录实质变更走 {@link #bumpRevision} 作废旧签名。</li>
 * </ul>
 *
 * <p><b>哈希口径（plan D4 / NFR-5）</b>：哈希输入 = {@code <规则版本>|<canonical JSON>}，
 * canonical JSON 采用<b>字段白名单 + 固定顺序</b>，排除 {@code updated_at} / {@code record_revision}
 * 以及由受控动作本身写入的处置字段（status / released / coaNo / verified* / closed*），
 * 避免「无害的流程推进」被误判为篡改；规则版本随白名单变更而升版，历史签名按各自版本复算。
 */
@Service
public class SignatureService {

  private static final Logger log = LoggerFactory.getLogger(SignatureService.class);
  private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  /** 当前 canonical JSON 规则版本（白名单或顺序变更时升版，NFR-5）。 */
  public static final String SERIALIZE_RULE = "v1";

  public static final String AUTHORED = "AUTHORED";
  public static final String REVIEWED = "REVIEWED";
  public static final String APPROVED = "APPROVED";
  public static final String VERIFIED = "VERIFIED";
  public static final String WITNESSED = "WITNESSED";

  /** 含义中文名（Part 11 §11.50(b)：签名须标明含义）。 */
  public static final Map<String, String> MEANING_LABEL = Map.of(
      AUTHORED, "编制", REVIEWED, "复核", APPROVED, "批准", VERIFIED, "验证", WITNESSED, "见证");

  /** FR-16：系统 / 集成账号不得签名（共享账号签名不具唯一性）。 */
  private static final List<String> BANNED_SIGNERS =
      List.of("service", "integration", "system", "anonymous", "root", "batch");

  public static final int MAX_FAILURES = 5;
  public static final int LOCK_MINUTES = 15;

  private final SignaturePolicyMapper policies;
  private final ESignatureMapper signatures;
  private final SignatureAttemptMapper attempts;
  private final AppUserMapper users;
  private final BatchMapper batches;
  private final DeviationMapper deviations;
  private final CapaMapper capas;
  private final RecipeVersionMapper recipeVersions;
  private final AuditService audit;
  private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

  public SignatureService(SignaturePolicyMapper policies, ESignatureMapper signatures,
      SignatureAttemptMapper attempts, AppUserMapper users, BatchMapper batches,
      DeviationMapper deviations, CapaMapper capas, RecipeVersionMapper recipeVersions,
      AuditService audit) {
    this.policies = policies;
    this.signatures = signatures;
    this.attempts = attempts;
    this.users = users;
    this.batches = batches;
    this.deviations = deviations;
    this.capas = capas;
    this.recipeVersions = recipeVersions;
    this.audit = audit;
  }

  /* ==================== 策略 ==================== */

  public List<Map<String, Object>> listPolicies() {
    return policies.selectList(Wrappers.<SignaturePolicy>lambdaQuery()
            .orderByAsc(SignaturePolicy::getAction)).stream()
        .map(SignatureService::policyView)
        .toList();
  }

  public static Map<String, Object> policyView(SignaturePolicy p) {
    return map(
        "action", p.getAction(),
        "name", p.getName(),
        "recordType", p.getRecordType(),
        "requiredMeaning", p.getRequiredMeaning(),
        "requiredMeaningLabel", MEANING_LABEL.getOrDefault(p.getRequiredMeaning(), p.getRequiredMeaning()),
        "requiredRole", p.getRequiredRole(),
        "enabled", Boolean.TRUE.equals(p.getEnabled()),
        "note", p.getNote());
  }

  /* ==================== FR-21 门禁 ==================== */

  /**
   * 受控动作签名门禁：策略未配置或已关闭时旁路返回 {@code null}（plan D7 测试/降级通道）。
   *
   * @param signatureBody 请求体中的 {@code signature:{meaning,password}}；策略启用时不得为空
   * @throws ResponseStatusException 409 —— 策略启用但未提交签名（说明所需含义与角色）
   */
  public ESignature requireSignature(String action, String recordId,
      Map<String, Object> signatureBody, String ip, String ua) {
    SignaturePolicy p = policies.selectById(action);
    if (p == null || !Boolean.TRUE.equals(p.getEnabled())) return null;
    if (signatureBody == null || signatureBody.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "该操作需要电子签名（" + p.getName() + "）：请在请求体提交 signature={meaning:\""
              + p.getRequiredMeaning() + "（" + MEANING_LABEL.getOrDefault(p.getRequiredMeaning(), "") 
              + "）\", password:\"<登录密码>\"}，所需角色 " + p.getRequiredRole());
    }
    return sign(action, p.getRecordType(), recordId,
        str(signatureBody.get("meaning")), str(signatureBody.get("password")), ip, ua);
  }

  /* ==================== FR-14 ~ FR-17 签名 ==================== */

  /** 执行一次电子签名：二次身份确认（密码）+ 含义声明 + 角色校验 + 要素落库。 */
  public ESignature sign(String action, String recordType, String recordId, String meaning,
      String password, String ip, String userAgent) {

    SignaturePolicy p = policies.selectById(action);
    if (p == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "未配置签名策略: " + action);
    if (!Boolean.TRUE.equals(p.getEnabled())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "该动作未启用电子签名: " + action);
    }

    String username = CurrentUser.username();
    String role = CurrentUser.role();

    // FR-16 · 禁止共享/系统账号签名
    if (username == null || BANNED_SIGNERS.contains(username.toLowerCase())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,
          "禁止共享/系统账号签名（FR-16）：签名人须为已启用的实名账号，当前: " + username);
    }

    // FR-17 · 锁定态拦截
    assertNotLocked(username);

    // FR-14 · 含义声明须与策略一致
    String required = p.getRequiredMeaning();
    if (meaning == null || meaning.isBlank() || !required.equals(meaning)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "签名含义不符：本动作需 " + required + "（"
              + MEANING_LABEL.getOrDefault(required, required) + "），收到: " + meaning);
    }

    AppUser user = users.selectOne(Wrappers.<AppUser>lambdaQuery().eq(AppUser::getUsername, username));
    if (user == null || !Boolean.TRUE.equals(user.getEnabled())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "签名账号不存在或已停用: " + username);
    }

    // FR-14 · 二次身份确认（BCrypt 复用既有认证路径，不引入新认证库）
    // 顺序说明：先验身份（密码）后判授权（角色），与登录语义一致；
    // 好处是失败锁定计数对任意账号一致生效，不因角色不足而绕过尝试防护（FR-17）。
    if (!encoder.matches(password == null ? "" : password, user.getPasswordHash())) {
      recordFailure(username);   // 内部直接抛出 401 / 423
    }
    clearFailures(username);

    // C4 · 角色校验
    if (!Roles.atLeast(role, p.getRequiredRole())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,
          "签名角色不足：本动作需 " + p.getRequiredRole() + " 及以上，当前: " + role);
    }

    String rtype = recordType == null || recordType.isBlank() ? p.getRecordType() : recordType;
    int revision = revisionOf(rtype, recordId);
    LinkedHashMap<String, Object> projection = projection(rtype, recordId);
    String hash = sha256Hex(SERIALIZE_RULE + "|" + canonicalJson(projection));

    ESignature s = new ESignature();
    s.setAction(action);
    s.setRecordType(rtype);
    s.setRecordId(recordId);
    s.setRecordVersion(revision);
    s.setMeaning(meaning);
    s.setSigner(username);
    s.setSignerName(user.getDisplayName());
    s.setSignerRole(role);
    s.setSignedAt(OffsetDateTime.now());          // D6 · 服务端时间，不接受客户端传入
    s.setPayloadHash(hash);
    s.setHashAlgo("SHA-256");
    s.setSerializeRuleVersion(SERIALIZE_RULE);
    s.setIp(ip);
    s.setUserAgent(userAgent == null ? null : truncate(userAgent, 256));
    s.setStatus("VALID");
    signatures.insert(s);

    audit.record("signature.create", "e_signature", String.valueOf(s.getId()), null, map(
        "action", action, "recordType", rtype, "recordId", recordId, "recordVersion", revision,
        "meaning", meaning, "signer", username, "payloadHash", hash));
    log.info("e-signature {} by {} on {}:{} rev{} meaning={}",
        s.getId(), username, rtype, recordId, revision, meaning);
    return s;
  }

  /** FR-19 · 篡改检测：重算当前记录哈希与签名哈希比对，并报告是否已修订（stale）。 */
  public Map<String, Object> verify(Long id) {
    ESignature s = signatures.selectById(id);
    if (s == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "签名不存在: " + id);
    Map<String, Object> out = new LinkedHashMap<>(toView(s));
    boolean tampered;
    String currentHash = null;
    String error = null;
    int currentRevision;
    try {
      LinkedHashMap<String, Object> projection = projection(s.getRecordType(), s.getRecordId());
      currentHash = sha256Hex(s.getSerializeRuleVersion() + "|" + canonicalJson(projection));
      tampered = !currentHash.equals(s.getPayloadHash());
      currentRevision = revisionOf(s.getRecordType(), s.getRecordId());
    } catch (ResponseStatusException e) {
      tampered = true;                    // 记录已不存在视为不可信
      currentRevision = -1;
      error = e.getReason();
    }
    boolean stale = currentRevision != s.getRecordVersion();
    out.put("currentHash", currentHash);
    out.put("tampered", tampered);
    out.put("stale", stale);
    out.put("currentRecordVersion", currentRevision);
    out.put("valid", "VALID".equals(s.getStatus()) && !tampered && !stale);
    if (error != null) out.put("error", error);
    return out;
  }

  /** 签名清单（FR-20 / SC-12）。 */
  public List<Map<String, Object>> list(String recordType, String recordId, String signer, String action) {
    return signatures.selectList(Wrappers.<ESignature>lambdaQuery()
            .eq(recordType != null && !recordType.isBlank(), ESignature::getRecordType, recordType)
            .eq(recordId != null && !recordId.isBlank(), ESignature::getRecordId, recordId)
            .eq(signer != null && !signer.isBlank(), ESignature::getSigner, signer)
            .eq(action != null && !action.isBlank(), ESignature::getAction, action)
            .orderByDesc(ESignature::getSignedAt)
            .orderByDesc(ESignature::getId)).stream()
        .map(SignatureService::toView)
        .toList();
  }

  public static Map<String, Object> toView(ESignature s) {
    return map(
        "id", s.getId(),
        "action", s.getAction(),
        "recordType", s.getRecordType(),
        "recordId", s.getRecordId(),
        "recordVersion", s.getRecordVersion(),
        "meaning", s.getMeaning(),
        "meaningLabel", MEANING_LABEL.getOrDefault(s.getMeaning(), s.getMeaning()),
        "signer", s.getSigner(),
        "signerName", s.getSignerName(),
        "signerRole", s.getSignerRole(),
        "signedAt", s.getSignedAt() == null ? null : s.getSignedAt().toString(),
        "payloadHash", s.getPayloadHash(),
        "hashAlgo", s.getHashAlgo(),
        "serializeRuleVersion", s.getSerializeRuleVersion(),
        "ip", s.getIp(),
        "userAgent", s.getUserAgent(),
        "status", s.getStatus(),
        "voidedAt", s.getVoidedAt() == null ? null : s.getVoidedAt().toString(),
        "voidedBy", s.getVoidedBy(),
        "voidReason", s.getVoidReason());
  }

  /* ==================== FR-18 / NFR-2 修订与作废 ==================== */

  /**
   * 记录内容实质变更：修订号 +1，并作废该记录全部有效签名（更正 = 作废 + 新签）。
   *
   * @return 被作废的签名条数
   */
  public int bumpRevision(String recordType, String recordId, String reason) {
    switch (recordType) {
      case "BATCH" -> batches.update(null, new UpdateWrapper<Batch>()
          .setSql("record_revision = record_revision + 1").eq("id", recordId));
      case "DEVIATION" -> deviations.update(null, new UpdateWrapper<Deviation>()
          .setSql("record_revision = record_revision + 1").eq("id", recordId));
      case "CAPA" -> capas.update(null, new UpdateWrapper<Capa>()
          .setSql("record_revision = record_revision + 1").eq("id", recordId));
      case "RECIPE_VERSION" -> {
        String[] parts = splitRecipeRef(recordId);
        recipeVersions.update(null, new UpdateWrapper<RecipeVersion>()
            .setSql("record_revision = record_revision + 1")
            .eq("recipe_code", parts[0]).eq("version", parts[1]));
      }
      default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "不支持的记录类型: " + recordType);
    }
    List<ESignature> valid = signatures.selectList(Wrappers.<ESignature>lambdaQuery()
        .eq(ESignature::getRecordType, recordType)
        .eq(ESignature::getRecordId, recordId)
        .eq(ESignature::getStatus, "VALID"));
    for (ESignature s : valid) {
      s.setStatus("VOID");
      s.setVoidedAt(OffsetDateTime.now());
      s.setVoidedBy(CurrentUser.username());
      s.setVoidReason(reason);
      signatures.updateById(s);          // 唯一允许的签名变更：作废（NFR-2）
    }
    if (!valid.isEmpty()) {
      audit.record("signature.void", "e_signature", recordType + ":" + recordId, null, map(
          "reason", reason, "voidedCount", valid.size(),
          "signatureIds", valid.stream().map(ESignature::getId).toList()));
    }
    return valid.size();
  }

  /* ==================== FR-20 导出（服务端渲染，与前端无关，R6） ==================== */

  /** 签名证据包导出：Markdown（默认）或 JSON，供监管检查提交。 */
  public Map<String, Object> export(String recordType, String recordId, String format) {
    List<Map<String, Object>> rows = list(recordType, recordId, null, null);
    String scope = (recordType == null ? "全部记录" : recordType)
        + (recordId == null || recordId.isBlank() ? "" : " / " + recordId);
    String stamp = OffsetDateTime.now().format(TS);
    if ("json".equalsIgnoreCase(format)) {
      return map(
          "format", "json",
          "contentType", "application/json",
          "filename", "signatures-" + (recordId == null || recordId.isBlank() ? "all" : recordId) + ".json",
          "count", rows.size(),
          "markdown", null,
          "signatures", rows,
          "generatedAt", stamp,
          "generatedBy", CurrentUser.username());
    }
    StringBuilder md = new StringBuilder();
    md.append("# 电子签名证据清单（21 CFR Part 11 · EU GMP 附录 11）\n\n")
        .append("- 记录范围：").append(scope).append('\n')
        .append("- 签名条数：").append(rows.size()).append('\n')
        .append("- 导出人：").append(CurrentUser.username())
        .append(" · ").append(CurrentUser.role()).append('\n')
        .append("- 导出时间：").append(stamp).append('\n')
        .append("- 哈希算法：SHA-256（canonical JSON，规则版本 ").append(SERIALIZE_RULE).append("）\n\n")
        .append("| # | 签名人 | 姓名 | 角色 | 服务器时间 | 含义 | 动作 | 记录类型 | 记录 ID | 版本 | 哈希(SHA-256) | 状态 |\n")
        .append("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
    int i = 1;
    for (Map<String, Object> r : rows) {
      md.append("| ").append(i++).append(" | ").append(r.get("signer")).append(" | ")
          .append(r.get("signerName")).append(" | ").append(r.get("signerRole")).append(" | ")
          .append(r.get("signedAt")).append(" | ").append(r.get("meaningLabel")).append(" | ")
          .append(r.get("action")).append(" | ").append(r.get("recordType")).append(" | ")
          .append(r.get("recordId")).append(" | v").append(r.get("recordVersion")).append(" | `")
          .append(r.get("payloadHash")).append("` | ").append(r.get("status")).append(" |\n");
    }
    md.append("\n> 本清单由 FluxMES 服务端渲染，哈希绑定签名时的记录内容；")
        .append("记录经实质修订后原签名作废（状态 VOID），历史签名不可删除、不可修改。\n");
    return map(
        "format", "md",
        "contentType", "text/markdown",
        "filename", "signatures-" + (recordId == null || recordId.isBlank() ? "all" : recordId) + ".md",
        "count", rows.size(),
        "markdown", md.toString(),
        "signatures", rows,
        "generatedAt", stamp,
        "generatedBy", CurrentUser.username());
  }

  /* ==================== 投影与哈希（plan D4） ==================== */

  /**
   * canonical 投影：**字段白名单 + 固定顺序**。
   * 排除易变字段（updated_at / record_revision）与受控动作本身写入的处置字段，
   * 使「流程推进」不被误判为篡改，而「内容被改」必被检出（SC-8）。
   */
  public LinkedHashMap<String, Object> projection(String recordType, String recordId) {
    LinkedHashMap<String, Object> m = new LinkedHashMap<>();
    switch (recordType) {
      case "BATCH" -> {
        Batch b = batches.selectById(recordId);
        if (b == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "记录不存在: BATCH " + recordId);
        m.put("id", b.getId());
        m.put("product", b.getProduct());
        m.put("recipe", b.getRecipe());
        m.put("recipeVersion", b.getRecipeVersion());
        m.put("equipment", b.getEquipment());
        m.put("stage", b.getStage());
        m.put("planYield", b.getPlanYield() == null ? null : b.getPlanYield().stripTrailingZeros().toPlainString());
        m.put("materialLots", b.getMaterialLots());
        m.put("line", b.getLine());
        m.put("site", b.getSite());
        m.put("productionDate", b.getProductionDate() == null ? null : b.getProductionDate().toString());
        m.put("shelfLifeDays", b.getShelfLifeDays());
        m.put("expiryDate", b.getExpiryDate() == null ? null : b.getExpiryDate().toString());
      }
      case "DEVIATION" -> {
        Deviation d = deviations.selectById(recordId);
        if (d == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "记录不存在: DEVIATION " + recordId);
        m.put("id", d.getId());
        m.put("batchId", d.getBatchId());
        m.put("source", d.getSource());
        m.put("description", d.getDescription());
        m.put("rootCause", d.getRootCause());
        m.put("capa", d.getCapa());
      }
      case "CAPA" -> {
        Capa c = capas.selectById(recordId);
        if (c == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "记录不存在: CAPA " + recordId);
        m.put("id", c.getId());
        m.put("sourceType", c.getSourceType());
        m.put("sourceId", c.getSourceId());
        m.put("type", c.getType());
        m.put("title", c.getTitle());
        m.put("description", c.getDescription());
        m.put("rootCause", c.getRootCause());
        m.put("owner", c.getOwner());
        m.put("dueDate", c.getDueDate() == null ? null : c.getDueDate().toString());
        m.put("severity", c.getSeverity());
      }
      case "RECIPE_VERSION" -> {
        String[] parts = splitRecipeRef(recordId);
        RecipeVersion rv = recipeVersions.selectOne(Wrappers.<RecipeVersion>lambdaQuery()
            .eq(RecipeVersion::getRecipeCode, parts[0])
            .eq(RecipeVersion::getVersion, parts[1]).last("LIMIT 1"));
        if (rv == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "记录不存在: RECIPE_VERSION " + recordId);
        m.put("id", rv.getId());
        m.put("recipeCode", rv.getRecipeCode());
        m.put("version", rv.getVersion());
        m.put("name", rv.getName());
        m.put("product", rv.getProduct());
        m.put("yieldRate", rv.getYieldRate());
        m.put("stages", rv.getStages());
        m.put("sourceVersion", rv.getSourceVersion());
      }
      default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的记录类型: " + recordType);
    }
    return m;
  }

  /** 记录当前修订号（签名绑定的版本来源，FR-15）。 */
  public int revisionOf(String recordType, String recordId) {
    if ("CAPA".equals(recordType)) {
      Capa c = capas.selectById(recordId);
      if (c == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "记录不存在: CAPA " + recordId);
      return c.getRecordRevision() == null ? 1 : c.getRecordRevision();
    }
    Number n;
    switch (recordType) {
      case "BATCH" -> n = rawRevision(batches.selectMaps(
          new QueryWrapper<Batch>().select("record_revision").eq("id", recordId)));
      case "DEVIATION" -> n = rawRevision(deviations.selectMaps(
          new QueryWrapper<Deviation>().select("record_revision").eq("id", recordId)));
      case "RECIPE_VERSION" -> {
        String[] parts = splitRecipeRef(recordId);
        n = rawRevision(recipeVersions.selectMaps(new QueryWrapper<RecipeVersion>()
            .select("record_revision").eq("recipe_code", parts[0]).eq("version", parts[1])));
      }
      default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的记录类型: " + recordType);
    }
    if (n == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "记录不存在: " + recordType + " " + recordId);
    return n.intValue();
  }

  private static Number rawRevision(List<Map<String, Object>> rows) {
    if (rows == null || rows.isEmpty()) return null;
    Object v = rows.get(0).get("record_revision");
    return v instanceof Number n ? n : null;
  }

  /** 稳定序列化：不加空格、不排序（顺序即白名单顺序），空值输出 JSON null。 */
  public static String canonicalJson(Map<String, Object> m) {
    StringBuilder sb = new StringBuilder("{");
    boolean first = true;
    for (Map.Entry<String, Object> e : m.entrySet()) {
      if (!first) sb.append(',');
      first = false;
      sb.append(quote(e.getKey())).append(':').append(value(e.getValue()));
    }
    return sb.append('}').toString();
  }

  private static String value(Object v) {
    if (v == null) return "null";
    if (v instanceof Number || v instanceof Boolean) return v.toString();
    return quote(String.valueOf(v));
  }

  private static String quote(String s) {
    StringBuilder sb = new StringBuilder("\"");
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
          else sb.append(c);
        }
      }
    }
    return sb.append('"').toString();
  }

  /** SHA-256（JDK 内置 MessageDigest，不引第三方哈希库 —— 依赖红线）。 */
  public static String sha256Hex(String input) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(input.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(digest.length * 2);
      for (byte b : digest) sb.append(String.format("%02x", b));
      return sb.toString();
    } catch (Exception e) {
      throw new IllegalStateException("SHA-256 不可用", e);
    }
  }

  /* ==================== FR-17 失败计数与锁定 ==================== */

  private void assertNotLocked(String username) {
    SignatureAttempt a = attempts.selectById(username);
    if (a == null || a.getLockedUntil() == null) return;
    OffsetDateTime now = OffsetDateTime.now();
    if (a.getLockedUntil().isAfter(now)) {
      long mins = Math.max(1, Duration.between(now, a.getLockedUntil()).toMinutes() + 1);
      throw new ResponseStatusException(HttpStatus.LOCKED,
          "签名能力已锁定（连续 " + MAX_FAILURES + " 次密码校验失败），请约 " + mins + " 分钟后再试");
    }
    a.setLockedUntil(null);
    a.setFailedCount(0);
    a.setUpdatedAt(now);
    attempts.updateById(a);
  }

  private void recordFailure(String username) {
    OffsetDateTime now = OffsetDateTime.now();
    SignatureAttempt a = attempts.selectById(username);
    if (a == null) {
      a = new SignatureAttempt();
      a.setUsername(username);
      a.setFirstFailedAt(now);
      a.setFailedCount(0);
    }
    int n = (a.getFailedCount() == null ? 0 : a.getFailedCount()) + 1;
    a.setFailedCount(n);
    a.setUpdatedAt(now);
    if (n >= MAX_FAILURES) {
      a.setLockedUntil(now.plusMinutes(LOCK_MINUTES));
      a.setFailedCount(0);
      a.setFirstFailedAt(null);
      upsert(a);
      audit.record("signature.lock", "app_user", username, null, map(
          "failedCount", n, "lockMinutes", LOCK_MINUTES, "lockedUntil", a.getLockedUntil().toString()));
      throw new ResponseStatusException(HttpStatus.LOCKED,
          "连续 " + MAX_FAILURES + " 次签名密码校验失败，签名能力已锁定 " + LOCK_MINUTES + " 分钟并记入审计");
    }
    upsert(a);
    audit.record("signature.fail", "app_user", username, null, map("failedCount", n));
    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
        "签名密码校验失败（第 " + n + "/" + MAX_FAILURES + " 次，达 " + MAX_FAILURES + " 次将锁定 "
            + LOCK_MINUTES + " 分钟）");
  }

  private void clearFailures(String username) {
    SignatureAttempt a = attempts.selectById(username);
    if (a == null) return;
    if ((a.getFailedCount() == null || a.getFailedCount() == 0) && a.getLockedUntil() == null) return;
    a.setFailedCount(0);
    a.setFirstFailedAt(null);
    a.setLockedUntil(null);
    a.setUpdatedAt(OffsetDateTime.now());
    attempts.updateById(a);
  }

  private void upsert(SignatureAttempt a) {
    if (attempts.selectById(a.getUsername()) == null) attempts.insert(a);
    else attempts.updateById(a);
  }

  /** 当前账号签名失败/锁定状态（前端提示用，不泄露是否已锁定以外的信息）。 */
  public Map<String, Object> attemptState(String username) {
    SignatureAttempt a = attempts.selectById(username);
    OffsetDateTime now = OffsetDateTime.now();
    boolean locked = a != null && a.getLockedUntil() != null && a.getLockedUntil().isAfter(now);
    return map(
        "username", username,
        "failedCount", a == null || a.getFailedCount() == null ? 0 : a.getFailedCount(),
        "locked", locked,
        "lockedUntil", a == null || a.getLockedUntil() == null ? null : a.getLockedUntil().toString(),
        "maxFailures", MAX_FAILURES,
        "lockMinutes", LOCK_MINUTES);
  }

  /* ==================== helpers ==================== */

  private static String[] splitRecipeRef(String recordId) {
    int at = recordId == null ? -1 : recordId.lastIndexOf('@');
    if (at <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
        "配方记录 ID 形如 R-CA-07@v3.2，收到: " + recordId);
    return new String[] {recordId.substring(0, at), recordId.substring(at + 1)};
  }

  private static String truncate(String s, int max) {
    return s.length() <= max ? s : s.substring(0, max);
  }

  private static String str(Object v) {
    return v == null ? null : String.valueOf(v);
  }
}
