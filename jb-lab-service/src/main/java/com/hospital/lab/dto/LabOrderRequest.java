package com.hospital.lab.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public class LabOrderRequest {

    @NotNull
    private Long visitId;

    @NotBlank
    @Size(max = 20)
    private String patientMrn;

    @NotEmpty(message = "At least one test code is required")
    private List<@NotBlank @Size(max = 20) String> testCodes;

    @Size(max = 150)
    private String patientName;

    private String clinicalHistory;

    /** Optional idempotency key: the same key always maps to the same order. */
    private UUID requestId;

    /** Only honoured when the caller is a SYSTEM service acting for a doctor; ignored for doctor tokens. */
    @Size(max = 50)
    private String orderedByDoctor;

    public Long getVisitId() { return visitId; }
    public void setVisitId(Long visitId) { this.visitId = visitId; }
    public String getPatientMrn() { return patientMrn; }
    public void setPatientMrn(String patientMrn) { this.patientMrn = patientMrn; }
    public List<String> getTestCodes() { return testCodes; }
    public void setTestCodes(List<String> testCodes) { this.testCodes = testCodes; }
    public String getPatientName() { return patientName; }
    public void setPatientName(String patientName) { this.patientName = patientName; }
    public String getClinicalHistory() { return clinicalHistory; }
    public void setClinicalHistory(String clinicalHistory) { this.clinicalHistory = clinicalHistory; }
    public UUID getRequestId() { return requestId; }
    public void setRequestId(UUID requestId) { this.requestId = requestId; }
    public String getOrderedByDoctor() { return orderedByDoctor; }
    public void setOrderedByDoctor(String orderedByDoctor) { this.orderedByDoctor = orderedByDoctor; }
}