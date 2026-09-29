package com.fluxmes.api.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 签名适用范围配置（plan D2/D7）。
 * 门禁不硬编码在业务代码里：企业可按 GMP 要求逐动作开关；演示环境可置 enabled=false 降级。
 */
@Data
@TableName("signature_policy")
public class SignaturePolicy {
  @TableId
  private String action;           // BATCH_RELEASE / DEVIATION_CLOSE / CAPA_CLOSE / CAPA_VERIFY /
                                   // RECIPE_ACTIVATE / EBR_REVIEW
  private String name;
  private String recordType;       // BATCH / DEVIATION / CAPA / RECIPE_VERSION
  private String requiredMeaning;  // AUTHORED / REVIEWED / APPROVED / VERIFIED / WITNESSED
  private String requiredRole;
  private Boolean enabled;
  private String note;
}
