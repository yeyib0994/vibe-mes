package com.fluxmes.api.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Data;

/** 偏差单（工作流 open→investigating→capa→closed；未关闭阻断关联批次放行）。 */
@Data
@TableName("deviation")
public class Deviation {
  @TableId
  private String id;           // DEV-260826-002
  private String batchId;
  private String source;       // spc / manual / alarm
  private String description;
  private String status;       // open / investigating / capa / closed
  private String rootCause;
  private String capa;
  private String createdBy;
  private String closedBy;
  private OffsetDateTime createdAt;
  private OffsetDateTime closedAt;
}
