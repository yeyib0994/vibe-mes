package com.fluxmes.api.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Data;

/** CAPA 状态流水（FR-3 流转留痕，配合 audit_log 形成双重佐证）。 */
@Data
@TableName("capa_event")
public class CapaEvent {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String capaId;
  private String fromStatus;
  private String toStatus;
  private String comment;
  private String operator;
  private OffsetDateTime createdAt;
}
