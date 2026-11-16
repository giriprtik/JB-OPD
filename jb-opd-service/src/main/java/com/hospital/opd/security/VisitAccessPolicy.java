package com.hospital.opd.security;

import com.hospital.opd.access.AccessGrantRepository;
import com.hospital.opd.model.VisitStatus;
import com.hospital.opd.repository.VisitAccessView;
import com.hospital.opd.repository.VisitRepository;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * Every per-visit access rule in one place, used from @PreAuthorize as {@code @visitAccess.…}.
 * The rules mirror the policy table in the hardening guide; change both together.
 *
 * <pre>
 *   clinical read  (diagnosis, notes, lab results/PDF): assigned doctor, or a doctor with a live break-glass grant
 *   clinical write (start, send to lab, conclude):      assigned doctor, visit not COMPLETED/CANCELLED
 *   record vitals:                                      nurse, visit REGISTERED / TRIAGED / IN_CONSULTATION
 *   visit summary / history (no clinical text):         nurse, admin, or anyone with clinical read
 * </pre>
 */
@Component("visitAccess")
public class VisitAccessPolicy {

    private static final Set<VisitStatus> VITALS_OPEN =
            EnumSet.of(VisitStatus.REGISTERED, VisitStatus.TRIAGED, VisitStatus.IN_CONSULTATION);
    private static final Set<VisitStatus> CLOSED = EnumSet.of(VisitStatus.COMPLETED, VisitStatus.CANCELLED);

    private final VisitRepository visits;
    private final AccessGrantRepository grants;

    public VisitAccessPolicy(VisitRepository visits, AccessGrantRepository grants) {
        this.visits = visits;
        this.grants = grants;
    }

    public boolean isAssignedDoctor(Long visitId, Authentication auth) {
        return hasRole(auth, "DOCTOR") && view(visitId)
                .map(v -> auth.getName().equals(v.assignedDoctorUsername()))
                .orElse(false);
    }

    public boolean canReadClinical(Long visitId, Authentication auth) {
        if (!hasRole(auth, "DOCTOR")) return false;
        return isAssignedDoctor(visitId, auth)
                || grants.existsByVisitIdAndUsernameAndExpiresAtAfter(visitId, auth.getName(), OffsetDateTime.now());
    }

    public boolean canWriteClinical(Long visitId, Authentication auth) {
        return hasRole(auth, "DOCTOR") && view(visitId)
                .filter(v -> auth.getName().equals(v.assignedDoctorUsername()))
                .map(v -> !CLOSED.contains(v.status()))
                .orElse(false);
    }

    public boolean canRecordVitals(Long visitId, Authentication auth) {
        return hasRole(auth, "NURSE") && view(visitId)
                .map(v -> VITALS_OPEN.contains(v.status()))
                .orElse(false);
    }

    /** Summary fields and the status history carry no clinical free text, so desk staff may read them. */
    public boolean canReadSummary(Long visitId, Authentication auth) {
        return hasRole(auth, "NURSE") || hasRole(auth, "ADMIN") || canReadClinical(visitId, auth);
    }

    private Optional<VisitAccessView> view(Long visitId) {
        return visitId == null ? Optional.empty() : visits.findAccessView(visitId);
    }

    private static boolean hasRole(Authentication auth, String role) {
        if (auth == null) return false;
        String authority = "ROLE_" + role;
        return auth.getAuthorities().stream().anyMatch(a -> authority.equals(a.getAuthority()));
    }
}
