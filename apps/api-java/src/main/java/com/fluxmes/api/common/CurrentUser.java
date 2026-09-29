package com.fluxmes.api.common;

/** 认证上下文：AuthFilter 解析 JWT 后写入，Controller/Service 内读取当前操作人。 */
public final class CurrentUser {

  /** G4 · site 为用户归属厂区（null / 空 = 集团账号，可跨厂区）。 */
  public record Ctx(Long userId, String username, String role, String site) {}

  private static final ThreadLocal<Ctx> HOLDER = new ThreadLocal<>();

  private CurrentUser() {}

  public static void set(Ctx ctx) { HOLDER.set(ctx); }
  public static void clear() { HOLDER.remove(); }

  public static Ctx get() { return HOLDER.get(); }

  public static String username() {
    Ctx c = HOLDER.get();
    return c == null ? "anonymous" : c.username();
  }

  public static String role() {
    Ctx c = HOLDER.get();
    return c == null ? null : c.role();
  }

  /** G4 · 当前用户归属厂区（null = 集团账号）。 */
  public static String site() {
    Ctx c = HOLDER.get();
    return c == null ? null : c.site();
  }

  /** 角色等级是否 ≥ target（见 Roles）。 */
  public static boolean hasRole(String target) {
    return Roles.atLeast(role(), target);
  }
}
