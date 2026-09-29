package com.fluxmes.api.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import lombok.Data;

/** F3 · 留样管理：法定要求留至保质期后 6 个月。 */
@Data
@TableName("retention_sample")
public class RetentionSample {
  @TableId
  private String id;                // RS-260826-007
  private String batchId;
  private BigDecimal qty;
  private String unit;
  private String location;
  private String retainedBy;
  private OffsetDateTime retainedAt;
  private LocalDate expiryDate;     // 成品效期 + 180 天
  private String status;            // RETAINED / TESTED / DISCARDED
  private OffsetDateTime disposedAt;
  private String disposedBy;
  private String remark;
}
