package com.fluxmes.alarm.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * 报警审计轨迹（constitution C3）。记录 who/when/what/before/after，仅追加不可篡改，保留 ≥ 3 年（C6）。
 */
@Entity
@Table(name = "alarm_audit")
public class AlarmAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "alarm_id", nullable = false)
    private Alarm alarm;

    /** 动作类型，如 ACK / RECOVER / CLOSE / CREATE。 */
    @Column(nullable = false, length = 32)
    private String action;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 16)
    private AlarmStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", length = 16)
    private AlarmStatus toStatus;

    @Column(length = 64)
    private String operator;

    @Column(name = "occurred_at", nullable = false)
    private OffsetDateTime occurredAt;

    @Column(length = 256)
    private String note;

    protected AlarmAudit() {
    }

    public AlarmAudit(Alarm alarm, String action, AlarmStatus fromStatus, AlarmStatus toStatus,
                      String operator, OffsetDateTime occurredAt, String note) {
        this.alarm = alarm;
        this.action = action;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.operator = operator;
        this.occurredAt = occurredAt;
        this.note = note;
    }

    public Long getId() { return id; }
    public String getAction() { return action; }
    public AlarmStatus getFromStatus() { return fromStatus; }
    public AlarmStatus getToStatus() { return toStatus; }
    public String getOperator() { return operator; }
    public OffsetDateTime getOccurredAt() { return occurredAt; }
    public String getNote() { return note; }
}
