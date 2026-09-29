package com.fluxmes.api.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Data;

/** SPC 控制限配置（按产品/特性配置化，替换前端硬编码常量；标准版本留痕 C5）。 */
@Data
@TableName("spc_limit")
public class SpcLimit {
  @TableId(type = com.baomidou.mybatisplus.annotation.IdType.AUTO)
  private Long id;
  private String product;
  private String feature;
  private BigDecimal ucl;
  private BigDecimal cl;
  private BigDecimal lcl;
  private String unit;
  private String standardVersion;
  private String updatedBy;
  private OffsetDateTime updatedAt;
}
