package com.hospital.opd;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospital.opd.crypto.EncryptionBackfill;
import com.hospital.opd.crypto.FieldCipher;
import com.hospital.opd.dto.StaffSummary;
import com.hospital.opd.service.DoctorIntegrationService;
import com.hospital.opd.service.LabIntegrationService;
import com.hospital.opd.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

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

/** Clinical text is ciphertext in PostgreSQL and plain text through the API. */
class EncryptionIT extends PostgresIntegrationTest {

    private static final String NURSE = bearer("nurse", "NURSE");
    private static final String DOCTOR = bearer("doctor", "DOCTOR");
    private static final AtomicLong PHONES = new AtomicLong(9_861_000_000L);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired FieldCipher cipher;

    @MockBean DoctorIntegrationService doctors;
    @MockBean LabIntegrationService lab;

    @BeforeEach
    void knownDoctor() {
        when(doctors.requireDoctor("doctor")).thenReturn(
                new StaffSummary(UUID.randomUUID(), "doctor", "Dr. Ramesh Sharma", "DOCTOR", "GENERAL_OPD", "General Medicine"));
    }

    @Test
    void clinicalColumnsAreEncryptedAtRestAndReadableThroughTheApi() throws Exception {
        long visitId = registerAndTriage();
        post204("/api/v1/opd/visits/{id}/conclude", visitId,
                "{\"diagnosis\": \"Enteric fever (A01.0)\", \"consultationNotes\": \"Start azithromycin\"}");

        var visit = jdbc.queryForMap("SELECT chief_complaint, diagnosis, consultation_notes FROM visits WHERE id = ?", visitId);
        assertThat(visit.values()).allSatisfy(v -> assertThat((String) v).startsWith("enc:k1:"));
        assertThat((String) visit.get("diagnosis")).doesNotContain("Enteric");

        String allergies = jdbc.queryForObject(
                "SELECT p.allergies FROM patients p JOIN visits v ON v.patient_id = p.id WHERE v.id = ?", String.class, visitId);
        assertThat(allergies).startsWith("enc:k1:");
        String symptoms = jdbc.queryForObject("SELECT symptoms_brief FROM vital_signs WHERE visit_id = ?", String.class, visitId);
        assertThat(symptoms).startsWith("enc:k1:");

        // Entity reads (JPA converter) and the native worklist query (explicit decrypt) both return plain text.
        mvc.perform(get("/api/v1/opd/visits/{id}/clinical", visitId).header(AUTHORIZATION, DOCTOR))
                .andExpect(jsonPath("$.diagnosis").value("Enteric fever (A01.0)"))
                .andExpect(jsonPath("$.consultationNotes").value("Start azithromycin"));
        mvc.perform(get("/api/v1/opd/visits/{id}", visitId).header(AUTHORIZATION, NURSE))
                .andExpect(jsonPath("$.chiefComplaint").value("High fever"))
                .andExpect(jsonPath("$.patientAllergies").value("Penicillin"))
                .andExpect(jsonPath("$.symptomsBrief").value("[FEVER] chills"));

        // The audit trail no longer copies the diagnosis.
        mvc.perform(get("/api/v1/opd/visits/{id}/events", visitId).header(AUTHORIZATION, NURSE))
                .andExpect(jsonPath("$[*].note").value(hasItem("Signed off")));
    }

    @Test
    void aTamperedValueFailsLoudlyWithoutLeakingIt() throws Exception {
        long visitId = registerAndTriage();
        String stored = jdbc.queryForObject("SELECT chief_complaint FROM visits WHERE id = ?", String.class, visitId);
        String tampered = stored.substring(0, stored.length() - 4) + (stored.charAt(stored.length() - 4) == 'A' ? "B" : "A")
                + stored.substring(stored.length() - 3);
        jdbc.update("UPDATE visits SET chief_complaint = ? WHERE id = ?", tampered, visitId);
        try {
            String body = mvc.perform(get("/api/v1/opd/visits/{id}", visitId).header(AUTHORIZATION, NURSE))
                    .andExpect(status().isInternalServerError())
                    .andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContain("High fever");
        } finally {
            // Put the real value back: the test classes share one database, and this visit sits in
            // "doctor"'s queue, so a corrupted row would break every later test that lists that queue.
            jdbc.update("UPDATE visits SET chief_complaint = ? WHERE id = ?", stored, visitId);
        }
    }

    @Test
    void theBackfillEncryptsLegacyPlaintextAndIsIdempotent() throws Exception {
        long visitId = registerAndTriage();
        jdbc.update("UPDATE vital_signs SET symptoms_brief = 'legacy plain text' WHERE visit_id = ?", visitId);
        EncryptionBackfill backfill = new EncryptionBackfill(jdbc, cipher);

        backfill.run(null);
        String after = jdbc.queryForObject("SELECT symptoms_brief FROM vital_signs WHERE visit_id = ?", String.class, visitId);
        assertThat(after).startsWith("enc:k1:");
        assertThat(cipher.decrypt(after)).isEqualTo("legacy plain text");

        backfill.run(null);   // second run changes nothing
        assertThat(jdbc.queryForObject("SELECT symptoms_brief FROM vital_signs WHERE visit_id = ?", String.class, visitId))
                .isEqualTo(after);
    }

    // ---------------------------------------------------------------- helpers

    private long registerAndTriage() throws Exception {
        String patient = """
                {"fullName": "Gita Thapa", "dateOfBirth": "1975-06-06", "gender": "FEMALE", "phone": "%d",
                 "allergies": "Penicillin", "chiefComplaint": "High fever", "priority": "URGENT",
                 "assignedDoctorUsername": "doctor"}
                """.formatted(PHONES.incrementAndGet());
        String created = mvc.perform(post("/api/v1/opd/patients").header(AUTHORIZATION, NURSE)
                        .contentType(MediaType.APPLICATION_JSON).content(patient))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long visitId = json.readTree(created).get("visitId").asLong();

        mvc.perform(post("/api/v1/opd/triage/vitals").header(AUTHORIZATION, NURSE)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"visitId": %d, "temperatureCelsius": 39.1, "pulseRate": 104,
                                 "bloodPressure": "110/70", "oxygenSaturation": 97, "symptomsBrief": "chills"}
                                """.formatted(visitId)))
                .andExpect(status().isCreated());
        return visitId;
    }

    private void post204(String path, long id, String body) throws Exception {
        mvc.perform(post(path, id).header(AUTHORIZATION, DOCTOR).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNoContent());
    }
}
