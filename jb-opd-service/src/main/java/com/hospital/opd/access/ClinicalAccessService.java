package com.hospital.opd.access;

import com.hospital.opd.dto.VisitClinicalView;
import com.hospital.opd.exception.ResourceNotFoundException;
import com.hospital.opd.model.Visit;
import com.hospital.opd.model.VisitStatus;
import com.hospital.opd.repository.VisitRepository;
import com.hospital.opd.service.VisitService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.OffsetDateTime;

/** Read audit and break-glass grants. */
@Service
public class ClinicalAccessService {

    static final Duration GRANT_LENGTH = Duration.ofMinutes(30);

    private final ClinicalAccessLogRepository log;
    private final AccessGrantRepository grants;
    private final VisitRepository visits;
    private final VisitService visitService;

    public ClinicalAccessService(ClinicalAccessLogRepository log, AccessGrantRepository grants,
                                 VisitRepository visits, VisitService visitService) {
        this.log = log;
        this.grants = grants;
        this.visits = visits;
        this.visitService = visitService;
    }

    /** Own transaction: the audit row is kept even if the read that follows fails. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordRead(Long visitId, String username, ClinicalAccessLog.Action action) {
        log.save(new ClinicalAccessLog(visitId, username, action, OffsetDateTime.now()));
    }

    @Transactional(readOnly = true)
    public VisitClinicalView clinicalView(Long visitId) {
        Visit v = visits.findById(visitId)
                .orElseThrow(() -> new ResourceNotFoundException("Visit not found: " + visitId));
        return new VisitClinicalView(v.getId(), v.getStatus().name(), v.getDiagnosis(), v.getConsultationNotes(),
                v.getCompletedAt());
    }

    /**
     * Emergency access for a doctor who is not assigned to the visit. Short-lived, logged, and visible in the
     * visit's history so the assigned doctor sees who opened their patient and why.
     */
    @Transactional
    public AccessGrant breakGlass(Long visitId, String doctor, String reason) {
        Visit visit = visits.findById(visitId)
                .orElseThrow(() -> new ResourceNotFoundException("Visit not found: " + visitId));
        if (doctor.equals(visit.getAssignedDoctorUsername())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "You are already the assigned doctor");
        }
        OffsetDateTime now = OffsetDateTime.now();
        AccessGrant grant = grants.save(new AccessGrant(visitId, doctor, reason.trim(), now, now.plus(GRANT_LENGTH)));
        log.save(new ClinicalAccessLog(visitId, doctor, ClinicalAccessLog.Action.BREAK_GLASS, now));
        VisitStatus status = visit.getStatus();
        visitService.recordEvent(visitId, status, status, doctor, "Break-glass access: " + reason.trim());
        return grant;
    }
}
