package com.fluxmes.api.batch;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fluxmes.api.audit.AuditService;
import com.fluxmes.api.common.ApiSupport;
import com.fluxmes.api.common.CurrentUser;
import com.fluxmes.api.common.Roles;
import com.fluxmes.api.entity.Batch;
import com.fluxmes.api.entity.Deviation;
import com.fluxmes.api.entity.ProductionLine;
import com.fluxmes.api.mapper.BatchMapper;
import com.fluxmes.api.mapper.DeviationMapper;
import com.fluxmes.api.mapper.ProductionLineMapper;
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
 * 批次业务服务：服务端过滤/分页、状态机（running→waiting→done / running→abnormal）、
 * 新建批次、放行只读锁定（C2）、档案结构化（B3 T3）。
 */
@Service
public class BatchService {

  private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyMMdd");

  /** 默认产线（历史数据回填值，见 db/schema.sql Phase D 迁移）。 */
  public static final String DEFAULT_LINE = "LINE-1";
  /** G4 · 默认厂区（单厂区部署时全部批次归属此处）。 */
  public static final String DEFAULT_SITE = "SITE-01";

  /** 产线代码（与 production_line 主数据一致，种子数据轮转分配用）。 */
  public static final String[] LINE_CODES = {"LINE-1", "LINE-2", "LINE-3"};

  private final BatchMapper batches;
  private final DeviationMapper deviations;
  private final ProductionLineMapper lines;
  private final AuditService audit;
  private final ObjectMapper json;
  private final com.fluxmes.api.foodsafety.FoodSafetyService foodSafety;
  private final com.fluxmes.api.equipment.EquipmentService equipmentService;
  private final com.fluxmes.api.recipe.RecipeService recipeService;

  public BatchService(BatchMapper batches, DeviationMapper deviations, ProductionLineMapper lines,
      AuditService audit, ObjectMapper json,
      com.fluxmes.api.foodsafety.FoodSafetyService foodSafety,
      com.fluxmes.api.equipment.EquipmentService equipmentService,
      com.fluxmes.api.recipe.RecipeService recipeService) {
    this.batches = batches;
    this.deviations = deviations;
    this.lines = lines;
    this.audit = audit;
    this.json = json;
    this.foodSafety = foodSafety;
    this.equipmentService = equipmentService;
    this.recipeService = recipeService;
  }

  /* ---------------- 查询 ---------------- */

  /** D2 · 产线主数据（供驾驶舱 / 批次页切换器）。 */
  public List<Map<String, Object>> lines() {
    return lines(null);
  }

  /** G4 · 产线主数据，可按厂区过滤。 */
  public List<Map<String, Object>> lines(String site) {
    return lines.selectList(Wrappers.<ProductionLine>lambdaQuery()
            .eq(ProductionLine::getEnabled, true)
            .eq(notBlank(site), ProductionLine::getSiteCode, site)
            .orderByAsc(ProductionLine::getCode))
        .stream().map(l -> ApiSupport.map(
            "code", l.getCode(),
            "name", l.getName(),
            "workshop", l.getWorkshop(),
            "capacityT", l.getCapacityT(),
            "site", l.getSiteCode())).toList();
  }

  /** 服务端过滤 + 可选分页；page 为空时全量（兼容前端现有契约）。 */
  public Map<String, Object> list(String status, String stage, String product, String keyword,
      String line, Long page, Long size) {
    return list(status, stage, product, keyword, line, page, size, null);
  }

