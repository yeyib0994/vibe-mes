package com.fluxmes.api.core;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fluxmes.api.entity.AppUser;
import com.fluxmes.api.entity.Batch;
import com.fluxmes.api.entity.PersonCertificate;
import com.fluxmes.api.entity.WeighingItem;
import com.fluxmes.api.entity.WeighingTask;
import com.fluxmes.api.mapper.AppUserMapper;
import com.fluxmes.api.mapper.BatchMapper;
import com.fluxmes.api.mapper.PersonCertificateMapper;
import com.fluxmes.api.mapper.WeighingItemMapper;
import com.fluxmes.api.mapper.WeighingTaskMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Phase G（P3）种子（幂等）：
 * <ul>
 *   <li>G2 人员资质与健康证：四角色演示账号的能力项与健康证，外加一名资质过期的
 *       临时工账号（temp/temp123，SITE-02），用于演示资质门禁与到期预警；</li>
 *   <li>G3 称量配料：为在产批次创建一条称量任务与合格称量记录。</li>
 * </ul>
 */
@Component
@Order(120)
public class P3Seeder implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(P3Seeder.class);
  private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyMMdd");

  private final AppUserMapper users;
  private final PersonCertificateMapper certs;
  private final BatchMapper batches;
  private final WeighingTaskMapper weighingTasks;
  private final WeighingItemMapper weighingItems;

  public P3Seeder(AppUserMapper users, PersonCertificateMapper certs, BatchMapper batches,
      WeighingTaskMapper weighingTasks, WeighingItemMapper weighingItems) {
    this.users = users;
    this.certs = certs;
    this.batches = batches;
    this.weighingTasks = weighingTasks;
    this.weighingItems = weighingItems;
  }

  @Override
  public void run(ApplicationArguments args) {
    seedTempUser();
    seedCertificates();
    seedWeighing();
  }

  /** G4 演示：滨海工厂的操作工账号（资质过期，用于门禁演示）。 */
  private void seedTempUser() {
    if (users.selectCount(Wrappers.<AppUser>lambdaQuery().eq(AppUser::getUsername, "temp")) > 0) return;
    AppUser u = new AppUser();
    u.setUsername("temp");
    u.setPasswordHash(new BCryptPasswordEncoder().encode("temp123"));
    u.setDisplayName("赵临时（外包操作工）");
    u.setRole("OPERATOR");
    u.setEnabled(true);
    u.setSiteCode("SITE-02");
    u.setCreatedAt(OffsetDateTime.now());
    users.insert(u);
    log.info("seeded demo user temp/temp123 (SITE-02, 资质过期演示)");
  }

  private void seedCertificates() {
    if (certs.selectCount(null) > 0) return;
    LocalDate today = LocalDate.now();

    // 健康证（年度体检）：admin 25 天后到期 → 触发到期预警
    health("admin", "系统管理员", today.plusDays(25));
    health("supervisor", "王值班（值班长）", today.plusDays(200));
    health("qc", "李质检（质检员）", today.plusDays(180));
    health("operator", "张工艺（工艺员）", today.plusDays(150));
    health("temp", "赵临时（外包操作工）", today.minusDays(40));   // 已过期 → 门禁拦截

    // 岗位资质（能力项）
    qual("admin", "系统管理员", "RELEASE", today.plusDays(400));
    qual("admin", "系统管理员", "BATCH_REVIEW", today.plusDays(400));
    qual("admin", "系统管理员", "CCP_MONITOR", today.plusDays(400));
    qual("admin", "系统管理员", "WEIGHING", today.plusDays(400));
    qual("admin", "系统管理员", "SANITATION", today.plusDays(400));
    qual("admin", "系统管理员", "LAB_TEST", today.plusDays(400));

    qual("supervisor", "王值班（值班长）", "WEIGHING", today.plusDays(300));
    qual("supervisor", "王值班（值班长）", "CCP_MONITOR", today.plusDays(300));
    qual("supervisor", "王值班（值班长）", "SANITATION", today.plusDays(300));
    qual("supervisor", "王值班（值班长）", "BATCH_REVIEW", today.plusDays(300));

    qual("qc", "李质检（质检员）", "LAB_TEST", today.plusDays(250));
    qual("qc", "李质检（质检员）", "RELEASE", today.plusDays(250));
    qual("qc", "李质检（质检员）", "BATCH_REVIEW", today.plusDays(250));
    qual("qc", "李质检（质检员）", "CCP_MONITOR", today.plusDays(250));
    qual("qc", "李质检（质检员）", "SANITATION", today.plusDays(250));

    qual("operator", "张工艺（工艺员）", "WEIGHING", today.plusDays(220));
    qual("operator", "张工艺（工艺员）", "CCP_MONITOR", today.plusDays(220));
    qual("operator", "张工艺（工艺员）", "SANITATION", today.plusDays(220));

    log.info("seeded {} person certificates (健康证 5 / 岗位资质 19)", certs.selectCount(null));
  }

  private void health(String username, String displayName, LocalDate validUntil) {
    PersonCertificate c = new PersonCertificate();
    c.setUsername(username);
    c.setDisplayName(displayName);
    c.setCertType("HEALTH");
    c.setCertName("食品从业人员健康证");
    c.setCertNo("JK-" + username.toUpperCase() + "-2026");
    c.setIssuedBy("泰州市疾病预防控制中心");
    c.setIssuedAt(validUntil.minusYears(1));
    c.setValidUntil(validUntil);
    c.setStatus(validUntil.isBefore(LocalDate.now()) ? "EXPIRED" : "VALID");
    c.setRemark("年度健康检查（有效期 1 年）");
    c.setCreatedAt(OffsetDateTime.now());
    certs.insert(c);
  }

  private void qual(String username, String displayName, String capability, LocalDate validUntil) {
    PersonCertificate c = new PersonCertificate();
    c.setUsername(username);
    c.setDisplayName(displayName);
    c.setCertType("QUALIFICATION");
    c.setCertName(capabilityName(capability) + "岗位资质");
    c.setCapability(capability);
    c.setCertNo("ZZ-" + capability + "-" + username.toUpperCase());
    c.setIssuedBy("清禾生物 · 人力资源部");
    c.setIssuedAt(validUntil.minusYears(2));
    c.setValidUntil(validUntil);
    c.setStatus("VALID");
    c.setRemark("岗位能力确认 + 定期复审（2 年）");
    c.setCreatedAt(OffsetDateTime.now());
    certs.insert(c);
  }

  private void seedWeighing() {
    if (weighingTasks.selectCount(null) > 0) return;
    List<Batch> running = batches.selectList(
        Wrappers.<Batch>lambdaQuery().eq(Batch::getStatus, "running").orderByAsc(Batch::getId));
    List<Batch> src = running.isEmpty()
        ? batches.selectList(Wrappers.<Batch>lambdaQuery().orderByAsc(Batch::getId)) : running;
    if (src.isEmpty()) return;
    Batch b = src.get(0);

    String id = "WT-" + LocalDate.now().format(YYMMDD) + "-001";
    WeighingTask t = new WeighingTask();
    t.setId(id);
    t.setBatchId(b.getId());
    t.setMaterialCode("RM-001");
    t.setMaterialName("玉米淀粉");
    t.setTargetQty(new BigDecimal("500.000"));
    t.setUnit("kg");
    t.setTolerancePct(new BigDecimal("1.000"));
    t.setTotalWeighed(new BigDecimal("500.200"));
    t.setStatus("OPEN");
    t.setCreatedBy("张工艺");
    t.setCreatedAt(OffsetDateTime.now());
    weighingTasks.insert(t);

    WeighingItem i1 = new WeighingItem();
    i1.setTaskId(id);
    i1.setSeq(1);
    i1.setActualQty(new BigDecimal("500.200"));
    i1.setDeviationPct(new BigDecimal("0.040"));
    i1.setResult("PASS");
    i1.setOperator("张工艺");
    i1.setEquipment("SCALE-M201");
    i1.setWeighedAt(OffsetDateTime.now().minusHours(3));
    i1.setRemark("自动配料系统首秤");
    weighingItems.insert(i1);

    log.info("seeded weighing task {} for batch {}", id, b.getId());
  }

  private static String capabilityName(String code) {
    return switch (code) {
      case "WEIGHING" -> "配料称量";
      case "CCP_MONITOR" -> "CCP 监控";
      case "RELEASE" -> "成品放行";
      case "BATCH_REVIEW" -> "批记录复核";
      case "SANITATION" -> "清场作业";
      case "LAB_TEST" -> "理化检验";
      default -> code;
    };
  }
}
