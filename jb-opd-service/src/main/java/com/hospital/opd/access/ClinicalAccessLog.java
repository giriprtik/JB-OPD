package com.hospital.opd.access;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** One row per clinical read: answers "who looked at this patient's record". */
@Entity
@Table(name = "clinical_access_log")
public class ClinicalAccessLog {

    public enum Action { READ_CLINICAL_NOTES, READ_LAB_RESULTS, READ_LAB_REPORT, BREAK_GLASS }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "visit_id", nullable = false, updatable = false)
    private Long visitId;

    @Column(name = "username", nullable = false, length = 50, updatable = false)
    private String username;

    @Column(name = "action", nullable = false, length = 30, updatable = false)
    private String action;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private OffsetDateTime occurredAt;

    protected ClinicalAccessLog() {}

    public ClinicalAccessLog(Long visitId, String username, Action action, OffsetDateTime occurredAt) {
        this.visitId = visitId;
        this.username = username;
        this.action = action.name();
        this.occurredAt = occurredAt;
    }

    public Long getId() { return id; }
    public Long getVisitId() { return visitId; }
    public String getUsername() { return username; }
    public String getAction() { return action; }
    public OffsetDateTime getOccurredAt() { return occurredAt; }
}
