package com.fluxmes.api.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 质检任务（D3 班报合格率与放行状态的取数源）。 */
@Data
@TableName("qc_task")
public class QcTask {
  @TableId
  private String id;
  private String type;            // 原料 / 过程 / 成品
  private String batch;           // 关联批次号
  private String item;
  private String sample;
  private Boolean pass;
  private String inspector;
  private String status;          // pending / testing / done
  private String standard;
  private String standardVersion;
  private String due;
  private String completedAt;
}
