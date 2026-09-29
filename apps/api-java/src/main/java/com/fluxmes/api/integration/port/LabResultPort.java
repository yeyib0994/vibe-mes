package com.fluxmes.api.integration.port;

import com.fluxmes.api.integration.dto.LabResult;
import java.util.List;

/**
 * 检验结果回流端口（LIMS）。
 *
 * <p>对应章程 §3 非目标 3：MES **不做**仪器直连，检验结果由 LIMS 通过接口回流。
 * 落点是 MES 侧的 {@code qc_task} 与 {@code batch.coa_no}。
 *
 * <p>幂等由 {@link LabResult#taskNo()} 保证——适配器必须按 taskNo 去重，
 * 因为网络重试与 LIMS 的重复推送在真实环境里都会发生。
 */
public interface LabResultPort {

  default String system() {
    return "LIMS";
  }

  /** MOCK 或 LIMS。 */
  String dataSource();

  boolean available();

  String detail();

  String lastSuccessAt();

  /** 拉取自上次调用以来新出具的检验结果。 */
  List<LabResult> poll();

  /** 回传 COA 编号（MES 放行后出具，需通知 LIMS 归档）。 */
  void publishCoa(String batchId, String coaNo);
}
