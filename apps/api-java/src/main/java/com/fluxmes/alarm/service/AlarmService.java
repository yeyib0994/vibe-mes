package com.fluxmes.alarm.service;

import com.fluxmes.alarm.domain.Alarm;
import com.fluxmes.alarm.domain.AlarmLevel;
import com.fluxmes.alarm.domain.AlarmStatus;
import com.fluxmes.alarm.dto.AlarmDto;
import com.fluxmes.alarm.dto.AlarmListResponse;
import com.fluxmes.alarm.dto.AlarmSourceStat;
import com.fluxmes.alarm.dto.AlarmStats;
import com.fluxmes.alarm.dto.AlarmTrendPoint;
import com.fluxmes.alarm.exception.AlarmNotFoundException;
import com.fluxmes.alarm.exception.IllegalAlarmTransitionException;
import com.fluxmes.alarm.repo.AlarmRepository;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 报警核心服务：状态机流转、确认/恢复审计、实时接入与趋势/高频源/统计聚合。
 */
@Service
public class AlarmService {

    /** 示例产线部署于中国时区（清禾基地）。 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter HOUR_LABEL = DateTimeFormatter.ofPattern("HH:00");
    private static final DateTimeFormatter ID_PREFIX = DateTimeFormatter.ofPattern("yyMMdd");

    private final AlarmRepository repository;

    /** 当日报警序号发生器（首次访问时以现存总数初始化，用于生成 A-YYMMDD-NNN）。 */
    private final AtomicLong sequence = new AtomicLong(-1);

    public AlarmService(AlarmRepository repository) {
        this.repository = repository;
    }

    /** 报警列表 + 生成时间 + 未确认计数（spec FR-1/7、NFR-1）。 */
    @Transactional(readOnly = true)
    public AlarmListResponse list() {
        List<Alarm> alarms = repository.findAllByOrderByTriggeredAtDesc();
        long unacked = repository.countByStatus(AlarmStatus.UNACKED);
        return new AlarmListResponse(
                OffsetDateTime.now(),
                unacked,
                alarms.stream().map(a -> AlarmDto.from(a, ZONE)).toList());
    }

    /** 未确认实时计数（侧栏 badge 与顶栏铃铛来源唯一，spec FR-7）。 */
    @Transactional(readOnly = true)
    public long unackedCount() {
        return repository.countByStatus(AlarmStatus.UNACKED);
    }

    /**
     * 确认报警（spec FR-3/FR-10，任务 T3）：状态机守卫 unacked→acked，记录确认人/时间并写审计。
     * 非未确认态拒绝确认（风险 R2/R3：状态机守卫，恢复即停计时）。
     */
    @Transactional
    public AlarmDto ack(String id, String operator) {
        Alarm alarm = repository.findById(id).orElseThrow(() -> new AlarmNotFoundException(id));
        AlarmStatus from = alarm.getStatus();
        if (!from.canTransitionTo(AlarmStatus.ACKED)) {
            throw new IllegalAlarmTransitionException(from, AlarmStatus.ACKED);
        }
        OffsetDateTime now = OffsetDateTime.now();
        alarm.setStatus(AlarmStatus.ACKED);
        alarm.setAckBy(operator);
        alarm.setAckAt(now);
        alarm.addAudit("ACK", from, AlarmStatus.ACKED, operator, null);
        Alarm saved = repository.save(alarm);
        return AlarmDto.from(saved, ZONE);
    }

    /**
     * 恢复报警（spec FR-4，任务 T4）：状态机守卫进入 RECOVERED，记录恢复时间并写审计。
     * 支持系统自动恢复（{@code automatic=true}，操作人记为「系统」）与值班人员人工标记。
     * 恢复即停止 SLA 计时（风险 R3）。
     */
    @Transactional
    public AlarmDto recover(String id, String operator, boolean automatic) {
        Alarm alarm = repository.findById(id).orElseThrow(() -> new AlarmNotFoundException(id));
        AlarmStatus from = alarm.getStatus();
        if (!from.canTransitionTo(AlarmStatus.RECOVERED)) {
            throw new IllegalAlarmTransitionException(from, AlarmStatus.RECOVERED);
        }
        alarm.setStatus(AlarmStatus.RECOVERED);
        alarm.setRecoveredAt(OffsetDateTime.now());
        alarm.addAudit("RECOVER", from, AlarmStatus.RECOVERED,
                automatic ? "系统" : operator, automatic ? "工况恢复正常" : "人工标记恢复");
        Alarm saved = repository.save(alarm);
        return AlarmDto.from(saved, ZONE);
    }

