package com.fluxmes.api.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 手写 OpenAPI 3.0 契约端点（constitution：后端须提供 OpenAPI 与前台交互）。
 *
 * 不引入 springdoc：springdoc 2.8.x 与 Spring Boot 4.1.1（spring-context 7）不兼容，
 * 启动时会在 SwaggerConfig.swaggerWelcome 条件装配阶段失败，导致整个应用无法启动。
 * 因此改为零依赖手写契约，路径沿用约定 /v3/api-docs，可被前端生成 client 或导入 Apifox/Postman。
 */
@RestController
public class OpenApiController {

  @GetMapping("/v3/api-docs")
  public Map<String, Object> apiDocs() {
    Map<String, Object> doc = new LinkedHashMap<>();
    doc.put("openapi", "3.0.3");
    doc.put("info", Map.of(
        "title", "FluxMES API",
        "version", "0.5.0",
        "description", "食品流程制造执行系统 · 批次/质量/报警/追溯/HACCP/谱系召回/eBR"
            + "（JWT RBAC + 审计追踪 + PostgreSQL；P3：SLA 可配置 / 资质健康证 / 称量容差 / 多厂区）"));
    doc.put("servers", List.of(Map.of("url", "http://localhost:8080", "description", "本地")));
    doc.put("security", List.of(Map.of("bearerAuth", List.of())));
    doc.put("components", Map.of("securitySchemes", Map.of("bearerAuth", Map.of(
        "type", "http", "scheme", "bearer", "bearerFormat", "JWT"))));

    Map<String, Object> paths = new LinkedHashMap<>();

    /* ---- auth ---- */
    add(paths, "/api/auth/login", "post", "auth", "登录换取 JWT（admin/supervisor/qc/operator）", false);
    add(paths, "/api/auth/me", "get", "auth", "当前登录用户与角色", true);

    /* ---- dashboard（D2 多产线 / D3 班报） ---- */
    add(paths, "/api/dashboard/cockpit", "get", "dashboard",
        "生产驾驶舱聚合（KPI/趋势/设备/在产/报警），支持 ?line=LINE-1 产线维度", true);
    add(paths, "/api/dashboard/cockpit/{siteId}", "get", "dashboard", "按厂区取驾驶舱数据", true);
    add(paths, "/api/dashboard/lines", "get", "dashboard", "产线主数据与各线批次统计（?site= 过滤）", true);
    add(paths, "/api/dashboard/sites", "get", "dashboard", "厂区主数据与各厂区产线/批次统计（切换器数据源）", true);
    add(paths, "/api/dashboard/shift-report", "get", "dashboard",
        "班报：?date=&shift=DAY|NIGHT&line=，返回结构化数据 + Markdown（可导出）", true);

    /* ---- batch（D2 产线过滤） ---- */
    add(paths, "/api/batches", "get", "batch",
        "批次台账：status/stage/product/keyword/line 过滤 + page/size 分页", true);
    add(paths, "/api/batches", "post", "batch", "新建批次（工艺员及以上，校验配方/产量/原料批号）", true);
    add(paths, "/api/batches/lines", "get", "batch", "产线主数据列表", true);
    add(paths, "/api/batches/{id}", "get", "batch", "批次详情（含结构化档案与工艺路线）", true);
    add(paths, "/api/batches/{id}/advance", "post", "batch", "工序推进（状态机：running→waiting→done）", true);
    add(paths, "/api/batches/{id}/abnormal", "post", "batch", "标记异常并自动创建偏差单（阻断推进）", true);

    /* ---- quality ---- */
    add(paths, "/api/quality", "get", "quality", "质量总览：SPC 序列/帕累托/检验任务/控制限/判异/偏差", true);
    add(paths, "/api/quality/spc/detect", "get", "quality", "Western Electric 判异结果（R1/R2/R3）", true);
    add(paths, "/api/quality/deviations", "get", "quality", "偏差单列表", true);
    add(paths, "/api/quality/deviations/{id}/transition", "post", "quality",
        "偏差逐级推进（关闭需值班长及以上）", true);
    add(paths, "/api/quality/release", "post", "quality", "成品放行生成 COA 并锁定批次（质检员及以上）", true);

    /* ---- alarm（D1 SLA + 抑制） ---- */
    add(paths, "/api/alarms", "get", "alarm", "报警台账 + 未确认/逾期计数", true);
    add(paths, "/api/alarms/{id}/ack", "post", "alarm", "报警确认（写审计 + SSE 推送）", true);
    add(paths, "/api/alarms/{id}/recover", "post", "alarm", "报警恢复（写审计 + SSE 推送）", true);
    add(paths, "/api/alarms/stats", "get", "alarm",
        "报警统计（含 SLA 政策与逾期数、生效抑制规则数）", true);
    add(paths, "/api/alarms/suppressions", "get", "alarm", "抑制规则列表", true);
    add(paths, "/api/alarms/suppressions", "post", "alarm", "新建抑制规则（值班长及以上）", true);
    add(paths, "/api/alarms/suppressions/{id}", "delete", "alarm", "删除抑制规则（值班长及以上）", true);
    add(paths, "/api/alarms/sla/sweep", "post", "alarm", "手动触发一次 SLA 巡检（值班长及以上）", true);
    add(paths, "/api/alarms/sla/policy", "get", "alarm",
        "G1 · 各级别响应时限政策（0 表示该级别豁免考核）", true);
    add(paths, "/api/alarms/sla/policy/{level}", "put", "alarm",
        "G1 · 调整某级别响应时限 / 启停（值班长及以上）", true);
    add(paths, "/api/alarms/stream", "get", "alarm", "SSE 实时报警事件流（created/acked/recovered/escalated）", true);

    /* ---- F1 · 食品安全：HACCP / 清场 / 环境 ---- */
    add(paths, "/api/haccp/points", "get", "haccp", "CCP 关键控制点清单（?line= 过滤）", true);
    add(paths, "/api/haccp/points", "post", "haccp", "新增 CCP 点（值班长及以上）", true);
    add(paths, "/api/haccp/records", "get", "haccp", "CCP 监控记录（?batchId=&ccpCode=&onlyDeviation=）", true);
    add(paths, "/api/haccp/records", "post", "haccp",
        "上报 CCP 监控值；越出关键限值自动建偏差单 + critical 报警并阻断批次", true);
    add(paths, "/api/haccp/records/{id}/verify", "post", "haccp",
        "QA 复核 CCP 记录（双人复核，复核人不得为记录人）", true);
    add(paths, "/api/haccp/summary", "get", "haccp", "CCP 合规看板：记录数/偏离数/待复核/合规率", true);
    add(paths, "/api/sanitation/records", "get", "sanitation", "清场记录（?line=&type=）", true);
    add(paths, "/api/sanitation/records", "post", "sanitation",
        "登记清场（ROUTINE/CHANGEOVER/ALLERGEN/DEEP）", true);
    add(paths, "/api/sanitation/records/{id}/verify", "post", "sanitation",
        "QA 确认清场结果 PASS/FAIL，PASS 写入 72 小时有效期", true);
    add(paths, "/api/sanitation/status", "get", "sanitation",
        "开工前置校验：产线是否具备有效清场记录", true);
    add(paths, "/api/sanitation/environment", "get", "sanitation", "环境监测记录（?area=&metric=）", true);
    add(paths, "/api/sanitation/environment", "post", "sanitation", "上报环境指标；超标自动建偏差单", true);
    add(paths, "/api/sanitation/environment/summary", "get", "sanitation", "环境分区合格率汇总", true);

    /* ---- F2 · 物料谱系与召回 ---- */
    add(paths, "/api/materials", "get", "material", "物料主数据（含过敏原标识）", true);
    add(paths, "/api/materials/lots", "get", "material", "原料批清单（?materialCode=&qcStatus=&expiringSoon=）", true);
    add(paths, "/api/materials/lots", "post", "material", "原料到货登记（默认待检验）", true);
    add(paths, "/api/materials/lots/{id}/inspect", "post", "material",
        "原料检验放行 PASS/FAIL（质检员及以上）", true);
    add(paths, "/api/materials/{batchId}/inputs", "get", "material", "批次投料清单", true);
    add(paths, "/api/materials/{batchId}/inputs", "post", "material",
        "投料登记（校验原料批已放行且未过期）", true);
    add(paths, "/api/materials/genealogy/{batchId}", "get", "material",
        "真实谱系：上游原料批 + 下游用途", true);
    add(paths, "/api/materials/recall/{materialLotId}", "get", "material",
        "召回影响分析：问题原料批 → 受影响成品批次与处置建议", true);

    /* ---- F3 · eBR / 留样 / 效期 ---- */
    add(paths, "/api/ebr/{batchId}", "get", "ebr", "电子批记录：工序设定值/实际值/复核状态", true);
    add(paths, "/api/ebr/{batchId}/steps", "post", "ebr", "工序执行记录写入", true);
    add(paths, "/api/ebr/steps/{id}/review", "post", "ebr", "工序复核（双人复核）", true);
    add(paths, "/api/ebr/retention", "get", "ebr", "留样清单（?status=&expiringSoon=）", true);
    add(paths, "/api/ebr/retention", "post", "ebr", "留样登记（到期日 = 成品效期 + 180 天）", true);
    add(paths, "/api/ebr/retention/{id}/dispose", "post", "ebr", "留样处置（质检员及以上）", true);
    add(paths, "/api/ebr/expiry-alerts", "get", "ebr", "近效期与已过期成品批次预警（?warnDays=30）", true);

    /* ---- G2 · 人员资质与健康证 ---- */
    add(paths, "/api/personnel/certificates", "get", "personnel",
        "证书台账（?username=&certType=HEALTH|QUALIFICATION）", true);
    add(paths, "/api/personnel/certificates", "post", "personnel", "登记发证（值班长及以上）", true);
    add(paths, "/api/personnel/certificates/{id}/revoke", "post", "personnel", "吊销证书（值班长及以上）", true);
    add(paths, "/api/personnel/capabilities", "get", "personnel",
        "能力项字典与持证人数（WEIGHING/CCP_MONITOR/RELEASE/BATCH_REVIEW/SANITATION/LAB_TEST）", true);
    add(paths, "/api/personnel/alerts", "get", "personnel",
        "资质到期预警：已过期 + 即将到期（?days=30）", true);
    add(paths, "/api/personnel/summary", "get", "personnel", "资质合规看板", true);
    add(paths, "/api/personnel/{username}", "get", "personnel", "某人资质概览", true);

    /* ---- G3 · 称量 / 配料容差 ---- */
    add(paths, "/api/weighing/tasks", "get", "weighing", "称量任务列表（?batchId=&status=）", true);
    add(paths, "/api/weighing/tasks", "post", "weighing", "创建称量任务（目标量 + 容差百分比）", true);
    add(paths, "/api/weighing/tasks/{id}/items", "get", "weighing", "任务称量明细", true);
    add(paths, "/api/weighing/tasks/{id}/weigh", "post", "weighing",
        "登记实际称量；超差自动建偏差单 + major 报警并阻断任务（须持配料称量资质）", true);
    add(paths, "/api/weighing/items/{id}/review", "post", "weighing",
        "超差复核（质检员及以上，复核人不得为称量人本人）", true);
    add(paths, "/api/weighing/summary", "get", "weighing", "称量合规看板：合格率/超差/待复核", true);

    /* ---- H1 · 设备管理 ---- */
    add(paths, "/api/equipment", "get", "equipment",
        "设备台账（?status=&keyword=），主数据来自 PG，实时参数由采集层提供", true);
    add(paths, "/api/equipment/{code}", "get", "equipment",
        "设备详情：参数趋势 + 关联报警 + 在制批次 + 维护工单 + 状态事件", true);
    add(paths, "/api/equipment/{code}/status", "post", "equipment",
        "设备状态变更，写事件流水与审计（工艺员及以上）", true);
    add(paths, "/api/equipment/{code}/oee", "get", "equipment",
        "OEE 三因子分解（可用率取设备状态事件停机时长）", true);
    add(paths, "/api/equipment/alerts", "get", "equipment",
        "保养到期与校准超期预警（?withinDays=7）", true);
    add(paths, "/api/equipment/available", "get", "equipment",
        "批次建单可用设备：启用且校准在有效期内（FR-10）", true);
    add(paths, "/api/equipment/maintenance-orders", "get", "equipment", "维护工单列表（?equipmentCode=&status=）", true);
    add(paths, "/api/equipment/maintenance-orders", "post", "equipment", "创建维护工单（值班长及以上）", true);
    add(paths, "/api/equipment/maintenance-orders/{id}/done", "post", "equipment",
        "完成工单：回填运行小时并按周期顺延保养；CALIBRATION 类同时续期校准", true);

    /* ---- H2 · 配方版本受控 ---- */
    add(paths, "/api/recipes", "get", "recipe", "配方台账（版本与工序参数取自 recipe_version / recipe_step）", true);
    add(paths, "/api/recipes/{code}/versions", "get", "recipe", "全部版本倒序（NFR-2）", true);
    add(paths, "/api/recipes/{code}/versions/{version}", "get", "recipe", "单版本详情 + 工序参数与容差", true);
    add(paths, "/api/recipes/{code}/history", "get", "recipe", "版本历史（兼容前端抽屉契约）", true);
    add(paths, "/api/recipes/{code}/steps", "get", "recipe", "指定版本的工序参数与容差（FR-3）", true);
    add(paths, "/api/recipes/{code}/impact", "get", "recipe",
        "变更影响分析：执行旧版本的在制批次 + 是否需重评检验方法（FR-7）", true);
    add(paths, "/api/recipes/{code}/draft", "post", "recipe", "基于已有版本创建草稿，次版本递增（工艺员及以上）", true);
    add(paths, "/api/recipes/{code}/versions/{version}/submit", "post", "recipe", "提交审批 draft→pending（FR-5）", true);
    add(paths, "/api/recipes/{code}/versions/{version}/approve", "post", "recipe",
        "审批 pending→effective/rejected（管理员）", true);
    add(paths, "/api/recipes/{code}/versions/{version}/activate", "post", "recipe",
        "生效切换：原子事务内置旧版本 obsolete（管理员，NFR-3）", true);

    /* ---- H3 · 追溯闭环 ---- */
    add(paths, "/api/trace/targets", "get", "trace", "可追溯批次候选", true);
    add(paths, "/api/trace/chain/{batchId}", "get", "trace",
        "正向/逆向追溯链（真实数据组装）+ 横向关联报警与偏差 + 完整性", true);
    add(paths, "/api/trace/backward", "get", "trace", "逆向追溯：消耗该原料批的成品批次（FR-3）", true);
    add(paths, "/api/trace/impact", "get", "trace", "影响面分析：受影响批次与放行状态（FR-4）", true);
    add(paths, "/api/trace/{batchId}/completeness", "get", "trace", "档案完整性与缺失项清单（FR-8）", true);
    add(paths, "/api/trace/{batchId}/export", "get", "trace", "追溯报表导出（Markdown，含生成人与时间水印）", true);
    add(paths, "/api/trace/logs", "get", "trace", "追溯查询审计流水（FR-7）", true);
    add(paths, "/api/trace/genealogy", "post", "trace", "登记中间品流转父子关系", true);

    /* ---- audit ---- */
    add(paths, "/api/audit", "get", "audit", "审计日志查询（管理员）", true);

    doc.put("paths", paths);
    return doc;
  }

  /** 同一路径支持多方法（原实现用 put 会互相覆盖，导致 post 顶掉 get）。 */
  @SuppressWarnings("unchecked")
  private static void add(Map<String, Object> paths, String path, String method,
      String tag, String summary, boolean auth) {
    Map<String, Object> item = (Map<String, Object>) paths.computeIfAbsent(path, k -> new LinkedHashMap<>());
    item.put(method, operation(tag, summary, auth));
  }

  private static Map<String, Object> operation(String tag, String summary, boolean auth) {
    Map<String, Object> responses = new LinkedHashMap<>();
    responses.put("200", Map.of("description", "OK"));
    if (auth) {
      responses.put("401", Map.of("description", "未登录或 token 失效"));
      responses.put("403", Map.of("description", "角色不足"));
    } else {
      responses.put("401", Map.of("description", "用户名或密码错误"));
    }
    return Map.of("tags", List.of(tag), "summary", summary, "responses", responses);
  }
}
