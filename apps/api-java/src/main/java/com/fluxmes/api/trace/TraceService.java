package com.fluxmes.api.trace;

import static com.fluxmes.api.common.ApiSupport.map;
import static com.fluxmes.api.common.ApiSupport.nowIso;
import static com.fluxmes.api.common.ApiSupport.r1;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fluxmes.api.audit.AuditService;
import com.fluxmes.api.common.ApiSupport;
import com.fluxmes.api.common.CurrentUser;
import com.fluxmes.api.core.FixtureStore;
import com.fluxmes.api.entity.Alarm;
import com.fluxmes.api.entity.Batch;
import com.fluxmes.api.entity.BatchGenealogy;
import com.fluxmes.api.entity.BatchInput;
import com.fluxmes.api.entity.BatchStep;
import com.fluxmes.api.entity.Deviation;
import com.fluxmes.api.entity.Material;
import com.fluxmes.api.entity.MaterialLot;
import com.fluxmes.api.entity.TraceQueryLog;
import com.fluxmes.api.mapper.AlarmMapper;
import com.fluxmes.api.mapper.BatchGenealogyMapper;
import com.fluxmes.api.mapper.BatchInputMapper;
import com.fluxmes.api.mapper.BatchMapper;
import com.fluxmes.api.mapper.BatchStepMapper;
import com.fluxmes.api.mapper.DeviationMapper;
import com.fluxmes.api.mapper.MaterialLotMapper;
import com.fluxmes.api.mapper.MaterialMapper;
import com.fluxmes.api.mapper.TraceQueryLogMapper;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * H3 · 追溯闭环（traceability T3–T10）。
 *
 * 数据源：material_lot / batch_input 复用 Phase E (F2) 已落地结构，不重建同名表；
 * 本期新增 batch_genealogy（批间父子）与 trace_query_log（查询审计）两表承载。
 *
 * 合规要点：
 * - FR-1 正向链路由真实业务数据组装（原料 → 工序 → 设备 → 操作人 → 检验 → 入库）；
 * - FR-5 节点横向关联报警与偏差单；
 * - FR-7 追溯查询写 trace_query_log + audit_log（C3）；
 * - FR-8 缺失原料/设备/操作人即标记「档案不完整」并列出缺失项（C2）。
 */
@Service
public class TraceService {

  private static final Logger log = LoggerFactory.getLogger(TraceService.class);
  /** NFR-1 限制展开深度，避免深链路递归拖垮响应。 */
  private static final int MAX_DEPTH = 5;

  private final BatchMapper batches;
  private final BatchInputMapper inputs;
  private final MaterialLotMapper lots;
  private final MaterialMapper materials;
  private final BatchStepMapper steps;
  private final BatchGenealogyMapper genealogy;
  private final TraceQueryLogMapper queryLog;
  private final AlarmMapper alarms;
  private final DeviationMapper deviations;
  private final FixtureStore fixtures;
  private final AuditService audit;

  public TraceService(BatchMapper batches, BatchInputMapper inputs, MaterialLotMapper lots,
      MaterialMapper materials, BatchStepMapper steps, BatchGenealogyMapper genealogy,
      TraceQueryLogMapper queryLog, AlarmMapper alarms, DeviationMapper deviations,
      FixtureStore fixtures, AuditService audit) {
    this.batches = batches;
    this.inputs = inputs;
    this.lots = lots;
    this.materials = materials;
    this.steps = steps;
    this.genealogy = genealogy;
    this.queryLog = queryLog;
    this.alarms = alarms;
    this.deviations = deviations;
    this.fixtures = fixtures;
    this.audit = audit;
  }

  /* ---------------- T3 · 种子：批间父子关系 ---------------- */

