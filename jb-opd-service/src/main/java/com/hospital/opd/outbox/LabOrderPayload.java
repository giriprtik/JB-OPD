package com.hospital.opd.outbox;

import java.util.List;
import java.util.UUID;

/** What the relay sends to the lab for a LAB_ORDER_REQUESTED message (stored as JSON in the outbox). */
public record LabOrderPayload(
        UUID requestId,
        Long visitId,
        String patientMrn,
        String patientName,
        List<String> testCodes,
        String clinicalHistory,
        String orderedByDoctor) {}
