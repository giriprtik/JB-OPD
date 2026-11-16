package com.hospital.opd.access;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** A time-limited break-glass grant for one doctor on one visit. */
@Entity
@Table(name = "access_grant")
public class AccessGrant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "visit_id", nullable = false, updatable = false)
    private Long visitId;

    @Column(name = "username", nullable = false, length = 50, updatable = false)
    private String username;

    @Column(name = "reason", nullable = false, updatable = false)
    private String reason;

    @Column(name = "granted_at", nullable = false, updatable = false)
    private OffsetDateTime grantedAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private OffsetDateTime expiresAt;

    protected AccessGrant() {}

    public AccessGrant(Long visitId, String username, String reason, OffsetDateTime grantedAt, OffsetDateTime expiresAt) {
        this.visitId = visitId;
        this.username = username;
        this.reason = reason;
        this.grantedAt = grantedAt;
        this.expiresAt = expiresAt;
    }

    public Long getId() { return id; }
    public Long getVisitId() { return visitId; }
    public String getUsername() { return username; }
    public String getReason() { return reason; }
    public OffsetDateTime getGrantedAt() { return grantedAt; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
}
