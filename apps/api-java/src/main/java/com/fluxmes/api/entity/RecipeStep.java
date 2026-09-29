package com.fluxmes.api.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import lombok.Data;

/** H2 · 配方工序参数与容差（plan D3：结构化数值列便于索引与容差比较）。 */
@Data
@TableName("recipe_step")
public class RecipeStep {
  @TableId
  private Long id;
  private String recipeCode;
  private String version;
  private Integer seq;
  private String stage;
  private String paramName;
  private String paramKey;
  private BigDecimal targetValue;
  private BigDecimal lowerLimit;
  private BigDecimal upperLimit;
  private String unit;
  private Boolean ccp;              // 关键控制点参数
  private String equipmentCategory;
}
