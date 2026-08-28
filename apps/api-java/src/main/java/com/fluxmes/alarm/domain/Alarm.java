package com.fluxmes.alarm.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 报警记录。工业级持久化（spec FR-3/4/8/10/11/12）：状态机、确认人/时间、恢复时间、
 * 关联批次与不可篡改审计轨迹（constitution C3/C6）。
 */
@Entity
@Table(name = "alarm")
public class Alarm {

    /** 业务报警编号，形如 A-260826-017（A-YYMMDD-NNN）。 */
    @Id
    @Column(length = 32)
    private String id;

    @Column(name = "triggered_at", nullable = false)
    private OffsetDateTime triggeredAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AlarmLevel level;

    /** 来源设备编码，如 E-501 MVR 浓缩器（FR-11 可跳转设备监控）。 */
    @Column(nullable = false, length = 128)
    private String source;

    @Column(nullable = false, length = 256)
    private String content;

    @Column(length = 64)
    private String value;

    @Column(length = 64)
    private String threshold;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AlarmStatus status;

    @Column(name = "ack_by", length = 64)
    private String ackBy;

    @Column(name = "ack_at")
    private OffsetDateTime ackAt;

    @Column(name = "recovered_at")
    private OffsetDateTime recoveredAt;

    @Column(name = "related_batch", length = 64)
    private String relatedBatch;

    @OneToMany(mappedBy = "alarm", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("occurredAt ASC")
    private List<AlarmAudit> audit = new ArrayList<>();

    protected Alarm() {
    }

    public Alarm(String id, OffsetDateTime triggeredAt, AlarmLevel level, String source,
                 String content, String value, String threshold, AlarmStatus status) {
        this.id = id;
        this.triggeredAt = triggeredAt;
        this.level = level;
        this.source = source;
        this.content = content;
        this.value = value;
        this.threshold = threshold;
        this.status = status;
    }

    /**
     * 记录一条审计（who/when/what/before/after，constitution C3）。审计仅追加，不可篡改。
     */
    public void addAudit(String action, AlarmStatus from, AlarmStatus to, String operator, String note) {
        audit.add(new AlarmAudit(this, action, from, to, operator, OffsetDateTime.now(), note));
    }

    // getters / setters
    public String getId() { return id; }
    public OffsetDateTime getTriggeredAt() { return triggeredAt; }
    public AlarmLevel getLevel() { return level; }
    public String getSource() { return source; }
    public String getContent() { return content; }
    public String getValue() { return value; }
    public String getThreshold() { return threshold; }
    public AlarmStatus getStatus() { return status; }
    public void setStatus(AlarmStatus status) { this.status = status; }
    public String getAckBy() { return ackBy; }
    public void setAckBy(String ackBy) { this.ackBy = ackBy; }
    public OffsetDateTime getAckAt() { return ackAt; }
    public void setAckAt(OffsetDateTime ackAt) { this.ackAt = ackAt; }
    public OffsetDateTime getRecoveredAt() { return recoveredAt; }
    public void setRecoveredAt(OffsetDateTime recoveredAt) { this.recoveredAt = recoveredAt; }
    public String getRelatedBatch() { return relatedBatch; }
    public void setRelatedBatch(String relatedBatch) { this.relatedBatch = relatedBatch; }
    public List<AlarmAudit> getAudit() { return audit; }
}
