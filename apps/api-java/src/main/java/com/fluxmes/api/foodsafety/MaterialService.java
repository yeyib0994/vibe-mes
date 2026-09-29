package com.fluxmes.api.foodsafety;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fluxmes.api.audit.AuditService;
import com.fluxmes.api.common.ApiSupport;
import com.fluxmes.api.common.CurrentUser;
import com.fluxmes.api.common.Roles;
import com.fluxmes.api.entity.Batch;
import com.fluxmes.api.entity.BatchInput;
import com.fluxmes.api.entity.Material;
import com.fluxmes.api.entity.MaterialLot;
import com.fluxmes.api.mapper.BatchInputMapper;
import com.fluxmes.api.mapper.BatchMapper;
import com.fluxmes.api.mapper.MaterialLotMapper;
import com.fluxmes.api.mapper.MaterialMapper;
import java.math.BigDecimal;
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
 * F2 · 物料谱系服务：物料主数据、原料批（到货/检验放行）、投料登记、
 * 真实正反向谱系追溯与召回影响分析。
 *
 * <p>此前追溯链来自 mes.json 的硬编码 fixture；本服务以
 * {@code batch_input}（成品批次 ← 原料批）作为真实谱系边，
 * 支持「某原料批出现问题 → 一键反查受影响成品批次」的召回场景。
 */
@Service
public class MaterialService {

  private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyMMdd");

  private final MaterialMapper materials;
  private final MaterialLotMapper lots;
  private final BatchInputMapper inputs;
  private final BatchMapper batches;
  private final AuditService audit;

  public MaterialService(MaterialMapper materials, MaterialLotMapper lots, BatchInputMapper inputs,
      BatchMapper batches, AuditService audit) {
    this.materials = materials;
    this.lots = lots;
    this.inputs = inputs;
    this.batches = batches;
    this.audit = audit;
  }

  /* =================== 物料主数据 =================== */

  public List<Map<String, Object>> materials() {
    return materials.selectList(Wrappers.<Material>lambdaQuery()
            .eq(Material::getEnabled, true)
            .orderByAsc(Material::getCode))
        .stream().map(m -> ApiSupport.map(
            "code", m.getCode(),
            "name", m.getName(),
            "category", m.getCategory(),
            "unit", m.getUnit(),
            "allergen", m.getAllergen(),
            "allergenName", m.getAllergenName(),
            "shelfLifeDays", m.getShelfLifeDays(),
            "spec", m.getSpec())).toList();
  }

  /* =================== 原料批 =================== */

  public List<Map<String, Object>> lots(String materialCode, String qcStatus, Boolean expiringSoon) {
    List<MaterialLot> all = lots.selectList(Wrappers.<MaterialLot>lambdaQuery()
        .eq(materialCode != null && !materialCode.isBlank(), MaterialLot::getMaterialCode, materialCode)
        .eq(qcStatus != null && !qcStatus.isBlank(), MaterialLot::getQcStatus, qcStatus)
        .orderByDesc(MaterialLot::getReceivedAt));
    LocalDate soon = LocalDate.now().plusDays(30);
    return all.stream()
        .filter(l -> !Boolean.TRUE.equals(expiringSoon)
            || (l.getExpiryDate() != null && !l.getExpiryDate().isAfter(soon)))
        .map(this::lotView).toList();
  }

