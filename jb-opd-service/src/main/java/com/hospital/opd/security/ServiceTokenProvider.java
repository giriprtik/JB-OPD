package com.hospital.opd.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * Short-lived SYSTEM token for calls this service makes with no user present
 * (lab status sync, outbox relay). Cached and renewed a minute before it expires.
 *
 * Limitation: with the shared HS256 secret any service holding it can mint any role. Moving to RS256
 * (auth signs, everyone else verifies via JWKS) removes that; it is out of scope here.
 */
@Component
public class ServiceTokenProvider {

    public static final String SUBJECT = "svc:jb-opd-service";
    static final Duration TTL = Duration.ofMinutes(5);
    static final Duration RENEW_BEFORE = Duration.ofMinutes(1);

    private final SecretKey key;
    private final Clock clock;
    private String token;
    private Instant expiresAt = Instant.EPOCH;

    @Autowired
    public ServiceTokenProvider(@Value("${app.jwt.secret}") String secret) {
        this(secret, Clock.systemUTC());
    }

    ServiceTokenProvider(String secret, Clock clock) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.clock = clock;
    }

    /** A valid token with at least a minute left. */
    public synchronized String current() {
        Instant now = clock.instant();
        if (token == null || !now.isBefore(expiresAt.minus(RENEW_BEFORE))) {
            expiresAt = now.plus(TTL);
            token = Jwts.builder()
                    .subject(SUBJECT)
                    .claim("roles", "SYSTEM")
                    .claim("name", "OPD service")
                    .issuedAt(Date.from(now))
                    .expiration(Date.from(expiresAt))
                    .signWith(key)
                    .compact();
        }
        return token;
    }

    /** Ready-made Authorization header value. */
    public String bearer() {
        return "Bearer " + current();
    }
}
