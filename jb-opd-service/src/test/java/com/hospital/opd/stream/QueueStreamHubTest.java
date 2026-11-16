package com.hospital.opd.stream;

import com.hospital.opd.event.VisitChanged;
import com.hospital.opd.repository.VisitRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class QueueStreamHubTest {

    private final VisitRepository visits = mock(VisitRepository.class);
    private final QueueStreamHub hub = new QueueStreamHub(visits);

    @Test
    void subscribingRegistersAStream() {
        hub.subscribe("nurse", false);
        hub.subscribe("doctor", true);

        assertThat(hub.openStreams()).isEqualTo(2);
    }

    @Test
    void noLookupIsMadeWhenNobodyIsListening() {
        hub.onVisitChanged(new VisitChanged(5L, "TRIAGED"));

        verifyNoInteractions(visits);
    }

    @Test
    void theAssignedDoctorIsOnlyLookedUpWhenADoctorIsListening() {
        hub.subscribe("nurse", false);
        hub.onVisitChanged(new VisitChanged(5L, "TRIAGED"));
        verifyNoInteractions(visits);

        hub.subscribe("doctor", true);
        when(visits.findAssignedDoctorUsername(5L)).thenReturn(Optional.of("doctor"));
        hub.onVisitChanged(new VisitChanged(5L, "TRIAGED"));
        verify(visits).findAssignedDoctorUsername(5L);
    }

    @Test
    void streamsThatFailToSendAreDropped() {
        SseEmitter emitter = hub.subscribe("nurse", false);
        emitter.completeWithError(new IllegalStateException("client went away"));

        hub.heartbeat();   // sending to a completed emitter throws IllegalStateException -> dropped

        assertThat(hub.openStreams()).isZero();
    }
}