  /** G4 · 增加厂区维度过滤。 */
  public Map<String, Object> list(String status, String stage, String product, String keyword,
      String line, Long page, Long size, String site) {
    var q = Wrappers.<Batch>lambdaQuery()
        .eq(notBlank(status), Batch::getStatus, status)
        .eq(notBlank(stage), Batch::getStage, stage)
        .eq(notBlank(product), Batch::getProduct, product)
        .eq(notBlank(line), Batch::getLine, line)
        .eq(notBlank(site), Batch::getSite, site)
        .orderByDesc(Batch::getId);
    List<Batch> filtered = new ArrayList<>();
    for (Batch b : batches.selectList(q)) {
      if (notBlank(keyword) && !containsKeyword(b, keyword)) continue;
      filtered.add(b);
    }
    List<Map<String, Object>> items = filtered.stream().map(BatchService::toView).toList();
    if (page == null || page <= 0) {
      return ApiSupport.map("generatedAt", ApiSupport.nowIso(), "total", items.size(), "batches", items);
    }
    long s = Math.min(size == null || size <= 0 ? 20 : size, 50);
    int from = (int) Math.min((page - 1) * s, items.size());
    int to = (int) Math.min(from + s, items.size());
    return ApiSupport.map(
        "generatedAt", ApiSupport.nowIso(),
        "total", items.size(),
        "page", page,
        "size", s,
        "batches", items.subList(from, to));
  }

  public Map<String, Object> detail(String id) {
    Batch b = batches.selectById(id);
    if (b == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "批次不存在: " + id);
    return ApiSupport.map("generatedAt", ApiSupport.nowIso(), "batch", toView(b));
  }

  /* ---------------- 新建（FR-8）---------------- */

  /** 新建批次：校验配方/产量/原料批号必填 → 生成批次号（B-yyMMdd-NNN，当日递增）。 */
  public Map<String, Object> create(Map<String, Object> body) {
    String product = str(body.get("product"));
    String recipe = str(body.get("recipe"));
    // H2/FR-8 · 版本快照：自动锁定生效版本（传入失效版本将被拒绝）
    String recipeVersion = recipeService.resolveVersionForBatch(recipe, str(body.get("recipeVersion")));
    String equipment = str(body.get("equipment"));
    Object planYield = body.get("planYield");
    Object materialLots = body.get("materialLots");
    List<String> missing = new ArrayList<>();
    if (isBlank(product)) missing.add("product");
    if (isBlank(recipe)) missing.add("recipe");
    if (isBlank(equipment)) missing.add("equipment");
    if (planYield == null || ApiSupport.num(planYield, 0) <= 0) missing.add("planYield");
    if (materialLots == null || (materialLots instanceof List<?> l && l.isEmpty())) missing.add("materialLots");
    if (!missing.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少必填项: " + String.join(", ", missing));
    }

    String targetLine = notBlank(str(body.get("line"))) ? str(body.get("line")) : DEFAULT_LINE;

    // H1/T8 · 设备校准门禁：主设备与附属设备均须启用且在校准有效期内（FR-10 / C5）。
    equipmentService.assertCalibrationValid(equipment);
    for (Object extra : body.get("secondaryEquipment") instanceof List<?> l ? l : List.of()) {
      equipmentService.assertCalibrationValid(str(extra));
    }

    // F1 · 清场门禁：目标产线必须具备有效清场记录（PASS + QA 已确认 + 未过有效期）。
    // 值班长及以上可强制作业（须在 force=true 且写明原因，写审计）。
    Map<String, Object> sanitation = foodSafety.sanitationStatus(targetLine);
    boolean force = Boolean.TRUE.equals(body.get("force"));
    if (!Boolean.TRUE.equals(sanitation.get("cleared"))) {
      if (!force) {
        throw new ResponseStatusException(HttpStatus.CONFLICT,
            "产线 " + targetLine + " 未通过清场校验，禁止开工：" + sanitation.get("reason")
                + "（须先完成清场并经 QA 确认）");
      }
      if (!CurrentUser.hasRole(Roles.SUPERVISOR)) {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
            "强制开工（跳过清场校验）需要值班长及以上角色");
      }
      audit.record("batch.force_start", "batch", targetLine, null,
          ApiSupport.map("line", targetLine, "reason", str(body.get("forceReason")),
              "sanitation", sanitation.get("reason")));
    }

    String date = LocalDate.now().format(YYMMDD);
    long todayCount = batches.selectCount(Wrappers.<Batch>lambdaQuery()
        .likeRight(Batch::getId, "B-" + date)) + 1;
    String id = "B-" + date + "-" + String.format("%03d", todayCount);