    /**
     * 接入新报警（spec FR-8，任务 T5）：SCADA/OPC-UA 适配器产生的报警以未确认态入库并生成审计起点。
     */
    @Transactional
    public AlarmDto create(AlarmLevel level, String source, String content,
                           String value, String threshold, String relatedBatch) {
        Alarm alarm = new Alarm(nextAlarmId(), OffsetDateTime.now(), level, source,
                content, value, threshold, AlarmStatus.UNACKED);
        alarm.setRelatedBatch(relatedBatch);
        alarm.addAudit("CREATE", null, AlarmStatus.UNACKED, "SCADA", "实时报警接入（OPC-UA 适配器）");
        Alarm saved = repository.save(alarm);
        return AlarmDto.from(saved, ZONE);
    }

    private String nextAlarmId() {
        if (sequence.get() < 0) {
            sequence.compareAndSet(-1, repository.count());
        }
        long n = sequence.incrementAndGet();
        String prefix = OffsetDateTime.now().format(ID_PREFIX);
        return String.format("A-%s-%03d", prefix, n % 1000);
    }

    /** 报警趋势（spec FR-5）：最近 {@code hours} 小时按小时、按级别堆叠。 */
    @Transactional(readOnly = true)
    public List<AlarmTrendPoint> trend(int hours) {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime from = now.minusHours(hours - 1L).truncatedTo(ChronoUnit.HOURS);
        List<Alarm> inRange = repository.findByTriggeredAtBetween(from, now);

        // 预置连续小时桶（含零值），保证前端堆叠柱等距
        Map<String, long[]> buckets = new LinkedHashMap<>();
        for (int i = 0; i < hours; i++) {
            String label = from.plusHours(i).atZoneSameInstant(ZONE).format(HOUR_LABEL);
            buckets.put(label, new long[3]);
        }
        for (Alarm a : inRange) {
            String label = a.getTriggeredAt().atZoneSameInstant(ZONE).truncatedTo(ChronoUnit.HOURS).format(HOUR_LABEL);
            long[] cell = buckets.get(label);
            if (cell == null) {
                continue;
            }
            switch (a.getLevel()) {
                case CRITICAL -> cell[0]++;
                case MAJOR -> cell[1]++;
                case MINOR -> cell[2]++;
            }
        }
        List<AlarmTrendPoint> out = new ArrayList<>();
        buckets.forEach((t, c) -> out.add(new AlarmTrendPoint(t, c[0], c[1], c[2])));
        return out;
    }

    /** 高频报警源（spec FR-6）：近 {@code days} 日按设备聚合降序。 */
    @Transactional(readOnly = true)
    public List<AlarmSourceStat> topSources(int days, int limit) {
        OffsetDateTime from = OffsetDateTime.now().minusDays(days);
        return repository.findTopSources(from).stream()
                .limit(limit)
                .map(sc -> new AlarmSourceStat(sc.getSource(), sc.getCnt()))
                .toList();
    }

    /** KPI 统计（今日报警/活跃/平均响应/确认率）。 */
    @Transactional(readOnly = true)
    public AlarmStats stats() {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime todayStart = LocalDate.now(ZONE).atStartOfDay(ZONE).toOffsetDateTime();
        List<Alarm> todays = repository.findByTriggeredAtBetween(todayStart, now);

        long todayTotal = todays.size();
        long unacked = repository.countByStatus(AlarmStatus.UNACKED);
        long unackedCritical = todays.stream()
                .filter(a -> a.getStatus() == AlarmStatus.UNACKED && a.getLevel() == AlarmLevel.CRITICAL)
                .count();
        long active = repository.findAll().stream().filter(a -> a.getStatus().active()).count();

        // 平均响应时长：已确认/已恢复且记录了确认时间的报警，触发→确认均值
        List<Alarm> responded = repository.findAll().stream()
                .filter(a -> a.getAckAt() != null)
                .toList();
        Double avgResponseMinutes = null;
        if (!responded.isEmpty()) {
            double total = responded.stream()
                    .mapToDouble(a -> Duration.between(a.getTriggeredAt(), a.getAckAt()).toSeconds() / 60.0)
                    .sum();
            avgResponseMinutes = Math.round(total / responded.size() * 10) / 10.0;
        }

        long ackedToday = todays.stream().filter(a -> a.getStatus() != AlarmStatus.UNACKED).count();
        int ackRate = todayTotal == 0 ? 0 : (int) Math.round(ackedToday * 100.0 / todayTotal);

        return new AlarmStats(todayTotal, active, unacked, unackedCritical, avgResponseMinutes, ackRate);
    }

    public Optional<Alarm> findById(String id) {
        return repository.findById(id);
    }
}
