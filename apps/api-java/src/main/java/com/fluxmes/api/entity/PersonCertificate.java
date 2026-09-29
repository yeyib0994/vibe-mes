package com.fluxmes.api.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import lombok.Data;

/**
 * G2 · 人员资质与健康证（食品行业法定门槛：《食品安全法》第四十五条要求从业人员
 * 每年健康检查取证；CCP 监控、配料称量、成品放行等关键岗位须持相应资质上岗）。
 *
 * <ul>
 *   <li>{@code HEALTH} —— 健康证，全岗位通用前置条件，无有效期证件不得进入车间</li>
 *   <li>{@code QUALIFICATION} —— 岗位资质，按 {@code capability} 能力项做关键动作门禁</li>
 * </ul>
 */
@Data
@TableName("person_certificate")
public class PersonCertificate {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String username;
  private String displayName;
  private String certType;      // HEALTH / QUALIFICATION
  private String certName;
  private String capability;    // WEIGHING / CCP_MONITOR / RELEASE / BATCH_REVIEW / SANITATION / LAB_TEST
  private String certNo;
  private String issuedBy;
  private LocalDate issuedAt;
  private LocalDate validUntil;
  private String status;        // VALID / EXPIRED / REVOKED
  private String remark;
  private OffsetDateTime createdAt;
}
