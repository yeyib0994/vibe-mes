package com.fluxmes.api.integration.support;

import java.time.OffsetDateTime;

/**
 * 适配器运行状态（连通性 / 详情 / 最近成功时间）。
 *
 * <p>四个端口都有这三个健康字段，抽出共用避免重复实现；适配器在每次调用后
 * {@link #success(String)} 或 {@link #failure(String)}，健康端点直接读。
 */
public class AdapterStatus {

  private volatile boolean available;
  private volatile String detail = "尚未探测";
  private volatile String lastSuccessAt;

  public void success(String detail) {
    this.available = true;
    this.detail = detail;
    this.lastSuccessAt = OffsetDateTime.now().toString();
  }

  public void failure(String detail) {
    this.available = false;
    this.detail = detail;
  }

  /** 只更新说明文字，不改动连通状态（用于「已重置」「存量 N 条」这类中性提示）。 */
  public void note(String detail) {
    this.detail = detail;
  }

  public boolean available() {
    return available;
  }

  public String detail() {
    return detail;
  }

  public String lastSuccessAt() {
    return lastSuccessAt;
  }
}
