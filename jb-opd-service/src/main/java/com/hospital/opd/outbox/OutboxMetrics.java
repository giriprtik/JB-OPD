package com.hospital.opd.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * Two numbers worth alerting on, at /actuator/metrics/opd.outbox.*:
 * stuck = PENDING for more than 5 minutes (lab down for a while); failed = gave up, needs a human.
 */
@Component
public class OutboxMetrics {

    public OutboxMetrics(MeterRegistry registry, OutboxRepository outbox) {
        Gauge.builder("opd.outbox.stuck", outbox,
                        repo -> repo.countByStatusAndCreatedAtBefore(OutboxStatus.PENDING, OffsetDateTime.now().minusMinutes(5)))
                .description("Lab orders queued for more than 5 minutes")
                .register(registry);
        Gauge.builder("opd.outbox.failed", outbox, repo -> repo.countByStatus(OutboxStatus.FAILED))
                .description("Lab orders that could not be delivered")
                .register(registry);
    }
}
