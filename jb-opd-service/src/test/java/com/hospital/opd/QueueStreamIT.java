package com.hospital.opd;

import com.hospital.opd.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static com.hospital.opd.support.TestJwt.bearer;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The live-update stream: who may open it. Event routing is covered by QueueStreamHubTest. */
class QueueStreamIT extends PostgresIntegrationTest {

    @Autowired MockMvc mvc;

    @Test
    void nursesAndDoctorsCanOpenTheStream() throws Exception {
        for (String token : new String[]{bearer("nurse", "NURSE"), bearer("doctor", "DOCTOR")}) {
            mvc.perform(get("/api/v1/opd/stream").header(AUTHORIZATION, token).accept(MediaType.TEXT_EVENT_STREAM))
                    .andExpect(status().isOk())
                    .andExpect(request().asyncStarted());
        }
    }

    @Test
    void labTechniciansCannot() throws Exception {
        mvc.perform(get("/api/v1/opd/stream").header(AUTHORIZATION, bearer("labtech", "LABTECH"))
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousCallersAre401() throws Exception {
        mvc.perform(get("/api/v1/opd/stream").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isUnauthorized());
    }
}
