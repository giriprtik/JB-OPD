package com.hospital.opd.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "outbox_message")
public class OutboxMessage {

    public static final String LAB_ORDER_REQUESTED = "LAB_ORDER_REQUESTED";
    private static final int MAX_ERROR_LENGTH = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_id", nullable = false, unique = true, updatable = false)
    private UUID requestId;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private Long aggregateId;

    @Column(name = "message_type", nullable = false, length = 40, updatable = false)
    private String messageType;

    /** JSON document (serialised LabOrderPayload). */
    @Column(name = "payload", nullable = false, columnDefinition = "TEXT", updatable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    private OutboxStatus status = OutboxStatus.PENDING;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private OffsetDateTime nextAttemptAt;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "sent_at")
    private OffsetDateTime sentAt;

    protected OutboxMessage() {}

    public OutboxMessage(UUID requestId, Long aggregateId, String messageType, String payload, OffsetDateTime now) {
        this.requestId = requestId;
        this.aggregateId = aggregateId;
        this.messageType = messageType;
        this.payload = payload;
        this.createdAt = now;
        this.nextAttemptAt = now;
    }

    public void markSent(OffsetDateTime now) {
        this.status = OutboxStatus.SENT;
        this.sentAt = now;
        this.attempts++;
        this.lastError = null;
    }

    /** One more failed attempt; either schedule a retry or give up for good. */
    public void recordFailure(String error, boolean giveUp, OffsetDateTime nextAttemptAt) {
        this.attempts++;
        this.lastError = error == null ? null : error.substring(0, Math.min(error.length(), MAX_ERROR_LENGTH));
        if (giveUp) {
            this.status = OutboxStatus.FAILED;
        } else {
            this.nextAttemptAt = nextAttemptAt;
        }
    }

    public boolean isFailed() { return status == OutboxStatus.FAILED; }

    public Long getId() { return id; }
    public UUID getRequestId() { return requestId; }
    public Long getAggregateId() { return aggregateId; }
    public String getMessageType() { return messageType; }
    public String getPayload() { return payload; }
    public OutboxStatus getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public OffsetDateTime getNextAttemptAt() { return nextAttemptAt; }
    public String getLastError() { return lastError; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getSentAt() { return sentAt; }
}
