package com.hospital.auth.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Builds the refresh-token cookie. HttpOnly (no JS access), SameSite=Strict (never sent cross-site)
 * and scoped to /api/v1/auth so it isn't attached to every OPD/lab API call.
 */
@Component
public class RefreshCookieFactory {

    public static final String NAME = "jb_rt";
    public static final String PATH = "/api/v1/auth";

    private final boolean secure;
    private final Duration maxAge;

    public RefreshCookieFactory(@Value("${app.refresh.cookie-secure:true}") boolean secure,
                                @Value("${app.refresh.session-ttl:PT12H}") Duration maxAge) {
        this.secure = secure;
        this.maxAge = maxAge;
    }

    public ResponseCookie create(String rawToken) {
        return base(rawToken).maxAge(maxAge).build();
    }

    public ResponseCookie clear() {
        return base("").maxAge(Duration.ZERO).build();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Strict")
                .path(PATH);
    }
}
