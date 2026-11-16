package com.hospital.opd.controller;

import com.hospital.opd.access.ClinicalAccessLog;
import com.hospital.opd.access.ClinicalAccessService;
import com.hospital.opd.dto.BreakGlassRequest;
import com.hospital.opd.dto.SendToLabRequest;
import com.hospital.opd.dto.VisitClinicalView;
import com.hospital.opd.dto.VisitConclusionRequest;
import com.hospital.opd.dto.VisitLabResults;
import com.hospital.opd.dto.VisitQueueItem;
import com.hospital.opd.dto.VisitRegistrationRequest;
import com.hospital.opd.model.VisitEvent;
import com.hospital.opd.model.VisitStatus;
import com.hospital.opd.service.ConsultationWorkflowService;
import com.hospital.opd.service.VisitService;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * Visit endpoints. Role checks that depend on the visit itself (assigned doctor, status) go through
 * the {@code visitAccess} policy bean (security/VisitAccessPolicy), never ad-hoc in here.
 */
@RestController
@RequestMapping("/api/v1/opd/visits")
public class VisitController {

    private static final EnumSet<VisitStatus> DEFAULT_DOCTOR_QUEUE =
            EnumSet.of(VisitStatus.TRIAGED, VisitStatus.IN_CONSULTATION, VisitStatus.LAB_RESULTS_READY);

    private final VisitService visitService;
    private final ConsultationWorkflowService workflowService;
    private final ClinicalAccessService clinicalAccess;

    public VisitController(VisitService visitService, ConsultationWorkflowService workflowService,
                           ClinicalAccessService clinicalAccess) {
        this.visitService = visitService;
        this.workflowService = workflowService;
        this.clinicalAccess = clinicalAccess;
    }

    /** Returning patient: open a new visit by MRN. */
    @PostMapping
    @PreAuthorize("hasAnyRole('NURSE', 'ADMIN')")
    public ResponseEntity<VisitQueueItem> createVisit(@Valid @RequestBody VisitRegistrationRequest request,
                                                      @AuthenticationPrincipal String registrar) {
        return new ResponseEntity<>(visitService.registerVisit(request, registrar), HttpStatus.CREATED);
    }

    /** Visit summary (no diagnosis or notes). */
    @GetMapping("/{id}")
    @PreAuthorize("@visitAccess.canReadSummary(#id, authentication)")
    public VisitQueueItem getVisit(@PathVariable Long id) {
        return visitService.queueItem(id);
    }

    /** Diagnosis and consultation notes: assigned doctor, or a doctor with a break-glass grant. Logged. */
    @GetMapping("/{id}/clinical")
    @PreAuthorize("@visitAccess.canReadClinical(#id, authentication)")
    public VisitClinicalView getClinical(@PathVariable Long id, @AuthenticationPrincipal String user) {
        clinicalAccess.recordRead(id, user, ClinicalAccessLog.Action.READ_CLINICAL_NOTES);
        return clinicalAccess.clinicalView(id);
    }

    /** Emergency access to a visit assigned to another doctor: 30 minutes, with a reason, in the visit history. */
    @PostMapping("/{id}/break-glass")
    @PreAuthorize("hasRole('DOCTOR')")
    public Map<String, OffsetDateTime> breakGlass(@PathVariable Long id, @Valid @RequestBody BreakGlassRequest request,
                                                  @AuthenticationPrincipal String doctor) {
        return Map.of("expiresAt", clinicalAccess.breakGlass(id, doctor, request.reason()).getExpiresAt());
    }

    /** Audit trail: every status change with who did it and when. */
    @GetMapping("/{id}/events")
    @PreAuthorize("@visitAccess.canReadSummary(#id, authentication)")
    public List<VisitEvent> getVisitEvents(@PathVariable Long id) {
        return visitService.history(id);
    }

    /** The logged-in doctor's own worklist. Optional ?status=TRIAGED&status=AWAITING_LAB ... */
    @GetMapping("/doctor/queue")
    @PreAuthorize("hasRole('DOCTOR')")
    public List<VisitQueueItem> getMyQueue(@AuthenticationPrincipal String doctor,
                                           @RequestParam(value = "status", required = false) List<VisitStatus> status) {
        return visitService.getDoctorQueue(doctor, status == null || status.isEmpty() ? DEFAULT_DOCTOR_QUEUE : status);
    }

    /** Triage worklist: everyone waiting for vitals, emergencies first. */
    @GetMapping("/triage-queue")
    @PreAuthorize("hasAnyRole('NURSE', 'ADMIN')")
    public List<VisitQueueItem> getTriageQueue() {
        return visitService.getTriageQueue();
    }

    /** Everyone registered today, any status. */
    @GetMapping("/today")
    @PreAuthorize("hasAnyRole('NURSE', 'DOCTOR', 'ADMIN')")
    public List<VisitQueueItem> getTodaysVisits() {
        return visitService.getTodaysVisits();
    }

    @PostMapping("/{id}/start-consultation")
    @PreAuthorize("@visitAccess.canWriteClinical(#id, authentication)")
    public ResponseEntity<Void> startConsultation(@PathVariable Long id, @AuthenticationPrincipal String doctor) {
        workflowService.startConsultation(id, doctor);
        return ResponseEntity.noContent().build();
    }

    /** Queues the lab order; the outbox relay delivers it to the lab (usually within a couple of seconds). */
    @PostMapping("/{id}/send-to-lab")
    @PreAuthorize("@visitAccess.canWriteClinical(#id, authentication)")
    public ResponseEntity<Void> sendToLab(@PathVariable Long id,
                                          @Valid @RequestBody SendToLabRequest request,
                                          @AuthenticationPrincipal String doctor) {
        visitService.sendToLab(id, request.testCodes(), request.clinicalNotes(), doctor);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/conclude")
    @PreAuthorize("@visitAccess.canWriteClinical(#id, authentication)")
    public ResponseEntity<Void> conclude(@PathVariable Long id,
                                         @Valid @RequestBody VisitConclusionRequest request,
                                         @AuthenticationPrincipal String doctor) {
        workflowService.conclude(id, request, doctor);
        return ResponseEntity.noContent().build();
    }

    /** Pulls completed lab orders now (the background sync does the same every few seconds). */
    @PostMapping("/lab-status/refresh")
    @PreAuthorize("hasAnyRole('DOCTOR', 'ADMIN')")
    public Map<String, Integer> refreshLabStatuses() {
        return Map.of("updated", workflowService.refreshLabStatuses());
    }

    /** Lab order and results for a visit. 204 if no lab order was placed. Logged. */
    @GetMapping("/{id}/lab-results")
    @PreAuthorize("@visitAccess.canReadClinical(#id, authentication)")
    public ResponseEntity<VisitLabResults> getLabResults(@PathVariable Long id, @AuthenticationPrincipal String user) {
        clinicalAccess.recordRead(id, user, ClinicalAccessLog.Action.READ_LAB_RESULTS);
        return workflowService.getLabResults(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** PDF lab report. no-store: shared ward PCs must not keep it in the browser cache. Logged. */
    @GetMapping(value = "/{id}/lab-report", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize("@visitAccess.canReadClinical(#id, authentication)")
    public ResponseEntity<byte[]> getLabReport(@PathVariable Long id, @AuthenticationPrincipal String user) {
        clinicalAccess.recordRead(id, user, ClinicalAccessLog.Action.READ_LAB_REPORT);
        ConsultationWorkflowService.LabReport report = workflowService.getLabReport(id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + report.fileName() + "\"")
                .body(report.content());
    }
}
