package com.fluxmes.api.auth;

import static com.fluxmes.api.common.ApiSupport.map;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fluxmes.api.common.CurrentUser;
import com.fluxmes.api.common.JwtService;
import com.fluxmes.api.entity.AppUser;
import com.fluxmes.api.mapper.AppUserMapper;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 认证端点：登录换 JWT；当前用户信息。 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

  private final AppUserMapper users;
  private final JwtService jwt;
  private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

  public AuthController(AppUserMapper users, JwtService jwt) {
    this.users = users;
    this.jwt = jwt;
  }

  /** POST /api/auth/login —— { username, password } → { token, user } */
  @PostMapping("/login")
  public Map<String, Object> login(@RequestBody Map<String, String> body) {
    String username = body.getOrDefault("username", "");
    String password = body.getOrDefault("password", "");
    AppUser user = users.selectOne(
        Wrappers.<AppUser>lambdaQuery().eq(AppUser::getUsername, username));
    if (user == null || !Boolean.TRUE.equals(user.getEnabled())
        || !encoder.matches(password, user.getPasswordHash())) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
    }
    // G4 · token 携带厂区声明，前端据此默认选中厂区
    String token = jwt.issue(user.getId(), user.getUsername(), user.getRole(), user.getSiteCode());
    return map(
        "token", token,
        "user", map(
            "id", user.getId(),
            "username", user.getUsername(),
            "displayName", user.getDisplayName(),
            "role", user.getRole(),
            "site", user.getSiteCode()));
  }

  /** GET /api/auth/me —— 当前登录用户（前端会话恢复）。 */
  @GetMapping("/me")
  public Map<String, Object> me() {
    CurrentUser.Ctx ctx = CurrentUser.get();
    if (ctx == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
    return map("username", ctx.username(), "role", ctx.role(), "site", ctx.site());
  }
}
