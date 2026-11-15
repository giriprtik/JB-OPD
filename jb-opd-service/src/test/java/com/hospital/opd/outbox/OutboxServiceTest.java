package com.hospital.opd.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OutboxServiceTest {

    private final OutboxRepository repo = mock(OutboxRepository.class);
    private final ObjectMapper json = new ObjectMapper();
    private final OutboxService service = new OutboxService(repo, json);

    @Test
    void storesTheOrderAsJsonKeyedByItsRequestId() throws Exception {
        when(repo.save(any(OutboxMessage.class))).thenAnswer(inv -> inv.getArgument(0));
        LabOrderPayload payload = new LabOrderPayload(UUID.randomUUID(), 5L, "JB-000007", "Ram Bahadur",
                List.of("CBC"), "Fever", "doctor");

        service.enqueueLabOrder(payload);

        ArgumentCaptor<OutboxMessage> saved = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(repo).save(saved.capture());
        OutboxMessage m = saved.getValue();
        assertThat(m.getRequestId()).isEqualTo(payload.requestId());
        assertThat(m.getAggregateId()).isEqualTo(5L);
        assertThat(m.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(m.getNextAttemptAt()).isNotNull();
        assertThat(json.readValue(m.getPayload(), LabOrderPayload.class)).isEqualTo(payload);
    }
}
