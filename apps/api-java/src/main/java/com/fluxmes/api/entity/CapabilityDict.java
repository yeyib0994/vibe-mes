package com.fluxmes.api.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** G2 · 能力项字典：岗位资质可授予的能力项及建议的最低角色。 */
@Data
@TableName("capability_dict")
public class CapabilityDict {
  // 表中无名为 id 的列，须显式声明主键，否则 BaseMapper.selectById 无绑定语句
  @TableId
  private String code;          // WEIGHING / CCP_MONITOR / RELEASE / BATCH_REVIEW / SANITATION / LAB_TEST
  private String name;
  private String description;
  private String requiredRole;
}
