package com.fluxmes.api.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Data;

/** G3 · 称量明细：每一次实际称重与容差判定（PASS / OVER / UNDER）。 */
@Data
@TableName("weighing_item")
public class WeighingItem {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String taskId;
  private Integer seq;
  private BigDecimal actualQty;
  private BigDecimal deviationPct;
  private String result;        // PASS / OVER / UNDER
  private String deviationId;
  private String alarmId;
  private String operator;
  private String reviewer;
  private OffsetDateTime reviewedAt;
  private String equipment;     // 衡器编号
  private OffsetDateTime weighedAt;
  private String remark;
}
