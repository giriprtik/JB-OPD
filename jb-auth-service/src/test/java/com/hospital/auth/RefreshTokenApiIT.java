package com.hospital.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospital.auth.support.PostgresIntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpHeaders.SET_COOKIE;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Refresh-cookie flow over HTTP on a real PostgreSQL (demo accounts seeded, password Demo@1234). */
class RefreshTokenApiIT extends PostgresIntegrationTest {

    private static final String COOKIE = "jb_rt";
    private static final String MARKER = "X-JB-Refresh";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    @Test
    void loginSetsAHardenedRefreshCookieAndNeverPutsTheRefreshTokenInTheBody() throws Exception {
        MvcResult result = login("nurse")
                .andExpect(header().string(SET_COOKIE, allOf(
                        containsString(COOKIE + "="),
                        containsString("HttpOnly"),
                        containsString("Secure"),
                        containsString("SameSite=Strict"),
                        containsString("Path=/api/v1/auth"))))
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andReturn();

        assertThat(cookieValue(result)).isNotBlank();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM refresh_token t JOIN user_account u ON u.id = t.user_id WHERE u.username = 'nurse'",
                Integer.class)).isPositive();
    }

    @Test
    void refreshRotatesTheCookieAndReturnsAWorkingAccessToken() throws Exception {
        String first = cookieValue(login("doctor").andReturn());

        MvcResult refreshed = refresh(first)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("doctor"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andReturn();
        String second = cookieValue(refreshed);
        String accessToken = json.readTree(refreshed.getResponse().getContentAsString()).get("accessToken").asText();

        assertThat(second).isNotBlank().isNotEqualTo(first);
        mvc.perform(get("/api/v1/auth/profile").header(AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("doctor"));
    }

    @Test
    void refreshWithoutTheMarkerHeaderIsRefused() throws Exception {
        String cookie = cookieValue(login("nurse").andReturn());

        mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie(COOKIE, cookie)))
                .andExpect(status().isForbidden());
    }

    @Test
    void refreshWithoutACookieIs401() throws Exception {
        mvc.perform(post("/api/v1/auth/refresh").header(MARKER, "1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void replayingAnOldCookieAfterTheGraceWindowKillsTheWholeLogin() throws Exception {
        String first = cookieValue(login("labtech").andReturn());
        String second = cookieValue(refresh(first).andExpect(status().isOk()).andReturn());

        // Pretend the rotation happened a minute ago, i.e. outside the 10-second two-tab grace window.
        jdbc.update("UPDATE refresh_token SET revoked_at = NOW() - INTERVAL '1 minute' WHERE revoke_reason = 'ROTATED'");

        refresh(first)
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(SET_COOKIE, containsString("Max-Age=0")));
        // The legitimate holder of the newer token is signed out too: the family is revoked.
        refresh(second).andExpect(status().isUnauthorized());
    }

    @Test
    void twoTabsRefreshingAtOnceBothSucceed() throws Exception {
        String shared = cookieValue(login("drkarki").andReturn());

        refresh(shared).andExpect(status().isOk());
        refresh(shared).andExpect(status().isOk());   // inside the grace window
    }

    @Test
    void logoutRevokesTheSessionAndClearsTheCookie() throws Exception {
        String cookie = cookieValue(login("admin").andReturn());

        mvc.perform(post("/api/v1/auth/logout").cookie(new Cookie(COOKIE, cookie)))
                .andExpect(status().isNoContent())
                .andExpect(header().string(SET_COOKIE, containsString("Max-Age=0")));

        refresh(cookie).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutWithoutACookieIsStillNoContent() throws Exception {
        mvc.perform(post("/api/v1/auth/logout")).andExpect(status().isNoContent());
    }

    // ---------------------------------------------------------------- helpers

    private org.springframework.test.web.servlet.ResultActions login(String username) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\": \"%s\", \"password\": \"Demo@1234\"}".formatted(username)))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions refresh(String cookie) throws Exception {
        return mvc.perform(post("/api/v1/auth/refresh").header(MARKER, "1").cookie(new Cookie(COOKIE, cookie)));
    }

    /** Value of the jb_rt cookie from the Set-Cookie response header. */
    private static String cookieValue(MvcResult result) {
        String header = result.getResponse().getHeader(SET_COOKIE);
        assertThat(header).as("Set-Cookie header").isNotNull().startsWith(COOKIE + "=");
        return header.substring((COOKIE + "=").length(), header.indexOf(';'));
    }
}
