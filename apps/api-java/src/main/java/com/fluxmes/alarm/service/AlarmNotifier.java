package com.fluxmes.alarm.service;

import com.fluxmes.alarm.dto.AlarmEvent;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 报警实时推送（SSE）：维护在线订阅者，向所有前端广播报警状态变更事件（spec NFR-1、plan WebSocket/SSE）。
 * 生产可替换为 WebSocket/STOMP 或接入 Kafka 消费者做水平扩展；此处用 SSE 便于经网关/代理直连。
 */
@Component
public class AlarmNotifier {

    private static final Logger log = LoggerFactory.getLogger(AlarmNotifier.class);
    private static final long TIMEOUT_MS = 30 * 60 * 1000L; // 30 分钟，前端 EventSource 会自动重连

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> {
            emitter.complete();
            emitters.remove(emitter);
        });
        emitter.onError(e -> emitters.remove(emitter));
        emitters.add(emitter);
        try {
            emitter.send(SseEmitter.event().name("connected").data("ok"));
        } catch (IOException ex) {
            emitters.remove(emitter);
        }
        log.debug("SSE 订阅者接入，当前在线 {}", emitters.size());
        return emitter;
    }

    public void publish(AlarmEvent event) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("alarm").data(event));
            } catch (IOException ex) {
                emitters.remove(emitter);
            }
        }
    }

    public int online() {
        return emitters.size();
    }
}