  /**
   * 中间品流转登记：若某批次的产品同时是另一个批次消耗的物料（如发酵营养盐），
   * 则把生产批次登记为消耗批次的上游 parent。已存在关系跳过。
   */
  public void seed() {
    if (genealogy.selectCount(null) > 0) return;
    Map<String, Batch> latestByProduct = new LinkedHashMap<>();
    for (Batch b : batches.selectList(Wrappers.<Batch>lambdaQuery().orderByDesc(Batch::getId))) {
      if (b.getProduct() == null || b.getProduct().isBlank()) continue;
      latestByProduct.putIfAbsent(b.getProduct(), b);
    }
    if (latestByProduct.isEmpty()) return;
    int created = 0;
    for (BatchInput in : inputs.selectList(null)) {
      MaterialLot lot = lots.selectById(in.getMaterialLotId());
      if (lot == null) continue;
      Material m = materials.selectById(lot.getMaterialCode());
      if (m == null || m.getName() == null) continue;
      Batch parent = latestByProduct.get(m.getName());
      if (parent == null || parent.getId().equals(in.getBatchId())) continue;
      if (genealogy.selectCount(Wrappers.<BatchGenealogy>lambdaQuery()
          .eq(BatchGenealogy::getParentBatchId, parent.getId())
          .eq(BatchGenealogy::getChildBatchId, in.getBatchId())) > 0) continue;
      BatchGenealogy g = new BatchGenealogy();
      g.setParentBatchId(parent.getId());
      g.setChildBatchId(in.getBatchId());
      g.setRelation("INPUT");
      g.setQty(in.getQty());
      g.setUnit(in.getUnit());
      g.setStage("配料");
      g.setChargedAt(in.getFedAt() == null ? OffsetDateTime.now() : in.getFedAt());
      g.setOperator(in.getOperator());
      genealogy.insert(g);
      created++;
    }
    if (created > 0) log.info("trace seed: {} batch genealogy links", created);
  }

  /* ---------------- FR-1 · 正向链路 ---------------- */

  /** 可追溯批次候选列表（已生产完成或已放行的批次优先）。 */
  public List<Map<String, Object>> targets() {
    List<Map<String, Object>> db = batches.selectList(
            Wrappers.<Batch>lambdaQuery().orderByDesc(Batch::getId))
        .stream().limit(20).map(b -> map(
            "id", b.getId(),
            "product", b.getProduct(),
            "recipe", b.getRecipe(),
            "status", b.getStatus(),
            "released", b.getReleased(),
            "planYieldT", tons(b.getPlanYield()),
            "warehouseBin", b.getWarehouseBin(),
            "source", "db")).toList();
    List<Map<String, Object>> out = new ArrayList<>(db);
    // fixture 示例批次作为补充候选（历史演示数据，标记来源便于识别）
    boolean seen = !db.isEmpty();
    if (!seen) {
      out.addAll(fixtures.traceableBatches().stream()
          .map(t -> map("id", t.get("id"), "product", t.get("product"),
              "recipe", t.get("recipe"), "status", t.get("status"),
              "released", "released".equals(t.get("status")),
              "planYieldT", t.get("qty"), "warehouseBin", t.get("wh"),
              "source", "fixture")).toList());
    }
    return out;
  }

