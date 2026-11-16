package com.hospital.opd.outbox;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;

public interface OutboxRepository extends JpaRepository<OutboxMessage, Long> {

    long countByStatus(OutboxStatus status);

    long countByStatusAndCreatedAtBefore(OutboxStatus status, OffsetDateTime cutoff);
}
