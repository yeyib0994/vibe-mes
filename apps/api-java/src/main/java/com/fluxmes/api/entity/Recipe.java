package com.fluxmes.api.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Data;

/** 配方主数据。 */
@Data
@TableName("recipe")
public class Recipe {
  @TableId(type = IdType.AUTO)
  private Integer id;
  private String code;
  private String name;
  private String product;
  private String version;
  private String status;
  private String stages;
  private String params;
  private BigDecimal expectedYield;
  private OffsetDateTime updatedAt;
}
