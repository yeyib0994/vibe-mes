package com.fluxmes.api.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Data;

/** G4 · 厂区主数据：多工厂集团场景下，批次 / 产线 / 用户按厂区隔离与聚合。 */
@Data
@TableName("site")
public class Site {
  // 表中无名为 id 的列，须显式声明主键
  @TableId
  private String code;          // SITE-01
  private String name;
  private String address;
  private Boolean enabled;
  private OffsetDateTime createdAt;
}
