package com.fluxmes.api.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Data;

/**
 * 签名失败计数（plan D5 / NFR-4）。
 * 持久化而非内存计数：多实例部署下内存计数失效；且与登录失败计数隔离，互不影响。
 */
@Data
@TableName("signature_attempt")
public class SignatureAttempt {
  /** 主键为 username（表内无 id 列，必须显式 @TableId）。 */
  @TableId
  private String username;
  private Integer failedCount;
  private OffsetDateTime firstFailedAt;
  private OffsetDateTime lockedUntil;
  private OffsetDateTime updatedAt;
}