  /**
   * 正向追溯：原料 → 中间品 → 工序 → 成品 → 入库，兼容前端 traceChains 节点契约
   * （type/label/title/value/meta），并叠加横向关联的报警与偏差（FR-5）。
   */
  public Map<String, Object> chain(String batchId) {
    long t0 = System.currentTimeMillis();
    Batch b = batches.selectById(batchId);
    if (b == null) {
      TraceQueryLog entry = logQuery("forward", batchId, 0, 0);
      return map("generatedAt", nowIso(), "batchId", batchId, "chain", null,
          "meta", meta(0, 0, 0, "NOT_FOUND", List.of("批次不存在")), "logId", str(entry.getId()));
    }
    List<Map<String, Object>> forward = new ArrayList<>();

    // 1) 上游中间品批次（batch_genealogy）
    for (BatchGenealogy g : genealogy.selectList(Wrappers.<BatchGenealogy>lambdaQuery()
        .eq(BatchGenealogy::getChildBatchId, batchId))) {
      Batch parent = batches.selectById(g.getParentBatchId());
      forward.add(node("batch", "中间品投入", "批次 " + g.getParentBatchId(),
          parent == null ? g.getParentBatchId() : str(parent.getProduct()),
          (parent == null ? "" : parent.getProduct() + " · ") + "投用 " + nvl(g.getQty())
              + nvl(g.getUnit()) + " · " + nvl(g.getOperator())));
    }
    // 2) 原料投入（batch_input → material_lot → material）
    for (BatchInput in : inputs.selectList(Wrappers.<BatchInput>lambdaQuery()
        .eq(BatchInput::getBatchId, batchId))) {
      MaterialLot lot = lots.selectById(in.getMaterialLotId());
      Material m = lot == null ? null : materials.selectById(lot.getMaterialCode());
      String allergen = m != null && Boolean.TRUE.equals(m.getAllergen())
          ? " · 过敏原 " + nvl(m.getAllergenName()) : "";
      forward.add(node("material", "原料投入", nvl(m == null ? null : m.getName()),
          in.getMaterialLotId(),
          "供应商 " + nvl(lot == null ? null : lot.getSupplier()) + " · "
              + nvl(in.getQty()) + nvl(in.getUnit()) + " · 检验 "
              + nvl(lot == null ? null : lot.getQcStatus()) + allergen));
    }
    // 3) 工序与设备（batch_step）
    var stepQuery = Wrappers.<BatchStep>lambdaQuery()
        .eq(BatchStep::getBatchId, batchId)
        .orderByAsc(BatchStep::getStepNo);
    List<BatchStep> stepRows = steps.selectList(stepQuery);
    if (stepRows.isEmpty() && b.getStages() != null) {
      // 无 eBR 记录时按批次 stages 回显（plan R1：不伪造数据，仅标注来源）
      forward.add(node("process", "工序", str(b.getStage()), "未登记 eBR",
          "批次工序未完成电子批记录登记，链路深度降级"));
    }
    for (BatchStep s : stepRows) {
      forward.add(node("process", "工序 " + nvl(s.getStepNo()), nvl(s.getStepName()), nvl(s.getStatus()),
          "操作 " + nvl(s.getOperator()) + " · 复核 " + nvl(s.getReviewer())));
    }
    // 4) 主设备
    forward.add(node("process", "关键设备", nvl(b.getEquipment()), nvl(b.getLine()),
        "产线 " + nvl(b.getLine()) + " · 操作员 " + nvl(b.getOperator())));
    // 5) 检验与放行
    forward.add(node("test", "成品检验", b.getCoaNo() == null ? "未完成" : "COA " + b.getCoaNo(),
        Boolean.TRUE.equals(b.getReleased()) ? "已放行" : "待放行",
        b.getDeviationNo() == null ? "无关联偏差单" : "关联偏差单 " + b.getDeviationNo()));
    // 6) 入库
    forward.add(node("warehouse", "入库", nvl(b.getWarehouseBin()),
        tons(b.getPlanYield()) + " t", str(b.getStatus())));

    List<String> missing = completeness(b);
    long took = System.currentTimeMillis() - t0;
    TraceQueryLog entry = logQuery("forward", batchId, forward.size(), took);
    return map(
        "generatedAt", nowIso(),
        "batchId", batchId,
        "batch", batchView(b),
        "chain", map("forward", forward, "backward", backwardNodes(b)),
        "issues", lateral(b, stepRows),
        "meta", meta(typeCount(forward), forward.size(), took,
            missing.isEmpty() ? "COMPLETE" : "INCOMPLETE", missing),
        "logId", str(entry.getId()));
  }

  /* ---------------- FR-3 · 逆向追溯 ---------------- */

