package com.fluxmes.alarm.bootstrap;

import com.fluxmes.alarm.domain.Alarm;
import com.fluxmes.alarm.domain.AlarmLevel;
import com.fluxmes.alarm.domain.AlarmStatus;
import com.fluxmes.alarm.repo.AlarmRepository;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * 首次启动播种示例报警（清禾基地 · 柠檬酸发酵产线）。
 * 当日 8 条对应原型 alarmData（近 6 小时内，保证趋势窗口可见）；
 * 另补 11 条历史，使「近 7 日高频源」复现排行：E-501×5 / F-102×4 / M-201×3 / D-701×2 / C-601×2。
 */
@Component
public class AlarmSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(AlarmSeeder.class);
    private static final DateTimeFormatter PREFIX = DateTimeFormatter.ofPattern("yyMMdd");

    private final AlarmRepository repository;

    public AlarmSeeder(AlarmRepository repository) {
        this.repository = repository;
    }

    @Override
    public void run(String... args) {
        if (repository.count() > 0) {
            log.info("报警数据已存在，跳过播种");
            return;
        }
        OffsetDateTime now = OffsetDateTime.now();
        String p = now.format(PREFIX);

        // —— 当日活跃窗口（分钟级回溯，进入 8h 趋势桶）——
        seed("A-" + p + "-017", now.minusMinutes(8), AlarmLevel.CRITICAL, "E-501 MVR 浓缩器",
                "加热蒸汽压力高高", "0.62 MPa", "≥ 0.60 MPa", AlarmStatus.UNACKED, null, "B-260826-01");
        seedAcked("A-" + p + "-016", now.minusMinutes(22), AlarmLevel.MAJOR, "F-102 发酵罐 #2",
                "溶氧浓度偏低", "DO 18%", "< 25%", "张伟", 6, "B-260826-01");
        seedAcked("A-" + p + "-015", now.minusMinutes(44), AlarmLevel.MINOR, "M-201 配料罐 #1",
                "搅拌电流波动", "32.5 → 38.1 A", "Δ > 5 A", "王芳", 5, null);
        seedAcked("A-" + p + "-014", now.minusMinutes(68), AlarmLevel.MAJOR, "C-601 结晶罐",
                "冷却水流量低", "14.2 m³/h", "< 16 m³/h", "李倩", 9, "B-260826-02");
        seedRecovered("A-" + p + "-013", now.minusMinutes(105), AlarmLevel.MINOR, "D-701 流化床干燥机",
                "出风温度偏差", "63.8°C", "> 63°C", 12);
        seedRecovered("A-" + p + "-012", now.minusMinutes(250), AlarmLevel.MAJOR, "F-103 发酵罐 #3",
                "温度变送器信号丢失", "—", "信号超时 30 s", 7);
        seedRecovered("A-" + p + "-011", now.minusMinutes(320), AlarmLevel.MINOR, "IX-401 离子交换柱",
                "柱压差偏高", "0.14 MPa", "> 0.12 MPa", 20);
        seedRecovered("A-" + p + "-010", now.minusMinutes(380), AlarmLevel.MINOR, "S-201 连消机",
                "灭菌温度瞬时波动", "119.6°C", "< 121°C", 4);

        // —— 历史补种（近 7 日，复现高频源排行）——
        histE501(p);
        histF102(p);
        histM201(p);
        histD701C601(p);

        log.info("报警数据播种完成，共 {} 条", repository.count());
    }

    private void histE501(String p) {
        seedRecovered("A-" + p + "-007", days(1, 3), AlarmLevel.CRITICAL, "E-501 MVR 浓缩器", "加热蒸汽压力高高", "0.61 MPa", "≥ 0.60 MPa", 30);
        seedRecovered("A-" + p + "-006", days(2, 5), AlarmLevel.MAJOR, "E-501 MVR 浓缩器", "真空度偏低", "-0.07 MPa", "< -0.08 MPa", 25);
        seedRecovered("A-" + p + "-005", days(4, 6), AlarmLevel.MAJOR, "E-501 MVR 浓缩器", "循环电机电流高", "128 A", "> 120 A", 40);
        seedRecovered("A-" + p + "-004", days(6, 2), AlarmLevel.MINOR, "E-501 MVR 浓缩器", "冷凝水温度偏高", "52°C", "> 50°C", 15);
    }

    private void histF102(String p) {
        seedRecovered("A-" + p + "-003", days(1, 9), AlarmLevel.MAJOR, "F-102 发酵罐 #2", "溶氧浓度偏低", "DO 22%", "< 25%", 18);
        seedRecovered("A-" + p + "-002", days(3, 11), AlarmLevel.MINOR, "F-102 发酵罐 #2", "罐温波动", "33.4°C", "± 0.5°C", 10);
        seedRecovered("A-" + p + "-001", days(5, 14), AlarmLevel.MAJOR, "F-102 发酵罐 #2", "搅拌功率异常", "18.2 kW", "> 17 kW", 22);
    }

    private void histM201(String p) {
        seedRecovered("A-" + p + "-000M1", days(2, 8), AlarmLevel.MINOR, "M-201 配料罐 #1", "液位偏低", "0.42 m", "< 0.5 m", 12);
        seedRecovered("A-" + p + "-000M2", days(4, 16), AlarmLevel.MINOR, "M-201 配料罐 #1", "搅拌电流波动", "33.1 → 38.6 A", "Δ > 5 A", 8);
    }

    private void histD701C601(String p) {
        seedRecovered("A-" + p + "-000D1", days(3, 7), AlarmLevel.MINOR, "D-701 流化床干燥机", "出风温度偏差", "63.5°C", "> 63°C", 14);
        seedRecovered("A-" + p + "-000C1", days(5, 20), AlarmLevel.MAJOR, "C-601 结晶罐", "冷却水流量低", "15.1 m³/h", "< 16 m³/h", 19);
    }

    private OffsetDateTime days(int d, int h) {
        return OffsetDateTime.now().minusDays(d).withHour(h).withMinute((h * 7) % 60).withSecond(0).withNano(0);
    }

    private void seed(String id, OffsetDateTime at, AlarmLevel level, String source, String content,
                      String value, String threshold, AlarmStatus status, String ackBy, String batch) {
        Alarm a = new Alarm(id, at, level, source, content, value, threshold, status);
        a.setRelatedBatch(batch);
        a.addAudit("CREATE", null, status, "系统", "报警接入（SCADA/OPC-UA 适配器模拟）");
        if (status == AlarmStatus.ACKED && ackBy != null) {
            a.setAckBy(ackBy);
            a.setAckAt(at.plusMinutes(6));
            a.addAudit("ACK", AlarmStatus.UNACKED, AlarmStatus.ACKED, ackBy, null);
        }
        repository.save(a);
    }

    private void seedAcked(String id, OffsetDateTime at, AlarmLevel level, String source, String content,
                           String value, String threshold, String ackBy, long respMin, String batch) {
        Alarm a = new Alarm(id, at, level, source, content, value, threshold, AlarmStatus.ACKED);
        a.setRelatedBatch(batch);
        a.addAudit("CREATE", null, AlarmStatus.UNACKED, "系统", "报警接入（SCADA/OPC-UA 适配器模拟）");
        a.setAckBy(ackBy);
        a.setAckAt(at.plusMinutes(respMin));
        a.addAudit("ACK", AlarmStatus.UNACKED, AlarmStatus.ACKED, ackBy, null);
        repository.save(a);
    }

    private void seedRecovered(String id, OffsetDateTime at, AlarmLevel level, String source, String content,
                               String value, String threshold, long recoverMin) {
        Alarm a = new Alarm(id, at, level, source, content, value, threshold, AlarmStatus.RECOVERED);
        a.addAudit("CREATE", null, AlarmStatus.UNACKED, "系统", "报警接入（SCADA/OPC-UA 适配器模拟）");
        a.setRecoveredAt(at.plusMinutes(recoverMin));
        a.addAudit("RECOVER", AlarmStatus.UNACKED, AlarmStatus.RECOVERED, "系统", "工况恢复");
        repository.save(a);
    }
}
