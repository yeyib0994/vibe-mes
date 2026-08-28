package com.fluxmes.alarm.bootstrap;

import com.fluxmes.alarm.domain.AlarmLevel;
import com.fluxmes.alarm.dto.AlarmDto;
import com.fluxmes.alarm.dto.AlarmEvent;
import com.fluxmes.alarm.service.AlarmNotifier;
import com.fluxmes.alarm.service.AlarmService;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * SCADA / OPC-UA 报警流的<b>模拟适配器</b>（任务 T5）：周期性产生新报警、持久化并经 SSE 推送前端，
 * 用于验证「报警触发 ≤ 刷新周期可见」（NFR-1）。生产替换为真实 OPC-UA 订阅即可，边界不变。
 * 由 {@code fluxmes.alarm.simulator.enabled} 控制开关（默认开，便于演示）。
 */
@Component
@ConditionalOnProperty(name = "fluxmes.alarm.simulator.enabled", havingValue = "true", matchIfMissing = true)
public class AlarmSimulator {

    private static final Logger log = LoggerFactory.getLogger(AlarmSimulator.class);

    private record Feed(AlarmLevel level, String source, String content, String value, String threshold, String batch) {
    }

    // 与产线一致的一组模拟报警（设备编码与原型对齐，便于高频源与趋势延续）
    private static final Feed[] FEEDS = {
            new Feed(AlarmLevel.MAJOR, "E-501 MVR 浓缩器", "加热蒸汽压力高", "0.58 MPa", "≥ 0.55 MPa", "B-260826-01"),
            new Feed(AlarmLevel.MINOR, "F-102 发酵罐 #2", "溶氧浓度偏低", "DO 23%", "< 25%", "B-260826-01"),
            new Feed(AlarmLevel.MAJOR, "C-601 结晶罐", "冷却水流量低", "15.0 m³/h", "< 16 m³/h", "B-260826-02"),
            new Feed(AlarmLevel.MINOR, "D-701 流化床干燥机", "出风温度偏差", "63.4°C", "> 63°C", null),
            new Feed(AlarmLevel.CRITICAL, "F-103 发酵罐 #3", "罐压高高", "0.21 MPa", "≥ 0.20 MPa", "B-260826-03"),
            new Feed(AlarmLevel.MINOR, "IX-401 离子交换柱", "柱压差偏高", "0.13 MPa", "> 0.12 MPa", null),
            new Feed(AlarmLevel.MAJOR, "M-201 配料罐 #1", "搅拌电流波动", "34.0 → 39.5 A", "Δ > 5 A", null),
    };

    private final AlarmService service;
    private final AlarmNotifier notifier;
    private final Random random = new Random();

    public AlarmSimulator(AlarmService service, AlarmNotifier notifier) {
        this.service = service;
        this.notifier = notifier;
    }

    @Scheduled(initialDelayString = "${fluxmes.alarm.simulator.initial-delay-ms:15000}",
            fixedDelayString = "${fluxmes.alarm.simulator.interval-ms:25000}")
    public void emit() {
        Feed f = FEEDS[random.nextInt(FEEDS.length)];
        AlarmDto dto = service.create(f.level(), f.source(), f.content(), f.value(), f.threshold(), f.batch());
        notifier.publish(AlarmEvent.created(dto));
        log.info("模拟 SCADA 报警接入：{} [{}] {}", dto.id(), dto.level().label(), dto.source());
    }
}
