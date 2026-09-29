package com.fluxmes.api.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Data;

/**
 * 电子签名（21 CFR Part 11 / EU GMP 附录 11）。
 *
 * <p><b>append-only（NFR-2）</b>：本表无 UPDATE / DELETE 语义接口；更正以「作废（status=VOID）+ 新签」表达，
 * 历史签名不可删除、不可修改。签名绑定记录内容哈希，记录被改后可检出（FR-19）。
 */
@Data
@TableName("e_signature")
public class ESignature {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String action;                // 策略动作，如 BATCH_RELEASE
  private String recordType;            // BATCH / DEVIATION / CAPA / RECIPE_VERSION
  private String recordId;
  /** 签名时记录的 record_revision（FR-15）。 */
  private Integer recordVersion;
  private String meaning;               // AUTHORED / REVIEWED / APPROVED / VERIFIED / WITNESSED
  private String signer;
  /** Part 11 §11.50(b)：签名记录须含签名人姓名（打印体），非仅账号。 */
  private String signerName;
  private String signerRole;
  private OffsetDateTime signedAt;      // 服务端时间，不接受客户端传入（D6）
  private String payloadHash;           // SHA-256 hex（canonical JSON，规则版本 v1）
  private String hashAlgo;
  private String serializeRuleVersion;
  private String ip;
  private String userAgent;
  private String status;                // VALID / VOID
  private OffsetDateTime voidedAt;
  private String voidedBy;
  private String voidReason;
}
