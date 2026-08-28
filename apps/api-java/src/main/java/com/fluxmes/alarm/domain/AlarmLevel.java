package com.fluxmes.alarm.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 报警级别。优先级 critical &gt; major &gt; minor（spec 第 5 节）。
 * critical 必须人工确认，不可自动恢复后静默。
 * 线上契约使用小写 token（critical/major/minor），与前端 AlarmLevel 联合类型一致。
 */
public enum AlarmLevel {
    CRITICAL("严重"),
    MAJOR("重要"),
    MINOR("一般");

    private final String label;

    AlarmLevel(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    @JsonValue
    public String wire() {
        return name().toLowerCase();
    }

    @JsonCreator
    public static AlarmLevel from(String value) {
        return AlarmLevel.valueOf(value.trim().toUpperCase());
    }
}
