package com.fluxmes.api.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Data;

/** Phase I · 派工记录：撤销为软删，保留留痕（FR-9）。 */
@Data
@TableName("work_order_assignment")
public class WorkOrderAssignment {
  @TableId
  private Long id;
  private String orderId;
  private String username;
  private String displayName;
  private String roleInOrder;       // OPERATOR / REVIEWER
  private String capability;        // 派工时校验的能力项
  private String assignedBy;
  private OffsetDateTime assignedAt;
  private String revokedBy;
  private OffsetDateTime revokedAt;
  private String revokeReason;
}