  /** 按原料批号查消耗它的成品批次（plan D1：关系表索引查询，非遍历 JSON）。 */
  public Map<String, Object> backward(String lotNo) {
    long t0 = System.currentTimeMillis();
    List<BatchInput> used = inputs.selectList(Wrappers.<BatchInput>lambdaQuery()
        .eq(BatchInput::getMaterialLotId, lotNo));
    MaterialLot lot = lots.selectById(lotNo);
    Material m = lot == null ? null : materials.selectById(lot.getMaterialCode());
    List<Map<String, Object>> items = new ArrayList<>();
    for (BatchInput in : used) {
      Batch b = batches.selectById(in.getBatchId());
      items.add(map(
          "batchId", in.getBatchId(),
          "product", b == null ? null : b.getProduct(),
          "status", b == null ? null : b.getStatus(),
          "released", b != null && Boolean.TRUE.equals(b.getReleased()),
          "line", b == null ? null : b.getLine(),
          "planYieldT", b == null ? 0 : tons(b.getPlanYield()),
          "qty", in.getQty(),
          "unit", in.getUnit(),
          "fedAt", str(in.getFedAt()),
          "operator", in.getOperator()));
    }
    long took = System.currentTimeMillis() - t0;
    TraceQueryLog entry = logQuery("backward", lotNo, items.size(), took);
    return map(
        "generatedAt", nowIso(),
        "lotNo", lotNo,
        "material", m == null ? null : map("code", m.getCode(), "name", m.getName(),
            "allergen", m.getAllergen()),
        "lot", lot == null ? null : map("supplier", lot.getSupplier(), "qcStatus", lot.getQcStatus(),
            "receivedAt", str(lot.getReceivedAt()), "expiryDate", str(lot.getExpiryDate())),
        "batchCount", items.size(),
        "batches", items,
        "durationMs", took,
        "logId", str(entry.getId()));
  }

  /* ---------------- FR-4 · 影响面分析 ---------------- */

  /** 影响面：受影响成品批次、是否已放行、流向建议。复杂的多层展开受 MAX_DEPTH 限制。 */
  public Map<String, Object> impact(String lotNo) {
    long t0 = System.currentTimeMillis();
    List<BatchInput> used = inputs.selectList(Wrappers.<BatchInput>lambdaQuery()
        .eq(BatchInput::getMaterialLotId, lotNo));
    List<Map<String, Object>> affected = new ArrayList<>();
    double totalT = 0;
    int released = 0;
    for (BatchInput in : used) {
      Batch b = batches.selectById(in.getBatchId());
      double t = b == null || b.getPlanYield() == null ? 0 : b.getPlanYield().doubleValue() / 1000.0;
      totalT += t;
      if (b != null && Boolean.TRUE.equals(b.getReleased())) released++;
      affected.add(map(
          "batchId", in.getBatchId(),
          "product", b == null ? null : b.getProduct(),
          "status", b == null ? null : b.getStatus(),
          "released", b != null && Boolean.TRUE.equals(b.getReleased()),
          "warehouseBin", b == null ? null : b.getWarehouseBin(),
          "coaNo", b == null ? null : b.getCoaNo(),
          "line", b == null ? null : b.getLine(),
          "planYieldT", r1(t),
          "downstream", downstream(in.getBatchId(), 1)));
    }
    long took = System.currentTimeMillis() - t0;
    List<String> suggestions = new ArrayList<>();
    suggestions.add("隔离原料批 " + lotNo + " 剩余库存并暂停投料");
    suggestions.add("冻结受影响成品批次 " + affected.size() + " 个（其中已放行 " + released + " 个需启动市场召回）");
    suggestions.add("复核受影响批次的 CCP 记录与留样，确认是否存在同因偏离");
    TraceQueryLog entry = logQuery("impact", lotNo, affected.size(), took);
    audit.record("trace.impact", "material_lot", lotNo, null,
        map("affectedBatches", affected.size(), "releasedCount", released, "totalT", r1(totalT)));
    Map<String, Object> result = map(
        "generatedAt", nowIso(),
        "lotNo", lotNo,
        "affectedBatchCount", affected.size(),
        "releasedCount", released,
        "totalYieldT", r1(totalT),
        "affectedBatches", affected,
        "suggestions", suggestions,
        "durationMs", took,
        "logId", str(entry.getId()));
    return result;
  }

  /** 下游展开（≤ MAX_DEPTH 层）：成品被哪些批次继续耗用。 */
  private List<Map<String, Object>> downstream(String batchId, int depth) {
    if (depth > MAX_DEPTH) return List.of();
    List<Map<String, Object>> out = new ArrayList<>();
    for (BatchGenealogy g : genealogy.selectList(Wrappers.<BatchGenealogy>lambdaQuery()
        .eq(BatchGenealogy::getParentBatchId, batchId))) {
      Batch child = batches.selectById(g.getChildBatchId());
      out.add(map("batchId", g.getChildBatchId(), "product", child == null ? null : child.getProduct(),
          "released", child != null && Boolean.TRUE.equals(child.getReleased()),
          "depth", depth, "children", downstream(g.getChildBatchId(), depth + 1)));
    }
    return out;
  }

