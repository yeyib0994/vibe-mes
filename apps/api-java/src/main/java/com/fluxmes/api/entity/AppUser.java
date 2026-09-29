package com.fluxmes.api.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Data;

/** 应用用户（C4 RBAC 四角色：ADMIN/SUPERVISOR/QC/OPERATOR）。 */
@Data
@TableName("app_user")
public class AppUser {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String username;
  private String passwordHash;
  private String displayName;
  private String role;
  private Boolean enabled;
  /** G4 · 归属厂区（NULL = 集团账号，可跨厂区查看全部数据）。 */
  private String siteCode;
  private OffsetDateTime createdAt;
}
