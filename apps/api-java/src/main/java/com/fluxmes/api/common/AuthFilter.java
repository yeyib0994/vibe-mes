package com.fluxmes.api.common;

import io.jsonwebtoken.Claims;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;

/**
 * 轻量认证过滤器：/api/**（auth 白名单除外）要求 Authorization: Bearer &lt;jwt&gt;。
 * 解析成功写入 CurrentUser（ThreadLocal），请求结束清理。
 * 角色级权限由各 Controller 依 CurrentUser.hasRole(...) 检查（C4）。
 */
@Configuration
public class AuthFilter {

  private static final String[] WHITELIST = {
      "/api/auth/login",
      "/v3/api-docs", "/swagger-ui", "/swagger-ui.html", "/actuator/health"
  };

  private final JwtService jwt;

  public AuthFilter(JwtService jwt) {
    this.jwt = jwt;
  }

  @Bean
  FilterRegistrationBean<Filter> authFilterRegistration() {
    FilterRegistrationBean<Filter> reg = new FilterRegistrationBean<>();
    reg.setFilter((ServletRequest req, ServletResponse res, FilterChain chain) ->
        doFilter((HttpServletRequest) req, (HttpServletResponse) res, chain));
    reg.addUrlPatterns("/api/*");
    reg.setOrder(1);
    return reg;
  }

  private void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    String path = request.getRequestURI();
    for (String w : WHITELIST) {
      if (path.equals(w) || path.startsWith(w + "/")) {
        chain.doFilter(request, response);
        return;
      }
    }

    String header = request.getHeader("Authorization");
    if (header == null || !header.startsWith("Bearer ")) {
      reject(response, "未登录或缺少 token");
      return;
    }
    Claims claims = jwt.parse(header.substring(7));
    if (claims == null) {
      reject(response, "token 无效或已过期");
      return;
    }
    try {
      CurrentUser.set(new CurrentUser.Ctx(
          claims.get("user_id", Number.class) == null ? null
              : claims.get("user_id", Number.class).longValue(),
          claims.getSubject(),
          claims.get("role", String.class),
          claims.get("site", String.class)));   // G4 · 厂区
      chain.doFilter(request, response);
    } finally {
      CurrentUser.clear();
    }
  }

  private static void reject(HttpServletResponse response, String message) throws IOException {
    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    response.getWriter().write("{\"status\":401,\"message\":\"" + message + "\"}");
  }
}