  /* ---------------- FR-8 · 完整性校验 ---------------- */

  /** 缺失项清单：原料批 / 主设备 / 操作人 / 放行结论任一缺失即档案不完整（C2）。 */
  public Map<String, Object> completeness(String batchId) {
    Batch b = batches.selectById(batchId);
    if (b == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "批次不存在: " + batchId);
    List<String> missing = completeness(b);
    return map(
        "generatedAt", nowIso(),
        "batchId", batchId,
        "complete", missing.isEmpty(),
        "integrity", missing.isEmpty() ? "已校验" : "档案不完整",
        "missingCount", missing.size(),
        "missing", missing);
  }

  private List<String> completeness(Batch b) {
    List<String> missing = new ArrayList<>();
    long inputCount = inputs.selectCount(Wrappers.<BatchInput>lambdaQuery()
        .eq(BatchInput::getBatchId, b.getId()));
    if (inputCount == 0) missing.add("未登记投料记录（缺原料批号与投用量）");
    if (b.getEquipment() == null || b.getEquipment().isBlank()) missing.add("未关联主设备");
    if (b.getOperator() == null || b.getOperator().isBlank()) missing.add("未记录操作员");
    if (b.getRecipeVersion() == null || b.getRecipeVersion().isBlank()) {
      missing.add("未锁定配方版本快照（FR-8）");
    }
    if (Boolean.TRUE.equals(b.getReleased()) && (b.getCoaNo() == null || b.getCoaNo().isBlank())) {
      missing.add("已放行但缺少 COA 编号");
    }
    return missing;
  }

  /* ---------------- FR-6 · 报表导出 ---------------- */

  /** 追溯报表（Markdown）：含生成时间、操作人与完整链路，符合监管归档要求（C2/NFR-3）。 */
  public Map<String, Object> exportReport(String batchId) {
    long t0 = System.currentTimeMillis();
    Batch b = batches.selectById(batchId);
    if (b == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "批次不存在: " + batchId);
    @SuppressWarnings("unchecked")
    Map<String, Object> chainResult = chain(batchId);
    @SuppressWarnings("unchecked")
    Map<String, Object> chain = (Map<String, Object>) chainResult.get("chain");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> forward = chain == null ? List.of()
        : (List<Map<String, Object>>) chain.getOrDefault("forward", List.of());
    List<String> missing = completeness(b);
    StringBuilder md = new StringBuilder();
    md.append("# 批次追溯报表 · ").append(batchId).append("\n\n");
    md.append("- 产品：").append(nvl(b.getProduct())).append("\n");
    md.append("- 配方：").append(nvl(b.getRecipe())).append(" @ ").append(nvl(b.getRecipeVersion())).append("\n");
    md.append("- 产线 / 设备：").append(nvl(b.getLine())).append(" / ").append(nvl(b.getEquipment())).append("\n");
    md.append("- 计划产量：").append(tons(b.getPlanYield())).append(" t\n");
    md.append("- 状态：").append(nvl(b.getStatus())).append(" · 放行 ").append(Boolean.TRUE.equals(b.getReleased()) ? "是" : "否").append("\n");
    md.append("- 档完完整性：").append(missing.isEmpty() ? "完整" : "不完整（" + String.join("；", missing) + "）").append("\n\n");
    md.append("## 追溯链路\n\n| 环节 | 项目 | 内容 | 明细 |\n| --- | --- | --- | --- |\n");
    for (Map<String, Object> n : forward) {
      md.append("| ").append(nvl(n.get("label"))).append(" | ").append(nvl(n.get("title")))
          .append(" | ").append(nvl(n.get("value"))).append(" | ").append(nvl(n.get("meta"))).append(" |\n");
    }
    md.append("\n> 生成时间：").append(nowIso()).append(" · 操作人：").append(nvl(CurrentUser.username()))
        .append(" · 依据 constitution C2/C6 归档\n");
    long took = System.currentTimeMillis() - t0;
    TraceQueryLog entry = logQuery("export", batchId, forward.size(), took);
    audit.record("trace.export", "batch", batchId, null,
        map("nodes", forward.size(), "complete", missing.isEmpty()));
    return map(
        "fileName", "追溯报表_" + batchId + ".md",
        "contentType", "text/markdown",
        "content", md.toString(),
        "generatedAt", nowIso(),
        "generatedBy", CurrentUser.username(),
        "nodeCount", forward.size(),
        "logId", str(entry.getId()));
  }

