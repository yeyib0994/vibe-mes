package com.fluxmes.api.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import lombok.Data;

/** H1 · 维护工单（FR-6/FR-7）：PREVENTIVE 预防性保养 / CORRECTIVE 故障维修 / CALIBRATION 校准。 */
@Data
@TableName("maintenance_order")
public class MaintenanceOrder {
  @TableId
  private String id;                // MO-260914-001
  private String equipmentCode;
  private String type;              // PREVENTIVE / CORRECTIVE / CALIBRATION
  private String status;            // OPEN / IN_PROGRESS / DONE / CANCELLED
  private LocalDate planDate;
  private BigDecimal runtimeBefore;
  private BigDecimal runtimeAfter;
  private OffsetDateTime doneAt;
  private String doneBy;
  private String createdBy;
  private OffsetDateTime createdAt;
  private String remark;
}
