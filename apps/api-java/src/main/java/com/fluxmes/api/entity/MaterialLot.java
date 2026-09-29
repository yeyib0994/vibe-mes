package com.fluxmes.api.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import lombok.Data;

/** F2 · 原料批（供应商批号、检验状态、效期），是谱系追溯的上游节点。 */
@Data
@TableName("material_lot")
public class MaterialLot {
  @TableId
  private String id;                // LOT-260901-01
  private String materialCode;
  private String supplier;
  private String supplierLot;
  private OffsetDateTime receivedAt;
  private BigDecimal qty;
  private String unit;
  private String qcStatus;          // PENDING / PASS / FAIL / RELEASED
  private String qcBy;
  private OffsetDateTime qcAt;
  private LocalDate productionDate;
  private LocalDate expiryDate;
  private String warehouseBin;
  private String coaNo;
  private String remark;
}
