package com.hospital.opd.access;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ClinicalAccessLogRepository extends JpaRepository<ClinicalAccessLog, Long> {

    List<ClinicalAccessLog> findByVisitIdOrderByOccurredAtAsc(Long visitId);
}
