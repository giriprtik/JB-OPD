package com.hospital.opd.dto;

import java.math.BigDecimal;

/**
 * Read model for every worklist (triage, doctor, today's register). Built from VisitQueueRow with the
 * encrypted columns decrypted. Carries no diagnosis or consultation notes: queues are visible to nurses.
 *
 * @param labOrderId null while a lab order is still queued for delivery, or when none was placed
 */
public record VisitQueueItem(
        Long visitId,
        String patientMrn,
        String patientFullName,
        Integer tokenNumber,
        String chiefComplaint,
        String priority,
        String status,
        Integer patientAge,
        String patientGender,
        String patientBloodGroup,
        String patientAllergies,
        String assignedDoctorUsername,
        String assignedDoctorName,
        String registeredTime,
        Long labOrderId,
        BigDecimal temperatureCelsius,
        Integer pulseRate,
        String bloodPressure,
        Integer oxygenSaturation,
        String symptomsBrief) {}