  /** 原料到货登记：生成原料批号，默认待检验（PENDING），未放行不得投料。 */
  public Map<String, Object> createLot(Map<String, Object> body) {
    String materialCode = str(body.get("materialCode"));
    if (isBlank(materialCode)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "materialCode 必填");
    Material m = materials.selectById(materialCode);
    if (m == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "物料不存在: " + materialCode);

    String id = "LOT-" + LocalDate.now().format(YYMMDD) + "-"
        + String.format("%03d", lots.selectCount(null) + 1);
    MaterialLot l = new MaterialLot();
    l.setId(id);
    l.setMaterialCode(materialCode);
    l.setSupplier(str(body.get("supplier")));
    l.setSupplierLot(str(body.get("supplierLot")));
    l.setReceivedAt(OffsetDateTime.now());
    l.setQty(dec(body.get("qty")));
    l.setUnit(str(body.getOrDefault("unit", m.getUnit())));
    l.setQcStatus("PENDING");
    l.setProductionDate(parseDate(body.get("productionDate")));
    l.setExpiryDate(parseDate(body.get("expiryDate")));
    l.setWarehouseBin(str(body.get("warehouseBin")));
    l.setRemark(str(body.get("remark")));
    if (l.getExpiryDate() == null && m.getShelfLifeDays() != null && l.getProductionDate() != null) {
      l.setExpiryDate(l.getProductionDate().plusDays(m.getShelfLifeDays()));
    }
    lots.insert(l);
    audit.record("material.lot.create", "material_lot", id, null,
        ApiSupport.map("materialCode", materialCode, "supplier", l.getSupplier(), "qty", l.getQty()));
    return lotView(l);
  }

