package com.fluxmes.api.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** F2 · 物料主数据（原料 / 辅料 / 包装），带过敏原标识。 */
@Data
@TableName("material")
public class Material {
  @TableId
  private String code;              // RM-001
  private String name;
  private String category;          // RAW / EXCIPIENT / PACKAGING
  private String unit;
  private Boolean allergen;
  private String allergenName;
  private Integer shelfLifeDays;
  private String spec;
  private Boolean enabled;
}