  /** 追溯查询审计流水（FR-7）。 */
  public List<Map<String, Object>> queryLogs(int limit) {
    return queryLog.selectList(Wrappers.<TraceQueryLog>lambdaQuery()
            .orderByDesc(TraceQueryLog::getId))
        .stream().limit(Math.max(1, Math.min(limit, 200))).map(l -> map(
            "id", String.valueOf(l.getId()),
            "actor", l.getActor(),
            "queryType", l.getQueryType(),
            "queryKey", l.getQueryKey(),
            "resultCount", l.getResultCount(),
            "durationMs", l.getDurationMs(),
            "createdAt", str(l.getCreatedAt()))).toList();
  }

  /** 新增中间品流转关系（真实 MES 由投料登记触发，此处提供显式登记入口）。 */
  public Map<String, Object> link(Map<String, Object> body) {
    String parent = str(body.get("parentBatchId"));
    String child = str(body.get("childBatchId"));
    if (parent.isBlank() || child.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "parentBatchId 与 childBatchId 必填");
    }
    if (batches.selectById(parent) == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "上游批次不存在: " + parent);
    }
    if (batches.selectById(child) == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "下游批次不存在: " + child);
    }
    if (parent.equals(child)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不可自关联");
    }
    String relation = str(body.get("relation"));
    if (relation.isBlank()) relation = "INPUT";
    if (genealogy.selectCount(Wrappers.<BatchGenealogy>lambdaQuery()
        .eq(BatchGenealogy::getParentBatchId, parent)
        .eq(BatchGenealogy::getChildBatchId, child)
        .eq(BatchGenealogy::getRelation, relation)) > 0) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "该父子关系已存在");
    }
    BatchGenealogy g = new BatchGenealogy();
    g.setParentBatchId(parent);
    g.setChildBatchId(child);
    g.setRelation(relation);
    g.setQty(body.get("qty") == null ? null : new BigDecimal(str(body.get("qty"))));
    g.setUnit(str(body.get("unit")));
    g.setStage(str(body.get("stage")));
    g.setChargedAt(OffsetDateTime.now());
    g.setOperator(CurrentUser.username());
    genealogy.insert(g);
    audit.record("trace.link", "batch_genealogy", parent + "->" + child, null,
        map("qty", str(g.getQty()), "relation", relation));
    return map("id", String.valueOf(g.getId()), "parentBatchId", parent,
        "childBatchId", child, "relation", relation);
  }

  /* ---------------- views & helpers ---------------- */

  private Map<String, Object> batchView(Batch b) {
    return map("id", b.getId(), "product", b.getProduct(), "recipe", b.getRecipe(),
        "recipeVersion", b.getRecipeVersion(), "status", b.getStatus(), "stage", b.getStage(),
        "equipment", b.getEquipment(), "operator", b.getOperator(), "line", b.getLine(),
        "coaNo", b.getCoaNo(), "released", b.getReleased(), "planYieldT", tons(b.getPlanYield()),
        "productionDate", str(b.getProductionDate()), "expiryDate", str(b.getExpiryDate()),
        "warehouseBin", b.getWarehouseBin());
  }

  /** 逆向节点：从成品回看各自的来源，结构与前端 chain.backward 一致。 */
  private List<Map<String, Object>> backwardNodes(Batch b) {
    List<Map<String, Object>> out = new ArrayList<>();
    out.add(node("batch", "成品批次", b.getId(), nvl(b.getProduct()),
        "配方 " + nvl(b.getRecipe()) + " " + nvl(b.getRecipeVersion())));
    out.add(node("warehouse", "库存批次", nvl(b.getWarehouseBin()), tons(b.getPlanYield()) + " t",
        Boolean.TRUE.equals(b.getReleased()) ? "已放行" : "待放行"));
    out.add(node("test", "COA", b.getCoaNo() == null ? "未生成" : b.getCoaNo(),
        Boolean.TRUE.equals(b.getReleased()) ? "合格" : "待检验", "放行结论以 COA 为准"));
    out.add(node("process", "关键设备", nvl(b.getEquipment()), nvl(b.getLine()),
        "操作员 " + nvl(b.getOperator())));
    for (BatchInput in : inputs.selectList(Wrappers.<BatchInput>lambdaQuery()
        .eq(BatchInput::getBatchId, b.getId()))) {
      MaterialLot lot = lots.selectById(in.getMaterialLotId());
      Material m = lot == null ? null : materials.selectById(lot.getMaterialCode());
      out.add(node("material", "原料批号", (m == null ? "" : m.getName() + " ") + in.getMaterialLotId(),
          nvl(in.getQty()) + nvl(in.getUnit()),
          "检验 " + nvl(lot == null ? null : lot.getQcStatus()) + " · 供应商 " + nvl(lot == null ? null : lot.getSupplier())));
    }
    return out;
  }

  /** FR-5 · 节点横向关联：该批次关联设备的报警与批次偏差单。 */
  private Map<String, Object> lateral(Batch b, List<BatchStep> stepRows) {
    String equipmentPrefix = b.getEquipment() == null ? "" : b.getEquipment().split(" ")[0];
    List<Map<String, Object>> alarmList = equipmentPrefix.isBlank() ? List.of()
        : alarms.selectList(Wrappers.<Alarm>lambdaQuery()
            .likeRight(Alarm::getSource, equipmentPrefix))
        .stream().limit(5).map(com.fluxmes.api.alarm.AlarmService::toView).toList();
    List<Map<String, Object>> deviationList = deviations.selectList(
            Wrappers.<Deviation>lambdaQuery().eq(Deviation::getBatchId, b.getId()))
        .stream().limit(5).map(d -> map("id", d.getId(), "description", d.getDescription(),
            "status", d.getStatus(), "source", d.getSource(),
            "rootCause", d.getRootCause())).toList();
    return map("alarms", alarmList, "deviations", deviationList,
        "stepCount", stepRows.size());
  }

  private static Map<String, Object> node(String type, String label, String title, String value, String meta) {
    return map("type", type, "label", label, "title", title, "value", value, "meta", meta);
  }

  private int typeCount(List<Map<String, Object>> nodes) {
    return (int) nodes.stream().map(n -> String.valueOf(n.get("type"))).distinct().count();
  }

  private TraceQueryLog logQuery(String type, String key, int count, long ms) {
    try {
      TraceQueryLog l = new TraceQueryLog();
      l.setActor(CurrentUser.username());
      l.setQueryType(type);
      l.setQueryKey(key);
      l.setResultCount(count);
      l.setDurationMs(ms);
      l.setCreatedAt(OffsetDateTime.now());
      queryLog.insert(l);
      return l;
    } catch (Exception e) {
      log.warn("trace query log failed: {}", e.toString());
      return new TraceQueryLog();
    }
  }

  private static Map<String, Object> meta(int levelCount, int recordCount, long ms,
      String integrity, List<String> missing) {
    return map("levelCount", levelCount, "recordCount", recordCount, "querySec", ms / 1000.0,
        "integrity", integrity, "missing", missing);
  }

  private static double tons(BigDecimal planYield) {
    return ApiSupport.pct1(planYield == null ? 0 : planYield.doubleValue() / 1000.0);
  }

  private static String nvl(Object v) {
    return v == null || "null".equals(String.valueOf(v)) ? "—" : String.valueOf(v);
  }

  private static String str(Object v) {
    return v == null || "null".equals(String.valueOf(v)) ? "" : String.valueOf(v);
  }
}
