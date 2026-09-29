package com.fluxmes.api.core;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fluxmes.api.common.CurrentUser;
import com.fluxmes.api.entity.AppUser;
import com.fluxmes.api.entity.AuditFinding;
import com.fluxmes.api.entity.Capa;
import com.fluxmes.api.entity.CapaEvent;
import com.fluxmes.api.entity.CapaTask;
import com.fluxmes.api.entity.InternalAudit;
import com.fluxmes.api.entity.SignaturePolicy;
import com.fluxmes.api.mapper.AppUserMapper;
import com.fluxmes.api.mapper.AuditFindingMapper;
import com.fluxmes.api.mapper.CapaEventMapper;
import com.fluxmes.api.mapper.CapaMapper;
import com.fluxmes.api.mapper.CapaTaskMapper;
import com.fluxmes.api.mapper.InternalAuditMapper;
import com.fluxmes.api.mapper.SignaturePolicyMapper;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Phase I · 质量体系 RegTech 种子（幂等，@Order 150 于 P5Seeder 之后）。
 *
 * <p>落地内容：
 * <ul>
 *   <li>FR-16 演示账号 {@code service}（系统集成账号，具备 ADMIN 角色但<b>禁止签名</b>）。</li>
 *   <li>1 份内审（含 3 条分级发现项，其中 1 条 MAJOR 已转 CAPA）—— 供前端与自检演示。</li>
 *   <li>2 条 CAPA：一条源于内审（进行中），一条手工来源且<b>已逾期</b>（FR-5 临期/逾期与报警演示）。</li>
 * </ul>
 * 签名策略由 {@code db/schema-p6.sql} 幂等 INSERT 维护，此处仅校验条数并告警。
 */
