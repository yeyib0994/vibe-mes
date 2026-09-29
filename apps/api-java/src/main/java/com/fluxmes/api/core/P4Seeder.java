package com.fluxmes.api.core;

import com.fluxmes.api.equipment.EquipmentService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Phase H 种子（幂等）：三个遗留 Spec 模块的主数据导入。
 * <ul>
 *   <li>H1 设备台账：fixture 设备 → equipment 表（含状态与保养/校准周期）；</li>
 *   <li>H2 配方版本：fixture 配方 → recipe_version / recipe_step；</li>
 *   <li>H3 追溯谱系：批次间父子关系由 batch_genealogy 承载。</li>
 * </ul>
 * 各模块 seed() 内部自行判重，二次启动不重复插入。
 */
@Component
@Order(130)
public class P4Seeder implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(P4Seeder.class);

  private final List<SeedModule> modules;

  public P4Seeder(EquipmentService equipment, com.fluxmes.api.recipe.RecipeService recipes,
      com.fluxmes.api.trace.TraceService trace) {
    this.modules = List.of(equipment::seed, recipes::seed, trace::seed);
  }

  @Override
  public void run(ApplicationArguments args) {
    for (SeedModule m : modules) {
      try {
        m.seed();
      } catch (Exception e) {
        // 种子失败不阻断启动（业务表可能尚未就绪），仅告警便于排查
        log.warn("P4 seed failed: {}", e.toString());
      }
    }
  }

  /** 各模块种子入口，便于后续 H2/H3 顺序接入。 */
  public interface SeedModule {
    void seed();
  }
}
