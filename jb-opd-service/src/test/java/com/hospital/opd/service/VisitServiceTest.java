package com.hospital.opd.service;

import com.hospital.opd.crypto.FieldCipher;
import com.hospital.opd.dto.StaffSummary;
import com.hospital.opd.dto.VisitRegistrationRequest;
import com.hospital.opd.exception.ResourceNotFoundException;
import com.hospital.opd.model.*;
import com.hospital.opd.outbox.LabOrderPayload;
import com.hospital.opd.outbox.OutboxService;
import com.hospital.opd.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VisitServiceTest {

    private final VisitRepository visits = mock(VisitRepository.class);
    private final PatientRepository patients = mock(PatientRepository.class);
    private final TokenIssuer tokens = mock(TokenIssuer.class);
    private final VisitEventRepository events = mock(VisitEventRepository.class);
    private final DoctorIntegrationService doctors = mock(DoctorIntegrationService.class);
    private final OutboxService outbox = mock(OutboxService.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final FieldCipher cipher = mock(FieldCipher.class);

    private VisitService service;

    private static final StaffSummary DR_SHARMA =
            new StaffSummary(UUID.randomUUID(), "doctor", "Dr. Ramesh Sharma", "DOCTOR", "GENERAL_OPD", "General Medicine");

    @BeforeEach
    void setUp() {
        service = new VisitService(visits, patients, tokens, events, doctors, outbox, publisher, cipher,
                new TransactionTemplate(mock(PlatformTransactionManager.class)));   // runs callbacks inline
        when(visits.save(any(Visit.class))).thenAnswer(inv -> {
            Visit v = inv.getArgument(0);
            if (v.getId() == null) v.setId(42L);
            return v;
        });
    }

    @Nested
    @DisplayName("openVisit")
    class OpenVisit {

        @Test
        void issuesTodaysNextTokenAndRecordsTheRegistrationEvent() {
            Patient patient = patient(7L, "Ram Bahadur");
            LocalDate today = LocalDate.now(VisitService.HOSPITAL_ZONE);
            when(tokens.next(today)).thenReturn(4);

            Visit visit = service.openVisit(patient, "  Fever for 3 days ", VisitPriority.URGENT, DR_SHARMA, "nurse");

            verify(tokens).next(today);
            assertThat(visit.getTokenNumber()).isEqualTo(4);
            assertThat(visit.getVisitDate()).isEqualTo(today);
            assertThat(visit.getStatus()).isEqualTo(VisitStatus.REGISTERED);
            assertThat(visit.getChiefComplaint()).isEqualTo("Fever for 3 days");
            assertThat(visit.getAssignedDoctorUsername()).isEqualTo("doctor");
            assertThat(visit.getAssignedDoctorName()).isEqualTo("Dr. Ramesh Sharma");
            assertThat(visit.getRegisteredBy()).isEqualTo("nurse");

            VisitEvent event = capturedEvent();
            assertThat(event.getVisitId()).isEqualTo(42L);
            assertThat(event.getFromStatus()).isNull();
            assertThat(event.getToStatus()).isEqualTo("REGISTERED");
            assertThat(event.getActor()).isEqualTo("nurse");
            assertThat(event.getNote()).contains("token #4").contains("Dr. Ramesh Sharma");
        }

        @Test
        void refusesASecondOpenVisitForTheSamePatient() {
            Patient patient = patient(7L, "Ram Bahadur");
            when(visits.hasActiveVisit(7L)).thenReturn(true);

            assertThatThrownBy(() -> service.openVisit(patient, "Cough", VisitPriority.NORMAL, DR_SHARMA, "nurse"))
                    .satisfies(e -> assertStatus(e, 409))
                    .hasMessageContaining("already has an open visit");

            verifyNoInteractions(tokens);
            verify(visits, never()).save(any());
        }

        @Test
        void skipsTheOpenVisitCheckForABrandNewPatient() {
            when(tokens.next(any())).thenReturn(1);

            service.openVisit(patient(null, "New Patient"), "Headache", VisitPriority.NORMAL, DR_SHARMA, "nurse");

            verify(visits, never()).hasActiveVisit(any());
        }
    }

    @Nested
    @DisplayName("registerVisit (returning patient)")
    class RegisterVisit {

        @Test
        void unknownMrnIsNotFound() {
            when(patients.findByMrn("JB-999999")).thenReturn(Optional.empty());
            var request = new VisitRegistrationRequest(" JB-999999 ", "Follow-up", VisitPriority.NORMAL, "doctor");

            assertThatThrownBy(() -> service.registerVisit(request, "nurse"))
                    .isInstanceOf(ResourceNotFoundException.class);
            verifyNoInteractions(doctors);
        }
    }

    @Nested
    @DisplayName("transitionStatus")
    class TransitionStatus {

        @Test
        void movesToTheTargetAndAuditsTheChange() {
            Visit visit = visit(5L, VisitStatus.REGISTERED, "doctor");
            when(visits.findById(5L)).thenReturn(Optional.of(visit));

            service.transitionStatus(5L, EnumSet.of(VisitStatus.REGISTERED), VisitStatus.TRIAGED, "nurse", "Vitals recorded");

            assertThat(visit.getStatus()).isEqualTo(VisitStatus.TRIAGED);
            VisitEvent event = capturedEvent();
            assertThat(event.getFromStatus()).isEqualTo("REGISTERED");
            assertThat(event.getToStatus()).isEqualTo("TRIAGED");
            assertThat(event.getNote()).isEqualTo("Vitals recorded");
        }

        @Test
        void refusesToJumpFromAStatusThatIsNotAllowed() {
            Visit visit = visit(5L, VisitStatus.COMPLETED, "doctor");
            when(visits.findById(5L)).thenReturn(Optional.of(visit));

            assertThatThrownBy(() -> service.transitionStatus(5L, EnumSet.of(VisitStatus.REGISTERED),
                    VisitStatus.TRIAGED, "nurse", null))
                    .satisfies(e -> assertStatus(e, 409));

            assertThat(visit.getStatus()).isEqualTo(VisitStatus.COMPLETED);
            verifyNoInteractions(events);
        }

        @Test
        void isANoOpWhenAlreadyInTheTargetStatus() {
            when(visits.findById(5L)).thenReturn(Optional.of(visit(5L, VisitStatus.TRIAGED, "doctor")));

            service.transitionStatus(5L, EnumSet.of(VisitStatus.REGISTERED), VisitStatus.TRIAGED, "nurse", null);

            verifyNoInteractions(events);
        }

        @Test
        void unknownVisitIsNotFound() {
            when(visits.findById(5L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.transitionStatus(5L, EnumSet.allOf(VisitStatus.class),
                    VisitStatus.TRIAGED, "nurse", null))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("sendToLab")
    class SendToLab {

        @Test
        void queuesTheOrderInTheOutboxAndMovesTheVisitToAwaitingLabInOneStep() {
            Visit visit = visit(5L, VisitStatus.IN_CONSULTATION, "doctor");
            when(visits.findWithPatient(5L)).thenReturn(Optional.of(visit));

            service.sendToLab(5L, List.of("CBC", "LFT"), "  r/o anaemia ", "doctor");

            ArgumentCaptor<LabOrderPayload> payload = ArgumentCaptor.forClass(LabOrderPayload.class);
            verify(outbox).enqueueLabOrder(payload.capture());
            assertThat(payload.getValue().requestId()).isNotNull();
            assertThat(payload.getValue().visitId()).isEqualTo(5L);
            assertThat(payload.getValue().patientMrn()).isEqualTo("JB-000007");
            assertThat(payload.getValue().patientName()).isEqualTo("Ram Bahadur");
            assertThat(payload.getValue().testCodes()).containsExactly("CBC", "LFT");
            assertThat(payload.getValue().clinicalHistory()).isEqualTo("r/o anaemia");
            assertThat(payload.getValue().orderedByDoctor()).isEqualTo("doctor");

            assertThat(visit.getStatus()).isEqualTo(VisitStatus.AWAITING_LAB);
            assertThat(visit.getLatestLabOrderId()).isNull();   // "queued" until the lab accepts it
            VisitEvent event = capturedEvent();
            assertThat(event.getFromStatus()).isEqualTo("IN_CONSULTATION");
            assertThat(event.getToStatus()).isEqualTo("AWAITING_LAB");
            assertThat(event.getNote()).isEqualTo("Lab order queued: CBC, LFT");
        }

        @Test
        void fallsBackToTheChiefComplaintWhenNoClinicalNotesAreGiven() {
            when(visits.findWithPatient(5L)).thenReturn(Optional.of(visit(5L, VisitStatus.TRIAGED, "doctor")));

            service.sendToLab(5L, List.of("CBC"), "   ", "doctor");

            ArgumentCaptor<LabOrderPayload> payload = ArgumentCaptor.forClass(LabOrderPayload.class);
            verify(outbox).enqueueLabOrder(payload.capture());
            assertThat(payload.getValue().clinicalHistory()).isEqualTo("Fever");
        }

        @Test
        void eachOrderGetsItsOwnIdempotencyKey() {
            when(visits.findWithPatient(5L)).thenReturn(
                    Optional.of(visit(5L, VisitStatus.TRIAGED, "doctor")),
                    Optional.of(visit(5L, VisitStatus.IN_CONSULTATION, "doctor")));

            service.sendToLab(5L, List.of("CBC"), null, "doctor");
            service.sendToLab(5L, List.of("LFT"), null, "doctor");

            ArgumentCaptor<LabOrderPayload> payload = ArgumentCaptor.forClass(LabOrderPayload.class);
            verify(outbox, times(2)).enqueueLabOrder(payload.capture());
            assertThat(payload.getAllValues().get(0).requestId()).isNotEqualTo(payload.getAllValues().get(1).requestId());
        }

        @Test
        void anotherDoctorCannotOrderTestsForThisPatient() {
            when(visits.findWithPatient(5L)).thenReturn(Optional.of(visit(5L, VisitStatus.IN_CONSULTATION, "doctor")));

            assertThatThrownBy(() -> service.sendToLab(5L, List.of("CBC"), null, "drkarki"))
                    .satisfies(e -> assertStatus(e, 403));
            verifyNoInteractions(outbox);
        }

        @Test
        void cannotSendAPatientWhoHasNotBeenTriaged() {
            when(visits.findWithPatient(5L)).thenReturn(Optional.of(visit(5L, VisitStatus.REGISTERED, "doctor")));

            assertThatThrownBy(() -> service.sendToLab(5L, List.of("CBC"), null, "doctor"))
                    .satisfies(e -> assertStatus(e, 409));
            verifyNoInteractions(outbox);
        }

        @Test
        void aSecondClickWhileAlreadyAtTheLabIsAConflict() {
            when(visits.findWithPatient(5L)).thenReturn(Optional.of(visit(5L, VisitStatus.AWAITING_LAB, "doctor")));

            assertThatThrownBy(() -> service.sendToLab(5L, List.of("CBC"), null, "doctor"))
                    .satisfies(e -> assertStatus(e, 409));
            verifyNoInteractions(outbox);
        }
    }

    @Test
    void everyAuditEventAlsoPublishesALiveUpdate() {
        service.recordEvent(5L, VisitStatus.REGISTERED, VisitStatus.TRIAGED, "nurse", "Vitals recorded");

        verify(publisher).publishEvent(new com.hospital.opd.event.VisitChanged(5L, "TRIAGED"));
    }

    @Test
    void requireAssignedDoctorAllowsUnassignedVisits() {
        Visit unassigned = visit(5L, VisitStatus.TRIAGED, null);
        VisitService.requireAssignedDoctor(unassigned, "anyone"); // no exception
    }

    // ---------------------------------------------------------------- helpers

    private VisitEvent capturedEvent() {
        ArgumentCaptor<VisitEvent> captor = ArgumentCaptor.forClass(VisitEvent.class);
        verify(events).save(captor.capture());
        return captor.getValue();
    }

    static void assertStatus(Throwable e, int expected) {
        assertThat(e).isInstanceOf(ResponseStatusException.class);
        assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(expected);
    }

    static Patient patient(Long id, String name) {
        Patient p = new Patient();
        p.setId(id);
        p.setMrn("JB-000007");
        p.setFullName(name);
        return p;
    }

    static Visit visit(Long id, VisitStatus status, String doctor) {
        Visit v = new Visit();
        v.setId(id);
        v.setPatient(patient(7L, "Ram Bahadur"));
        v.setStatus(status);
        v.setChiefComplaint("Fever");
        v.setAssignedDoctorUsername(doctor);
        return v;
    }
}
