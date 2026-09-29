package com.fluxmes.api.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Data;

/** H3 · 批间父子关系（中间品流转），谱系独立成表以便索引查询（plan D1）。 */
@Data
@TableName("batch_genealogy")
public class BatchGenealogy {
  @TableId
  private Long id;
  private String parentBatchId;     // 上游：中间品 / 营养盐批次
  private String childBatchId;      // 下游：耗用它的成品批次
  private String relation;          // INPUT 投入 / OUTPUT 产出
  private BigDecimal qty;
  private String unit;
  private String stage;
  private OffsetDateTime chargedAt;
  private String operator;
}
