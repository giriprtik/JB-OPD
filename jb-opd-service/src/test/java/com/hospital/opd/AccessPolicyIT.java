package com.hospital.opd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospital.opd.access.ClinicalAccessLogRepository;
import com.hospital.opd.dto.StaffSummary;
import com.hospital.opd.service.DoctorIntegrationService;
import com.hospital.opd.service.LabIntegrationService;
import com.hospital.opd.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static com.hospital.opd.support.TestJwt.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Walks the access policy table over HTTP. Keep this in step with VisitAccessPolicy and the
 * policy table in the hardening guide: when one changes, all three change.
 */
class AccessPolicyIT extends PostgresIntegrationTest {

    private static final String NURSE = bearer("nurse", "NURSE");
    private static final String ADMIN = bearer("admin", "ADMIN");
    private static final String DOCTOR = bearer("doctor", "DOCTOR");
    private static final String OTHER_DOCTOR = bearer("drkarki", "DOCTOR");
    private static final String LABTECH = bearer("labtech", "LABTECH");
    private static final AtomicLong PHONES = new AtomicLong(9_851_000_000L);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ClinicalAccessLogRepository accessLog;

    @MockBean DoctorIntegrationService doctors;
    @MockBean LabIntegrationService lab;

    @BeforeEach
    void knownDoctor() {
        when(doctors.requireDoctor("doctor")).thenReturn(
                new StaffSummary(UUID.randomUUID(), "doctor", "Dr. Ramesh Sharma", "DOCTOR", "GENERAL_OPD", "General Medicine"));
    }

    // ---------------------------------------------------------------- summary vs clinical

    @Test
    void visitSummaryIsForDeskStaffAndTheAssignedDoctor() throws Exception {
        long id = registeredVisit();

        expect(get("/api/v1/opd/visits/{id}", id), NURSE, 200);
        expect(get("/api/v1/opd/visits/{id}", id), ADMIN, 200);
        expect(get("/api/v1/opd/visits/{id}", id), DOCTOR, 200);
        expect(get("/api/v1/opd/visits/{id}", id), OTHER_DOCTOR, 403);
        expect(get("/api/v1/opd/visits/{id}", id), LABTECH, 403);
    }

    @Test
    void queuesNeverCarryTheDiagnosis() throws Exception {
        long id = registeredVisit();

        mvc.perform(get("/api/v1/opd/visits/triage-queue").header(AUTHORIZATION, NURSE))
                .andExpect(jsonPath("$[?(@.visitId == %d)].diagnosis", id).isEmpty());
        mvc.perform(get("/api/v1/opd/visits/{id}", id).header(AUTHORIZATION, NURSE))
                .andExpect(jsonPath("$.diagnosis").doesNotExist());
    }

    @Test
    void clinicalNotesAndLabDataAreForTheAssignedDoctorOnly() throws Exception {
        long id = triagedVisit();

        for (String path : new String[]{"/api/v1/opd/visits/{id}/clinical", "/api/v1/opd/visits/{id}/lab-results"}) {
            expect(get(path, id), NURSE, 403);
            expect(get(path, id), ADMIN, 403);
            expect(get(path, id), OTHER_DOCTOR, 403);
            expect(get(path, id), LABTECH, 403);
        }
        expect(get("/api/v1/opd/visits/{id}/clinical", id), DOCTOR, 200);
        expect(get("/api/v1/opd/visits/{id}/lab-results", id), DOCTOR, 204);   // no lab order yet
    }

    @Test
    void everyClinicalReadIsLogged() throws Exception {
        long id = triagedVisit();

        expect(get("/api/v1/opd/visits/{id}/clinical", id), DOCTOR, 200);
        expect(get("/api/v1/opd/visits/{id}/lab-results", id), DOCTOR, 204);

        assertThat(accessLog.findByVisitIdOrderByOccurredAtAsc(id))
                .extracting(e -> e.getUsername() + ":" + e.getAction())
                .containsExactly("doctor:READ_CLINICAL_NOTES", "doctor:READ_LAB_RESULTS");
    }

    // ---------------------------------------------------------------- writes

    @Test
    void vitalsAreForNursesOnlyAndOnlyWhileTheVisitIsOpen() throws Exception {
        long id = registeredVisit();

        expect(post("/api/v1/opd/triage/vitals"), ADMIN, vitals(id), 403);
        expect(post("/api/v1/opd/triage/vitals"), DOCTOR, vitals(id), 403);
        expect(post("/api/v1/opd/triage/vitals"), NURSE, vitals(id), 201);

        // Once the doctor has concluded, no more vitals.
        expect(post("/api/v1/opd/visits/{id}/conclude", id), DOCTOR,
                "{\"diagnosis\": \"Viral fever\", \"consultationNotes\": \"Rest and fluids\"}", 204);
        expect(post("/api/v1/opd/triage/vitals"), NURSE, vitals(id), 403);
    }

