package com.hospital.opd.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.hospital.opd.dto.LabOrderSummary;
import com.hospital.opd.model.Patient;
import com.hospital.opd.model.Visit;
import com.hospital.opd.model.VisitStatus;
import com.hospital.opd.repository.VisitRepository;
import com.hospital.opd.security.ServiceTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LabReportCachingTest {

    private final VisitRepository visits = mock(VisitRepository.class);
    private final VisitService visitService = mock(VisitService.class);
    private final LabIntegrationService lab = mock(LabIntegrationService.class);
    private final LabReportPdfService pdf = mock(LabReportPdfService.class);
    private final ServiceTokenProvider tokens = mock(ServiceTokenProvider.class);
    private final Cache<Long, byte[]> cache = Caffeine.newBuilder().build();
    private final ConsultationWorkflowService service =
            new ConsultationWorkflowService(visits, visitService, lab, pdf, tokens, cache);

    private Visit visit;

    @BeforeEach
    void setUp() {
        Patient patient = new Patient();
        patient.setMrn("JB-000007");
        visit = new Visit();
        visit.setId(5L);
        visit.setPatient(patient);
        visit.setStatus(VisitStatus.LAB_RESULTS_READY);
        visit.setLatestLabOrderId(501L);
        when(visits.findWithPatient(5L)).thenReturn(Optional.of(visit));
        when(tokens.bearer()).thenReturn("Bearer svc");
        when(pdf.render(any(), any(), any())).thenReturn(new byte[]{1, 2, 3});
    }

    @Test
    void theSecondViewIsServedFromTheCacheWithoutCallingTheLabOrRendering() {
        completedOrder();

        var first = service.getLabReport(5L);
        var second = service.getLabReport(5L);

        assertThat(second.content()).isEqualTo(first.content());
        assertThat(second.fileName()).isEqualTo("lab-report-JB-000007-order-501.pdf");
        verify(pdf, times(1)).render(any(), any(), any());
        verify(lab, times(1)).getOrders(anyCollection(), anyString());
    }

    @Test
    void unreleasedResultsAreNotCached() {
        when(lab.getOrders(anyCollection(), anyString())).thenReturn(List.of(order("SAMPLE_COLLECTED")));

        assertThatThrownBy(() -> service.getLabReport(5L)).isInstanceOf(ResponseStatusException.class);
        assertThat(cache.getIfPresent(501L)).isNull();
        verifyNoInteractions(pdf);
    }

    @Test
    void aVisitWithoutALabOrderIs404WithoutCallingTheLab() {
        visit.setLatestLabOrderId(null);

        assertThatThrownBy(() -> service.getLabReport(5L)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(lab);
    }

    private void completedOrder() {
        when(lab.getOrders(anyCollection(), anyString())).thenReturn(List.of(order("COMPLETED")));
        when(lab.getResults(eq(501L), anyString())).thenReturn(List.of());
    }

    private static LabOrderSummary order(String status) {
        return new LabOrderSummary(501L, 5L, status, "doctor", OffsetDateTime.now(), List.of("CBC"));
    }
}
