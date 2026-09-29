package com.fluxmes.api.core;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 只读展示数据源：加载 mes.json 中「非业务台账」的展示型数据
 * （趋势、罐区设备、在产批次卡片、SPC 序列、帕累托、检验任务示例、设备台账、配方、追溯链）。
 *
 * Phase 1 变更：批次 / 报警等可变业务数据已迁入 PostgreSQL（BatchService / AlarmService），
 * 本类不再持有可变报警状态；业务 seed 由 DatabaseSeeder 完成。
 */
@Component
public class FixtureStore {

  private static final Logger log = LoggerFactory.getLogger(FixtureStore.class);
  private static final double SPC_UCL = 99.82;
  private static final double SPC_LCL = 99.18;

  private final ObjectMapper mapper;

  private double planRate;
  private List<Map<String, Object>> productionTrend = List.of();
  private List<Map<String, Object>> tankEquipment = List.of();
  private List<Map<String, Object>> runningBatches = List.of();
  private List<Map<String, Object>> spcData = List.of();
  private List<Map<String, Object>> pareto = List.of();
  private List<Map<String, Object>> qcTasks = List.of();
  private List<Map<String, Object>> equipmentSpec = List.of();
  private List<Map<String, Object>> recipes = List.of();
  private Map<String, Object> traceChains = Map.of();
  private List<Map<String, Object>> traceableBatches = List.of();

  public FixtureStore(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  @PostConstruct
  void load() throws Exception {
    Map<String, Object> root;
    try (InputStream in = new ClassPathResource("fixtures/mes.json").getInputStream()) {
      root = mapper.readValue(in, new TypeReference<>() {});
    }
    planRate = asDouble(root.get("planRate"));
    productionTrend = asList(root.get("productionTrend"));
    tankEquipment = asList(root.get("tankEquipment"));
    runningBatches = asList(root.get("runningBatches"));
    spcData = normalizeSpc(asList(root.get("spcData")));
    pareto = asList(root.get("pareto"));
    qcTasks = asList(root.get("qcTasks"));
    equipmentSpec = asList(root.get("equipmentSpec"));
    recipes = asList(root.get("recipes"));
    traceChains = asMap(root.get("traceChains"));
    traceableBatches = asList(root.get("traceableBatches"));
    log.info("readonly fixture loaded: {} trend points, {} equipment, {} recipes",
        productionTrend.size(), equipmentSpec.size(), recipes.size());
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> asList(Object v) {
    return v instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object v) {
    return v instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
  }

  private static double asDouble(Object v) {
    return v instanceof Number n ? n.doubleValue() : 0;
  }

  /** SPC 点补充 ooc（超出 UCL/LCL 即为失控点；控制限 Phase 1 起以 spc_limit 表为准，此处保留展示兜底）。 */
  private static List<Map<String, Object>> normalizeSpc(List<Map<String, Object>> src) {
    return src.stream().map(p -> {
      Map<String, Object> copy = new java.util.LinkedHashMap<>(p);
      double v = asDouble(p.get("v"));
      copy.put("ooc", v > SPC_UCL || v < SPC_LCL);
      return copy;
    }).toList();
  }

  /* ---------- getters（只读） ---------- */

  public double planRate() { return planRate; }
  public List<Map<String, Object>> productionTrend() { return productionTrend; }
  public List<Map<String, Object>> tankEquipment() { return tankEquipment; }
  public List<Map<String, Object>> runningBatches() { return runningBatches; }
  public List<Map<String, Object>> spcData() { return spcData; }
  public List<Map<String, Object>> pareto() { return pareto; }
  public List<Map<String, Object>> qcTasks() { return qcTasks; }
  public List<Map<String, Object>> equipmentSpec() { return equipmentSpec; }
  public List<Map<String, Object>> recipes() { return recipes; }
  public Map<String, Object> traceChains() { return traceChains; }
  public List<Map<String, Object>> traceableBatches() { return traceableBatches; }
}