    int shelfLife = (int) ApiSupport.num(body.get("shelfLifeDays"), 540);   // 柠檬酸默认 18 个月
    LocalDate productionDate = LocalDate.now();

    Batch b = new Batch();
    b.setId(id);
    b.setProduct(product);
    b.setRecipe(recipe);
    b.setRecipeVersion(recipeVersion);
    b.setEquipment(equipment);
    b.setStage("配料");
    b.setStatus("running");
    b.setProgress(0);
    b.setStartedAt(productionDate.toString());
    b.setProductionDate(productionDate);
    b.setShelfLifeDays(shelfLife);
    b.setExpiryDate(productionDate.plusDays(shelfLife));
    b.setStages("[[\"配料\",\"active\"],[\"发酵\",\"todo\"],[\"提取\",\"todo\"],[\"精制\",\"todo\"],[\"结晶\",\"todo\"],[\"干燥\",\"todo\"],[\"包装\",\"todo\"]]");
    b.setOperator(CurrentUser.username());
    b.setLine(targetLine);
    // G4 · 厂区归属：显式指定 > 当前用户厂区 > 默认 SITE-01
    String site = str(body.get("site"));
    if (isBlank(site)) site = CurrentUser.site();
    b.setSite(isBlank(site) ? DEFAULT_SITE : site);
    b.setPlanYield(java.math.BigDecimal.valueOf(ApiSupport.num(planYield, 0)));
    try {
      b.setMaterialLots(json.writeValueAsString(materialLots));
    } catch (Exception ignored) {
      b.setMaterialLots(String.valueOf(materialLots));
    }
    b.setMeta("[]");
    b.setParams("{}");
    b.setReleased(false);
    b.setCreatedAt(OffsetDateTime.now());
    b.setUpdatedAt(OffsetDateTime.now());
    batches.insert(b);
    audit.record("batch.create", "batch", id, null, toView(b));
    return toView(b);
  }

  /* ---------------- 状态机（FR T5：异常阻断推进）---------------- */

  /** 工序推进：stage 前进一步；末工序完成时 status running→waiting→done。 */
  public Map<String, Object> advance(String id) {
    Batch b = require(id);
    Map<String, Object> before = toView(b);
    if (Boolean.TRUE.equals(b.getReleased())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "批次已放行，锁定只读（C2）");
    }
    if ("abnormal".equals(b.getStatus())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "异常批次不可推进，须先处理偏差单 " + b.getDeviationNo());
    }
    List<List<String>> stages = parseStages(b.getStages());
    int active = -1;
    for (int i = 0; i < stages.size(); i++) {
      if ("active".equals(stages.get(i).get(1))) { active = i; break; }
    }
    if (active >= 0) {
      stages.get(active).set(1, "done");
      if (active + 1 < stages.size()) stages.get(active + 1).set(1, "active");
      b.setStage(active + 1 < stages.size() ? stages.get(active + 1).get(0) : b.getStage());
      b.setProgress((active + 1) * 100 / stages.size());
    }
    boolean allDone = stages.stream().allMatch(s -> "done".equals(s.get(1)));
    if (allDone && "running".equals(b.getStatus())) {
      b.setStatus("waiting");   // 全工序完成 → 待入库
    } else if (allDone && "waiting".equals(b.getStatus())) {
      b.setStatus("done");
      b.setEndedAt(LocalDate.now().toString());
    }
    b.setStages(writeStages(stages));
    b.setUpdatedAt(OffsetDateTime.now());
    batches.updateById(b);
    audit.record("batch.advance", "batch", id, before, toView(b));
    return toView(b);
  }

  /** 标记异常：running → abnormal，自动创建偏差单并阻断后续推进。 */
  public Map<String, Object> markAbnormal(String id, String reason) {
    Batch b = require(id);
    Map<String, Object> before = toView(b);
    if (Boolean.TRUE.equals(b.getReleased())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "批次已放行，锁定只读（C2）");
    }
    if (!"running".equals(b.getStatus()) && !"waiting".equals(b.getStatus())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "当前状态不可标记异常: " + b.getStatus());
    }
    b.setStatus("abnormal");
    b.setUpdatedAt(OffsetDateTime.now());

    String devId = "DEV-" + LocalDate.now().format(YYMMDD) + "-"
        + String.format("%03d", deviations.selectCount(null) + 1);
    Deviation d = new Deviation();
    d.setId(devId);
    d.setBatchId(id);
    d.setSource("manual");
    d.setDescription(reason == null || reason.isBlank() ? "批次异常（人工标记）" : reason);
    d.setStatus("open");
    d.setCreatedBy(CurrentUser.username());
    d.setCreatedAt(OffsetDateTime.now());
    deviations.insert(d);
    b.setDeviationNo(devId);

    batches.updateById(b);
    audit.record("batch.abnormal", "batch", id, before, toView(b));
    audit.record("deviation.create", "deviation", devId, null,
        ApiSupport.map("batchId", id, "status", "open", "source", "manual"));
    return toView(b);
  }

  /* ---------------- helpers ---------------- */

  private Batch require(String id) {
    Batch b = batches.selectById(id);
    if (b == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "批次不存在: " + id);
    return b;
  }

  private List<List<String>> parseStages(String raw) {
    try {
      if (raw == null || raw.isBlank()) return new ArrayList<>();
      return json.readValue(raw, new TypeReference<List<List<String>>>() {});
    } catch (Exception e) {
      return new ArrayList<>();
    }
  }

  private String writeStages(List<List<String>> stages) {
    try {
      return json.writeValueAsString(stages);
    } catch (Exception e) {
      return "[]";
    }
  }

  private static boolean containsKeyword(Batch b, String keyword) {
    String q = keyword.toLowerCase();
    for (String v : new String[] { b.getId(), b.getProduct(), b.getRecipe(),
        b.getEquipment(), b.getStage(), b.getOperator() }) {
      if (v != null && v.toLowerCase().contains(q)) return true;
    }
    return false;
  }

  /** 实体 → 前端契约视图（保留 stages 元组数组与 meta 数组形态）。 */
  public static Map<String, Object> toView(Batch b) {
    ObjectMapper m = new ObjectMapper();
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("id", b.getId());
    view.put("product", b.getProduct());
    view.put("recipe", b.getRecipe());
    view.put("recipeVersion", b.getRecipeVersion());
    view.put("equipment", b.getEquipment());
    view.put("stage", b.getStage());
    view.put("status", b.getStatus());
    view.put("progress", b.getProgress());
    view.put("start", b.getStartedAt());
    view.put("operator", b.getOperator());
    view.put("coaNo", b.getCoaNo());
    view.put("warehouseBin", b.getWarehouseBin());
    view.put("deviationNo", b.getDeviationNo());
    view.put("released", b.getReleased());
    view.put("line", b.getLine() == null ? DEFAULT_LINE : b.getLine());
    view.put("site", b.getSite() == null ? DEFAULT_SITE : b.getSite());
    view.put("productionDate", b.getProductionDate());
    view.put("shelfLifeDays", b.getShelfLifeDays());
    view.put("expiryDate", b.getExpiryDate());
    view.put("meta", parseJson(m, b.getMeta()));
    view.put("params", parseJson(m, b.getParams()));
    view.put("stages", parseJson(m, b.getStages()));
    view.put("materialLots", parseJson(m, b.getMaterialLots()));
    return view;
  }

  private static Object parseJson(ObjectMapper m, String raw) {
    if (raw == null || raw.isBlank()) return null;
    try {
      return m.readValue(raw, Object.class);
    } catch (Exception e) {
      return raw;
    }
  }

  private static boolean notBlank(String s) {
    return s != null && !s.isBlank();
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }

  private static String str(Object v) {
    return v == null ? null : String.valueOf(v);
  }
}
