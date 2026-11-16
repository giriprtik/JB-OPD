package com.hospital.opd.service;

import com.hospital.opd.dto.StaffSummary;
import com.hospital.opd.dto.VisitQueueItem;
import com.hospital.opd.dto.VisitRegistrationRequest;
import com.hospital.opd.crypto.FieldCipher;
import com.hospital.opd.event.VisitChanged;
import com.hospital.opd.exception.ResourceNotFoundException;
import com.hospital.opd.model.*;
import com.hospital.opd.outbox.LabOrderPayload;
import com.hospital.opd.outbox.OutboxService;
import com.hospital.opd.repository.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class VisitService {

    public static final ZoneId HOSPITAL_ZONE = ZoneId.of("Asia/Kathmandu");
    private static final Set<VisitStatus> SENDABLE_TO_LAB = EnumSet.of(VisitStatus.TRIAGED, VisitStatus.IN_CONSULTATION);

    private final VisitRepository visitRepository;
    private final PatientRepository patientRepository;
    private final TokenIssuer tokenIssuer;
    private final VisitEventRepository visitEventRepository;
    private final DoctorIntegrationService doctorIntegrationService;
    private final OutboxService outboxService;
    private final ApplicationEventPublisher events;
    private final FieldCipher cipher;
    private final TransactionTemplate tx;

    public VisitService(VisitRepository visitRepository,
                        PatientRepository patientRepository,
                        TokenIssuer tokenIssuer,
                        VisitEventRepository visitEventRepository,
                        DoctorIntegrationService doctorIntegrationService,
                        OutboxService outboxService,
                        ApplicationEventPublisher events,
                        FieldCipher cipher,
                        TransactionTemplate tx) {
        this.visitRepository = visitRepository;
        this.patientRepository = patientRepository;
        this.tokenIssuer = tokenIssuer;
        this.visitEventRepository = visitEventRepository;
        this.doctorIntegrationService = doctorIntegrationService;
        this.outboxService = outboxService;
        this.events = events;
        this.cipher = cipher;
        this.tx = tx;
    }

    /**
     * Returning patient: open a new visit by MRN.
     * The doctor lookup (HTTP to auth, cached) happens BEFORE the transaction, so no DB connection sits idle
     * while auth answers. Not @Transactional for that reason; the write part runs in the TransactionTemplate.
     */
    public VisitQueueItem registerVisit(VisitRegistrationRequest request, String registrar) {
        String mrn = request.patientMrn().trim();
        requirePatient(mrn);   // unknown MRN fails fast, before any call to the auth service
        StaffSummary doctor = doctorIntegrationService.requireDoctor(request.assignedDoctorUsername().trim());
        Long visitId = tx.execute(status ->
                openVisit(requirePatient(mrn), request.chiefComplaint(), request.priority(), doctor, registrar).getId());
        return queueItem(visitId);
    }

    private Patient requirePatient(String mrn) {
        return patientRepository.findByMrn(mrn)
                .orElseThrow(() -> new ResourceNotFoundException("No patient found with MRN " + mrn));
    }

    /**
     * Opens a visit and issues today's next token. Must run inside the caller's transaction so the
     * token, visit row and audit event commit (or roll back) together.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Visit openVisit(Patient patient, String chiefComplaint, VisitPriority priority,
                           StaffSummary doctor, String registrar) {
        if (patient.getId() != null && visitRepository.hasActiveVisit(patient.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    patient.getFullName() + " already has an open visit today. Complete or cancel it first.");
        }

        LocalDate today = LocalDate.now(HOSPITAL_ZONE);
        int token = tokenIssuer.next(today);   // one INSERT ... ON CONFLICT ... RETURNING: row-locked, race-free

        Visit visit = new Visit();
        visit.setPatient(patient);
        visit.setVisitDate(today);
        visit.setTokenNumber(token);
        visit.setChiefComplaint(chiefComplaint.trim());
        visit.setPriority(priority);
        visit.setStatus(VisitStatus.REGISTERED);
        visit.setAssignedDoctorId(doctor.id());
        visit.setAssignedDoctorUsername(doctor.username());
        visit.setAssignedDoctorName(doctor.fullName());
        visit.setRegisteredBy(registrar);
        Visit saved = visitRepository.save(visit);

        recordEvent(saved.getId(), null, VisitStatus.REGISTERED, registrar,
                "Registered with token #" + token + ", assigned to " + doctor.fullName());
        return saved;
    }

    /** State change + audit event in one transaction. */
    @Transactional
    public void transitionStatus(Long visitId, Set<VisitStatus> allowedFrom, VisitStatus target,
                                 String actor, String note) {
        Visit visit = visitRepository.findById(visitId)
                .orElseThrow(() -> new ResourceNotFoundException("Visit not found: " + visitId));
        VisitStatus from = visit.getStatus();
        if (from == target) return;
        if (!allowedFrom.contains(from)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Visit is " + from + "; cannot move to " + target);
        }
        visit.setStatus(target);
        recordEvent(visitId, from, target, actor, note);
    }

    @Transactional(readOnly = true)
    public List<VisitQueueItem> getDoctorQueue(String doctorUsername, Collection<VisitStatus> statuses) {
        return visitRepository.findDoctorQueue(doctorUsername, statuses.stream().map(Enum::name).toList())
                .stream().map(this::toItem).toList();
    }

    /**
     * One local transaction: move the visit to AWAITING_LAB and queue the lab order in the outbox.
     * No HTTP call here: the outbox relay delivers the order (with an idempotency key), so a slow or
     * down lab never fails the doctor's click and can never leave an orphan or duplicate order.
     * latest_lab_order_id stays null until the lab accepts the order ("queued for lab").
     */
    @Transactional
    public void sendToLab(Long visitId, List<String> testCodes, String clinicalNotes, String doctor) {
        Visit visit = visitRepository.findWithPatient(visitId)
                .orElseThrow(() -> new ResourceNotFoundException("Visit not found: " + visitId));
        requireAssignedDoctor(visit, doctor);

        VisitStatus from = visit.getStatus();
        if (!SENDABLE_TO_LAB.contains(from)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Visit is " + from + "; cannot send to lab");
        }

        String history = clinicalNotes != null && !clinicalNotes.isBlank() ? clinicalNotes.trim() : visit.getChiefComplaint();
        UUID requestId = UUID.randomUUID();

        visit.setStatus(VisitStatus.AWAITING_LAB);   // @Version on Visit: a concurrent double-click fails with 409
        visit.setLatestLabOrderId(null);
        outboxService.enqueueLabOrder(new LabOrderPayload(requestId, visitId, visit.getPatient().getMrn(),
                visit.getPatient().getFullName(), List.copyOf(testCodes), history, doctor));
        recordEvent(visitId, from, VisitStatus.AWAITING_LAB, doctor,
                "Lab order queued: " + String.join(", ", testCodes));
    }

    @Transactional(readOnly = true)
    public List<VisitQueueItem> getTriageQueue() {
        return visitRepository.findTriageQueue().stream().map(this::toItem).toList();
    }

    @Transactional(readOnly = true)
    public List<VisitQueueItem> getTodaysVisits() {
        return visitRepository.findTodaysVisits().stream().map(this::toItem).toList();
    }

    @Transactional(readOnly = true)
    public VisitQueueItem queueItem(Long visitId) {
        return visitRepository.findQueueItem(visitId)
                .map(this::toItem)
                .orElseThrow(() -> new ResourceNotFoundException("Visit not found: " + visitId));
    }

    @Transactional(readOnly = true)
    public List<VisitEvent> history(Long visitId) {
        return visitEventRepository.findByVisitIdOrderByOccurredAtAsc(visitId);
    }

    /** Audit row + a live-update event (delivered to open screens after the transaction commits). */
    public void recordEvent(Long visitId, VisitStatus from, VisitStatus to, String actor, String note) {
        visitEventRepository.save(new VisitEvent(visitId, from == null ? null : from.name(), to.name(), actor, note));
        events.publishEvent(new VisitChanged(visitId, to.name()));
    }

    /** Native query rows skip JPA converters: decrypt the encrypted columns here. */
    private VisitQueueItem toItem(VisitQueueRow r) {
        return new VisitQueueItem(r.getVisitId(), r.getPatientMrn(), r.getPatientFullName(), r.getTokenNumber(),
                cipher.decrypt(r.getChiefComplaint()), r.getPriority(), r.getStatus(), r.getPatientAge(),
                r.getPatientGender(), r.getPatientBloodGroup(), cipher.decrypt(r.getPatientAllergies()),
                r.getAssignedDoctorUsername(), r.getAssignedDoctorName(), r.getRegisteredTime(), r.getLabOrderId(),
                r.getTemperatureCelsius(), r.getPulseRate(), r.getBloodPressure(), r.getOxygenSaturation(),
                cipher.decrypt(r.getSymptomsBrief()));
    }

    /** Doctors may only act on their own patients. */
    public static void requireAssignedDoctor(Visit visit, String doctor) {
        if (visit.getAssignedDoctorUsername() != null && !visit.getAssignedDoctorUsername().equals(doctor)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This patient is assigned to another doctor");
        }
    }
}
