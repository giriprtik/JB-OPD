package com.hospital.opd.access;

import com.hospital.opd.model.Visit;
import com.hospital.opd.model.VisitStatus;
import com.hospital.opd.repository.VisitRepository;
import com.hospital.opd.service.VisitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ClinicalAccessServiceTest {

    private final ClinicalAccessLogRepository log = mock(ClinicalAccessLogRepository.class);
    private final AccessGrantRepository grants = mock(AccessGrantRepository.class);
    private final VisitRepository visits = mock(VisitRepository.class);
    private final VisitService visitService = mock(VisitService.class);
    private final ClinicalAccessService service = new ClinicalAccessService(log, grants, visits, visitService);

    @BeforeEach
    void setUp() {
        Visit visit = new Visit();
        visit.setId(5L);
        visit.setStatus(VisitStatus.IN_CONSULTATION);
        visit.setAssignedDoctorUsername("doctor");
        when(visits.findById(5L)).thenReturn(Optional.of(visit));
        when(grants.save(any(AccessGrant.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void breakGlassCreatesAThirtyMinuteGrantLogsItAndNotesItInTheVisitHistory() {
        AccessGrant grant = service.breakGlass(5L, "drkarki", "  Covering emergency  ");

        assertThat(grant.getUsername()).isEqualTo("drkarki");
        assertThat(grant.getReason()).isEqualTo("Covering emergency");
        assertThat(Duration.between(grant.getGrantedAt(), grant.getExpiresAt())).isEqualTo(ClinicalAccessService.GRANT_LENGTH);
        assertThat(grant.getGrantedAt()).isCloseTo(OffsetDateTime.now(), within(5, ChronoUnit.SECONDS));

        ArgumentCaptor<ClinicalAccessLog> logged = ArgumentCaptor.forClass(ClinicalAccessLog.class);
        verify(log).save(logged.capture());
        assertThat(logged.getValue().getAction()).isEqualTo("BREAK_GLASS");
        verify(visitService).recordEvent(5L, VisitStatus.IN_CONSULTATION, VisitStatus.IN_CONSULTATION,
                "drkarki", "Break-glass access: Covering emergency");
    }

    @Test
    void theAssignedDoctorDoesNotNeedBreakGlass() {
        assertThatThrownBy(() -> service.breakGlass(5L, "doctor", "Just because I can"))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(grants);
    }

    @Test
    void readsAreLoggedWithWhoAndWhat() {
        service.recordRead(5L, "doctor", ClinicalAccessLog.Action.READ_LAB_REPORT);

        ArgumentCaptor<ClinicalAccessLog> logged = ArgumentCaptor.forClass(ClinicalAccessLog.class);
        verify(log).save(logged.capture());
        assertThat(logged.getValue().getVisitId()).isEqualTo(5L);
        assertThat(logged.getValue().getUsername()).isEqualTo("doctor");
        assertThat(logged.getValue().getAction()).isEqualTo("READ_LAB_REPORT");
    }
}
