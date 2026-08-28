package com.fluxmes.alarm.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Set;

/**
 * 报警生命期状态机（spec 第 5 节）：
 * <pre>
 *   unacked  → acked     （人工确认，FR-3）
 *   unacked  → recovered （自动恢复且未确认也须留存，FR-4）
 *   acked    → recovered （工况恢复）
 *   acked    → closed    （人工结案）
 *   recovered→ closed    （归档关闭）
 * </pre>
 * 终态：closed。恢复即停止 SLA 计时（风险 R3）。
 */
public enum AlarmStatus {
    UNACKED("未确认"),
    ACKED("已确认"),
    RECOVERED("已恢复"),
    CLOSED("已关闭");

    private final String label;

    AlarmStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** 是否仍在告警中（活跃）：未确认或已确认但未恢复/关闭。 */
    public boolean active() {
        return this == UNACKED || this == ACKED;
    }

    public boolean canTransitionTo(AlarmStatus next) {
        return transitions().contains(next);
    }

    private Set<AlarmStatus> transitions() {
        return switch (this) {
            case UNACKED -> Set.of(ACKED, RECOVERED);
            case ACKED -> Set.of(RECOVERED, CLOSED);
            case RECOVERED -> Set.of(CLOSED);
            case CLOSED -> Set.of();
        };
    }

    @JsonValue
    public String wire() {
        return name().toLowerCase();
    }

    @JsonCreator
    public static AlarmStatus from(String value) {
        return AlarmStatus.valueOf(value.trim().toUpperCase());
    }
}
