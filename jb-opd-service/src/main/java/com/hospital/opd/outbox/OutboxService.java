package com.hospital.opd.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/** Writes outbox rows. MANDATORY: a row only makes sense inside the transaction of the change that needs it. */
@Service
public class OutboxService {

    private final OutboxRepository repository;
    private final ObjectMapper json;

    public OutboxService(OutboxRepository repository, ObjectMapper json) {
        this.repository = repository;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxMessage enqueueLabOrder(LabOrderPayload payload) {
        try {
            OutboxMessage message = new OutboxMessage(payload.requestId(), payload.visitId(),
                    OutboxMessage.LAB_ORDER_REQUESTED, json.writeValueAsString(payload), OffsetDateTime.now());
            return repository.save(message);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise the lab order", e);
        }
    }
}
