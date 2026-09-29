package com.fluxmes.api.common;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 响应构造小工具：统一 generatedAt 与 LinkedHashMap 组装，避免各控制器重复样板代码。 */
public final class ApiSupport {

  private ApiSupport() {}

  public static String nowIso() {
    return Instant.now().toString();
  }

  /** 成对 key/value 构造有序 Map：map("a", 1, "b", 2)。 */
  public static Map<String, Object> map(Object... kv) {
    if (kv.length % 2 != 0) throw new IllegalArgumentException("map requires even args");
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
    return m;
  }

  @SafeVarargs
  public static <T> List<T> list(T... items) {
    return new ArrayList<>(List.of(items));
  }

  public static double num(Object v, double fallback) {
    return v instanceof Number n ? n.doubleValue() : fallback;
  }

  public static long round1(double v) {
    return Math.round(v * 10);
  }

  /** 百分比保留 1 位小数（如 87.5）。 */
  public static double pct1(double v) {
    return Math.round(v * 10) / 10.0;
  }

  /** 保留 1 位小数（KPI 类字段）。 */
  public static double r1(double v) {
    return Math.round(v * 10) / 10.0;
  }

  /** 保留 3 位小数（趋势点，与前端 genSeries 精度一致）。 */
  public static double r3(double v) {
    return Math.round(v * 1000) / 1000.0;
  }
}
