package com.fluxmes.api.core;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxmes.api.alarm.AlarmService;
import com.fluxmes.api.batch.BatchService;
import com.fluxmes.api.entity.Alarm;
import com.fluxmes.api.entity.AppUser;
import com.fluxmes.api.entity.Batch;
import com.fluxmes.api.entity.Deviation;
import com.fluxmes.api.entity.SpcLimit;
import com.fluxmes.api.mapper.AlarmMapper;
import com.fluxmes.api.mapper.AppUserMapper;
import com.fluxmes.api.mapper.BatchMapper;
import com.fluxmes.api.mapper.DeviationMapper;
import com.fluxmes.api.mapper.SpcLimitMapper;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 数据库种子（幂等）：首次启动时把 mes.json 的批次/报警导入 PostgreSQL，
 * 并初始化四角色演示账号与 SPC 控制限配置。表非空则跳过，重复启动安全。
 */
@Component
@Order(100)
public class DatabaseSeeder implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(DatabaseSeeder.class);

  private final ObjectMapper json;
  private final AppUserMapper users;
  private final BatchMapper batches;
  private final AlarmMapper alarms;
  private final SpcLimitMapper spcLimits;
  private final DeviationMapper deviations;

  public DatabaseSeeder(ObjectMapper json, AppUserMapper users, BatchMapper batches,
      AlarmMapper alarms, SpcLimitMapper spcLimits, DeviationMapper deviations) {
    this.json = json;
    this.users = users;
    this.batches = batches;
    this.alarms = alarms;
    this.spcLimits = spcLimits;
    this.deviations = deviations;
  }

  @Override
  public void run(ApplicationArguments args) throws Exception {
    seedUsers();
    seedFromFixture();
    seedSpcLimits();
  }

  /** C4：四角色演示账号（BCrypt 哈希；生产环境必须删除或改密）。 */
  private void seedUsers() {
    if (users.selectCount(null) > 0) return;
    BCryptPasswordEncoder enc = new BCryptPasswordEncoder();
    record Seed(String u, String p, String name, String role) {}
    Seed[] seeds = {
        new Seed("admin", "admin123", "系统管理员", "ADMIN"),
        new Seed("supervisor", "super123", "王值班（值班长）", "SUPERVISOR"),
        new Seed("qc", "qc12345", "李质检（质检员）", "QC"),
        new Seed("operator", "op12345", "张工艺（工艺员）", "OPERATOR"),
    };
    for (Seed s : seeds) {
      AppUser u = new AppUser();
      u.setUsername(s.u());
      u.setPasswordHash(enc.encode(s.p()));
      u.setDisplayName(s.name());
      u.setRole(s.role());
      u.setEnabled(true);
      users.insert(u);
    }
    log.info("seeded {} demo users (admin/supervisor/qc/operator)", seeds.length);
  }

  /** mes.json → batch / alarm 表（仅当对应表为空）。 */
  private void seedFromFixture() throws Exception {
    Map<String, Object> root;
    try (InputStream in = new ClassPathResource("fixtures/mes.json").getInputStream()) {
      root = json.readValue(in, new TypeReference<>() {});
    }

    if (batches.selectCount(null) == 0) {
      for (Map<String, Object> b : asList(root.get("batches"))) {
        Batch e = new Batch();
        e.setId(str(b.get("id")));
        e.setProduct(str(b.get("product")));
        e.setRecipe(str(b.get("recipe")));
        e.setEquipment(str(b.get("equipment")));
        e.setStage(str(b.get("stage")));
        e.setStatus(str(b.get("status")));
        e.setParams(b.get("params") == null ? null : json.writeValueAsString(b.get("params")));
        e.setProgress(num(b.get("progress"), 0));
        e.setStartedAt(str(b.get("start")));
        e.setStages(b.get("stages") == null ? null : json.writeValueAsString(b.get("stages")));
        e.setMeta(b.get("meta") == null ? null : json.writeValueAsString(b.get("meta")));
        e.setReleased("done".equals(e.getStatus()));
        e.setCreatedAt(OffsetDateTime.now());
        e.setUpdatedAt(OffsetDateTime.now());
        batches.insert(e);
      }
      log.info("seeded batches from fixture");
    }

    if (alarms.selectCount(null) == 0) {
      for (Map<String, Object> a : asList(root.get("alarms"))) {
        Alarm e = new Alarm();
        e.setId(str(a.get("id")));
        e.setTime(str(a.get("time")));
        e.setLevel(str(a.get("level")));
        e.setSource(str(a.get("source")));
        e.setContent(str(a.get("content")));
        e.setValue(str(a.get("value")));
        e.setThreshold(str(a.get("threshold")));
        e.setStatus(str(a.get("status")));
        e.setSlaMinutes(AlarmService.defaultSla(str(a.get("level"))));  // D1 · SLA 时限按级别写入
        e.setEscalated(false);
        e.setSuppressionKey(str(a.get("source")) + "|" + str(a.get("content")));
        e.setCreatedAt(OffsetDateTime.now());
        alarms.insert(e);
      }
      log.info("seeded alarms from fixture");
    }
  }

  /** C5：SPC 控制限按产品/特性配置（替换前端/后端硬编码常量）。 */
  private void seedSpcLimits() {
    if (spcLimits.selectCount(null) > 0) return;
    SpcLimit l = new SpcLimit();
    l.setProduct("食品级一水柠檬酸");
    l.setFeature("柠檬酸含量");
    l.setUcl(new BigDecimal("99.82"));
    l.setCl(new BigDecimal("99.50"));
    l.setLcl(new BigDecimal("99.18"));
    l.setUnit("%");
    l.setStandardVersion("GB 1886.25—2016");
    l.setUpdatedBy("系统初始化");
    spcLimits.insert(l);
    log.info("seeded 1 spc limit");
  }

  @SuppressWarnings("unchecked")
  private static java.util.List<Map<String, Object>> asList(Object v) {
    return v instanceof java.util.List<?> l ? (java.util.List<Map<String, Object>>) l : java.util.List.of();
  }

  private static String str(Object v) {
    return v == null ? null : String.valueOf(v);
  }

  private static int num(Object v, int fallback) {
    return v instanceof Number n ? n.intValue() : fallback;
  }
}
