package com.fluxmes.api.regtech;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * RegTech 端点共用小工具：客户端上下文解析与 RBAC 断言。
 * 供本包控制器以及需要接入签名门禁的既有模块（quality / recipe）复用。
 */
public final class RegtechWeb {

  private RegtechWeb() {}

  /** 客户端 IP：优先 X-Forwarded-For（经 Vite / 反向代理时保留真实来源）。 */
  public static String clientIp(HttpServletRequest req) {
    if (req == null) return null;
    String xff = req.getHeader("X-Forwarded-For");
    if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
    String real = req.getHeader("X-Real-IP");
    if (real != null && !real.isBlank()) return real;
    return req.getRemoteAddr();
  }

  public static void requireRole(String currentRole, String role, String message) {
    if (!com.fluxmes.api.common.Roles.atLeast(currentRole, role)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, message);
    }
  }

  /** 从请求体取出嵌套的 {@code signature:{meaning,password}}；无则 null（门禁据此判定 409）。 */
  @SuppressWarnings("unchecked")
  public static Map<String, Object> signatureBody(Map<String, Object> body) {
    if (body == null) return null;
    Object sig = body.get("signature");
    return sig instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
  }
}
