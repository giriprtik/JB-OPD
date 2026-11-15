package com.hospital.opd.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospital.opd.exception.DownstreamServiceException;
import com.hospital.opd.model.VisitStatus;
import com.hospital.opd.repository.VisitRepository;
import com.hospital.opd.security.ServiceTokenProvider;
import com.hospital.opd.service.LabIntegrationService;
import com.hospital.opd.service.VisitService;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OutboxRelayTest {

    private final OutboxRepository outbox = mock(OutboxRepository.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    // A real TransactionTemplate over a mock manager: callbacks run, commit/rollback are no-ops.
    private final TransactionTemplate tx = new TransactionTemplate(mock(PlatformTransactionManager.class));
    private final LabIntegrationService lab = mock(LabIntegrationService.class);
    private final ServiceTokenProvider tokens = mock(ServiceTokenProvider.class);
    private final VisitRepository visits = mock(VisitRepository.class);
    private final VisitService visitService = mock(VisitService.class);
    private final ObjectMapper json = new ObjectMapper();

    private final OutboxRelay relay = new OutboxRelay(outbox, jdbc, tx, lab, tokens, visits, visitService, json);

    private OutboxMessage message;
    private LabOrderPayload payload;

    @BeforeEach
    void setUp() throws Exception {
        when(tokens.bearer()).thenReturn("Bearer svc");
        payload = new LabOrderPayload(UUID.randomUUID(), 5L, "JB-000007", "Ram Bahadur",
                List.of("CBC", "LFT"), "r/o anaemia", "doctor");
        message = new OutboxMessage(payload.requestId(), 5L, OutboxMessage.LAB_ORDER_REQUESTED,
                json.writeValueAsString(payload), OffsetDateTime.now());
        when(outbox.findById(1L)).thenReturn(Optional.of(message));
    }

    @Nested
    @DisplayName("successful delivery")
    class Success {

        @Test
        void sendsThePayloadWithTheServiceTokenAndAttachesTheOrderToTheVisit() {
            when(lab.raiseLabInvestigationOrder(payload, "Bearer svc")).thenReturn(501L);
            when(visits.attachLabOrder(5L, 501L)).thenReturn(1);

            relay.deliver(1L);

            assertThat(message.getStatus()).isEqualTo(OutboxStatus.SENT);
            assertThat(message.getSentAt()).isNotNull();
            verify(outbox).save(message);
            verify(visitService).recordEvent(eq(5L), eq(VisitStatus.AWAITING_LAB), eq(VisitStatus.AWAITING_LAB),
                    eq("system"), contains("Lab order #501 accepted"));
        }

        @Test
        void aVisitThatMovedOnMeanwhileStillMarksTheMessageSentWithoutAnEvent() {
            when(lab.raiseLabInvestigationOrder(any(), anyString())).thenReturn(501L);
            when(visits.attachLabOrder(5L, 501L)).thenReturn(0);

            relay.deliver(1L);

            assertThat(message.getStatus()).isEqualTo(OutboxStatus.SENT);
            verifyNoInteractions(visitService);
        }

        @Test
        void alreadySentMessagesAreSkipped() {
            message.markSent(OffsetDateTime.now());

            relay.deliver(1L);

            verifyNoInteractions(lab);
        }
    }

    @Nested
    @DisplayName("failures")
    class Failures {

        @Test
        void aLabOutageSchedulesARetryWithBackoff() {
            when(lab.raiseLabInvestigationOrder(any(), anyString())).thenThrow(downstream(HttpStatus.SERVICE_UNAVAILABLE));
            OffsetDateTime before = OffsetDateTime.now();

            relay.deliver(1L);

            assertThat(message.getStatus()).isEqualTo(OutboxStatus.PENDING);
            assertThat(message.getAttempts()).isEqualTo(1);
            assertThat(message.getLastError()).contains("unreachable");
            assertThat(message.getNextAttemptAt()).isAfter(before);
            verifyNoInteractions(visitService);
        }

        @Test
        void anInvalidOrderFailsPermanentlyAndReturnsTheVisitToTheDoctor() {
            when(lab.raiseLabInvestigationOrder(any(), anyString())).thenThrow(downstream(HttpStatus.BAD_REQUEST));
            when(visits.returnUndeliveredLabOrder(5L)).thenReturn(1);

            relay.deliver(1L);

            assertThat(message.getStatus()).isEqualTo(OutboxStatus.FAILED);
            verify(visitService).recordEvent(eq(5L), eq(VisitStatus.AWAITING_LAB), eq(VisitStatus.IN_CONSULTATION),
                    eq("system"), startsWith("Lab order could not be delivered"));
        }

        @Test
        void aDuplicateKeyRaceIsRetriedNotFailed() {
            when(lab.raiseLabInvestigationOrder(any(), anyString())).thenThrow(downstream(HttpStatus.CONFLICT));

            relay.deliver(1L);

            assertThat(message.getStatus()).isEqualTo(OutboxStatus.PENDING);
            verify(visits, never()).returnUndeliveredLabOrder(any());
        }

        @Test
        void afterTheLastAttemptItGivesUp() {
            for (int i = 0; i < OutboxRelay.MAX_ATTEMPTS - 1; i++) {
                message.recordFailure("earlier", false, OffsetDateTime.now());
            }
            when(lab.raiseLabInvestigationOrder(any(), anyString())).thenThrow(downstream(HttpStatus.SERVICE_UNAVAILABLE));

            relay.deliver(1L);

            assertThat(message.getStatus()).isEqualTo(OutboxStatus.FAILED);
            verify(visits).returnUndeliveredLabOrder(5L);
        }

        @Test
        void anOpenBreakerLeavesTheMessageAloneWithoutUsingAnAttempt() {
            CircuitBreaker open = CircuitBreaker.ofDefaults("lab");
            open.transitionToOpenState();
            when(lab.raiseLabInvestigationOrder(any(), anyString()))
                    .thenThrow(CallNotPermittedException.createCallNotPermittedException(open));

            relay.deliver(1L);

            assertThat(message.getStatus()).isEqualTo(OutboxStatus.PENDING);
            assertThat(message.getAttempts()).isZero();
            verify(outbox, never()).save(any());
        }

        @Test
        void anUnreadablePayloadFailsPermanently() {
            OutboxMessage broken = new OutboxMessage(UUID.randomUUID(), 6L, OutboxMessage.LAB_ORDER_REQUESTED,
                    "{not json", OffsetDateTime.now());
            when(outbox.findById(2L)).thenReturn(Optional.of(broken));

            relay.deliver(2L);

            assertThat(broken.getStatus()).isEqualTo(OutboxStatus.FAILED);
            verifyNoInteractions(lab);
        }
    }

    @Test
    void drainDeliversEveryClaimedMessageAndSurvivesABadOne() {
        when(jdbc.queryForList(eq(OutboxRelay.CLAIM_DUE_SQL), eq(Long.class), eq(OutboxRelay.BATCH_SIZE)))
                .thenReturn(List.of(99L, 1L));
        when(outbox.findById(99L)).thenThrow(new IllegalStateException("db hiccup"));
        when(lab.raiseLabInvestigationOrder(any(), anyString())).thenReturn(501L);

        relay.drain();

        assertThat(message.getStatus()).isEqualTo(OutboxStatus.SENT);
    }

    @Test
    void backoffDoublesAndIsCapped() {
        assertThat(OutboxRelay.backoff(1)).isEqualTo(Duration.ofSeconds(2));
        assertThat(OutboxRelay.backoff(3)).isEqualTo(Duration.ofSeconds(8));
        assertThat(OutboxRelay.backoff(30)).isEqualTo(OutboxRelay.MAX_BACKOFF);
    }

    private static DownstreamServiceException downstream(HttpStatus status) {
        String msg = status == HttpStatus.SERVICE_UNAVAILABLE ? "Lab service unreachable" : "Lab service returned " + status.value();
        return new DownstreamServiceException("lab-service", status.is5xxServerError() ? null : status.value(), status, msg, null);
    }
}
