package com.hospital.opd.service;

import com.hospital.opd.exception.DownstreamServiceException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

class LabStatusSyncJobTest {

    private final ConsultationWorkflowService workflow = mock(ConsultationWorkflowService.class);
    private final LabStatusSyncJob job = new LabStatusSyncJob(workflow);

    @Test
    void asksTheLabForCompletedOrders() {
        job.sync();

        verify(workflow).refreshLabStatuses();
    }

    @Test
    void aLabOutageIsLoggedNotThrown() {
        when(workflow.refreshLabStatuses()).thenThrow(
                new DownstreamServiceException("lab-service", null, HttpStatus.SERVICE_UNAVAILABLE, "Lab service unreachable", null));

        assertThatCode(job::sync).doesNotThrowAnyException();
    }

    @Test
    void anOpenBreakerIsLoggedNotThrown() {
        CircuitBreaker open = CircuitBreaker.ofDefaults("lab");
        open.transitionToOpenState();
        when(workflow.refreshLabStatuses()).thenThrow(CallNotPermittedException.createCallNotPermittedException(open));

        assertThatCode(job::sync).doesNotThrowAnyException();
    }

    @Test
    void anUnexpectedErrorDoesNotKillTheSchedulerThread() {
        when(workflow.refreshLabStatuses()).thenThrow(new IllegalStateException("boom"));

        assertThatCode(job::sync).doesNotThrowAnyException();
    }
}
