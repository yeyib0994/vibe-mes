package com.fluxmes.api.common;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * JWT 签发与校验（HS256；claims: user_id / username / role / site / exp，对齐 vibe-erp 方案）。
 * G4：新增 {@code site} 声明 —— 用户归属厂区，空值表示集团账号可跨厂区查看。
 */
@Service
public class JwtService {

  private final SecretKey key;
  private final long expireHours;

  public JwtService(@Value("${fluxmes.jwt.secret}") String secret,
      @Value("${fluxmes.jwt.expire-hours:12}") long expireHours) {
    this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    this.expireHours = expireHours;
  }

  public String issue(Long userId, String username, String role) {
    return issue(userId, username, role, null);
  }

  /** G4 · 带厂区声明的签发。 */
  public String issue(Long userId, String username, String role, String site) {
    Instant now = Instant.now();
    return Jwts.builder()
        .subject(username)
        .claim("user_id", userId)
        .claim("username", username)
        .claim("role", role)
        .claim("site", site == null ? "" : site)
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plusSeconds(expireHours * 3600)))
        .signWith(key)
        .compact();
  }

  /** 校验并返回 claims；非法/过期返回 null。 */
  public Claims parse(String token) {
    try {
      return Jwts.parser().verifyWith(key).build()
          .parseSignedClaims(token).getPayload();
    } catch (Exception e) {
      return null;
    }
  }
}
