package com.fluxmes.api.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Data;

/** H3 · 追溯查询审计（FR-7）：监管核查需要「谁在何时查了什么」的专项统计。 */
@Data
@TableName("trace_query_log")
public class TraceQueryLog {
  @TableId
  private Long id;
  private String actor;
  private String queryType;         // forward / backward / impact / export / completeness
  private String queryKey;
  private Integer resultCount;
  private Long durationMs;
  private OffsetDateTime createdAt;
}
