package com.fluxmes.api.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import lombok.Data;

/** CAPA 行动项（FR-2 至少一条；FR-4 未完成项阻断流转至 pending_verify）。 */
@Data
@TableName("capa_task")
public class CapaTask {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String capaId;
  private Integer seq;
  private String action;
  private String owner;
  private LocalDate dueDate;
  private Boolean done;
  private OffsetDateTime doneAt;
  private String doneBy;
  private String evidence;
  private String remark;
  private OffsetDateTime createdAt;
}
