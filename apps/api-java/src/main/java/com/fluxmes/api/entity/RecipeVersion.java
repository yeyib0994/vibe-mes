package com.fluxmes.api.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Data;

/**
 * H2 · 配方版本受控（plan D1：recipe 主表保留为当前生效视图，版本受控由本表承载）。
 * NFR-3 生效唯一性由数据库部分唯一索引 uq_recipe_effective 强制。
 */
@Data
@TableName("recipe_version")
public class RecipeVersion {
  @TableId
  private Long id;
  private String recipeCode;        // R-CA-07
  private String version;           // v3.2
  private String name;
  private String product;
  private String yieldRate;
  private String stages;            // JSON 工艺路线
  private String status;            // draft / pending / effective / obsolete / rejected
  private String sourceVersion;     // 派生自哪个版本
  private String changeNote;
  private String createdBy;
  private OffsetDateTime createdAt;
  private String submittedBy;
  private OffsetDateTime submittedAt;
  private String approvedBy;
  private OffsetDateTime approvedAt;
  private OffsetDateTime effectiveAt;
  private OffsetDateTime obsoleteAt;
}
