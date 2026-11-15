package com.hospital.opd.repository;

import java.math.BigDecimal;

/**
 * Raw row of the shared worklist query (VisitRepository.QUEUE_SELECT): each getter maps to a column alias.
 * Native queries bypass JPA converters, so chiefComplaint, patientAllergies and symptomsBrief arrive
 * ENCRYPTED here. Never return this to a client; VisitService maps it to VisitQueueItem and decrypts.
 */
public interface VisitQueueRow {
    Long getVisitId();
    String getPatientMrn();
    String getPatientFullName();
    Integer getTokenNumber();
    String getChiefComplaint();
    String getPriority();
    String getStatus();
    Integer getPatientAge();
    String getPatientGender();
    String getPatientBloodGroup();
    String getPatientAllergies();
    String getAssignedDoctorUsername();
    String getAssignedDoctorName();
    String getRegisteredTime();
    Long getLabOrderId();

    BigDecimal getTemperatureCelsius();
    Integer getPulseRate();
    String getBloodPressure();
    Integer getOxygenSaturation();
    String getSymptomsBrief();
}
