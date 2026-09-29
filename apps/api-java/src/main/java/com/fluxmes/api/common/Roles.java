package com.fluxmes.api.common;

/** 角色常量与等级（C4 RBAC：工艺员 < 质检员 < 值班长 < 管理员）。 */
public final class Roles {

  public static final String OPERATOR = "OPERATOR";     // 工艺员
  public static final String QC = "QC";                 // 质检员
  public static final String SUPERVISOR = "SUPERVISOR";  // 值班长
  public static final String ADMIN = "ADMIN";            // 管理员

  private Roles() {}

  public static int level(String role) {
    return switch (role == null ? "" : role) {
      case ADMIN -> 4;
      case SUPERVISOR -> 3;
      case QC -> 2;
      case OPERATOR -> 1;
      default -> 0;
    };
  }

  /** 当前角色是否 ≥ 目标角色等级（如 require(QC) 表示 QC/值班长/管理员均可）。 */
  public static boolean atLeast(String role, String target) {
    return level(role) >= level(target);
  }
}
