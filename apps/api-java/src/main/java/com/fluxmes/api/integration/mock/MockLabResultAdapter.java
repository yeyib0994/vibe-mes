package com.fluxmes.api.integration.mock;

import com.fluxmes.api.core.FixtureStore;
import com.fluxmes.api.integration.DataSourceTag;
import com.fluxmes.api.integration.dto.LabResult;
import com.fluxmes.api.integration.port.LabResultPort;
import com.fluxmes.api.integration.support.AdapterStatus;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * L1 · 进程内 Fake 的检验结果回流源（{@code fluxmes.integration.lims.mode=mock}）。
 *
 * <p>语义模拟「LIMS 侧已出具、MES 尚未收取」的存量报告：
 * <ul>
 *   <li>首次 {@link #poll()} 返回全部存量（对应系统上线时的初次同步）；</li>
 *   <li>之后返回空列表（没有新报告就不该造数据）；</li>
 *   <li>{@link #reset()} 供演示重放。</li>
 * </ul>
 *
 * <p>刻意**不**做成"每次轮询都造一条"——那会让 {@code qc_task} 无限增长，
 * 且与真实 LIMS 的行为不符（检验结果不是每分钟都有的）。
 */
@Component
@ConditionalOnProperty(name = "fluxmes.integration.lims.mode", havingValue = "mock",
    matchIfMissing = true)
public class MockLabResultAdapter implements LabResultPort {

  private final FixtureStore fixtures;
  private final AdapterStatus status = new AdapterStatus();
  private final Deque<LabResult> backlog = new ArrayDeque<>();
  private volatile boolean seeded;

  public MockLabResultAdapter(FixtureStore fixtures) {
    this.fixtures = fixtures;
  }

  @Override
  public String dataSource() {
    return DataSourceTag.LIMS;
  }

  @Override
  public boolean available() {
    return status.available();
  }

  @Override
  public String detail() {
    return status.detail();
  }

  @Override
  public String lastSuccessAt() {
    return status.lastSuccessAt();
  }

  @Override
  public List<LabResult> poll() {
    try {
      seedIfNeeded();
      List<LabResult> out = new ArrayList<>(backlog);
      backlog.clear();
      status.success(out.isEmpty()
          ? "mock LIMS：无新报告（存量已同步完毕）"
          : "mock LIMS：回流 " + out.size() + " 条检验结果");
      return out;
    } catch (Exception e) {
      status.failure("mock LIMS 取数失败：" + e);
      return List.of();
    }
  }

  @Override
  public void publishCoa(String batchId, String coaNo) {
    // mock：仅记录，真实实现应 POST 到 LIMS
    status.success("mock LIMS：已接收 COA 回传 " + coaNo + "（批次 " + batchId + "）");
  }

  /** 演示重放：清空已同步标记，下次 poll 重新回流全部存量。 */
  public synchronized void reset() {
    seeded = false;
    backlog.clear();
    status.note("mock LIMS：已重置，下次拉取将重新回流存量");
  }

  private synchronized void seedIfNeeded() {
    if (seeded) return;
    seeded = true;
    OffsetDateTime now = OffsetDateTime.now();
    for (Map<String, Object> task : fixtures.qcTasks()) {
      String taskNo = str(task.get("id"));
      String batchId = str(task.get("batch"));
      if (taskNo.isBlank() || batchId.isBlank()) continue;
      String item = str(task.get("item"));
      int pass = (int) num(task.get("pass"), 0);
      int sample = (int) num(task.get("sample"), 0);
      boolean allPass = sample > 0 && pass >= sample;
      backlog.add(new LabResult(
          taskNo,
          batchId,
          item.isBlank() ? "成品检验" : item,
          null,
          null,
          "GB 1886.25—2016",
          allPass ? LabResult.PASS : LabResult.PENDING,
          allPass ? coaOf(batchId) : null,
          str(task.get("inspector")),
          now.minusMinutes(30)));
    }
    status.note("mock LIMS：存量 " + backlog.size() + " 条待回流");
  }

  /** COA 编号规则与 QualityService 保持一致：COA-yyMMdd-批次尾号。 */
  private static String coaOf(String batchId) {
    String tail = batchId.length() >= 3 ? batchId.substring(batchId.length() - 3) : batchId;
    return "COA-" + LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyMMdd"))
        + "-" + tail;
  }

  private static double num(Object v, double fallback) {
    return com.fluxmes.api.common.ApiSupport.num(v, fallback);
  }

  private static String str(Object v) {
    return v == null ? "" : String.valueOf(v);
  }
}
