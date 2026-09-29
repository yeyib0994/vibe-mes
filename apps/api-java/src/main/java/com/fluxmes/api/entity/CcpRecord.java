package com.fluxmes.api.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Data;

/** F1 · CCP 监控记录：一次实测一条，偏离自动联动偏差单与报警。 */
@Data
@TableName("ccp_record")
public class CcpRecord {
  @TableId
  private Long id;
  private String ccpCode;
  private String batchId;
  private String line;
  private BigDecimal value;
  private Boolean inLimit;
  private String deviationId;
  private String alarmId;
  private String corrective;
  private String operator;
  private String verifier;
  private OffsetDateTime verifiedAt;
  private String remark;
  private OffsetDateTime recordedAt;
}