    @Test
    void onlyTheAssignedDoctorActsOnTheVisitAndNotAfterSignOff() throws Exception {
        long id = triagedVisit();

        expect(post("/api/v1/opd/visits/{id}/start-consultation", id), OTHER_DOCTOR, "", 403);
        expect(post("/api/v1/opd/visits/{id}/start-consultation", id), NURSE, "", 403);
        expect(post("/api/v1/opd/visits/{id}/start-consultation", id), DOCTOR, "", 204);
        expect(post("/api/v1/opd/visits/{id}/conclude", id), DOCTOR,
                "{\"diagnosis\": \"Viral fever\", \"consultationNotes\": \"Rest and fluids\"}", 204);

        // Signed off: the diagnosis can no longer be rewritten.
        expect(post("/api/v1/opd/visits/{id}/conclude", id), DOCTOR,
                "{\"diagnosis\": \"Changed\", \"consultationNotes\": \"x\"}", 403);
        mvc.perform(get("/api/v1/opd/visits/{id}/clinical", id).header(AUTHORIZATION, DOCTOR))
                .andExpect(jsonPath("$.diagnosis").value("Viral fever"));
    }

    // ---------------------------------------------------------------- break-glass

    @Test
    void breakGlassGivesAnotherDoctorTimeLimitedReadAccessAndLeavesATrail() throws Exception {
        long id = triagedVisit();
        expect(get("/api/v1/opd/visits/{id}/clinical", id), OTHER_DOCTOR, 403);

        JsonNode grant = send(post("/api/v1/opd/visits/{id}/break-glass", id), OTHER_DOCTOR,
                "{\"reason\": \"Covering emergency for Dr. Sharma\"}", 200);
        assertThat(grant.get("expiresAt").asText()).isNotBlank();

        expect(get("/api/v1/opd/visits/{id}/clinical", id), OTHER_DOCTOR, 200);
        expect(post("/api/v1/opd/visits/{id}/start-consultation", id), OTHER_DOCTOR, "", 403);   // read only

        mvc.perform(get("/api/v1/opd/visits/{id}/events", id).header(AUTHORIZATION, NURSE))
                .andExpect(jsonPath("$[*].note").value(hasItem("Break-glass access: Covering emergency for Dr. Sharma")));
        assertThat(accessLog.findByVisitIdOrderByOccurredAtAsc(id))
                .extracting(e -> e.getUsername() + ":" + e.getAction())
                .contains("drkarki:BREAK_GLASS", "drkarki:READ_CLINICAL_NOTES");
    }

    @Test
    void breakGlassNeedsARealReasonAndADoctor() throws Exception {
        long id = triagedVisit();

        expect(post("/api/v1/opd/visits/{id}/break-glass", id), OTHER_DOCTOR, "{\"reason\": \"x\"}", 400);
        expect(post("/api/v1/opd/visits/{id}/break-glass", id), NURSE,
                "{\"reason\": \"I would like to look please\"}", 403);
    }

    // ---------------------------------------------------------------- helpers

    private long registeredVisit() throws Exception {
        String body = """
                {"fullName": "Hari Prasad", "dateOfBirth": "1980-02-02", "gender": "MALE", "phone": "%d",
                 "chiefComplaint": "Cough", "priority": "NORMAL", "assignedDoctorUsername": "doctor"}
                """.formatted(PHONES.incrementAndGet());
        return send(post("/api/v1/opd/patients"), NURSE, body, 201).get("visitId").asLong();
    }

    private long triagedVisit() throws Exception {
        long id = registeredVisit();
        send(post("/api/v1/opd/triage/vitals"), NURSE, vitals(id), 201);
        return id;
    }

    private static String vitals(long visitId) {
        return """
                {"visitId": %d, "temperatureCelsius": 37.0, "pulseRate": 80,
                 "bloodPressure": "120/80", "oxygenSaturation": 98}
                """.formatted(visitId);
    }

    private void expect(MockHttpServletRequestBuilder request, String token, int expected) throws Exception {
        mvc.perform(request.header(AUTHORIZATION, token)).andExpect(status().is(expected));
    }

    private void expect(MockHttpServletRequestBuilder request, String token, String body, int expected) throws Exception {
        send(request, token, body, expected);
    }

    private JsonNode send(MockHttpServletRequestBuilder request, String token, String body, int expected) throws Exception {
        String response = mvc.perform(request.header(AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected))
                .andReturn().getResponse().getContentAsString();
        return response.isEmpty() ? json.nullNode() : json.readTree(response);
    }
}
