package com.hospital.opd.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospital.opd.exception.DownstreamServiceException;
import com.hospital.opd.model.VisitStatus;
import com.hospital.opd.repository.VisitRepository;
import com.hospital.opd.security.ServiceTokenProvider;
import com.hospital.opd.service.LabIntegrationService;
import com.hospital.opd.service.VisitService;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Delivers outbox messages to the lab. At-least-once: a crash between "lab accepted" and "marked SENT"
 * means the message is sent again, and the lab returns the same order for the same requestId.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    static final int BATCH_SIZE = 20;
    static final int MAX_ATTEMPTS = 12;   // with the backoff below: roughly an hour of retrying
    static final Duration MAX_BACKOFF = Duration.ofMinutes(10);

    /** Leases due rows for 30 s. SKIP LOCKED: several OPD instances can drain without taking the same row. */
    static final String CLAIM_DUE_SQL = """
            UPDATE outbox_message SET next_attempt_at = NOW() + INTERVAL '30 seconds'
            WHERE id IN (SELECT id FROM outbox_message
                         WHERE status = 'PENDING' AND next_attempt_at <= NOW()
                         ORDER BY id
                         LIMIT ?
                         FOR UPDATE SKIP LOCKED)
            RETURNING id
            """;

    private final OutboxRepository outbox;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final LabIntegrationService lab;
    private final ServiceTokenProvider serviceTokens;
    private final VisitRepository visits;
    private final VisitService visitService;
    private final ObjectMapper json;

    public OutboxRelay(OutboxRepository outbox, JdbcTemplate jdbc, TransactionTemplate tx,
                       LabIntegrationService lab, ServiceTokenProvider serviceTokens,
                       VisitRepository visits, VisitService visitService, ObjectMapper json) {
        this.outbox = outbox;
        this.jdbc = jdbc;
        this.tx = tx;
        this.lab = lab;
        this.serviceTokens = serviceTokens;
        this.visits = visits;
        this.visitService = visitService;
        this.json = json;
    }

    @Scheduled(fixedDelayString = "${app.outbox.delay-ms:1000}")
    public void drain() {
        List<Long> due;
        try {
            due = tx.execute(status -> jdbc.queryForList(CLAIM_DUE_SQL, Long.class, BATCH_SIZE));
        } catch (RuntimeException ex) {
            log.error("Could not claim outbox messages", ex);
            return;
        }
        if (due == null) return;
        for (Long id : due) {
            try {
                deliver(id);
            } catch (RuntimeException ex) {
                // One bad row must not stop the rest; its lease expires and it is retried.
                log.error("Outbox message {} failed unexpectedly", id, ex);
            }
        }
    }

    /** Delivers one message. Package-private so tests can drive it directly. */
    void deliver(Long id) {
        OutboxMessage message = outbox.findById(id).orElse(null);
        if (message == null || message.getStatus() != OutboxStatus.PENDING) return;

        LabOrderPayload payload;
        try {
            payload = json.readValue(message.getPayload(), LabOrderPayload.class);
        } catch (JsonProcessingException ex) {
            giveUp(message, "Unreadable payload: " + ex.getOriginalMessage());
            return;
        }

        try {
            Long orderId = lab.raiseLabInvestigationOrder(payload, serviceTokens.bearer());
            tx.executeWithoutResult(status -> {
                int attached = visits.attachLabOrder(payload.visitId(), orderId);
                message.markSent(OffsetDateTime.now());
                outbox.save(message);
                if (attached > 0) {
                    visitService.recordEvent(payload.visitId(), VisitStatus.AWAITING_LAB, VisitStatus.AWAITING_LAB,
                            "system", "Lab order #" + orderId + " accepted by the lab: " + String.join(", ", payload.testCodes()));
                } else {
                    log.warn("Lab order #{} created, but visit {} is no longer waiting for it", orderId, payload.visitId());
                }
            });
        } catch (CallNotPermittedException | BulkheadFullException ex) {
            // Breaker open / lab saturated: not this message's fault. Leave it PENDING without using up an attempt;
            // the lease (30 s) spaces out the next try.
            log.debug("Lab unavailable; outbox message {} will be retried", id);
        } catch (DownstreamServiceException ex) {
            HttpStatus st = ex.getClientStatus();
            // 400/422 = the order itself is invalid (e.g. unknown test code): retrying won't help.
            // 409 = two deliveries raced on the same requestId: the retry will find the existing order.
            boolean permanent = st != null && st.is4xxClientError() && st != HttpStatus.CONFLICT;
            boolean exhausted = message.getAttempts() + 1 >= MAX_ATTEMPTS;
            if (permanent || exhausted) {
                giveUp(message, ex.getMessage());
            } else {
                tx.executeWithoutResult(status -> {
                    message.recordFailure(ex.getMessage(), false, OffsetDateTime.now().plus(backoff(message.getAttempts() + 1)));
                    outbox.save(message);
                });
                log.info("Outbox message {} attempt {} failed: {}", id, message.getAttempts(), ex.getMessage());
            }
        }
    }

    /** Marks the message FAILED and hands the visit back to the doctor with the reason in its history. */
    private void giveUp(OutboxMessage message, String reason) {
        tx.executeWithoutResult(status -> {
            message.recordFailure(reason, true, null);
            outbox.save(message);
            if (visits.returnUndeliveredLabOrder(message.getAggregateId()) > 0) {
                visitService.recordEvent(message.getAggregateId(), VisitStatus.AWAITING_LAB, VisitStatus.IN_CONSULTATION,
                        "system", "Lab order could not be delivered: " + reason);
            }
        });
        log.warn("Outbox message {} failed permanently: {}", message.getId(), reason);
    }

    /** 2 s, 4 s, 8 s ... capped at 10 minutes. */
    static Duration backoff(int attempt) {
        long seconds = 1L << Math.min(attempt, 20);
        return seconds >= MAX_BACKOFF.toSeconds() ? MAX_BACKOFF : Duration.ofSeconds(seconds);
    }
}
