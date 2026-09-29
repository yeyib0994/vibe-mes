package com.fluxmes.api.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Data;

/** H2 · 配方版本变更记录（FR-7 影响面分析留痕）。 */
@Data
@TableName("recipe_change")
public class RecipeChange {
  @TableId
  private Long id;
  private String recipeCode;
  private String fromVersion;
  private String toVersion;
  private String affectedBatches;   // JSON
  private Boolean reviewRequired;   // 是否需重评检验方法
  private String summary;
  private String createdBy;
  private OffsetDateTime createdAt;
}
