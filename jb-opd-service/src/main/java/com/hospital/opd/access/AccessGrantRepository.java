package com.hospital.opd.access;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;

public interface AccessGrantRepository extends JpaRepository<AccessGrant, Long> {

    boolean existsByVisitIdAndUsernameAndExpiresAtAfter(Long visitId, String username, OffsetDateTime now);
}
