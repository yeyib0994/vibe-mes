package com.fluxmes.api.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Data;

/** F2 · 投料记录：成品批次 ← 原料批，构成真实谱系边（召回分析的基础）。 */
@Data
@TableName("batch_input")
public class BatchInput {
  @TableId
  private Long id;
  private String batchId;
  private String materialLotId;
  private String materialCode;
  private BigDecimal qty;
  private String unit;
  private OffsetDateTime fedAt;
  private String operator;
  private String remark;
}