@Component
@Order(150)
public class RegtechSeeder implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(RegtechSeeder.class);
  private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyMMdd");
  private static final int EXPECTED_POLICIES = 6;

  private final AppUserMapper users;
  private final InternalAuditMapper audits;
  private final AuditFindingMapper findings;
  private final CapaMapper capas;
  private final CapaTaskMapper capaTasks;
  private final CapaEventMapper capaEvents;
  private final SignaturePolicyMapper policies;
  private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

  public RegtechSeeder(AppUserMapper users, InternalAuditMapper audits, AuditFindingMapper findings,
      CapaMapper capas, CapaTaskMapper capaTasks, CapaEventMapper capaEvents,
      SignaturePolicyMapper policies) {
    this.users = users;
    this.audits = audits;
    this.findings = findings;
    this.capas = capas;
    this.capaTasks = capaTasks;
    this.capaEvents = capaEvents;
    this.policies = policies;
  }

  @Override
  public void run(ApplicationArguments args) {
    // 种子无 HTTP 上下文：显式设置操作人，保证 created_by / 审计语义正确
    CurrentUser.set(new CurrentUser.Ctx(null, "admin", "ADMIN", null));
    try {
      seedServiceAccount();
      checkPolicies();
      if (audits.selectCount(null) == 0 && capas.selectCount(null) == 0) {
        seedDemo();
      } else {
        log.info("regtech seed skipped: audit={} capa={}", audits.selectCount(null), capas.selectCount(null));
      }
    } catch (Exception e) {
      log.warn("regtech seed failed: {}", e.toString());
    } finally {
      CurrentUser.clear();
    }
  }

  /** FR-16 · 系统集成账号：有角色但被签名服务拒绝（不具签名唯一性）。 */
  private void seedServiceAccount() {
    AppUser existing = users.selectOne(Wrappers.<AppUser>lambdaQuery()
        .eq(AppUser::getUsername, "service"));
    if (existing != null) return;
    AppUser u = new AppUser();
    u.setUsername("service");
    u.setPasswordHash(encoder.encode("service123"));
    u.setDisplayName("系统集成账号");
    u.setRole("ADMIN");
    u.setEnabled(true);
    u.setCreatedAt(OffsetDateTime.now());
    users.insert(u);
    log.info("seed FR-16 demo account: service/service123（禁止电子签名）");
  }

  private void checkPolicies() {
    long n = policies.selectCount(null);
    if (n < EXPECTED_POLICIES) {
      log.warn("signature_policy 仅 {} 条（期望 {}），签名门禁可能失效；请检查 db/schema-p6.sql",
          n, EXPECTED_POLICIES);
    } else {
      log.info("signature_policy 已就绪：{} 条（启用 {} 条）", n,
          policies.selectCount(Wrappers.<SignaturePolicy>lambdaQuery()
              .eq(SignaturePolicy::getEnabled, true)));
    }
  }

  private void seedDemo() {
    LocalDate today = LocalDate.now();
    String day = today.format(YYMMDD);

    /* ---------- J2 · 内审（in_progress）+ 3 条分级发现项 ---------- */
    InternalAudit a = new InternalAudit();
    a.setId("AUDIT-" + today.getYear() + "-01");
    a.setTitle("2026 年度 FSSC 22000 体系内审（一车间）");
    a.setAuditType("SYSTEM");
    a.setScope("柠檬酸一车间全工序：发酵 / 提取 / 精制 / 包装，含 CCP、清场、批记录控制");
    a.setLead("admin");
    a.setTeam("[\"admin\",\"qc\",\"supervisor\"]");
    a.setPlanStart(today.minusDays(20));
    a.setPlanEnd(today.minusDays(5));
    a.setStatus("in_progress");
    a.setSite("SITE-01");
    a.setCreatedBy("admin");
    a.setCreatedAt(OffsetDateTime.now());
    a.setUpdatedAt(OffsetDateTime.now());
    audits.insert(a);

    AuditFinding f1 = finding(a.getId(), 1, "FSSC 22000 8.9.5", "MAJOR",
        "清场记录缺双人签字：CIP 记录单仅一名操作员签署", "一车间 · 精制工段", "supervisor");
    finding(a.getId(), 2, "FSSC 22000 8.5.4", "MINOR",
        "CCP 记录复核时间戳缺失（复核人字段为空）", "一车间 · 发酵工段", "qc");
    finding(a.getId(), 3, "FSSC 22000 7.2", "OBSERVATION",
        "岗位培训记录归档滞后约两周", "一车间", "operator");

    /* ---------- J1 · CAPA（源自内审发现项，进行中） ---------- */
    Capa c1 = new Capa();
    c1.setId("CAPA-" + day + "-001");
    c1.setSourceType("AUDIT_FINDING");
    c1.setSourceId(String.valueOf(f1.getId()));
    c1.setType("CORRECTIVE");
    c1.setTitle("[MAJOR] 清场记录缺双人签字");
    c1.setDescription("内审 " + a.getId() + " 发现项 #1（条款 FSSC 22000 8.9.5）");
    c1.setRootCause("CIP 记录单未设复核签名栏；SOP-CIP-03 未规定清场记录须双人签署，"
        + "操作员培训未覆盖记录完整性要求");
    c1.setOwner("supervisor");
    c1.setDueDate(today.plusDays(20));
    c1.setSeverity("major");
    c1.setStatus("in_progress");
    c1.setSite("SITE-01");
    c1.setRecordRevision(1);
    c1.setCreatedBy("admin");
    c1.setCreatedAt(OffsetDateTime.now());
    c1.setUpdatedAt(OffsetDateTime.now());
    capas.insert(c1);

    task(c1.getId(), 1, "隔离并复核 8 月全部 CIP 记录，补齐复核签署", "supervisor",
        today.plusDays(5), true);
    task(c1.getId(), 2, "修订 SOP-CIP-03 增设复核签名栏，并完成在岗培训与考核", "supervisor",
        today.plusDays(15), false);
    event(c1.getId(), null, "open", "创建 CAPA（内审发现项转办）");
    event(c1.getId(), "open", "in_progress", "责任人启动整改");

    f1.setCapaId(c1.getId());
    findings.updateById(f1);

    /* ---------- J1 · CAPA（手工来源且已逾期 → FR-5 演示） ---------- */
    Capa c2 = new Capa();
    c2.setId("CAPA-" + day + "-002");
    c2.setSourceType("MANUAL");
    c2.setType("PREVENTIVE");
    c2.setTitle("更换周期预防性调整（按压差趋势替代固定周期）");
    c2.setDescription("精制工段过滤网以时间周期更换，未结合压差趋势，存在提前堵塞导致非计划停机风险");
    c2.setRootCause("设备点检规范仅规定固定更换周期，未纳入压差趋势作为触发条件；"
        + "历史 3 次非计划停机中有 2 次为此原因");
    c2.setOwner("operator");
    c2.setDueDate(today.minusDays(3));      // 逾期 3 天
    c2.setSeverity("major");
    c2.setStatus("in_progress");
    c2.setSite("SITE-01");
    c2.setRecordRevision(1);
    c2.setCreatedBy("admin");
    c2.setCreatedAt(OffsetDateTime.now());
    c2.setUpdatedAt(OffsetDateTime.now());
    capas.insert(c2);
    task(c2.getId(), 1, "采集 3 个月压差数据，确定预警阈值并写入点检规范", "operator",
        today.minusDays(3), false);
    event(c2.getId(), null, "open", "创建 CAPA（手工）");
    event(c2.getId(), "open", "in_progress", "责任人启动整改");

    log.info("regtech seed done: audit={} findings=3 capa=2（含 1 条逾期）", a.getId());
  }

  private AuditFinding finding(String auditId, int seq, String clause, String severity,
      String description, String area, String owner) {
    AuditFinding f = new AuditFinding();
    f.setAuditId(auditId);
    f.setSeq(seq);
    f.setClause(clause);
    f.setSeverity(severity);
    f.setDescription(description);
    f.setArea(area);
    f.setOwner(owner);
    f.setCreatedBy("admin");
    f.setCreatedAt(OffsetDateTime.now());
    findings.insert(f);
    return f;
  }

  private void task(String capaId, int seq, String action, String owner, LocalDate due, boolean done) {
    CapaTask t = new CapaTask();
    t.setCapaId(capaId);
    t.setSeq(seq);
    t.setAction(action);
    t.setOwner(owner);
    t.setDueDate(due);
    t.setDone(done);
    if (done) {
      t.setDoneAt(OffsetDateTime.now());
      t.setDoneBy(owner);
      t.setEvidence("8 月 CIP 记录 12 份已复核，缺失签署 3 份已补签（附件：CIP-2026-08-review.pdf）");
    }
    t.setCreatedAt(OffsetDateTime.now());
    capaTasks.insert(t);
  }

  private void event(String capaId, String from, String to, String comment) {
    CapaEvent e = new CapaEvent();
    e.setCapaId(capaId);
    e.setFromStatus(from);
    e.setToStatus(to);
    e.setComment(comment);
    e.setOperator("admin");
    e.setCreatedAt(OffsetDateTime.now());
    capaEvents.insert(e);
  }
}