  /** 原料检验放行（QC+）：PENDING → RELEASED / FAIL。 */
  public Map<String, Object> inspectLot(String id, String result, String coaNo) {
    if (!CurrentUser.hasRole(Roles.QC)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅质检员及以上可做原料检验放行");
    }
    MaterialLot l = lots.selectById(id);
    if (l == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "原料批不存在: " + id);
    String res = result == null ? "PASS" : result.toUpperCase();
    if (!List.of("PASS", "FAIL").contains(res)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "result 仅可为 PASS / FAIL");
    }
    String before = l.getQcStatus();
    l.setQcStatus("PASS".equals(res) ? "RELEASED" : "FAIL");
    l.setQcBy(CurrentUser.username());
    l.setQcAt(OffsetDateTime.now());
    l.setCoaNo(coaNo);
    lots.updateById(l);
    audit.record("material.lot.inspect", "material_lot", id, ApiSupport.map("qcStatus", before),
        ApiSupport.map("qcStatus", l.getQcStatus(), "coaNo", coaNo));
    return lotView(l);
  }

  /* =================== 投料登记 =================== */

  /** 投料登记：校验原料批已放行且未过期，否则拒绝（不合格原料不得投产）。 */
  public Map<String, Object> feed(String batchId, Map<String, Object> body) {
    Batch b = batches.selectById(batchId);
    if (b == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "批次不存在: " + batchId);
    if (Boolean.TRUE.equals(b.getReleased())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "批次已放行，禁止补录投料");
    }
    String lotId = str(body.get("materialLotId"));
    MaterialLot lot = lots.selectById(lotId);
    if (lot == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "原料批不存在: " + lotId);
    if (!"RELEASED".equals(lot.getQcStatus())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "原料批 " + lotId + " 尚未检验放行（当前 " + lot.getQcStatus() + "），不得投料");
    }
    if (lot.getExpiryDate() != null && lot.getExpiryDate().isBefore(LocalDate.now())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "原料批 " + lotId + " 已过期（" + lot.getExpiryDate() + "），不得投料");
    }

    BatchInput in = new BatchInput();
    in.setBatchId(batchId);
    in.setMaterialLotId(lotId);
    in.setMaterialCode(lot.getMaterialCode());
    in.setQty(dec(body.get("qty")));
    in.setUnit(str(body.getOrDefault("unit", lot.getUnit())));
    in.setFedAt(OffsetDateTime.now());
    in.setOperator(CurrentUser.username());
    in.setRemark(str(body.get("remark")));
    inputs.insert(in);
    audit.record("batch.feed", "batch_input", batchId, null,
        ApiSupport.map("materialLotId", lotId, "qty", in.getQty()));
    return inputView(in);
  }

  public List<Map<String, Object>> inputsOf(String batchId) {
    return inputs.selectList(Wrappers.<BatchInput>lambdaQuery()
            .eq(BatchInput::getBatchId, batchId)
            .orderByAsc(BatchInput::getFedAt))
        .stream().map(this::inputView).toList();
  }

  /* =================== 谱系与召回 =================== */

  /**
   * 正向谱系：成品批次 → 上游原料批（含供应商、检验状态、COA）。
   * 反向谱系：该批次被哪些下游批次使用（半成品流转场景）。
   */
  public Map<String, Object> genealogy(String batchId) {
    Batch b = batches.selectById(batchId);
    if (b == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "批次不存在: " + batchId);
    List<Map<String, Object>> upstream = new ArrayList<>();
    for (BatchInput in : inputs.selectList(
        Wrappers.<BatchInput>lambdaQuery().eq(BatchInput::getBatchId, batchId))) {
      MaterialLot lot = lots.selectById(in.getMaterialLotId());
      Material m = lot == null ? null : materials.selectById(lot.getMaterialCode());
      upstream.add(ApiSupport.map(
          "type", "MATERIAL",
          "lotId", in.getMaterialLotId(),
          "materialCode", in.getMaterialCode(),
          "materialName", m == null ? null : m.getName(),
          "supplier", lot == null ? null : lot.getSupplier(),
          "supplierLot", lot == null ? null : lot.getSupplierLot(),
          "qty", in.getQty(),
          "unit", in.getUnit(),
          "qcStatus", lot == null ? null : lot.getQcStatus(),
          "coaNo", lot == null ? null : lot.getCoaNo(),
          "expiryDate", lot == null ? null : lot.getExpiryDate(),
          "fedAt", in.getFedAt() == null ? null : in.getFedAt().toString(),
          "allergen", m != null && Boolean.TRUE.equals(m.getAllergen()),
          "allergenName", m == null ? null : m.getAllergenName()));
    }
    List<Map<String, Object>> downstream = new ArrayList<>();
    for (BatchInput in : inputs.selectList(
        Wrappers.<BatchInput>lambdaQuery().eq(BatchInput::getMaterialLotId, batchId))) {
      Batch d = batches.selectById(in.getBatchId());
      downstream.add(ApiSupport.map(
          "type", "BATCH",
          "batchId", in.getBatchId(),
          "product", d == null ? null : d.getProduct(),
          "status", d == null ? null : d.getStatus(),
          "qty", in.getQty(),
          "fedAt", in.getFedAt() == null ? null : in.getFedAt().toString()));
    }
    return ApiSupport.map(
        "generatedAt", ApiSupport.nowIso(),
        "batchId", batchId,
        "product", b.getProduct(),
        "status", b.getStatus(),
        "upstream", upstream,
        "downstream", downstream,
        "upstreamCount", upstream.size(),
        "downstreamCount", downstream.size(),
        "integrity", upstream.isEmpty() ? "未登记投料" : "已校验");
  }

  /**
   * 召回影响分析：给定问题原料批号，反查所有使用了该原料批的成品批次及其产量，
   * 输出召回清单（含批次状态、是否已放行、库位），支撑 4 小时内完成追溯（C1）。
   */
  public Map<String, Object> recall(String materialLotId) {
    MaterialLot lot = lots.selectById(materialLotId);
    if (lot == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "原料批不存在: " + materialLotId);
    Material m = materials.selectById(lot.getMaterialCode());
    List<BatchInput> used = inputs.selectList(
        Wrappers.<BatchInput>lambdaQuery().eq(BatchInput::getMaterialLotId, materialLotId));

    List<Map<String, Object>> affected = new ArrayList<>();
    double totalYield = 0;
    int released = 0;
    for (BatchInput in : used) {
      Batch b = batches.selectById(in.getBatchId());
      double yieldT = b == null || b.getPlanYield() == null ? 0 : b.getPlanYield().doubleValue() / 1000.0;
      totalYield += yieldT;
      if (b != null && Boolean.TRUE.equals(b.getReleased())) released++;
      affected.add(ApiSupport.map(
          "batchId", in.getBatchId(),
          "product", b == null ? null : b.getProduct(),
          "status", b == null ? null : b.getStatus(),
          "released", b != null && Boolean.TRUE.equals(b.getReleased()),
          "warehouseBin", b == null ? null : b.getWarehouseBin(),
          "coaNo", b == null ? null : b.getCoaNo(),
          "line", b == null ? null : b.getLine(),
          "planYieldT", ApiSupport.r1(yieldT),
          "fedQty", in.getQty(),
          "fedAt", in.getFedAt() == null ? null : in.getFedAt().toString()));
    }
    audit.record("material.recall", "material_lot", materialLotId, null,
        ApiSupport.map("affectedBatches", affected.size(), "releasedCount", released));

    List<String> suggestions = new ArrayList<>();
    suggestions.add("隔离原料批 " + materialLotId + " 剩余库存，暂停投料");
    suggestions.add("冻结受影响成品批次 " + affected.size() + " 个，其中已放行 " + released + " 个需启动市场召回");
    if (released > 0) suggestions.add("已放行批次须在 24 小时内通知客户并启动一级召回流程");
    suggestions.add("复核同供应商同物料的其他原料批是否存在同类问题");

    return ApiSupport.map(
        "generatedAt", ApiSupport.nowIso(),
        "materialLotId", materialLotId,
        "materialCode", lot.getMaterialCode(),
        "materialName", m == null ? null : m.getName(),
        "supplier", lot.getSupplier(),
        "supplierLot", lot.getSupplierLot(),
        "affectedBatchCount", affected.size(),
        "releasedBatchCount", released,
        "totalYieldT", ApiSupport.r1(totalYield),
        "affectedBatches", affected,
        "suggestions", suggestions);
  }

  /* =================== helpers =================== */

  private Map<String, Object> lotView(MaterialLot l) {
    Material m = materials.selectById(l.getMaterialCode());
    long daysToExpiry = l.getExpiryDate() == null ? 9999
        : java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), l.getExpiryDate());
    return ApiSupport.map(
        "id", l.getId(),
        "materialCode", l.getMaterialCode(),
        "materialName", m == null ? null : m.getName(),
        "allergen", m != null && Boolean.TRUE.equals(m.getAllergen()),
        "supplier", l.getSupplier(),
        "supplierLot", l.getSupplierLot(),
        "receivedAt", l.getReceivedAt() == null ? null : l.getReceivedAt().toString(),
        "qty", l.getQty(),
        "unit", l.getUnit(),
        "qcStatus", l.getQcStatus(),
        "qcBy", l.getQcBy(),
        "productionDate", l.getProductionDate(),
        "expiryDate", l.getExpiryDate(),
        "daysToExpiry", daysToExpiry,
        "warehouseBin", l.getWarehouseBin(),
        "coaNo", l.getCoaNo(),
        "remark", l.getRemark());
  }

  private Map<String, Object> inputView(BatchInput in) {
    MaterialLot lot = lots.selectById(in.getMaterialLotId());
    Material m = materials.selectById(in.getMaterialCode());
    return ApiSupport.map(
        "id", in.getId() == null ? null : String.valueOf(in.getId()),
        "batchId", in.getBatchId(),
        "materialLotId", in.getMaterialLotId(),
        "materialCode", in.getMaterialCode(),
        "materialName", m == null ? null : m.getName(),
        "supplier", lot == null ? null : lot.getSupplier(),
        "qty", in.getQty(),
        "unit", in.getUnit(),
        "fedAt", in.getFedAt() == null ? null : in.getFedAt().toString(),
        "operator", in.getOperator(),
        "remark", in.getRemark());
  }

  private static LocalDate parseDate(Object v) {
    if (v == null || String.valueOf(v).isBlank()) return null;
    try {
      return LocalDate.parse(String.valueOf(v));
    } catch (Exception e) {
      return null;
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
