package com.hospital.opd.security;

import com.hospital.opd.access.AccessGrantRepository;
import com.hospital.opd.model.VisitStatus;
import com.hospital.opd.repository.VisitAccessView;
import com.hospital.opd.repository.VisitRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VisitAccessPolicyTest {

    private final VisitRepository visits = mock(VisitRepository.class);
    private final AccessGrantRepository grants = mock(AccessGrantRepository.class);
    private final VisitAccessPolicy policy = new VisitAccessPolicy(visits, grants);

    private static final Authentication DOCTOR = user("doctor", "DOCTOR");
    private static final Authentication OTHER_DOCTOR = user("drkarki", "DOCTOR");
    private static final Authentication NURSE = user("nurse", "NURSE");
    private static final Authentication ADMIN = user("admin", "ADMIN");
    private static final Authentication LABTECH = user("labtech", "LABTECH");

    @BeforeEach
    void visit5IsAssignedToDoctor() {
        status(VisitStatus.IN_CONSULTATION);
    }

    @Test
    void onlyTheAssignedDoctorReadsClinicalData() {
        assertThat(policy.canReadClinical(5L, DOCTOR)).isTrue();
        assertThat(policy.canReadClinical(5L, OTHER_DOCTOR)).isFalse();
        assertThat(policy.canReadClinical(5L, NURSE)).isFalse();
        assertThat(policy.canReadClinical(5L, ADMIN)).isFalse();
        assertThat(policy.canReadClinical(5L, LABTECH)).isFalse();
    }

    @Test
    void aLiveBreakGlassGrantLetsAnotherDoctorRead() {
        when(grants.existsByVisitIdAndUsernameAndExpiresAtAfter(eq(5L), eq("drkarki"), any())).thenReturn(true);

        assertThat(policy.canReadClinical(5L, OTHER_DOCTOR)).isTrue();
        assertThat(policy.canWriteClinical(5L, OTHER_DOCTOR)).isFalse();   // read-only access
    }

    @Test
    void aGrantNeverHelpsANonDoctor() {
        when(grants.existsByVisitIdAndUsernameAndExpiresAtAfter(any(), any(), any())).thenReturn(true);

        assertThat(policy.canReadClinical(5L, NURSE)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = VisitStatus.class, names = {"COMPLETED", "CANCELLED"})
    void noClinicalWritesOnceTheVisitIsClosed(VisitStatus closed) {
        status(closed);

        assertThat(policy.canWriteClinical(5L, DOCTOR)).isFalse();
        assertThat(policy.canReadClinical(5L, DOCTOR)).isTrue();
    }

    @Test
    void writesNeedTheAssignedDoctor() {
        assertThat(policy.canWriteClinical(5L, DOCTOR)).isTrue();
        assertThat(policy.canWriteClinical(5L, OTHER_DOCTOR)).isFalse();
        assertThat(policy.canWriteClinical(5L, NURSE)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = VisitStatus.class, names = {"REGISTERED", "TRIAGED", "IN_CONSULTATION"})
    void nursesRecordVitalsWhileTheVisitIsOpen(VisitStatus open) {
        status(open);
        assertThat(policy.canRecordVitals(5L, NURSE)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = VisitStatus.class, names = {"AWAITING_LAB", "LAB_RESULTS_READY", "COMPLETED", "CANCELLED"})
    void andNotAfterwards(VisitStatus later) {
        status(later);
        assertThat(policy.canRecordVitals(5L, NURSE)).isFalse();
    }

    @Test
    void onlyNursesRecordVitals() {
        assertThat(policy.canRecordVitals(5L, ADMIN)).isFalse();
        assertThat(policy.canRecordVitals(5L, DOCTOR)).isFalse();
    }

    @Test
    void summaryIsForDeskStaffAndClinicalReaders() {
        assertThat(policy.canReadSummary(5L, NURSE)).isTrue();
        assertThat(policy.canReadSummary(5L, ADMIN)).isTrue();
        assertThat(policy.canReadSummary(5L, DOCTOR)).isTrue();
        assertThat(policy.canReadSummary(5L, OTHER_DOCTOR)).isFalse();
        assertThat(policy.canReadSummary(5L, LABTECH)).isFalse();
    }

    @Test
    void anUnknownVisitGrantsNothing() {
        when(visits.findAccessView(99L)).thenReturn(Optional.empty());

        assertThat(policy.canReadClinical(99L, DOCTOR)).isFalse();
        assertThat(policy.canWriteClinical(99L, DOCTOR)).isFalse();
        assertThat(policy.canRecordVitals(99L, NURSE)).isFalse();
        assertThat(policy.canRecordVitals(null, NURSE)).isFalse();
    }

    private void status(VisitStatus status) {
        when(visits.findAccessView(5L)).thenReturn(Optional.of(new VisitAccessView("doctor", status)));
    }

    private static Authentication user(String name, String role) {
        return new UsernamePasswordAuthenticationToken(name, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }
}
