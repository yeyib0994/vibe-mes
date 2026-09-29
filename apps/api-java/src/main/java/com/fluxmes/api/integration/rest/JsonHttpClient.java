package com.fluxmes.api.integration.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * L2 · 轻量 JSON over HTTP 调用器（JDK {@link HttpClient} + Jackson 2）。
 *
 * <p>刻意用 JDK 自带 HttpClient 而不是 Spring 的 RestClient：LIMS / ERP / WMS 的
 * 集成点是外部系统，需要**明确的超时与重试语义**，而这些语义 JDK API 表达得最直白、
 * 也不受 Spring 版本演进影响（本项目踩过 springdoc 与 SB4 不兼容的坑）。
 *
 * <p>重试策略：只对网络异常与 5xx 重试；4xx 视为契约问题，立即失败——
 * 重试一个参数错误只会把问题放大。
 */
@Component
public class JsonHttpClient {

  private final ObjectMapper mapper;
  private final HttpClient http;

  public JsonHttpClient(ObjectMapper mapper) {
    this.mapper = mapper;
    this.http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
  }

  /** GET，带重试。失败抛 {@link IntegrationCallException}。 */
  public JsonNode get(String url, int timeoutMs, int retries) {
    return withRetry("GET " + url, retries, () -> {
      HttpRequest req = HttpRequest.newBuilder(URI.create(url))
          .timeout(Duration.ofMillis(timeoutMs))
          .header("Accept", "application/json")
          .GET()
          .build();
      return send(req);
    });
  }

  /** POST JSON，带重试。失败抛 {@link IntegrationCallException}。 */
  public JsonNode post(String url, Object payload, int timeoutMs, int retries) {
    return withRetry("POST " + url, retries, () -> {
      String body;
      try {
        body = mapper.writeValueAsString(payload);
      } catch (Exception e) {
        throw new IntegrationCallException("请求体序列化失败：" + e, e);
      }
      HttpRequest req = HttpRequest.newBuilder(URI.create(url))
          .timeout(Duration.ofMillis(timeoutMs))
          .header("Content-Type", "application/json")
          .header("Accept", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
          .build();
      return send(req);
    });
  }

  public ObjectMapper mapper() {
    return mapper;
  }

  private JsonNode send(HttpRequest req) {
    try {
      HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(
          StandardCharsets.UTF_8));
      int sc = resp.statusCode();
      if (sc / 100 == 2) {
        String b = resp.body();
        if (b == null || b.isBlank()) return mapper.createObjectNode();
        return mapper.readTree(b);
      }
      // 4xx 是契约问题，重试无意义
      boolean retryable = sc >= 500;
      throw new IntegrationCallException("HTTP " + sc + " " + trim(resp.body()), null, retryable);
    } catch (IntegrationCallException e) {
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IntegrationCallException("调用被中断：" + e, e, false);
    } catch (Exception e) {
      // 连接失败 / 超时 / 解析失败：网络类问题，可重试
      throw new IntegrationCallException("调用失败：" + e, e, true);
    }
  }

  private JsonNode withRetry(String what, int retries, java.util.function.Supplier<JsonNode> call) {
    int attempts = Math.max(1, retries + 1);
    IntegrationCallException last = null;
    for (int i = 1; i <= attempts; i++) {
      try {
        return call.get();
      } catch (IntegrationCallException e) {
        last = e;
        if (!e.retryable() || i == attempts) break;
      }
    }
    throw new IntegrationCallException(what + " 失败（已尝试 " + attempts + " 次）："
        + (last == null ? "未知错误" : last.getMessage()), last);
  }

  private static String trim(String s) {
    if (s == null) return "";
    return s.length() > 200 ? s.substring(0, 200) + "..." : s;
  }

  /** 外部系统调用失败。{@code retryable=false} 表示重试无意义（4xx / 序列化错误）。 */
  public static class IntegrationCallException extends RuntimeException {
    private final boolean retryable;

    public IntegrationCallException(String message, Throwable cause) {
      this(message, cause, false);
    }

    public IntegrationCallException(String message, Throwable cause, boolean retryable) {
      super(message, cause);
      this.retryable = retryable;
    }

    public boolean retryable() {
      return retryable;
    }
  }
}
