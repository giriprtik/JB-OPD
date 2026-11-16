package com.hospital.opd.service;

import com.hospital.opd.exception.DownstreamServiceException;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * One server-side job asks the lab which waiting orders are done, instead of every doctor's screen
 * calling /lab-status/refresh on its own timer. Lab load stays flat no matter how many doctors are signed in.
 * Runs only when scheduling is enabled (see SchedulingConfig).
 */
@Component
public class LabStatusSyncJob {

    private static final Logger log = LoggerFactory.getLogger(LabStatusSyncJob.class);

    private final ConsultationWorkflowService workflow;

    public LabStatusSyncJob(ConsultationWorkflowService workflow) {
        this.workflow = workflow;
    }

    @Scheduled(fixedDelayString = "${app.lab-sync.delay-ms:15000}", initialDelayString = "${app.lab-sync.initial-delay-ms:10000}")
    public void sync() {
        try {
            int moved = workflow.refreshLabStatuses();   // uses the SYSTEM service token
            if (moved > 0) log.info("Lab sync moved {} visit(s) to LAB_RESULTS_READY", moved);
        } catch (DownstreamServiceException | CallNotPermittedException | BulkheadFullException ex) {
            // Lab down or breaker open: try again next tick. Never let the scheduler thread die.
            log.warn("Lab sync skipped: {}", ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("Lab sync failed unexpectedly", ex);
        }
    }
}
